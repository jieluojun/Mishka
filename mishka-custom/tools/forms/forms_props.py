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
    text = path.read_text(encoding="utf-8")
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
    print()
    print("覆盖：" + "，".join(f"{k} {v} 次" for k, v in sorted(stats.items())))
    print(f"全部通过：{total_ok} 项，失败 {total_bad} 项")
    return 0 if total_bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
