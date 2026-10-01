#!/usr/bin/env python3
"""AnchorEdit / AnchorBlock / YamlValue 的性质测试（跑在 Python 转写模型上 + 真 YAML 解析器验证）。

每个手术都要求满足三件事（这正是面板敢把改动写回配置的前提）：
  1. **YAML 仍然可解析**（用 PyYAML 真解析；悬空别名会让解析直接报错，等于内核会拒的配置当场暴露）；
  2. **锚点图变化符合预期**（改名级联、摘定义、改绑、删行各自的引用计数走向）；
  3. **未触及的字节原样保留**（行数/内容做子序列与逐行比对，注释、空行、缩进、CRLF 不许被动）。

用法: python3 tools/equiv/edit_props.py
"""
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import kotlin_edit as ke  # noqa: E402
from scan_kotlin import block_end_index, scan, tokens_on_line  # noqa: E402

CORPUS = HERE / "corpus"
FAILURES: list[str] = []
COVER: dict[str, int] = {}


def hit(key: str) -> None:
    COVER[key] = COVER.get(key, 0) + 1


def fail(msg: str) -> None:
    FAILURES.append(msg)


def parses(text: str) -> bool:
    hit("parse_calls")
    try:
        yaml.safe_load(text)
        return True
    except Exception:
        return False


def load(text: str):
    return yaml.safe_load(text)


def files():
    return sorted(CORPUS.glob("*.yaml"))


def def_indices(text: str) -> list[tuple[int, str]]:
    """(0 基行号, 锚点名) 列表：所有裸文本里的 `&名` 出现处。"""
    out = []
    for i, line in enumerate(text.split("\n")):
        for name in tokens_on_line(line)["defs"]:
            out.append((i, name))
    return out


def block_of(text: str, index: int, name: str) -> dict | None:
    lines = text.split("\n")
    end = block_end_index(lines, index, name)
    return ke.parse_block(lines, index, end, name)


def refs_of(text: str) -> dict[str, list[int]]:
    """锚点 → 引用行号（1 基）。"""
    return {a["name"]: [r["line"] for r in a["refs"]] for a in scan(text)["anchors"]}


def defs_of(text: str) -> dict[str, list[int]]:
    return {a["name"]: [d["line"] for d in a["defs"]] for a in scan(text)["anchors"]}


def dangler_refs(text: str) -> dict[str, list[int]]:
    """悬空引用（有 `*名` 没有 `&名`）→ 引用行号（1 基）。"""
    return {d["name"]: [r["line"] for r in d["refs"]] for d in scan(text)["danglers"]}


def names_of(text: str) -> set[str]:
    return {a["name"] for a in scan(text)["anchors"]}


def sorted_lines(text: str) -> list[str]:
    return [l for l in text.split("\n")]


def explainable(a, b, token: str, new: str) -> bool:
    """b 是否 = a 里「裸文本 *token / &token」被换成 new 的结果（改名只能带来这种数据差异）。

    例：规则串 `MATCH,*base` 是普通标量（YAML 不展开它），但按裸文本规则也会被改名——
    这与 mihomo_box 的参考实现一致，所以数据差异必须被解释成「token 替换」才算通过。
    """
    if isinstance(a, str) and isinstance(b, str):
        if a == b:
            return True
        return b == a.replace("*" + token, "*" + new) or b == a.replace("&" + token, "&" + new)
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(explainable(a[k], b[k], token, new) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(explainable(x, y, token, new) for x, y in zip(a, b))
    return a == b


def def_line_of(text: str, name: str) -> int:
    """锚点第一个定义所在的 1 基行号。"""
    a = next((x for x in scan(text)["anchors"] if x["name"] == name), None)
    return a["defs"][0]["line"] if a and a["defs"] else 0


def ref_candidates(text: str, ref: dict, current: str) -> list[str]:
    """镜像 AnchorPanel.refCandidates：定义在引用行之前 + 合并目标必须是映射。"""
    out = []
    for a in scan(text)["anchors"]:
        if a["name"] == current or not a["defs"]:
            continue
        if not any(d["line"] < ref["line"] for d in a["defs"]):
            continue
        if ref["merge"] and not ke.is_mergeable_anchor(text, a["defs"][0]["line"], a["name"]):
            continue
        out.append(a["name"])
    return out


# ==================== T1 定义块 parse → render 逐字还原 ====================


def t1_block_roundtrip() -> None:
    for f in files():
        text = f.read_text(encoding="utf-8")
        for index, name in def_indices(text):
            block = block_of(text, index, name)
            if block is None:
                continue
            hit("T1.blocks")
            hit(f"T1.kind.{block['kind']}")
            original = "\n".join(block["lines"])
            if block["kind"] in (ke.DEF_MAP, ke.DEF_SEQ):
                rows = [ke.row(seed=e, key=e["key_raw"], value=e["value"]) for e in block["entries"]]
            elif block["kind"] == ke.DEF_SCALAR:
                rows = [ke.row(value=block["header_value"])]
            else:
                rows = []
            out = ke.render_block(block, rows)
            if out != original:
                fail(f"T1 {f.name}:{index + 1} &{name} ({block['kind']}) 逐字还原失败\n"
                     f"    原: {original!r}\n    新: {out!r}")


def t1b_unknown_block() -> None:
    """合成样本：正文是 flow 根的块必须是 Unknown（面板只给「文本」模式），且渲染不改变字节。"""
    for body in ("  [1, 2]\n", "  {a: 1}\n"):
        text = "k: &u\n" + body + "tail: 1\n"
        block = block_of(text, 0, "u")
        hit("T1b.synthetic")
        if block is None or block["kind"] != ke.DEF_UNKNOWN:
            fail(f"T1b 合成样本 {body!r} 应判为 Unknown，实际 {None if block is None else block['kind']}")
            continue
        if ke.render_block(block, []) != "\n".join(block["lines"]):
            fail(f"T1b 合成样本 {body!r}: Unknown 块的渲染改动了原文")


# ==================== T2 改名级联 ====================


def t2_rename() -> None:
    for f in files():
        text = f.read_text(encoding="utf-8")
        before = parses(text)
        for name in sorted(names_of(text)):
            new = name + "_r"
            out = ke.rename_anchor(text, name, new)
            hit("T2.renames")
            # 往返：改名再改回来必须逐字复原
            if ke.rename_anchor(out, new, name) != text:
                fail(f"T2 {f.name} &{name}: 改名往返不能逐字复原")
            if names_of(out) - {new} != names_of(text) - {name}:
                fail(f"T2 {f.name} &{name}: 改名后锚点集合不对 {names_of(out)} vs {names_of(text)}")
            if defs_of(out).get(new) != defs_of(text).get(name) or refs_of(out).get(new) != refs_of(text).get(name):
                fail(f"T2 {f.name} &{name}: 改名后定义/引用行号变了")
            if parses(out) != before:
                fail(f"T2 {f.name} &{name}: 改名改变了可解析性")
            if before and not explainable(load(text), load(out), name, new):
                fail(f"T2 {f.name} &{name}: 改名后的 YAML 数据变化无法用「token 替换」解释")
            # 只有含 token 的行可以变，且变化必须恰好是 token 替换
            for old_line, new_line in zip(sorted_lines(text), sorted_lines(out)):
                if old_line == new_line:
                    continue
                expect = ke.replace_token_in_line(old_line, name, new)
                if expect != new_line:
                    fail(f"T2 {f.name} &{name}: 差异行不是纯 token 替换\n    原: {old_line!r}\n    新: {new_line!r}")


# ==================== T3 摘定义 ====================


def t3_drop() -> None:
    """摘定义：无引用时按「mihomo 段只摘 token / 自建容器整块删」两条路径走，且必须保持数据等价。

    有引用时算法本身也会照删（留下悬空别名、配置直接不可解析）——这是刻意留给 UI 的职责：
    面板的「删除定义」按钮按引用数禁用（AnchorDropDialog 的 blocked 判断），这条测试反过来把
    「不拦就会坏」记成事实。
    """
    for f in files():
        text = f.read_text(encoding="utf-8")
        before = parses(text)
        refs = refs_of(text)
        for index, name in def_indices(text):
            shape = ke.parse_def_line(text.split("\n")[index])
            top_key = shape["key"] if (shape and shape["indent"] == 0 and not shape["dash"]) else None
            is_mihomo = top_key in ke.MIHOMO_TOP_KEYS
            has_refs = bool(refs.get(name))
            result = ke.drop_anchor(text, name)
            kind = result[0]
            if kind == "ambiguous":
                hit("T3.ambiguous")
                continue
            if kind == "notfound":
                fail(f"T3 {f.name}: &{name} 明明有定义却报 notfound")
                continue
            out = result[1]
            if kind == "kept":
                hit("T3.kept")
                if len(sorted_lines(out)) != len(sorted_lines(text)):
                    fail(f"T3 {f.name} &{name}: kept 分支改了行数")
                if name in names_of(out):
                    fail(f"T3 {f.name} &{name}: kept 分支没摘掉 &{name}")
                if top_key is not None and not is_mihomo:
                    fail(f"T3 {f.name} &{name}: 顶层键 {top_key} 不是 mihomo 段却走了 kept 分支")
                if has_refs:
                    hit("T3.kept_with_refs")
                    if before and parses(out):
                        fail(f"T3 {f.name} &{name}: 摘掉定义后引用悬空，却仍能解析")
                elif before:
                    if not parses(out) or load(out) != load(text):
                        fail(f"T3 {f.name} &{name}: kept 分支改变了 YAML 数据")
                continue
            # removed
            hit("T3.removed")
            reported = result[2]
            if has_refs:
                hit("T3.removed_with_refs")
            if top_key is None:
                fail(f"T3 {f.name} &{name}: removed 分支却没有可解析的顶层键")
            if is_mihomo and top_key is not None:
                fail(f"T3 {f.name} &{name}: mihomo 段 {top_key} 不该被整块删除")
            delta = len(sorted_lines(text)) - len(sorted_lines(out))
            if reported != delta:
                fail(f"T3 {f.name} &{name}: 报告删除 {reported} 行，实际少了 {delta} 行")
            if name in names_of(out):
                fail(f"T3 {f.name} &{name}: removed 分支后定义仍在")
            # 幸存行必须按顺序、逐字保留（out 是 text 的子序列）
            remaining = iter(sorted_lines(text))
            if not all(any(l == cand for cand in remaining) for l in sorted_lines(out)):
                fail(f"T3 {f.name} &{name}: removed 分支改了幸存行的内容或顺序")
            if has_refs:
                # 定义被删掉后，原来的引用必然变成悬空引用（scan 会把它归到 danglers）
                if name not in dangler_refs(out):
                    fail(f"T3 {f.name} &{name}: 定义已删，原引用却既不是引用也不是悬空引用")
                extra = set(dangler_refs(out)) - set(dangler_refs(text)) - {name}
                if extra:
                    fail(f"T3 {f.name} &{name}: 删除引入了新的悬空引用 {sorted(extra)}")
                if before and parses(out):
                    fail(f"T3 {f.name} &{name}: 定义已删、引用悬空，却仍能解析")
            elif before:
                if not parses(out):
                    fail(f"T3 {f.name} &{name}: removed 分支把可解析配置写坏了")
                else:
                    data = load(out)
                    expect = dict(load(text))
                    expect.pop(top_key, None)
                    if data != expect:
                        fail(f"T3 {f.name} &{name}: removed 分支的 YAML 数据 != 原数据去掉 {top_key}")


# ==================== T4 引用改绑 / 删除 ====================


def t4_rebind_and_delete() -> None:
    """改绑只允许指向「已定义在前」的锚点；`<<:` 只能改绑到映射锚点——与面板候选表同规则。"""
    for f in files():
        text = f.read_text(encoding="utf-8")
        for line1, line in enumerate(text.split("\n"), start=1):
            info = tokens_on_line(line)
            if len(info["refs"]) != 1:
                continue
            shape = ke.parse_ref_line(line)
            if shape is None:
                continue
            cur = info["refs"][0]
            is_merge = shape["key"] is None
            ref = {"line": line1, "merge": is_merge, "name": cur}
            cands = ref_candidates(text, ref, cur)
            hit("T4.refs")
            if is_merge:
                hit("T4.merge_refs")
            if not cands:
                hit("T4.no_candidate")
            for target in cands[:3]:
                hit("T4.rebound")
                out = ke.rebind_ref_line(text, line1, target)
                if out is None:
                    fail(f"T4 {f.name}:{line1}: 改绑返回 null")
                    continue
                target_anchor = next((a for a in scan(out)["anchors"] if a["name"] == target), None)
                if target_anchor is None or not any(r["line"] == line1 for r in target_anchor["refs"]):
                    fail(f"T4 {f.name}:{line1}: 改绑后该行不再引用 *{target}")
                if cur != target and cur in refs_of(out) and line1 in refs_of(out)[cur]:
                    fail(f"T4 {f.name}:{line1}: 改绑后旧锚点 *{cur} 在该行仍被引用")
                if parses(out) != parses(text):
                    fail(f"T4 {f.name}:{line1}: 改绑到 *{target} 改变了可解析性")
            # merge 行的「不继承」= 删行
            if is_merge:
                deleted = ke.rebind_ref_line(text, line1, None)
                if deleted is None:
                    fail(f"T4 {f.name}:{line1}: merge 行删除返回 null")
                    continue
                hit("T4.merge_cleared")
                if len(sorted_lines(deleted)) != len(sorted_lines(text)) - 1:
                    fail(f"T4 {f.name}:{line1}: 删除 merge 行后行数不对")
                if cur in refs_of(deleted) and line1 in refs_of(deleted)[cur]:
                    fail(f"T4 {f.name}:{line1}: 删除 merge 行后引用还在")
                if parses(deleted) != parses(text):
                    fail(f"T4 {f.name}:{line1}: 删除 merge 行改变了可解析性")


# ==================== T6 块替换幂等 ====================


def t6_replace_lines() -> None:
    for f in files():
        text = f.read_text(encoding="utf-8")
        for index, name in def_indices(text):
            block = block_of(text, index, name)
            if block is None:
                continue
            original = "\n".join(block["lines"])
            out = ke.replace_lines(text, block["start_line1"], block["start_line1"] + len(block["lines"]) - 1, original)
            hit("T6.replaces")
            if out != text:
                fail(f"T6 {f.name}:{index + 1} &{name}: 原块替换不幂等")


# ==================== T7 新建顶层块 ====================


def t7_build_top() -> None:
    base = (CORPUS / "01-basic.yaml").read_text(encoding="utf-8")
    cases = [
        ("手动合集", "myanchor", [("a", "1"), ("b", "hello")]),
        ("长块", "longanchor", [(f"k{i}", f"v{i}") for i in range(25)]),
    ]
    for top_key, anchor, pairs in cases:
        rows = [ke.row(key=k, value=v) for k, v in pairs]
        block = ke.build_top_block(top_key, anchor, rows)
        out = ke.insert_top_block(base, block)
        hit("T7.built")
        hit("T7.flow" if "{" in block.split("\n")[0] else "T7.block")
        if not parses(out):
            fail(f"T7 {top_key}: 新建块后配置不可解析\n{block}")
            continue
        data = load(out)
        if data.get(top_key) != {k: int(v) if v.isdigit() else v for k, v in pairs}:
            fail(f"T7 {top_key}: 新建块的数据不对：{data.get(top_key)}")
        if anchor not in names_of(out):
            fail(f"T7 {top_key}: 新建块后锚点 &{anchor} 不在锚点表里")
        if not out.startswith(block.strip("\n") + "\n"):
            fail(f"T7 {top_key}: 新块没插到文件头")
        # 原文件内容必须完整保留在尾部
        if not out.endswith(base):
            fail(f"T7 {top_key}: 插入后原内容被改动")
    # 空参数表 → 只能块式，且值为 null
    block = ke.build_top_block("空块", "empty", [])
    out = ke.insert_top_block("proxies: []\n", block)
    hit("T7.empty")
    if not parses(out) or load(out).get("空块", "MISSING") is not None:
        fail(f"T7 空参数表：{block!r} → {out!r}")


# ==================== T8 值 / 键规范化规则 ====================


def t8_value_rules() -> None:
    value_cases = [
        ("1", "1"), ("hello", "hello"), ("  x  ", "x"), ("", ""),
        ("a: b", None), ("a:", None), ("a:b", "a:b"), ("1:30", "1:30"), ("http://x", "http://x"),
        ('"a: b"', '"a: b"'), ("'x: y'", "'x: y'"),
        ("{a: 1, b: 2}", "{a: 1, b: 2}"), ("[1, 2]", "[1, 2]"),
        ("{a: 1", None), ("[1, 2", None), ("x}", None), ("x]", None),
        ("&x", None), ("!x", None), ("%x", None), ("|x", None), (">x", None),
        ("value # comment", "value"), ("a#b", "a#b"),
        ("{a: 1} # tail", "{a: 1}"),
    ]
    for raw, expect in value_cases:
        got = ke.normalize_value(raw)
        hit("T8.value")
        if got != expect:
            fail(f"T8 normalize_value({raw!r}) = {got!r}，期望 {expect!r}")
    key_cases = [("a", "a"), ("", None), ("a:b", None), ("a#b", None), ("*x", None), ("  k  ", "k")]
    for raw, expect in key_cases:
        got = ke.normalize_key(raw)
        hit("T8.key")
        if got != expect:
            fail(f"T8 normalize_key({raw!r}) = {got!r}，期望 {expect!r}")
    top_cases = [("a", "a"), ("a b", None), ("a:b", None), ("&a", None), ("a-b", "a-b"), ("", None)]
    for raw, expect in top_cases:
        got = ke.normalize_top_key(raw)
        hit("T8.top_key")
        if got != expect:
            fail(f"T8 normalize_top_key({raw!r}) = {got!r}，期望 {expect!r}")
    # hasTopLevelKey / topLevelKeys
    text = (CORPUS / "09-realistic.yaml").read_text(encoding="utf-8")
    if not ke.has_top_level_key(text, "proxies"):
        fail("T8 has_top_level_key(proxies) 应为 True")
    hit("T8.has_key")
    if ke.has_top_level_key(text, "proxy"):
        fail("T8 has_top_level_key(proxy) 不应命中 proxies")
    keys = ke.top_level_keys(text)
    hit("T8.top_keys")
    if "proxies" not in keys or any(k.startswith(" ") for k in keys):
        fail(f"T8 top_level_keys 结果异常：{keys}")


def t9_merge_target_rule() -> None:
    """合成样本：把 `<<:` 指到标量/序列上确实会让配置不可解析——这正是候选表过滤掉它们的理由。"""
    scalar_def = "a: &s 1\n"
    seq_def = "b: &q\n  - 1\n  - 2\n"
    for prefix, name in ((scalar_def, "s"), (seq_def, "q")):
        text = prefix + "c:\n  <<: *" + name + "\n"
        hit("T9.synthetic")
        if ke.is_mergeable_anchor(text, 1, name):
            fail(f"T9 合成样本：&{name} 不该被判为可合并")
        if parses(text):
            fail(f"T9 合成样本：`<<: *{name}`（非映射）竟然能解析，规则可能过严/过松")
    # 反向：映射与行内 flow 映射必须允许
    ok_text = "a: &m\n  x: 1\nc:\n  <<: *m\n"
    if not ke.is_mergeable_anchor(ok_text, 1, "m"):
        fail("T9 映射锚点应可合并")
    flow = "a: &f {x: 1}\nc:\n  <<: *f\n"
    if not ke.is_mergeable_anchor(flow, 1, "f"):
        fail("T9 行内 flow 映射锚点应可合并")
    if not parses(flow):
        fail("T9 行内 flow 映射合并应可解析")


def main() -> int:
    for fn in (t1_block_roundtrip, t1b_unknown_block, t2_rename, t3_drop, t4_rebind_and_delete, t6_replace_lines,
               t7_build_top, t8_value_rules, t9_merge_target_rule):
        fn()
    if FAILURES:
        print("\n".join(FAILURES))
        print(f"\n{len(FAILURES)} 个失败")
        return 1
    print(f"覆盖: parses() 调用 {COVER.get('parse_calls', 0)} 次, "
          f"T1 块 {COVER.get('T1.blocks', 0)} 个 (Scalar/Map/Seq/Unknown: "
          f"{COVER.get('T1.kind.Scalar', 0)}/{COVER.get('T1.kind.Map', 0)}/{COVER.get('T1.kind.Seq', 0)}/{COVER.get('T1.kind.Unknown', 0)}), "
          f"T2 改名 {COVER.get('T2.renames', 0)} 次, "
          f"T3 kept/removed/ambiguous: {COVER.get('T3.kept', 0)}/{COVER.get('T3.removed', 0)}/{COVER.get('T3.ambiguous', 0)} "
          f"(其中带引用 kept/removed: {COVER.get('T3.kept_with_refs', 0)}/{COVER.get('T3.removed_with_refs', 0)}), "
          f"T4 引用 {COVER.get('T4.refs', 0)} 条 (merge {COVER.get('T4.merge_refs', 0)}, 换绑 {COVER.get('T4.rebound', 0)}, "
          f"清继承 {COVER.get('T4.merge_cleared', 0)}, 无候选 {COVER.get('T4.no_candidate', 0)}), "
          f"T6 替换 {COVER.get('T6.replaces', 0)} 次, T7 新建 {COVER.get('T7.built', 0)} 个, "
          f"T8 值/键/顶层键 {COVER.get('T8.value', 0)}/{COVER.get('T8.key', 0)}/{COVER.get('T8.top_key', 0)} 例")
    print("T1 定义块 parse→render 逐字还原：通过（含 Unknown 形态合成样本）")
    print("T2 改名级联（往返复原 + 数据不变 + 只动含 token 的行）：通过")
    print("T3 摘定义（kept/removed 两条路径 + 字节保留 + 数据等价）：通过")
    print("T4 引用改绑 / 清继承：通过")
    print("T6 块替换幂等：通过")
    print("T7 新建顶层块（flow / 块式回退）可解析、数据正确：通过")
    print("T8 值/键/顶层键规范化规则：通过")
    print("T9 合并目标形态规则（合成样本）：通过")
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
