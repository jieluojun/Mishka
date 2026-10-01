#!/usr/bin/env python3
"""AnchorScan.kt 的逐行 Python 转写，用于和参考实现（mihomo_box core.js 的 scanAnchorGraph）对拍。

只做「算法等价性」验证：字段名保持与 JS 版一致（anchors/defs/refs/mergeCnt/aliasCnt/danglers），
路径拼接、行号（1 基）、merge 判定都要逐字对上。
用法: python3 tools/equiv/scan_kotlin.py <corpus 目录>   # 输出同 ref_scan.mjs 的 JSON
"""
import json
import re
import sys
from pathlib import Path

NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.\-]*$")
DEF_TOKEN_RE = re.compile(r"&([^\s,\[\]{}#]+)")
REF_TOKEN_RE = re.compile(r"\*([^\s,\[\]{}#]+)")
MERGE_LINE_RE = re.compile(r"^\s*<<\s*:")
NAME_KEY_RE = re.compile(r"^\s*(?:-\s+)?(?:name|\"name\"|'name')\s*:\s*([^\s,}]+)")
KEY_RE = re.compile(r"^\s*(?:-\s+)?(?:&\S+\s+)?([^:#]+?)\s*:(?:\s|$)")


def is_valid_name(name: str) -> bool:
    return bool(NAME_RE.match(name))


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" \t"))


def bare_mask(line: str) -> list[bool]:
    mask = [True] * len(line)
    quote = None
    i = 0
    while i < len(line):
        c = line[i]
        if quote is not None:
            mask[i] = False
            if c == quote:
                if i + 1 < len(line) and line[i + 1] == quote:
                    mask[i + 1] = False
                    i += 2
                    continue
                quote = None
        elif c in "\"'":
            mask[i] = False
            quote = c
        elif c == "#":
            for j in range(i, len(line)):
                mask[j] = False
            break
        i += 1
    return mask


def tokens_on_line(line: str) -> dict:
    if "&" not in line and "*" not in line:
        return {"defs": [], "refs": []}
    mask = bare_mask(line)
    defs = [m.group(1) for m in DEF_TOKEN_RE.finditer(line) if m.start() < len(mask) and mask[m.start()]]
    refs = [m.group(1) for m in REF_TOKEN_RE.finditer(line) if m.start() < len(mask) and mask[m.start()]]
    return {"defs": defs, "refs": refs}


def is_merge_line(line: str) -> bool:
    return bool(MERGE_LINE_RE.search(line))


def unquote(raw: str) -> str:
    out = raw
    if len(out) >= 2 and out.startswith('"') and out.endswith('"'):
        out = out[1:-1]
    if len(out) >= 2 and out.startswith("'") and out.endswith("'"):
        out = out[1:-1]
    return out.strip()


MAX_CTX_DEPTH = 4  # mihomo_box ctxOf 的回溯层数上限


def block_end_index(lines: list[str], start_index: int, name: str | None = None) -> int:
    """AnchorScan.blockEndIndex 的转写：返回块尾行下标（含）。"""
    base = indent_of(lines[start_index])
    end = start_index + 1
    while end < len(lines):
        line = lines[end]
        if line.strip() != "" and indent_of(line) <= base:
            break
        end += 1
    if name is not None:
        end = flow_extend(lines, start_index, end)
    while end > start_index + 1 and lines[end - 1].strip() == "":
        end -= 1
    return end - 1


def flow_extend(lines: list[str], start_index: int, end0: int) -> int:
    """跨行 flow 的闭括号可能与定义行同级：按裸文本括号平衡往后补足。"""
    balance = 0
    i = start_index
    while i < len(lines):
        line = lines[i]
        mask = bare_mask(line)
        for k, ch in enumerate(line):
            if not mask[k]:
                continue
            if ch in "{[":
                balance += 1
            elif ch in "}]":
                balance -= 1
        if balance <= 0 and i >= end0 - 1:
            break
        i += 1
    if i >= len(lines) and balance > 0:
        return end0
    return max(end0, min(i + 1, len(lines)))


def path_of(lines: list[str], index: int) -> str:
    stack: list[str] = []
    want = indent_of(lines[index])
    for j in range(index, -1, -1):
        if len(stack) >= MAX_CTX_DEPTH:
            break
        line = lines[j]
        if line.strip() == "" or line.lstrip().startswith("#"):
            continue
        ind = indent_of(line)
        if j != index:
            m = NAME_KEY_RE.search(line)
            if m is not None and ind >= want:
                stack.insert(0, unquote(m.group(1).rstrip(",}]")))
                want = ind
                continue
        km = KEY_RE.search(line)
        if km is not None and (ind < want or j == index):
            stack.insert(0, unquote(km.group(1).strip()))
            want = min(want, ind)
            if ind == 0:
                break
    return " → ".join(stack) if stack else "(顶层)"


def scan(text: str) -> dict:
    lines = text.split("\n")
    defs: dict[str, list] = {}
    refs: dict[str, list] = {}
    for index, line in enumerate(lines):
        tokens = tokens_on_line(line)
        if not tokens["defs"] and not tokens["refs"]:
            continue
        path = path_of(lines, index)
        merge = is_merge_line(line)
        for name in tokens["defs"]:
            defs.setdefault(name, []).append({"line": index + 1, "path": path})
        for name in tokens["refs"]:
            refs.setdefault(name, []).append({"line": index + 1, "path": path, "merge": merge})
    anchors = []
    for name, locations in defs.items():
        rs = refs.get(name, [])
        anchors.append({
            "name": name,
            "defs": locations,
            "refs": rs,
            "mergeCnt": sum(1 for r in rs if r["merge"]),
            "aliasCnt": sum(1 for r in rs if not r["merge"]),
        })
    danglers = [{"name": n, "refs": rs} for n, rs in refs.items() if n not in defs]
    return {"anchors": anchors, "danglers": danglers}


def main(argv: list[str]) -> int:
    corpus = Path(argv[0] if argv else "tools/equiv/corpus")
    out = {}
    for f in sorted(corpus.glob("*.yaml")):
        out[f.name] = scan(f.read_text(encoding="utf-8"))
    print(json.dumps(out, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
