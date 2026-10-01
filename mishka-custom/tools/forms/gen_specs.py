#!/usr/bin/env python3
"""从 tools/forms/fields.json 生成两样东西：

1. FORMS-P1.md        —— P1 阶段的界面清单（hub 13 格 + 每格的字段表），给人看
2. FormSpecs.kt       —— Kotlin 字段表（Kotlin 侧不手抄，改表就重跑）

用法：python3 tools/forms/gen_specs.py [--write | --check]
不带参数只打印摘要；--write 重新生成产物；--check 逐字节比对产物是否与 fields.json 同步（CI 用）。
"""
from __future__ import annotations

import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
DELIVER = HERE.parent.parent
FIELDS = HERE / "fields.json"

# P1 = 配置主页（hub + 全局配置/DNS/域名嗅探/入站/NTP/实验性）
P1_TABLES = [
    ("GENERAL_SECTIONS", "全局配置"),
    ("DNS_SECTIONS", "DNS"),
    ("SNIFF_SECTIONS", "域名嗅探"),
    ("INBOUND_PORTS", "入站·端口"),
    ("TUN_SECTIONS", "入站·TUN"),
    ("EBPF_SECTIONS", "入站·eBPF"),
    ("NTP_SECTIONS", "NTP"),
    ("EXPERIMENTAL_SECTIONS", "实验性配置"),
]

# 参考实现里的类型 → Kotlin 枚举名
TYPE_MAP = {
    "text": "TEXT", "bool": "BOOL", "number": "NUMBER", "select": "SELECT",
    "list": "LIST", "numlist": "NUMLIST", "userlist": "USERLIST",
    "textarea": "TEXTAREA", "maptext": "MAPTEXT", "maplist": "MAPLIST",
    "dnslist": "DNSLIST", "applist": "APPLIST", "headers": "HEADERS",
    "fakeiprule": "FAKEIPRULE", "rulesetpick": "RULESETPICK", "checkbox": "CHECKBOX",
    "password": "PASSWORD", "file": "FILE", "button": "BUTTON",
}


def kstr(s) -> str:
    """Kotlin 字符串字面量（转义 $ 与引号，避免模板串误展开）。"""
    out = str(s).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return '"' + out + '"'


def section_of(value):
    """把参考实现的一节 {title, fields:[...]} 规范化。"""
    out = []
    for sec in value:
        title = sec.get("title") or ""
        fields = []
        for f in sec.get("fields", []):
            fields.append(f)
        out.append((title, fields))
    return out


def kotlin_row(f: dict, indent: str) -> str:
    """一节里的一行：普通字段 → FormField；{custom: "..."} → FormCustomRow（专门控件）。"""
    if "custom" in f and "path" not in f:
        return f"FormCustomRow({kstr(f['custom'])})"
    return kotlin_field(f, indent)


def kotlin_field(f: dict, indent: str) -> str:
    args = [f"path = {kstr(f['path'])}", f"label = {kstr(f['label'])}",
            f"type = FormFieldType.{TYPE_MAP.get(f.get('type', 'text'), 'TEXT')}"]
    if f.get("options"):
        if isinstance(f["options"], dict) and "__dynamic__" in f["options"]:
            args.append(f"optionsDynamic = {kstr(f['options']['__dynamic__'])}")
        else:
            opts = ", ".join(f"FormOption({kstr(o[0])}, {kstr(o[1])})" for o in f["options"])
            args.append(f"options = listOf({opts})")
    for src, dst in (("placeholder", "placeholder"), ("desc", "desc"), ("hint", "hint"),
                     ("tag", "tag")):
        if f.get(src):
            args.append(f"{dst} = {kstr(f[src])}")
    if f.get("def") is not None:
        args.append(f"default = {str(bool(f['def'])).lower()}")
    if f.get("tagCls") == "o":
        args.append("tagWarn = true")
    for src, dst in (("optional", "optional"), ("allowEmpty", "allowEmpty"),
                     ("asSwitch", "asSwitch"), ("restartOnSave", "restartOnSave")):
        if f.get(src):
            args.append(f"{dst} = true")
    if f.get("bool") is not None:
        args.append(f"boolKind = {kstr(f['bool'])}")
    if f.get("tri"):
        args.append("tri = true")
    if f.get("boolAs"):
        args.append(f"boolAs = {kstr(f['boolAs'])}")
    if f.get("keyPlaceholder"):
        args.append(f"keyPlaceholder = {kstr(f['keyPlaceholder'])}")
    if f.get("dnsIpOnly"):
        args.append("dnsIpOnly = true")
    if f.get("dns"):
        args.append(f"dns = {str(bool(f['dns'])).lower()}")
    body = ",\n".join(f"{indent}    {a}" for a in args)
    return f"FormField(\n{body},\n{indent})"


def gen_kotlin(data) -> str:
    parts = ['''package top.yukonga.mishka.custom.forms

// 本文件由 tools/forms/gen_specs.py 从参考实现的字段表生成，请勿手改。
// 数据源：tools/forms/fields.json（extract_fields.mjs 从 mihomo_box 的 WebUI 求值得到）。

enum class FormFieldType {
    TEXT, BOOL, NUMBER, SELECT, LIST, NUMLIST, USERLIST, TEXTAREA, MAPTEXT, MAPLIST,
    DNSLIST, APPLIST, HEADERS, FAKEIPRULE, RULESETPICK, CHECKBOX, PASSWORD, FILE, BUTTON,
}

data class FormOption(val value: String, val label: String)

data class FormField(
    val path: String,
    val label: String,
    val type: FormFieldType,
    val options: List<FormOption> = emptyList(),
    val optionsDynamic: String? = null,
    val desc: String? = null,
    val placeholder: String? = null,
    val hint: String? = null,
    val tag: String? = null,
    val tagWarn: Boolean = false,
    val default: Boolean? = null,
    val optional: Boolean = false,
    val allowEmpty: Boolean = false,
    val asSwitch: Boolean = false,
    val restartOnSave: Boolean = false,
    val boolKind: String? = null,
    val boolAs: String? = null,
    val tri: Boolean = false,
    val keyPlaceholder: String? = null,
    val dnsIpOnly: Boolean = false,
    val dns: Boolean = false,
) : FormRow

sealed interface FormRow
data class FormCustomRow(val kind: String) : FormRow

data class FormSection(val title: String, val fields: List<FormRow>)
''']
    for name, label in P1_TABLES:
        if name not in data["pages"]:
            continue
        secs = section_of(data["pages"][name]["value"])
        parts.append(f"\n/** {label}（对应参考实现 {data['pages'][name]['file']}:{data['pages'][name]['line']}） */")
        parts.append(f"val {name}: List<FormSection> = listOf(")
        for title, fields in secs:
            parts.append(f"    FormSection({kstr(title)}, listOf(")
            for f in fields:
                parts.append("        " + kotlin_row(f, " " * 8) + ",")
            parts.append("    )),")
        parts.append(")")
    return "\n".join(parts) + "\n"


def gen_markdown(data) -> str:
    lines = ["# P1 配置主页：界面清单（自动生成，勿手改）", "",
             "来源：`tools/forms/fields.json`（从 mihomo_box WebUI 求值提取）。", "",
             "## hub：13 个入口", "",
             "| # | 入口 | 子标题（计数） | 本阶段 |",
             "| --- | --- | --- | --- |"]
    hub = [("全局配置", "模式 / API / 日志 / Geo / Smart"), ("DNS", "已启用 · 增强模式"),
           ("域名嗅探", "TLS/HTTP/QUIC 域名恢复"), ("入站", "N 个端口 · TUN 开/关 · N 个监听器"),
           ("出站代理", "N 个节点"), ("代理集合", "N 个订阅"), ("代理组", "N 个代理组"),
           ("路由规则", "N 条规则"), ("规则集合", "N 个规则集"), ("子规则", "N 组子规则"),
           ("流量隧道", "TCP/UDP 端口转发"), ("NTP", "时间同步"), ("实验性配置", "QUIC / 拨号器")]
    p1_names = {"全局配置", "DNS", "域名嗅探", "入站", "NTP", "实验性配置"}
    for i, (name, sub) in enumerate(hub, 1):
        lines.append(f"| {i} | {name} | {sub} | {'✅ P1' if name in p1_names else 'P2'} |")
    lines += ["", "## P1 各页字段", ""]
    for name, label in P1_TABLES:
        if name not in data["pages"]:
            continue
        secs = section_of(data["pages"][name]["value"])
        total = sum(len(f) for _, f in secs)
        lines.append(f"### {label}（{name}，共 {total} 个字段）")
        lines.append("")
        for title, fields in secs:
            lines.append(f"**{title}**（{len(fields)}）")
            lines.append("")
            lines.append("| 路径 | 标签 | 类型 | 说明 |")
            lines.append("| --- | --- | --- | --- |")
            for f in fields:
                if "custom" in f and "path" not in f:
                    lines.append(f"| — | （专门控件） | `{f['custom']}` | 由代码实现的定制行，见 P1 渲染层 |")
                    continue
                t = f.get("type", "text")
                extra = []
                if f.get("options") and not isinstance(f["options"], dict):
                    vals = " / ".join(str(o[0]) for o in f["options"][:8])
                    extra.append(f"取值：{vals}")
                if f.get("def") is not None:
                    extra.append(f"默认 {'开' if f['def'] else '关'}")
                if f.get("tag"):
                    extra.append(f"⚠️ {f['tag']}")
                if f.get("placeholder"):
                    extra.append(f"提示：{f['placeholder']}")
                if f.get("hint"):
                    extra.append(f"格式：{f['hint']}")
                if f.get("desc"):
                    extra.append(str(f["desc"])[:60])
                lines.append(f"| `{f['path']}` | {f['label']} | {t} | {'；'.join(extra)} |")
            lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    data = json.loads(FIELDS.read_text(encoding="utf-8"))
    kt = gen_kotlin(data)
    md = gen_markdown(data)
    n_fields = sum(1 for line in kt.splitlines() if line.strip().startswith("path ="))
    n_sections = kt.count("FormSection(")
    print(f"生成：FormSpecs.kt {len(kt.splitlines())} 行（{n_sections} 个小节 / {n_fields} 个字段），"
          f"FORMS-P1.md {len(md.splitlines())} 行")
    out_kt = DELIVER / "app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormSpecs.kt"
    out_md = DELIVER / "FORMS-P1.md"
    if "--write" in sys.argv:
        out_kt.parent.mkdir(parents=True, exist_ok=True)
        out_kt.write_text(kt, encoding="utf-8")
        out_md.write_text(md, encoding="utf-8")
        print("已写入：")
        print("  " + str(out_kt))
        print("  " + str(out_md))
        return 0
    if "--check" in sys.argv:
        # 产物是生成物：改字段表后必须重生成，否则这里会抓到漂移
        bad = 0
        for path, want in ((out_kt, kt), (out_md, md)):
            if not path.exists():
                print(f"FAIL 缺产物 {path}")
                bad += 1
            elif path.read_text(encoding="utf-8") != want:
                print(f"FAIL 产物与 fields.json 不同步：{path}（跑 python3 tools/forms/gen_specs.py --write）")
                bad += 1
            else:
                print(f"ok  {path.name} 与字段表一致")
        return 1 if bad else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
