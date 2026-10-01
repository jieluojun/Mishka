#!/usr/bin/env python3
"""盯住「Kotlin 源码 ↔ Python 转写模型」的同步：任一边改了另一边没改就报警。

Python 转写（scan_kotlin.py / kotlin_edit.py）是把行级手术算法搬到沙箱里跑测试用的模型，
它不会自动跟着 Kotlin 变。这个脚本记录两边的 sha256：跑测试前先跑它，看到 STALE 就去对照
AnchorScan.kt / AnchorEdit.kt / AnchorBlock.kt 更新转写，再 `--update` 记下新基线。

用法: python3 tools/equiv/model_freshness.py [--update]
"""
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCK = HERE / "model.lock.json"
WATCHED = {
    "kotlin": [
        "app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorScan.kt",
        "app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorEdit.kt",
        "app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorBlock.kt",
    ],
    "model": ["tools/equiv/scan_kotlin.py", "tools/equiv/kotlin_edit.py"],
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str]) -> int:
    root = HERE.parent.parent
    current = {k: {rel: sha(root / rel) for rel in rels} for k, rels in WATCHED.items()}
    if "--update" in argv or not LOCK.exists():
        LOCK.write_text(json.dumps(current, indent=2, ensure_ascii=False) + "\n")
        print(f"已记录基线 -> {LOCK}")
        return 0
    old = json.loads(LOCK.read_text())
    stale = []
    for group in WATCHED:
        for rel, h in current[group].items():
            if old.get(group, {}).get(rel) != h:
                stale.append(f"{group}: {rel}")
    if stale:
        print("STALE（转写模型可能已过期，请核对后再跑 edit_props.py）：")
        for s in stale:
            print("  " + s)
        return 1
    print("模型与 Kotlin 源码的基线一致")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
