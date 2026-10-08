#!/usr/bin/env python3
"""面板 / Web 界面相关文件里的资源与图标引用静态检查。

背景：这个环境里没有 Android SDK / Gradle，`R.string.xxx` 写错、`MiuixIcons.Xxx` 拼错都等到 CI 才炸
（Unresolved reference）。这两类引用纯靠字符串，可以离线核：

1) 代码里的 `R.string.foo` 必须在 **全部** locale 的 strings.xml 里都存在（缺一个 locale，
   对应语言下就是硬崩或者掉到默认语言——`values-ru` 这种第三方 locale 最容易漏）；
2) 这批新增的 `home_panel*` / `panel_*` 键在各个 locale 之间必须**一一对应**，不许只在英文里有；
3) 代码里的 `MiuixIcons.Xxx` 必须在 miuix-icons-0.9.4-extended.txt（0.9.4 的 extended 图标清单，
   从 classes.jar 里导出）里，拼错即报。

用法: tools/panel/check_panel_refs.py --repo <Mishka 仓库>
"""
import argparse
import pathlib
import re
import sys

# 本功能新增/改动的 Kotlin 文件（相对仓库）
TARGET_FILES = [
    "app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelEntry.kt",
    "app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt",
    "app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelSheet.kt",
    "app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelStore.kt",
    "app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt",
    "app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/QuickEntriesSection.kt",
]

LOCALES = ["values", "values-zh-rCN", "values-zh-rTW", "values-ru"]

# 本功能新增的字符串键（要求各 locale 一一对应）
REQUIRED_KEYS = [
    "home_panel",
    "home_panel_subtitle",
    "panel_title",
    "panel_sheet_add_title",
    "panel_field_name",
    "panel_field_url",
    "panel_local_name",
    "panel_local_subtitle",
    "panel_local_not_running",
    "panel_error_name_required",
    "panel_error_url_invalid",
    "panel_add",
    "panel_clear_cache",
    "panel_clear_cache_message",
    "panel_clear_cache_failed",
    "panel_save_failed",
    "panel_no_file_picker",
]

STRING_RE = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
ICON_RE = re.compile(r"MiuixIcons\.([A-Za-z0-9_]+)")
STRING_DECL_RE = re.compile(r'<string name="([A-Za-z0-9_]+)"')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="Mishka 仓库根目录")
    args = ap.parse_args()
    repo = pathlib.Path(args.repo)
    tool_dir = pathlib.Path(__file__).resolve().parent

    problems: list[str] = []

    # --- 各 locale 的字符串键集合 ---
    locale_keys: dict[str, set[str]] = {}
    for loc in LOCALES:
        path = repo / "app/src/main/res" / loc / "strings.xml"
        if not path.is_file():
            problems.append(f"缺少 locale 文件：{path.relative_to(repo)}")
            continue
        locale_keys[loc] = set(STRING_DECL_RE.findall(path.read_text(encoding="utf-8")))

    if "values" in locale_keys:
        for key in REQUIRED_KEYS:
            for loc in LOCALES:
                if loc == "values" or loc not in locale_keys:
                    continue
                if key not in locale_keys[loc]:
                    problems.append(f"{loc}/strings.xml 缺字符串 {key}（values/ 有）")
            if key not in locale_keys["values"]:
                problems.append(f"values/strings.xml 缺字符串 {key}")

    # --- 代码里的引用 ---
    icon_inventory = {
        line.strip()
        for line in (tool_dir / "miuix-icons-0.9.4-extended.txt").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }
    used_strings: set[str] = set()
    used_icons: set[str] = set()
    for rel in TARGET_FILES:
        path = repo / rel
        if not path.is_file():
            problems.append(f"缺少文件：{rel}")
            continue
        text = path.read_text(encoding="utf-8")
        used_strings |= set(STRING_RE.findall(text))
        used_icons |= set(ICON_RE.findall(text))

    for key in sorted(used_strings):
        for loc in LOCALES:
            keys = locale_keys.get(loc)
            if keys is not None and key not in keys:
                problems.append(f"{loc}/strings.xml 缺 {key}（代码 R.string.{key} 引用了）")

    for icon in sorted(used_icons):
        if icon not in icon_inventory:
            problems.append(f"MiuixIcons.{icon} 不在 miuix-icons 0.9.4 的 extended 清单里")

    if problems:
        for p in problems:
            print(f"MISMATCH {p}", file=sys.stderr)
        return 1

    print(
        f"ok 面板引用检查通过（{len(used_strings)} 个字符串键 × {len(locale_keys)} 个 locale / "
        f"{len(used_icons)} 个图标）"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
