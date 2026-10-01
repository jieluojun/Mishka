#!/usr/bin/env python3
"""三方对拍：参考实现(JS) == Kotlin 转写(Python) == 手写期望(expected.json)。

用途：AnchorScan.kt 是 mihomo_box `scanAnchorGraph` 的 Kotlin 移植，这个脚本保证
「扫描结果」在 10 个边界样本上逐字段一致（名字集合与顺序、定义行、引用行、merge 判定、
路径文案、悬空引用）。任何一方偏离都会以 diff 形式打印出来。

用法: python3 tools/equiv/compare.py [--update-js]
      --update-js 先跑 gen_ref_scan.py 从参考 core.js 重新生成 ref_scan.mjs（默认用已生成的）
"""
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORPUS = HERE / "corpus"
REF_JS = HERE / "ref_scan.mjs"
CORE_JS = Path("/home/user/work/ref/mihomo_box/webroot/ui/js/core.js")


def run_ref() -> dict:
    out = subprocess.run(
        ["node", str(REF_JS), str(CORPUS)], capture_output=True, text=True, check=True
    )
    return json.loads(out.stdout)


def run_kotlin() -> dict:
    sys.path.insert(0, str(HERE))
    import scan_kotlin  # noqa: E402  (同目录脚本，作为库用)

    out = {}
    for f in sorted(CORPUS.glob("*.yaml")):
        out[f.name] = scan_kotlin.scan(f.read_text(encoding="utf-8"))
    return out


def norm(d: dict) -> dict:
    """统一成可比较的形状：anchors 列表（保序）+ danglers 列表（保序）。"""
    return {
        "anchors": [
            {
                "name": a["name"],
                "defs": [[x["line"], x["path"]] for x in a["defs"]],
                "refs": [[x["line"], x["path"], bool(x["merge"])] for x in a["refs"]],
                "mergeCnt": a["mergeCnt"],
                "aliasCnt": a["aliasCnt"],
            }
            for a in d["anchors"]
        ],
        "danglers": [[x["name"], [r["line"] for r in x["refs"]]] for x in d["danglers"]],
    }


def check_expected(name: str, got: dict, exp: dict) -> list[str]:
    problems: list[str] = []
    got = norm(got)
    exp_anchors = [
        {
            "name": n,
            "defs": [list(x) for x in v["defs"]],
            "refs": [[x[0], x[1], bool(x[2])] for x in v["refs"]],
            "mergeCnt": sum(1 for x in v["refs"] if x[2]),
            "aliasCnt": sum(1 for x in v["refs"] if not x[2]),
        }
        for n, v in exp["anchors"].items()
    ]
    exp_danglers = [[n, lines] for n, lines in exp["danglers"].items()]
    if got["anchors"] != exp_anchors:
        problems.append(f"  anchors 与期望不一致\n    got: {json.dumps(got['anchors'], ensure_ascii=False)}\n    exp: {json.dumps(exp_anchors, ensure_ascii=False)}")
    if got["danglers"] != exp_danglers:
        problems.append(f"  danglers 与期望不一致\n    got: {json.dumps(got['danglers'], ensure_ascii=False)}\n    exp: {json.dumps(exp_danglers, ensure_ascii=False)}")
    return problems


def main(argv: list[str]) -> int:
    if "--update-js" in argv:
        subprocess.run(
            [sys.executable, str(HERE / "gen_ref_scan.py"), str(CORE_JS), str(REF_JS)], check=True
        )
    if not REF_JS.exists():
        print(f"缺少 {REF_JS}，请先跑 gen_ref_scan.py", file=sys.stderr)
        return 2

    ref = run_ref()
    kot = run_kotlin()
    expected = json.loads((CORPUS / "expected.json").read_text(encoding="utf-8"))

    failures = 0
    for name in sorted(ref):
        js, py = norm(ref[name]), norm(kot[name])
        if js != py:
            failures += 1
            print(f"FAIL {name}: 参考实现与 Kotlin 转写不一致")
            print(f"    js: {json.dumps(js, ensure_ascii=False)}")
            print(f"    py: {json.dumps(py, ensure_ascii=False)}")
            continue
        if name not in expected:
            print(f"SKIP {name}: 没有手写期望")
            continue
        problems = check_expected(name, kot[name], expected[name])
        if problems:
            failures += 1
            print(f"FAIL {name}: 与手写期望不一致")
            print("\n".join(problems))
        else:
            print(f"OK   {name}: 参考实现 / Kotlin 转写 / 期望 三方一致")

    print(f"\n{len(ref)} 个样本，{failures} 个失败")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
