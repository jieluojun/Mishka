#!/usr/bin/env python3
"""Kotlin 引擎 ↔ Python 规范 逐字节对拍。

Kotlin 侧（deliver/app/.../custom/forms/YamlEngine.kt）是真正跑在 App 里的实现；
Python 侧（tools/forms/forms_model.py）是规范 + 71 项 PyYAML 性质测试。
同一个「YAML 原文 + 操作序列」喂给两边，结果必须逐字节一致 —— 这样 Kotlin 引擎
就继承了规范那套保真保证。

跑法：
    python3 tools/forms/engine_diff.py [--keep] [--jar <engine.jar>]

需要先编译 Kotlin CLI（见 tools/forms/kotlin/FormEngineCli.kt 顶部注释）：
    kotlinc app/src/main/kotlin/top/yukonga/mishka/custom/forms/YamlEngine.kt \\
            tools/forms/kotlin/FormEngineCli.kt -include-runtime -d /tmp/engine.jar
"""
from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / ".." / "equiv"))
import forms_model as fm  # noqa: E402
import yaml  # noqa: E402

FS = "\u0001"
FP = "\u0002"

FILES = sorted((HERE / "corpus").glob("*.yaml")) + sorted((HERE / "corpus").glob("*.yml"))


def enc_value(v) -> tuple[str, str]:
    if v is None:
        return "n", ""
    if isinstance(v, bool):
        return "b", "true" if v else "false"
    if isinstance(v, int):
        return "i", str(v)
    if isinstance(v, list):
        return "l", FS.join(str(x) for x in v)
    if isinstance(v, dict):
        return "m", FS.join(f"{k}{FP}{x}" for k, x in v.items())
    return "s", str(v)


def build_cases() -> list[dict]:
    cases = []
    for path in FILES:
        text = path.read_text(encoding="utf-8")
        try:
            before = yaml.safe_load(text)
        except Exception:
            continue
        if not isinstance(before, dict):
            continue
        ops: list[tuple[str, list]] = []   # (描述, [(action, path, value)])

        def add(desc, action, p, v=None):
            ops.append((desc, [(action, p, v)]))

        # 顶层标量：改值 + 删 + 改回
        for k, v in before.items():
            if isinstance(v, (dict, list)):
                continue
            if isinstance(v, bool):
                add(f"改 {k}", "set", k, (not v))
            elif isinstance(v, int):
                add(f"改 {k}", "set", k, 9999)
            else:
                add(f"改 {k}", "set", k, "edited-value")
            add(f"删 {k}", "del", k)
            if isinstance(v, str):
                add(f"改回 {k}", "set", k, v)
        # 嵌套标量
        for k, v in before.items():
            if not isinstance(v, dict):
                continue
            for sub, sv in list(v.items()):
                if isinstance(sv, (dict, list)):
                    continue
                nv = (not sv) if isinstance(sv, bool) else ("nested-edit" if isinstance(sv, str) else 4242)
                add(f"改 {k}.{sub}", "set", f"{k}.{sub}", nv)
        # 新增 / 整块替换 / 深层新建
        add("新增顶层", "set", "__probe_top", "x")
        add("新增列表", "set", "__probe_list", ["a", "b c", "d:e"])
        add("新增映射", "set", "__probe_map", {"k": "1", "s": "x y"})
        add("深层新建", "set", "aaa.bbb.ccc", "x")
        if "dns" in before:
            add("新增 dns 子键", "set", "dns.__probe", 1)
        if "listeners" in before:
            add("序列下拒绝", "set", "listeners.__probe", 1)
        if "geox-url" in before:
            add("flow 改值", "set", "geox-url.geoip", "https://new/x.dat")
            add("flow 新增", "set", "geox-url.asn", "https://new/asn.dat")
            add("flow 删除", "del", "geox-url.geosite")
        cases.append({"file": path.name, "text": text, "ops": ops})
    return cases


def run_python(text: str, ops: list) -> str:
    doc = fm.parse(text)
    for action, path, value in ops:
        if action == "set":
            doc = fm.set_value(doc, path, value)
        elif action == "del":
            doc = fm.remove_key(doc, path)
    return doc.dump()


def kotlin_protocol(flat: list) -> str:
    """每条操作一个 @@CASE（与 Python 侧逐条执行一一对应）。"""
    out = []
    for c, _desc, ops in flat:
        out.append("@@CASE")
        out.append("@@TEXT")
        out.append(c["text"])
        out.append("@@ENDTEXT")
        for action, path, value in ops:
            if action == "del":
                out.append(f"del{FS}{path}")
            else:
                t, raw = enc_value(value)
                out.append(f"set{FS}{path}{FS}{t}{FS}{raw}")
        out.append("@@OUT")
    return "\n".join(out) + "\n"


def parse_kotlin_output(stdout: str) -> list[str]:
    parts = stdout.split("@@OUT\n")
    results = []
    for part in parts[1:]:
        cut = part.index("\n@@ENDOUT\n")
        results.append(part[:cut])
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default="/tmp/engine.jar")
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    jar = pathlib.Path(args.jar)
    if not jar.exists():
        print(f"找不到 Kotlin 引擎：{jar}（先按文件头注释编译）")
        return 2

    cases = build_cases()
    flat = [(c, desc, ops) for c in cases for (desc, ops) in c["ops"]]
    print(f"语料 {len(cases)} 个文件，操作 {len(flat)} 条")

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as fh:
        fh.write(kotlin_protocol(flat))
        proto = fh.name
    try:
        proc = subprocess.run(["java", "-jar", str(jar), proto],
                              capture_output=True, text=True, timeout=600)
        if proc.returncode != 0:
            print("Kotlin 引擎退出码", proc.returncode)
            print(proc.stderr[-2000:])
            return 2
        kt_results = parse_kotlin_output(proc.stdout)
    finally:
        if not args.keep:
            pathlib.Path(proto).unlink(missing_ok=True)

    if len(kt_results) != len(flat):
        print(f"结果条数不匹配：Kotlin {len(kt_results)} vs 预期 {len(flat)}")
        return 2

    same = diff = 0
    for (case, desc, ops), kt in zip(flat, kt_results):
        py = run_python(case["text"], ops)
        if py == kt:
            same += 1
        else:
            diff += 1
            if diff <= 5:
                print(f"\n✗ {case['file']} · {desc}")
                import difflib
                for line in list(difflib.unified_diff(py.splitlines(), kt.splitlines(),
                                                      "python", "kotlin", lineterm="", n=1))[:16]:
                    print("   ", line)
    print(f"\n逐字节一致 {same} 条，不一致 {diff} 条")
    return 0 if diff == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
