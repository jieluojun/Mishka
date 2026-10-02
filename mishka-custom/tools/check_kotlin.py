#!/usr/bin/env python3
"""用 tree-sitter-kotlin 逐个解析 Kotlin 源文件，报告语法错误（ERROR / MISSING 节点）。

用法: python3 tools/check_kotlin.py <文件或目录> [...]
退出码非 0 表示存在解析错误。
"""
import re
import sys
from pathlib import Path

from tree_sitter import Language, Parser
from tree_sitter_language_pack import get_language

LANG: Language = get_language("kotlin")
PARSER = Parser(LANG)


def walk(node):
    stack = [node]
    while stack:
        cur = stack.pop()
        if cur.type == "ERROR" or cur.is_missing:
            yield cur
        stack.extend(cur.children)


def check(path: Path) -> int:
    src = path.read_bytes()
    # The bundled tree-sitter Kotlin grammar predates Compose's type-use
    # annotation syntax (`@Composable () -> Unit`) and can cascade into false
    # ERROR nodes across otherwise valid lambdas. The annotation is orthogonal
    # to Kotlin grammar shape, so parse a copy without it; the original files
    # remain untouched.
    parse_src = re.sub(rb"@Composable\b", b"", src)
    tree = PARSER.parse(parse_src)
    problems = list(walk(tree.root_node))
    if not problems:
        print(f"OK   {path}")
        return 0
    print(f"FAIL {path}  ({len(problems)} problem node(s))")
    text = src.decode("utf-8", "replace").splitlines()
    for node in problems[:20]:
        row, col = node.start_point
        snippet = text[row].strip() if row < len(text) else ""
        kind = "MISSING" if node.is_missing else "ERROR"
        print(f"     {kind} L{row + 1}:{col + 1}  {node.type}  | {snippet[:120]}")
    return 1


def main(argv: list[str]) -> int:
    targets: list[Path] = []
    for arg in argv or ["."]:
        p = Path(arg)
        targets.extend(sorted(p.rglob("*.kt")) if p.is_dir() else [p])
    bad = sum(check(p) for p in targets)
    print(f"\n{len(targets)} file(s), {bad} with syntax errors")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
