#!/usr/bin/env python3
"""跨包 import 检查：引用了 `top.yukonga.mishka.custom.*` 的顶层符号、却漏 import 的文件。

背景（教训）：SubscriptionOverridesScreen 调用了 `dragSortItem(...)`，调用点本身写对了，
但那个文件（`ui.screen.overrides` 包）漏了 `import top.yukonga.mishka.custom.forms.dragSortItem`，
CI 在 `:app:compileReleaseKotlin` 报 `Unresolved reference 'dragSortItem'`。
没有编译器时，用本脚本兜底这一类「调用点对、import 漏」的错误。

做法：
1) 解析 `app/src/main/kotlin/top/yukonga/mishka/custom/**` 里的**顶层声明**（第 0 列、
   非 private；扩展函数取最后一个点后的名字），得到「符号 → 定义它的包集合」；
2) 对每个不在定义包目录下的 .kt 文件：去掉注释后，若正文里出现该符号（词边界、且不是
   `xx.Sym` 形式的成员访问），就必须存在 `import <定义包之一>.<Sym>` 或 `import <包>.*`，
   否则报 MISMATCH（文件 + 符号 + 需要补的 import）。

用法: tools/check_cross_package_imports.py --repo <Mishka 仓库>
"""
import argparse
import pathlib
import re
import sys

CUSTOM = "top.yukonga.mishka.custom"
KT_ROOT = "app/src/main/kotlin"

_PKG_RE = re.compile(r"^package\s+([\w.]+)")
_DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"          # 同行注解
    r"(?:public\s+|internal\s+|expect\s+|actual\s+|inline\s+|suspend\s+|operator\s+|infix\s+)*"
    r"(?:fun|val|var|class|object|interface|enum\s+class|sealed\s+class|data\s+class|typealias)\s+"
    r"([\w.]+)"                              # 名字（扩展函数形如 Receiver.Name，取最后一段）
)


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="Mishka 仓库根目录")
    ap.add_argument("--root", default=KT_ROOT, help="Kotlin 源码根（相对仓库）")
    args = ap.parse_args()

    repo = pathlib.Path(args.repo)
    kt_root = repo / args.root
    custom_root = kt_root / CUSTOM.replace(".", "/")
    if not custom_root.is_dir():
        print(f"错误：找不到 {custom_root}", file=sys.stderr)
        return 2

    # 1) 顶层符号 → 定义包集合
    symbols: dict[str, set[str]] = {}
    for f in sorted(custom_root.rglob("*.kt")):
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg = None
        for line in text.splitlines():
            m = _PKG_RE.match(line)
            if m:
                pkg = m.group(1)
                break
        if not pkg:
            continue
        for line in strip_comments(text).splitlines():
            if line[:1] in (" ", "\t"):
                continue  # 只看第 0 列的顶层声明，避免把局部函数/局部类算进来
            if line.startswith("private ") or line.startswith("private\t"):
                continue
            m = _DECL_RE.match(line)
            if not m:
                continue
            name = m.group(1).split(".")[-1]
            if len(name) < 4 or not name[0].isalpha():
                continue
            symbols.setdefault(name, set()).add(pkg)

    files = sorted(kt_root.rglob("*.kt"))
    bodies = {f: strip_comments(f.read_text(encoding="utf-8", errors="replace")) for f in files}
    file_pkg = {}
    for f in files:
        for line in bodies[f].splitlines():
            m = _PKG_RE.match(line)
            if m:
                file_pkg[f] = m.group(1)
                break

    missing: list[tuple[str, str, str]] = []
    refs = 0
    for sym in sorted(symbols):
        pkgs = symbols[sym]
        body_re = re.compile(r"(?<![\w.])%s(?![\w])" % re.escape(sym))
        impl_re = re.compile(
            r"^import\s+(?:%s)\s*$"
            % "|".join(re.escape(p + "." + sym) for p in sorted(pkgs)),
            re.M,
        )
        star_re = re.compile(r"^import\s+(?:%s)\.\*\s*$" % "|".join(re.escape(p) for p in sorted(pkgs)), re.M)
        for f in files:
            if file_pkg.get(f) in pkgs:
                continue  # 与某个定义同包，无需 import
            body = bodies[f]
            if sym not in body or not body_re.search(body):
                continue
            refs += 1
            if impl_re.search(body) or star_re.search(body):
                continue
            want = sorted(p + "." + sym for p in pkgs)[0]
            missing.append((str(f.relative_to(repo)), sym, want))

    if missing:
        for rel, sym, want in sorted(missing):
            print(f"MISMATCH {rel}: 用了 {sym} 但没有 import {want}", file=sys.stderr)
        print(
            f"跨包 import 检查失败：{len(missing)} 处缺失（共检查 {len(symbols)} 个符号 / {refs} 处跨包引用）",
            file=sys.stderr,
        )
        return 1

    print(f"ok 跨包 import 检查通过（{len(symbols)} 个符号 / {refs} 处跨包引用）")
    return 0



if __name__ == "__main__":
    sys.exit(main())
