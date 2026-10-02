#!/usr/bin/env python3
"""YAML 引擎的性质测试（PyYAML 真解析 + 保真度检查）。

跑法：python3 tools/forms/forms_props.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import forms_model as fm  # noqa: E402

CORPUS = Path(__file__).resolve().parent / "corpus"
stats = {}


def bump(k, n=1):
    stats[k] = stats.get(k, 0) + n


def top_blocks(text: str) -> dict[str, str]:
    """顶层块的「内容」原文（键行 + 它的值所占行），不含块间空行/注释间隔。

    不含间隔是有意的：删掉中间某个键时，相邻块的**内容**不该受影响，
    边界漂移属于正常现象，不该算「动了别的块」。
    """
    doc = fm.parse(text)
    out, lines = {}, doc.lines
    for e in doc.root.entries:
        if e.key.startswith("__"):
            continue
        start = e.line
        end = e.node.end if e.node is not None else e.line + 1
        out[e.key] = "\n".join(lines[start:end])
    return out



def removed_anchor_dangling(doc, key, out_text) -> bool:
    """删掉 key 之后别名解析不了 → 说明 key 上挂着被人引用的锚点。"""
    e = fm.find_entry(doc.root, key)
    if e is None or e.node is None or not getattr(e.node, "anchor", None):
        return False
    try:
        yaml.safe_load(out_text)
        return False
    except yaml.composer.ComposerError as exc:
        return "undefined alias" in str(exc)
    except Exception:
        return False


def check(cond: bool, msg: str) -> bool:
    if not cond:
        print(f"  ✗ {msg}")
    return cond


def case_file(path: Path) -> tuple[int, int]:
    ok = bad = 0
    text = path.read_bytes().decode("utf-8")  # 不走 read_text：它会把 CRLF 悄悄翻成 LF，CRLF 语料就白测了
    try:
        before = yaml.safe_load(text)
    except Exception as e:
        print(f"  - {path.name}: 跳过（PyYAML 也解析不了：{e}）")
        return 0, 0
    if not isinstance(before, dict):
        return 0, 0
    doc = fm.parse(text)
    blocks_before = top_blocks(text)

    # 1) 改标量：每个顶层标量键改成同类型新值
    for key, val in list(before.items()):
        if isinstance(val, (dict, list)):
            continue
        newval = {"int": 9999, "str": "edited-value", "bool": not val, "float": 1.5}.get(
            type(val).__name__)
        if newval is None or isinstance(val, bool):
            newval = (not val) if isinstance(val, bool) else newval
        out = fm.set_many(text, [(key, newval)])
        after = yaml.safe_load(out)
        bump("T1 标量改值")
        if not check(after.get(key) == newval, f"{path.name}: {key} 改后值不对（{after.get(key)!r}）"):
            bad += 1
            continue
        other_ok = all(
            blocks_before[k] == top_blocks(out)[k] for k in blocks_before if k != key
        )
        if not check(other_ok, f"{path.name}: 改 {key} 时动了别的块"):
            bad += 1
            continue
        # 往返还原
        back = fm.set_many(out, [(key, val)])
        if not check(back == text, f"{path.name}: {key} 改回原值后不是逐字节还原"):
            bad += 1
            continue
        # 幂等
        twice = fm.set_many(out, [(key, newval)])
        if not check(twice == out, f"{path.name}: {key} 重复设置不幂等"):
            bad += 1
            continue
        ok += 1

    # 2) 删键
    for key in list(before.keys()):
        if key.startswith("__"):
            continue
        out = fm.remove_many(text, [key])
        # 删掉的是「被别处 *别名 引用的锚点定义」→ 结果本身非法（参考实现也一样），
        # 这属于 UI 该拦的情况（锚点面板已实现该告警），不在这里算失败。
        if removed_anchor_dangling(doc, key, out):
            bump("T2 删键·跳过（删的是被引用的锚点）")
            continue
        after = yaml.safe_load(out)
        bump("T2 删键")
        if not check(key not in (after or {}), f"{path.name}: 删 {key} 失败"):
            bad += 1
            continue
        rest_ok = all(blocks_before[k] == top_blocks(out)[k] for k in blocks_before if k != key)
        if not check(rest_ok, f"{path.name}: 删 {key} 时动了别的块"):
            bad += 1
            continue
        ok += 1

    # 3) 嵌套标量
    for key, val in list(before.items()):
        if not isinstance(val, dict):
            continue
        for sub, sv in list(val.items()):
            if isinstance(sv, (dict, list)):
                continue
            newval = (not sv) if isinstance(sv, bool) else ("nested-edit" if isinstance(sv, str) else 4242)
            out = fm.set_many(text, [(f"{key}.{sub}", newval)])
            after = yaml.safe_load(out) or {}
            bump("T3 嵌套改值")
            if not check(after.get(key, {}).get(sub) == newval, f"{path.name}: {key}.{sub} 改后不对"):
                bad += 1
                continue
            if not check(top_blocks(out)[key] != blocks_before[key], f"{path.name}: {key}.{sub} 改了但块没变"):
                bad += 1
                continue
            others = all(blocks_before[k] == top_blocks(out)[k] for k in blocks_before if k != key)
            if not check(others, f"{path.name}: 改 {key}.{sub} 动了别的块"):
                bad += 1
                continue
            ok += 1

    # 4) 新增键（顶层 + 嵌套）
    out = fm.set_many(text, [("__probe_top", "x")])
    after = yaml.safe_load(out) or {}
    bump("T4 新增键")
    if check(after.get("__probe_top") == "x", f"{path.name}: 新增顶层键失败"):
        ok += 1
    else:
        bad += 1
    if "dns" in before and isinstance(before["dns"], dict):
        out2 = fm.set_many(text, [("dns.__probe", 1)])
        after2 = yaml.safe_load(out2) or {}
        bump("T4 新增键")
        if check(after2.get("dns", {}).get("__probe") == 1, f"{path.name}: 新增嵌套键失败"):
            ok += 1
        else:
            bad += 1

    # 5) 整块替换（列表 / 映射）
    out3 = fm.set_many(text, [("__probe_list", ["a", "b c", "d:e"])])
    after3 = yaml.safe_load(out3) or {}
    bump("T5 整块替换")
    if check(after3.get("__probe_list") == ["a", "b c", "d:e"], f"{path.name}: 新增列表值不对"):
        ok += 1
    else:
        bad += 1
    out4 = fm.set_many(text, [("__probe_map", {"k": 1, "s": "x y"})])
    after4 = yaml.safe_load(out4) or {}
    if check(after4.get("__probe_map") == {"k": 1, "s": "x y"}, f"{path.name}: 新增映射值不对"):
        ok += 1
    else:
        bad += 1

    # 6) CRLF 文档
    if "\r\n" in text:
        out5 = fm.set_many(text, [("__probe_crlf", "v")])
        bump("T6 CRLF")
        crlf_ok = ("\r\n" in out5) and (out5.count("\n") == out5.count("\r\n"))
        if check(crlf_ok, f"{path.name}: CRLF 被打乱"):
            ok += 1
        else:
            bad += 1

    o2, b2 = case_items(path, text, before, blocks_before)
    return ok + o2, bad + b2


# ---------------------------------------------------------------- P2：序列项 / 项内字段 / 改名

def item_raws(text: str, seq_path: list) -> list[str]:
    """序列各项的原文（块序列按行区间，flow 序列按列区间）。用来证明「只动了那一项」。"""
    doc = fm.parse(text)
    seq = fm.get_path(doc, seq_path)
    if seq is None or seq.kind != "seq":
        return []
    if seq.flow:
        line = doc.lines[seq.start]
        return [line[it.value_start:it.value_end] for it in seq.items]
    out = []
    for it in seq.items:
        a, b = fm.item_bounds(it)
        out.append("\n".join(doc.lines[a:b]))
    return out


def others_same(blocks_before, out, key) -> bool:
    after = top_blocks(out)
    return all(blocks_before[k] == after[k] for k in blocks_before if k != key)


def case_items(path: Path, text: str, before: dict, blocks_before: dict) -> tuple[int, int]:
    ok = bad = 0
    doc = fm.parse(text)

    # 7) 结构对齐：PyYAML 眼里的顶层列表/映射，模型也必须看成同类型、同项数（无缩进序列、flow 项都在这里暴露）
    for key, val in before.items():
        n = fm.get_path(doc, key)
        bump("T7 结构对齐")
        if isinstance(val, list):
            good = n is not None and n.kind == "seq" and len(n.items) == len(val)
        elif isinstance(val, dict):
            good = n is not None and n.kind == "map" and len(n.entries) == len(val)
        else:
            good = n is not None and n.kind in ("scalar", "raw")
        if check(good, f"{path.name}: {key} 的结构与 PyYAML 不一致（{None if n is None else n.kind}）"):
            ok += 1
        else:
            bad += 1

    lists = {k: v for k, v in before.items() if isinstance(v, list) and v}

    # 8) 改项（标量项手术式；映射项整项重排）+ 还原
    for key, val in lists.items():
        for i in {0, len(val) - 1}:
            old = val[i]
            new = "EDITED,x.com,DIRECT" if not isinstance(old, dict) else {"name": "__e", "type": "ss", "server": "s", "port": 1}
            d2 = fm.set_item(doc, [key], i, new)
            out = d2.dump()
            after = yaml.safe_load(out)
            bump("T8 改项")
            exp = list(val)
            exp[i] = new
            if not check(after.get(key) == exp, f"{path.name}: {key}[{i}] 改后不对"):
                bad += 1
                continue
            raws_b, raws_a = item_raws(text, [key]), item_raws(out, [key])
            same = [raws_b[j] == raws_a[j] for j in range(len(val)) if j != i]
            if not check(all(same) and others_same(blocks_before, out, key), f"{path.name}: 改 {key}[{i}] 动了别的项/块"):
                bad += 1
                continue
            if not isinstance(old, dict):
                back = fm.set_item(d2, [key], i, old).dump()
                if not check(back == text, f"{path.name}: {key}[{i}] 改回后不是逐字节还原"):
                    bad += 1
                    continue
            ok += 1

    # 9) 项内字段：改 / 新增 / 删首键（`- name:` 那一行的 `-` 必须保住）
    for key, val in lists.items():
        for i, item in enumerate(val):
            if not isinstance(item, dict) or not item:
                continue
            node = fm.get_path(doc, [key, i])
            if node is None or any(e.is_merge for e in node.entries):
                continue      # 带 `<<:` 合并键的项：PyYAML 眼里的键集合不是文本里的键集合，跳过
            for sub, sv in list(item.items())[:3]:
                if isinstance(sv, (dict, list)):
                    continue
                nv = (not sv) if isinstance(sv, bool) else ("item-edit" if isinstance(sv, str) else 4242)
                out = fm.set_value(doc, [key, i, sub], nv).dump()
                after = yaml.safe_load(out)
                bump("T9 项内字段")
                exp = dict(item)
                exp[sub] = nv
                if not check(after[key][i] == exp and after[key][:i] == val[:i] and after[key][i + 1:] == val[i + 1:],
                             f"{path.name}: {key}[{i}].{sub} 改后不对"):
                    bad += 1
                    continue
                raws_b, raws_a = item_raws(text, [key]), item_raws(out, [key])
                same = all(raws_b[j] == raws_a[j] for j in range(len(val)) if j != i)
                if not check(same and others_same(blocks_before, out, key), f"{path.name}: 改 {key}[{i}].{sub} 动了别的项"):
                    bad += 1
                    continue
                back = fm.set_value(fm.parse(out), [key, i, sub], sv).dump()
                if not check(back == text, f"{path.name}: {key}[{i}].{sub} 改回后不是逐字节还原"):
                    bad += 1
                    continue
                ok += 1
            # 新增字段
            out = fm.set_value(doc, [key, i, "__probe"], "p").dump()
            after = yaml.safe_load(out)
            bump("T9 项内字段")
            if check(after[key][i].get("__probe") == "p" and len(after[key]) == len(val) and
                     all(after[key][j] == val[j] for j in range(len(val)) if j != i),
                     f"{path.name}: {key}[{i}] 新增字段失败"):
                ok += 1
            else:
                bad += 1
            # 新增嵌套字段（ws-opts.headers.Host 这类：中间映射不存在要建出来；flow 项要在行内建）
            out = fm.set_value(doc, [key, i, "__nest", "deep"], "v").dump()
            after = yaml.safe_load(out)
            bump("T9 项内字段")
            if check(after[key][i].get("__nest") == {"deep": "v"} and len(after[key]) == len(val) and
                     all(after[key][j] == val[j] for j in range(len(val)) if j != i) and others_same(blocks_before, out, key),
                     f"{path.name}: {key}[{i}] 新增嵌套字段失败"):
                ok += 1
            else:
                bad += 1
            # 删首键
            first = next(iter(item))
            if len(item) > 1:
                out = fm.remove_key(doc, [key, i, first]).dump()
                after = yaml.safe_load(out)
                bump("T9 项内字段")
                exp = {k: v for k, v in item.items() if k != first}
                if check(after[key][i] == exp and len(after[key]) == len(val) and
                         all(after[key][j] == val[j] for j in range(len(val)) if j != i),
                         f"{path.name}: 删 {key}[{i}].{first} 后不对"):
                    ok += 1
                else:
                    bad += 1

    # 10) 插项：开头 / 中间 / 末尾
    for key, val in lists.items():
        scalar = not isinstance(val[0], dict)
        new = "INSERTED,x,DIRECT" if scalar else {"name": "__new", "type": "ss", "server": "s", "port": 1, "udp": True}
        for i in sorted({0, len(val) // 2, len(val)}):
            out = fm.insert_item(doc, [key], i, new).dump()
            after = yaml.safe_load(out)
            bump("T10 插项")
            if not check(after.get(key) == val[:i] + [new] + val[i:], f"{path.name}: {key} 第 {i} 位插项后不对"):
                bad += 1
                continue
            raws_b, raws_a = item_raws(text, [key]), item_raws(out, [key])
            if not check(raws_a[:i] == raws_b[:i] and raws_a[i + 1:] == raws_b[i:] and others_same(blocks_before, out, key),
                         f"{path.name}: {key} 第 {i} 位插项动了别的项"):
                bad += 1
                continue
            ok += 1

    # 11) 删项：逐个删；删光后是 `key: []`
    for key, val in lists.items():
        for i in sorted({0, len(val) - 1}):
            out = fm.remove_item(doc, [key], i).dump()
            after = yaml.safe_load(out)
            bump("T11 删项")
            if not check(after.get(key) == val[:i] + val[i + 1:], f"{path.name}: 删 {key}[{i}] 后不对"):
                bad += 1
                continue
            raws_b, raws_a = item_raws(text, [key]), item_raws(out, [key])
            if not check(raws_a == raws_b[:i] + raws_b[i + 1:] or len(val) == 1, f"{path.name}: 删 {key}[{i}] 动了别的项"):
                bad += 1
                continue
            ok += 1
        d = doc
        for _ in val:
            d = fm.remove_item(d, [key], 0)
        after = yaml.safe_load(d.dump())
        bump("T11 删项")
        if check(after.get(key) == [], f"{path.name}: {key} 删光后不是空列表"):
            ok += 1
        else:
            bad += 1

    # 12) 挪项：纯行重排 → 行的多重集不变
    for key, val in lists.items():
        if len(val) < 2:
            continue
        for frm, to in {(0, len(val) - 1), (len(val) - 1, 0), (1, min(2, len(val) - 1))}:
            if frm == to:
                continue
            out = fm.move_item(doc, [key], frm, to).dump()
            after = yaml.safe_load(out)
            exp = list(val)
            exp.insert(to, exp.pop(frm))
            bump("T12 挪项")
            if not check(after.get(key) == exp, f"{path.name}: {key} {frm}→{to} 后不对"):
                bad += 1
                continue
            seq = fm.get_path(doc, key)
            if seq.flow:
                pure = sorted(item_raws(out, [key])) == sorted(item_raws(text, [key])) and others_same(blocks_before, out, key)
            else:
                pure = sorted(out.split(doc.eol)) == sorted(text.split(doc.eol))
            if not check(pure, f"{path.name}: {key} {frm}→{to} 不是纯重排"):
                bad += 1
                continue
            ok += 1

    # 13) 改名：顶层「映射的映射」（代理集合 / 规则集合 / 子规则）
    for key, val in before.items():
        if not isinstance(val, dict) or not val:
            continue
        for name in list(val)[:2]:
            out = fm.rename_key(doc, [key, name], "renamed.name").dump()
            after = yaml.safe_load(out)
            bump("T13 改名")
            exp = {("renamed.name" if k == name else k): v for k, v in val.items()}
            if not check(after.get(key) == exp and list(after[key]) == list(exp), f"{path.name}: {key}.{name} 改名后不对"):
                bad += 1
                continue
            back = fm.rename_key(fm.parse(out), [key, "renamed.name"], name).dump()
            # 原键若本来就带引号（'cn.rules'），改回时按需加引号，只要求语义一致；否则必须逐字节还原
            src_entry = fm.find_entry(fm.get_path(doc, key), name)
            src_plain = doc.lines[src_entry.line][src_entry.key_indent:].startswith(name)
            restored = back == text if src_plain else yaml.safe_load(back) == before
            if not check(restored and others_same(blocks_before, out, key), f"{path.name}: {key}.{name} 改名改回不还原"):
                bad += 1
                continue
            ok += 1

    # 14) 嵌套列表（代理组的 proxies，块式或 flow）：插 / 删 / 挪
    groups = before.get("proxy-groups")
    if isinstance(groups, list):
        for i, g in enumerate(groups):
            if not isinstance(g, dict) or not isinstance(g.get("proxies"), list):
                continue
            pl = g["proxies"]
            p = ["proxy-groups", i, "proxies"]
            node = fm.get_path(doc, p)
            if node is None or node.kind == "raw":
                # `proxies: *members` 这类别名值：引擎拒绝改（改了会连带锚点处），UI 会提示去编辑器里改
                bump("T14 嵌套列表·跳过（别名）")
                if check(fm.insert_item(doc, p, 0, "x").dump() == text, f"{path.name}: 组 {i} 别名 proxies 不该被改动"):
                    ok += 1
                else:
                    bad += 1
                continue
            d2 = fm.insert_item(doc, p, len(pl), "NEW,node")
            d2 = fm.insert_item(d2, p, 0, "first")
            a2 = yaml.safe_load(d2.dump())
            bump("T14 嵌套列表")
            if not check(a2["proxy-groups"][i]["proxies"] == ["first"] + pl + ["NEW,node"], f"{path.name}: 组 {i} proxies 插项后不对"):
                bad += 1
                continue
            d3 = fm.remove_item(d2, p, 0)
            d3 = fm.remove_item(d3, p, len(pl))
            if not check(d3.dump() == text, f"{path.name}: 组 {i} proxies 插后再删不还原"):
                bad += 1
                continue
            if len(pl) >= 2:
                d4 = fm.move_item(doc, p, 0, len(pl) - 1)
                a4 = yaml.safe_load(d4.dump())
                if not check(a4["proxy-groups"][i]["proxies"] == pl[1:] + [pl[0]], f"{path.name}: 组 {i} proxies 挪项后不对"):
                    bad += 1
                    continue
            ok += 1

    # 15) 多行字符串：顶层 / 项内 / 新项 / 嵌套新建 都得按 `|` 块写，读回逐字相同（flow 项里则拒绝）
    ML = ["L1\nL2", "L1\n\nL3\n", "-----BEGIN-----\nabc\n-----END-----"]
    for mi, mv in enumerate(ML):
        bump("T15 多行")
        d2 = fm.set_value(doc, ["__ml_top"], mv)
        a2 = yaml.safe_load(d2.dump())
        if not check(a2.get("__ml_top") == mv and others_same(blocks_before, d2.dump(), "__ml_top"), f"{path.name}: 顶层多行 {mi} 不对"):
            bad += 1
            continue
        d3 = fm.set_value(doc, ["__ml_nest", "deep", "ca"], mv)
        a3 = yaml.safe_load(d3.dump())
        if not check(a3.get("__ml_nest") == {"deep": {"ca": mv}}, f"{path.name}: 嵌套多行 {mi} 不对"):
            bad += 1
            continue
        d4 = fm.set_value(doc, ["__ml_list"], [mv, "plain"])
        a4 = yaml.safe_load(d4.dump())
        if not check(a4.get("__ml_list") == [mv, "plain"], f"{path.name}: 列表多行 {mi} 不对"):
            bad += 1
            continue
        good = True
        for key, val in before.items():
            if not isinstance(val, list) or not val:
                continue
            for i, item in enumerate(val):
                if not isinstance(item, dict) or not item:
                    continue
                node = fm.get_path(doc, [key, i])
                d5 = fm.set_value(doc, [key, i, "ca"], mv)
                a5 = yaml.safe_load(d5.dump())
                if node is not None and node.flow:
                    exp = d5.dump() == text          # flow 项：拒绝、原样
                else:
                    exp = a5[key][i].get("ca") == mv and len(a5[key]) == len(val) and others_same(blocks_before, d5.dump(), key)
                if not check(exp, f"{path.name}: {key}[{i}] 多行 ca {mi} 不对"):
                    good = False
            d6 = fm.insert_item(doc, [key], 0, {"name": "ml", "ca": mv})
            a6 = yaml.safe_load(d6.dump())
            n6 = fm.get_path(doc, [key])
            if n6 is not None and n6.flow:
                continue
            if not check(a6[key][0] == {"name": "ml", "ca": mv} and a6[key][1:] == val, f"{path.name}: {key} 插多行项 {mi} 不对"):
                good = False
        if good:
            ok += 1
        else:
            bad += 1
    return ok, bad


# 括号不配对的 flow 写法：PyYAML 读不了，所以不能放进语料文件，单独列在这里。
# 要求：解析必须结束（曾经 `[1, 2}` 会让两边引擎死循环，P2 对拍时发现）；坏节点按不透明值处理；
# 改别的键时坏行原样保留；对坏节点本身 set 也不能把它「猜」成别的东西（允许拒绝或整行重写，但结果须是合法 YAML）。
BROKEN_FLOW = [
    "a: {k: [1, 2}\n",
    "a: [1, {k: v]\n",
    "a: [1, 2}\n",
    "a: {k: v]\n",
    "a: {k: [1, 2}, b: 3}\n",
    "- [1, 2}\n- x\n",
    "a: [1, 2\n",
    "a: {k: v\n",
]


def case_broken_flow() -> tuple[int, int]:
    ok = bad = 0
    for t in BROKEN_FLOW:
        bump("T16 坏 flow")
        text = "head: 1\n" + t + "tail: 2\n"
        try:
            doc = fm.parse(text)
        except Exception as e:  # noqa: BLE001
            check(False, f"坏 flow 解析抛异常：{t!r} → {e!r}")
            bad += 1
            continue
        d2 = fm.set_value(doc, ["tail"], 3)
        d3 = fm.set_value(d2, ["__new"], "x")
        out = d3.dump()
        lines = out.split("\n")
        exp_line = t.split("\n")[0]
        if not check(lines[0] == "head: 1" and exp_line in lines and "tail: 3" in lines and "__new: x" in lines,
                     f"坏 flow 邻居改值后原文没保住：{t!r} → {out!r}"):
            bad += 1
            continue
        ok += 1
    return ok, bad


def main() -> int:
    files = sorted(CORPUS.glob("*.yaml")) + sorted(CORPUS.glob("*.yml"))
    if not files:
        print("语料为空：" + str(CORPUS))
        return 2
    total_ok = total_bad = 0
    for f in files:
        ok, bad = case_file(f)
        if ok or bad:
            print(f"  {f.name}: 通过 {ok}，失败 {bad}")
        total_ok += ok
        total_bad += bad
    ok, bad = case_broken_flow()
    print(f"  （内置）括号不配对的 flow 写法: 通过 {ok}，失败 {bad}")
    total_ok += ok
    total_bad += bad
    print()
    print("覆盖：" + "，".join(f"{k} {v} 次" for k, v in sorted(stats.items())))
    print(f"全部通过：{total_ok} 项，失败 {total_bad} 项")
    return 0 if total_bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
