#!/usr/bin/env python3
"""跨包 import 检查：调用了别处的顶层函数 / 构造函数、却漏 import 的文件。

背景（教训，两次都是同一类错误）：
1. `SubscriptionOverridesScreen` 调用了 `dragSortItem(...)`，调用点本身写对了，但那个文件
   （`ui.screen.overrides` 包）漏了 `import top.yukonga.mishka.custom.forms.dragSortItem`，
   CI 在 `:app:compileReleaseKotlin` 报 `Unresolved reference 'dragSortItem'`；
2. `App.kt` 改调新抽出的 `MishkaTheme(...)`（`ui.theme` 包）却没补 import，CI 报
   `Unresolved reference 'MishkaTheme'` + 连带的「@Composable invocations can only happen
   from the context of a @Composable function」。

没有编译器时，用本脚本兜底这一类「调用点对、import 漏」的错误——不需要 Android SDK，只看源码
文本。扫描范围是**整个 app 模块**（早期版本只扫 `custom/`，漏掉了第 2 条那种自定义包之外的
符号）。

做法：
1) 解析 `app/src/main/kotlin/**` 里所有 .kt 的**顶层声明**（第 0 列、非 private；扩展函数取
   最后一个点后的名字），得到「符号 → 定义它的包」；只保留**全项目唯一**的符号名（同名多包
   有歧义，宁可不报，否则误报一堆）；
2) 对每个 .kt：先去掉注释与字符串字面量，若正文里出现该符号的**调用**（`Sym(`，词边界、前面
   不是 `.`，即不是 `xx.Sym(...)` 这种成员/扩展调用），就必须满足下面之一，否则报 MISMATCH：
   - 文件所在包就是符号的定义包；
   - 文件所在包里有同名顶层声明（同包可见，无需 import）；
   - 文件自己声明过同名符号（成员函数 / 局部函数等，任何缩进层级都算）；
   - 文件里有 `import <定义包>.<Sym>` 或 `import <定义包>.*`；
   - 文件里显式 import 了**别的** `*.Sym`（那说明调用的是第三方同名符号，跳过不判）。

**已知边界**：只查「调用位置」（`Sym(`）。类型位置（形参类型、泛型实参）不查——实测那会引入
几十条误报（项目里 `label` / `summary` 这类扩展属性名与局部变量同名）。漏 import 一个纯类型
的写法还得靠编译器兜。

用法: tools/check_cross_package_imports.py --repo <Mishka 仓库>
"""
import argparse
import pathlib
import re
import sys

KT_ROOT = "app/src/main/kotlin"

_PKG_RE = re.compile(r"^package\s+([\w.]+)")
_DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"          # 同行注解
    r"(?:public\s+|internal\s+|expect\s+|actual\s+|inline\s+|suspend\s+|operator\s+|infix\s+)*"
    r"(?:fun|val|var|class|object|interface|enum\s+class|sealed\s+class|data\s+class|typealias)\s+"
    r"([\w.]+)"                              # 名字（扩展函数形如 Receiver.Name，取最后一段）
)
# 任意缩进层级的声明，用来判断「文件自己声明过这个符号」（成员函数 / 局部函数等）
_DECL_ANY_RE = re.compile(r"\b(?:fun|val|var|class|object|interface|typealias)\s+([\w.]+)")
_IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+\w+)?\s*$", re.M)


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def strip_strings(text: str) -> str:
    """去掉字符串字面量与字符字面量（避免文案里出现符号名被当成调用）。"""
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])'", "''", text)
    return text


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="Mishka 仓库根目录")
    ap.add_argument("--root", default=KT_ROOT, help="Kotlin 源码根（相对仓库）")
    args = ap.parse_args()

    repo = pathlib.Path(args.repo)
    kt_root = repo / args.root
    if not kt_root.is_dir():
        print(f"错误：找不到 {kt_root}", file=sys.stderr)
        return 2

    files = sorted(kt_root.rglob("*.kt"))
    bodies = {f: strip_strings(strip_comments(f.read_text(encoding="utf-8", errors="replace"))) for f in files}

    file_pkg = {}
    for f in files:
        for line in bodies[f].splitlines():
            m = _PKG_RE.match(line)
            if m:
                file_pkg[f] = m.group(1)
                break

    # 1) 「符号 → 定义包」+「包 → 该包声明过的名字」+「文件 → 该文件声明过的名字」
    symbols: dict[str, set[str]] = {}
    pkg_names: dict[str, set[str]] = {}
    file_names: dict[pathlib.Path, set[str]] = {}
    for f in files:
        pkg = file_pkg.get(f)
        body = bodies[f]
        own: set[str] = set()
        for line in body.splitlines():
            if line.startswith("private ") or line.startswith("private\t"):
                continue
            m = _DECL_RE.match(line) if line[:1] not in (" ", "\t") else None
            m = m or _DECL_ANY_RE.search(line)
            if not m:
                continue
            name = m.group(1).split(".")[-1]
            if len(name) < 4 or not name[0].isalpha():
                continue
            own.add(name)
        file_names[f] = own
        if not pkg:
            continue
        for line in body.splitlines():
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
            pkg_names.setdefault(pkg, set()).add(name)

    # 只保留全项目唯一的符号名（同名多包 = 歧义，跳过以免误报）
    unique = {name: next(iter(pkgs)) for name, pkgs in symbols.items() if len(pkgs) == 1}
    imports = {f: set(_IMPORT_RE.findall(bodies[f])) for f in files}

    missing: list[tuple[str, str, str]] = []
    refs = 0
    for sym in sorted(unique):
        pkg = unique[sym]
        want = f"{pkg}.{sym}"
        call_re = re.compile(r"(?<![\w.])%s\s*\(" % re.escape(sym))
        for f in files:
            fpkg = file_pkg.get(f)
            if fpkg == pkg:
                continue  # 与定义同包，无需 import
            if fpkg and sym in pkg_names.get(fpkg, set()):
                continue  # 同包有同名顶层声明，调用的应该是它
            if sym in file_names.get(f, set()):
                continue  # 文件自己声明过同名符号（成员函数 / 局部函数）
            body = bodies[f]
            if sym not in body or not call_re.search(body):
                continue
            refs += 1
            fimports = imports[f]
            if want in fimports or f"{pkg}.*" in fimports:
                continue
            if any(imp.endswith(f".{sym}") for imp in fimports):
                continue  # 显式 import 的是别的同名符号，跳过
            missing.append((str(f.relative_to(repo)), sym, want))

    if missing:
        for rel, sym, want in sorted(missing):
            print(f"MISMATCH {rel}: 调用了 {sym}( 但没有 import {want}", file=sys.stderr)
        print(
            f"跨包 import 检查失败：{len(missing)} 处缺失（共检查 {len(unique)} 个唯一符号 / {refs} 处跨包调用）",
            file=sys.stderr,
        )
        return 1

    print(f"ok 跨包 import 检查通过（{len(unique)} 个唯一符号 / {refs} 处跨包调用）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
