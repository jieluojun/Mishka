#!/usr/bin/env python3
"""从 mihomo_box 参考实现的 core.js 里**逐字提取**扫描相关函数，生成 node 可跑的 ref_scan.mjs。

提取的东西（不做任何改写，只去掉 `export`）：
  bareMask / anchorsOnLine / scanAnchorGraph

用法: python3 tools/equiv/gen_ref_scan.py <core.js> <输出 .mjs>
"""
import sys
from pathlib import Path

DRIVER = """

// ---- 驱动器：读取目录下所有 *.yaml，输出 { 文件名: 扫描结果 } 的 JSON ----
import { readFileSync, readdirSync } from 'node:fs';
const dir = process.argv[2];
const files = readdirSync(dir).filter((f) => f.endsWith('.yaml')).sort();
const out = {};
for (const f of files) out[f] = scanAnchorGraph(readFileSync(`${dir}/${f}`, 'utf8'));
process.stdout.write(JSON.stringify(out, null, 1));
"""


def grab(src: str, start: str, end: str | None = None) -> str:
    i = src.index(start)
    if end is not None:
        return src[i:src.index(end, i)]
    lines = src[i:].split("\n")
    out = [lines[0]]
    for line in lines[1:]:
        out.append(line)
        if line == "}":
            break
    return "\n".join(out)


def main(core_js: str, out_path: str) -> int:
    src = Path(core_js).read_text()
    parts = [
        grab(src, "function bareMask(line) {"),
        grab(src, "function anchorsOnLine(line) {"),
        grab(src, "export function scanAnchorGraph(text) {", "// 已有定义块的可视化编辑").rstrip() + "\n",
    ]
    body = "\n\n".join(parts).replace("export function", "function")
    Path(out_path).write_text(body + DRIVER)
    print(f"wrote {out_path} ({len(body)} bytes of reference code)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
