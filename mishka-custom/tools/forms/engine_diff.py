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
import json
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
    if isinstance(v, list) and all(isinstance(x, str) and "\n" not in x for x in v):
        return "l", FS.join(v)
    if isinstance(v, dict) and all(isinstance(x, str) and "\n" not in x for x in v.values()):
        return "m", FS.join(f"{k}{FP}{x}" for k, x in v.items())
    if isinstance(v, (list, dict)) or (isinstance(v, str) and "\n" in v):
        return "j", json.dumps(v, ensure_ascii=False)     # 嵌套值（代理 / 隧道）与多行字符串走 JSON（协议按行传）
    return "s", str(v)


def build_cases() -> list[dict]:
    cases = []
    for path in FILES:
        text = path.read_bytes().decode("utf-8")  # 不走 read_text：它会把 CRLF 悄悄翻成 LF，CRLF 语料就白测了
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
        # 多行字符串：顶层 / 深层 / 列表项 / 新项里的字段 → `|` 块
        add("多行顶层", "set", "__ml_top", "L1\nL2")
        add("多行顶层·尾换行", "set", "__ml_keep", "L1\n\nL3\n")
        add("多行深层", "set", "__ml_nest.deep.ca", "-----BEGIN-----\nabc\n-----END-----")
        add("多行列表", "set", "__ml_list", ["A\nB", "plain"])
        ops.append(("多行新项", [("ins", "__ml_items", 0, {"name": "ml", "ca": "X\nY", "udp": True})]))
        if "dns" in before:
            add("新增 dns 子键", "set", "dns.__probe", 1)
        if "listeners" in before:
            add("序列下拒绝", "set", "listeners.__probe", 1)
        if "geox-url" in before:
            add("flow 改值", "set", "geox-url.geoip", "https://new/x.dat")
            add("flow 新增", "set", "geox-url.asn", "https://new/asn.dat")
            add("flow 删除", "del", "geox-url.geosite")
        # 标量键 → 集合值（行内 flow）/ 太长的 flow 退回块式
        add("标量改成列表", "set", "mode", ["a", "b,c"])
        add("标量改成长列表", "set", "mode", [f"very-long-item-number-{i}-abcdefghijklmnop" for i in range(6)])
        # ---- P2：序列项 / 项内字段 / 改名（见 forms_model.py 的 set_item / insert_item / remove_item / move_item / rename_key）
        proxy_item = {"name": "新节点", "type": "vmess", "server": "x.example.com", "port": 443,
                      "uuid": "u", "tls": True, "network": "ws",
                      "ws-opts": {"path": "/ws", "headers": {"Host": "x.example.com"}}, "alpn": ["h2", "http/1.1"]}
        for key, val in before.items():
            if not isinstance(val, list) or not val:
                continue
            n = len(val)
            scalar = not isinstance(val[0], dict)
            new = "EDITED,x.com,DIRECT" if scalar else proxy_item
            ops.append((f"改项 {key}[0]", [("seti", f"{key}[0]", new)]))
            ops.append((f"改项 {key}[{n - 1}]", [("seti", f"{key}[{n - 1}]", new)]))
            for i in sorted({0, n // 2, n}):
                ops.append((f"插项 {key}@{i}", [("ins", key, i, new)]))
            for i in sorted({0, n - 1}):
                ops.append((f"删项 {key}[{i}]", [("rmi", key, i)]))
            ops.append((f"删光 {key}", [("rmi", key, 0)] * n))
            if n >= 2:
                for frm, to in sorted({(0, n - 1), (n - 1, 0), (1, min(2, n - 1))}):
                    if frm != to:
                        ops.append((f"挪项 {key} {frm}→{to}", [("mov", key, frm, to)]))
            for i, item in enumerate(val):
                if not isinstance(item, dict) or not item:
                    continue
                for sub, sv in list(item.items())[:3]:
                    if isinstance(sv, (dict, list)):
                        continue
                    nv = (not sv) if isinstance(sv, bool) else ("item-edit" if isinstance(sv, str) else 4242)
                    ops.append((f"项内改 {key}[{i}].{sub}", [("set", f"{key}[{i}].{sub}", nv)]))
                ops.append((f"项内增 {key}[{i}].__probe", [("set", f"{key}[{i}].__probe", "p")]))
                ops.append((f"项内增嵌套 {key}[{i}].ws-opts.headers.Host", [("set", f"{key}[{i}].ws-opts.headers.Host", "h")]))
                ops.append((f"项内多行 {key}[{i}].ca", [("set", f"{key}[{i}].ca", "L1\nL2")]))
                ops.append((f"项内删首键 {key}[{i}]", [("del", f"{key}[{i}].{next(iter(item))}")]))
                ops.append((f"项内删末键 {key}[{i}]", [("del", f"{key}[{i}].{list(item)[-1]}")]))
                if isinstance(item.get("proxies"), list):
                    ops.append((f"组 {i} proxies 插/挪/删", [("ins", f"{key}[{i}].proxies", 0, "NEW,node"),
                                                           ("mov", f"{key}[{i}].proxies", 0, len(item["proxies"])),
                                                           ("rmi", f"{key}[{i}].proxies", 1)]))
                    ops.append((f"组 {i} proxies 整列表改写", [("set", f"{key}[{i}].proxies", ["a", "b", "c, d"])]))
        for key, val in before.items():
            if not isinstance(val, dict) or not val:
                continue
            name = next(iter(val))
            ops.append((f"改名 {key}.{name}", [("ren", f"{key}.{fm.quote_seg(name)}", "renamed.name")]))
            ops.append((f"改名冲突 {key}.{name}", [("ren", f"{key}.{fm.quote_seg(name)}", name)]))
            ops.append((f"改名成中文 {key}.{name}", [("ren", f"{key}.{fm.quote_seg(name)}", "机场 B")]))
        cases.append({"file": path.name, "text": text, "ops": ops})
    # 括号不配对的 flow 写法（PyYAML 读不了，进不了语料文件）：两边都必须解析得完、坏行原样保留、
    # 对坏节点本身的改写两边一致（曾经 `[1, 2}` 让两边引擎都死循环）
    broken = ["a: {k: [1, 2}", "a: [1, {k: v]", "a: [1, 2}", "a: {k: v]", "a: {k: [1, 2}, b: 3}", "a: [1, 2", "a: {k: v"]
    for i, line in enumerate(broken):
        text = "head: 1\n" + line + "\ntail: 2\n"
        ops = [
            ("邻居改值", [("set", "tail", 3)]),
            ("新增顶层", [("set", "__new", "x")]),
            ("坏节点整个改写", [("set", "a", "fixed")]),
            ("坏节点改成列表", [("set", "a", ["p", "q"])]),
            ("坏节点下新建子键", [("set", "a.k", "v")]),
            ("删坏节点", [("del", "a")]),
        ]
        cases.append({"file": f"<broken-flow-{i}>", "text": text, "ops": ops})
    return cases


def run_python(text: str, ops: list) -> str:
    doc = fm.parse(text)
    for op in ops:
        action, path = op[0], op[1]
        if action == "set":
            doc = fm.set_value(doc, path, op[2])
        elif action == "seti":
            doc = fm.set_value(doc, path, op[2])
        elif action == "del":
            doc = fm.remove_key(doc, path)
        elif action == "ins":
            doc = fm.insert_item(doc, fm._split_path(path), op[2], op[3])
        elif action == "rmi":
            doc = fm.remove_item(doc, fm._split_path(path), op[2])
        elif action == "mov":
            doc = fm.move_item(doc, fm._split_path(path), op[2], op[3])
        elif action == "ren":
            doc = fm.rename_key(doc, path, op[2])
    return doc.dump()


def kotlin_protocol(flat: list) -> str:
    """每条操作一个 @@CASE（与 Python 侧逐条执行一一对应）。"""
    out = []
    for c, _desc, ops in flat:
        out.append("@@CASE")
        out.append("@@TEXT")
        out.append(c["text"])
        out.append("@@ENDTEXT")
        for op in ops:
            action, path = op[0], op[1]
            if action == "del":
                out.append(f"del{FS}{path}")
            elif action in ("set", "seti"):
                t, raw = enc_value(op[2])
                out.append(f"set{FS}{path}{FS}{t}{FS}{raw}")
            elif action == "ins":
                t, raw = enc_value(op[3])
                out.append(f"ins{FS}{path}{FS}{op[2]}{FS}{t}{FS}{raw}")
            elif action == "rmi":
                out.append(f"rmi{FS}{path}{FS}{op[2]}")
            elif action == "mov":
                out.append(f"mov{FS}{path}{FS}{op[2]}{FS}{op[3]}")
            elif action == "ren":
                out.append(f"ren{FS}{path}{FS}{op[2]}")
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

    # 协议文件与子进程输出都按字节走：text 模式 / text=True 会做换行归一化，CRLF 语料会被悄悄抹平
    with tempfile.NamedTemporaryFile("wb", suffix=".txt", delete=False) as fh:
        fh.write(kotlin_protocol(flat).encode("utf-8"))
        proto = fh.name
    try:
        proc = subprocess.run(["java", "-jar", str(jar), proto],
                              capture_output=True, timeout=600)
        if proc.returncode != 0:
            print("Kotlin 引擎退出码", proc.returncode)
            print(proc.stderr.decode("utf-8", "replace")[-2000:])
            return 2
        kt_results = parse_kotlin_output(proc.stdout.decode("utf-8"))
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
