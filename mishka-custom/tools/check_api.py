#!/usr/bin/env python3
"""静态核对「自定义代码用到的 API 是否真的存在」——沙箱里跑不了 Gradle，这是最接近编译的检查。

做两件事：
  1. **符号存在性**：逐个 import 检查目标（miuix 0.9.4 源码/class、Mishka 上游源码、scripta 源码）
     里确实有同名声明（fun / class / object / val / 图标扩展属性 / 资源）。
  2. **命名参数存在性**：把自定义代码里 `Foo(a = ..., b = ...)` 的具名实参抽出来，
     对照 `Foo` 的声明参数表，报出「在任何重载里都找不到」的名字（这类必然是编译错误）。

用法: python3 tools/check_api.py [--root <仓库根>]
默认按 tools/api_paths.json 里的路径找依赖源码，缺失的路径会跳过并计入「未核对」。
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CUSTOM_DIRS = [
    "app/src/main/kotlin/top/yukonga/mishka/custom",
]

DECL_RES = [
    re.compile(r"^\s*(?:public |internal |private |expect |actual |inline |@\w+(?:\([^)]*\))?\s*)*fun\s+(?:<[^>]*>\s*)?(?:(?:[\w.<>?,]|\[|\]|\.)+\.)?([A-Za-z_][A-Za-z0-9_]*)"),
    re.compile(r"^\s*(?:public |internal |private |expect |actual |open |abstract |sealed |data |value |@\w+(?:\([^)]*\))?\s*)*(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"),
    re.compile(r"^\s*(?:public |internal |private |@\w+(?:\([^)]*\))?\s*)*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"),
]


def params_of(text: str, start: int) -> str:
    """从 fun 名之后第一个 '(' 起抓平衡的参数表文本。"""
    i = text.find("(", start)
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return ""


def split_top_level(text: str) -> list[str]:
    """按顶层逗号切分；`<`/`>` 只在泛型位置算嵌套（避开 `->` / `=>` 里的箭头）。"""
    depth, cur, parts = 0, "", []
    prev = ""
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "<" and prev not in "-=":
            depth += 1
        elif ch == ">" and prev not in "-=":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
        prev = ch
    parts.append(cur)
    return parts


def param_names(param_text: str) -> set[str]:
    names = set()
    parts = split_top_level(param_text)
    for p in parts:
        head = p.split("=")[0]
        if ":" in head:
            name = head.split(":")[0].strip()
            name = re.sub(r"^(?:val|var|private|internal|public|protected|final|open|override|lateinit|const)\s+", "", name)
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                names.add(name)
    return names


def arg_names(call_text: str) -> set[str]:
    """抓 `Foo(a = ..., b = ..., Modifier.x, { ... })` 里的具名实参名。"""
    names = set()
    for p in split_top_level(call_text):
        m = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", p)
        if m:
            names.add(m.group(1))
    return names


class Decls:
    def __init__(self) -> None:
        self.by_pkg: dict[str, dict[str, list[str]]] = {}

    def add_file(self, text: str, fallback_pkg: str) -> None:
        m = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = m.group(1) if m else fallback_pkg
        bucket = self.by_pkg.setdefault(pkg, {})
        lines = text.split("\n")
        for idx, line in enumerate(lines):
            for rx in DECL_RES:
                mm = rx.match(line)
                if not mm:
                    continue
                name = mm.group(1)
                tail = "\n".join(lines[idx:idx + 60])
                bucket.setdefault(name, []).append(params_of(tail, mm.end() - len(name)))
                break

    def add_class_names(self, pkg: str, names: list[str]) -> None:
        bucket = self.by_pkg.setdefault(pkg, {})
        for n in names:
            bucket.setdefault(n, []).append(None)  # class 文件没有参数表

    def has(self, fq: str) -> bool:
        pkg, _, name = fq.rpartition(".")
        return name in self.by_pkg.get(pkg, {})

    def params(self, name: str) -> list[str]:
        out = []
        for bucket in self.by_pkg.values():
            for p in bucket.get(name, []):
                if p is not None:
                    out.append(p)
        return out


def scan_dir(decls: Decls, root: Path, pkg_hint: str = "") -> int:
    n = 0
    for f in root.rglob("*.kt"):
        try:
            decls.add_file(f.read_text(encoding="utf-8", errors="replace"), pkg_hint)
            n += 1
        except OSError:
            pass
    return n


def main(argv: list[str]) -> int:
    cfg_path = HERE / "api_paths.json"
    if not cfg_path.exists():
        print(f"缺少 {cfg_path}", file=sys.stderr)
        return 2
    cfg = json.loads(cfg_path.read_text())
    for i, a in enumerate(argv):
        if a == "--root" and i + 1 < len(argv):
            cfg["repo_root"] = argv[i + 1]
    print("依赖源码路径：")
    decls = Decls()
    missing: list[str] = []
    for label, path in cfg["sources"].items():
        p = Path(path)
        if not p.exists():
            missing.append(label)
            print(f"  [缺失] {label}: {p}")
            continue
        n = scan_dir(decls, p)
        print(f"  {label}: {n} 个 .kt  ({p})")
    for label, path in cfg.get("class_dirs", {}).items():
        p = Path(path)
        if not p.exists():
            missing.append(label)
            print(f"  [缺失] {label}: {p}")
            continue
        # 目录名即包名；`XxxKt.class` 代表包级属性/函数 Xxx
        pkg = cfg["class_dirs_packages"][label]
        names = [f.stem[:-2] if f.stem.endswith("Kt") else f.stem for f in p.glob("*.class")]
        # 类文件里没有参数表，只用于「符号存在性」；`XxxKt` 代表包级属性/函数 Xxx
        decls.add_class_names(pkg, names)
        print(f"  {label}: {len(names)} 个 class（仅核对符号存在）({p})")

    # 上游 app 自己 import 过的 FQN：上游能编译，说明符号一定存在
    upstream_fqns: set[str] = set()
    upstream_root = Path(cfg["sources"].get("mishka-app", ""))
    if upstream_root.exists():
        for f in upstream_root.rglob("*.kt"):
            upstream_fqns |= set(re.findall(r"^import\s+([\w.]+)", f.read_text(encoding="utf-8", errors="replace"), re.M))

    # R 资源
    res_root = Path(cfg["android_res"])
    res_names: set[str] = set()
    if res_root.exists():
        for f in res_root.rglob("*.xml"):
            if f.name != "strings.xml":
                continue
            res_names |= set(re.findall(r'<(?:string|plurals)\s+name="([^"]+)"', f.read_text(encoding="utf-8", errors="replace")))

    # ---- 逐个自定义文件检查 ----
    # 自定义源码根目录：优先 --root / 配置里的 repo_root，找不到就按「脚本所在位置向上找」，
    # 这样工具既能放在交付根目录下跑，也能被复制进仓库的 mishka-custom/ 后原地跑。
    custom_rel = CUSTOM_DIRS[0]
    candidates = [Path(cfg["repo_root"]) if cfg.get("repo_root") else None, HERE.parent, HERE.parent.parent, HERE.parent.parent.parent, Path.cwd()]
    root = None
    for cand in candidates:
        if cand and (cand / custom_rel).is_dir():
            root = cand
            break
    if root is None:
        print(f"找不到自定义源码目录 {custom_rel}；用 --root <仓库根> 指定", file=sys.stderr)
        return 2
    cfg["repo_root"] = str(root)
    print(f"自定义源码根：{root}")
    files = []
    for d in CUSTOM_DIRS:
        files += sorted((root / d).rglob("*.kt"))

    unknown_imports: list[str] = []
    bad_params: list[str] = []
    checked_imports = 0
    checked_calls = 0

    pkg_re = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+\w+)?$", re.M)
    call_re = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*\(")

    for f in files:
        text = f.read_text(encoding="utf-8")
        body = re.sub(r"^import .*$", "", text, flags=re.M)
        imported_names = set()
        for fq in pkg_re.findall(text):
            imported_names.add(fq.rpartition(".")[2])
            if fq.startswith(("androidx.", "kotlin.", "kotlinx.", "java.")):
                continue  # 平台/Compose 标准库不在核对范围
            if fq == "top.yukonga.mishka.R" or fq.endswith(".R"):
                continue
            if fq.startswith("top.yukonga.mishka.R."):
                res = fq.rpartition(".")[2]
                if res not in res_names:
                    bad_params.append(f"{f.name}: 资源 R.{res} 不存在")
                continue
            if fq.startswith(("top.yukonga.miuix.", "top.yukonga.mishka.", "top.yukonga.scripta.")):
                checked_imports += 1
                if not decls.has(fq) and fq not in upstream_fqns:
                    unknown_imports.append(f"{f.name}: import {fq} —— 在依赖源码里找不到声明")

        # 具名实参核对：只看我们自己 import 进来的类型/函数，避免误报
        for m in call_re.finditer(body):
            name = m.group(1)
            if name not in imported_names:
                continue
            tail = body[m.end():m.end() + 2000]
            depth = 1
            end = len(tail)
            for i, ch in enumerate(tail):
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        end = i
                        break
            arg_text = tail[:end]
            if len(arg_text) > 1500:
                continue  # 疑似不是调用点
            named = arg_names(arg_text)
            decl_params = decls.params(name)
            if not decl_params:
                continue
            known = set()
            for p in decl_params:
                known |= param_names(p)
            checked_calls += 1
            for a in sorted(named - known):
                if len(a) < 3:
                    continue
                bad_params.append(f"{f.name}: {name}(...) 的具名实参 `{a}` 在任何重载里都不存在")

    print(f"\n核对 import {checked_imports} 条、调用点 {checked_calls} 个")
    if missing:
        print(f"未核对的依赖（路径缺失，{len(missing)} 个）：{', '.join(missing)}")
    problems = unknown_imports + bad_params
    if problems:
        print("\n发现问题：")
        for p in problems:
            print("  " + p)
        print(f"\n{len(problems)} 个问题")
        return 1
    print("\n全部通过：import 的符号都存在，具名实参都能在对应重载里找到")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
