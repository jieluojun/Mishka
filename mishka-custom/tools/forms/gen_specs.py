#!/usr/bin/env python3
"""从 tools/forms/fields.json（参考实现提取）+ tools/forms/fields_p2.json（extract_flow.mjs 从参考实现流程页提取）生成四样东西：

1. FORMS-P1.md        —— P1 阶段的界面清单（hub 13 格 + 每格的字段表），给人看
2. FormSpecs.kt       —— P1 Kotlin 字段表（Kotlin 侧不手抄，改表就重跑）
3. FORMS-P2.md        —— P2 阶段（出站代理 / 代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道）的字段清单
4. FormSpecsP2.kt     —— P2 Kotlin 字段表（单独一个文件：各表 by lazy，避免一个巨大的静态初始化块）

用法：python3 tools/forms/gen_specs.py [--write | --check]
不带参数只打印摘要；--write 重新生成产物；--check 逐字节比对产物是否与字段表同步（CI 用）。
"""
from __future__ import annotations

import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
DELIVER = HERE.parent.parent
FIELDS = HERE / "fields.json"
FIELDS_P2 = HERE / "fields_p2.json"

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

# P2 = 流程页里「字段表驱动」的四页（出站代理的表直接来自 fields.json，规则 / 子规则没有字段表）
P2_PAGES = [
    ("GROUP_SECTIONS", "代理组", "proxy-groups（序列，按下标编辑；带 only 的字段按组的 type 显隐）"),
    ("PROXY_PROVIDER_SECTIONS", "代理集合", "proxy-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）"),
    ("RULE_PROVIDER_SECTIONS", "规则集合", "rule-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）"),
    ("TUNNEL_SECTIONS", "流量隧道", "tunnels（序列；单行写法 `tcp/udp,地址,目标[,策略]` 整行改，映射写法按字段表）"),
]

# 参考实现里的类型 → Kotlin 枚举名
TYPE_MAP = {
    "text": "TEXT", "bool": "BOOL", "number": "NUMBER", "select": "SELECT",
    "list": "LIST", "numlist": "NUMLIST", "userlist": "USERLIST",
    "textarea": "TEXTAREA", "maptext": "MAPTEXT", "maplist": "MAPLIST",
    "dnslist": "DNSLIST", "applist": "APPLIST", "headers": "HEADERS",
    "fakeiprule": "FAKEIPRULE", "rulesetpick": "RULESETPICK", "checkbox": "CHECKBOX",
    "password": "PASSWORD", "file": "FILE", "button": "BUTTON", "picklist": "PICKLIST",
}


def kstr(s) -> str:
    """Kotlin 字符串字面量（转义 $ 与引号，避免模板串误展开；换行写成 \\n）。"""
    out = (str(s).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
           .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
    return '"' + out + '"'


def kval(v) -> str:
    """任意 JSON 值 → Kotlin 字面量（模板用：嵌套 linkedMapOf / listOf）。"""
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, list):
        return "listOf<Any?>(" + ", ".join(kval(x) for x in v) + ")"
    if isinstance(v, dict):
        return "linkedMapOf<String, Any?>(" + ", ".join(f"{kstr(k)} to {kval(x)}" for k, x in v.items()) + ")"
    return kstr(v)


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


def kotlin_row(f: dict, indent: str, force_pick: bool = False) -> str:
    """一节里的一行：普通字段 → FormField；{custom: "..."} → FormCustomRow（专门控件）。"""
    if "custom" in f and "path" not in f:
        return f"FormCustomRow({kstr(f['custom'])})"
    return kotlin_field(f, indent, force_pick)


def kotlin_field(f: dict, indent: str, force_pick: bool = False) -> str:
    """force_pick：P2 流程页编辑器的布尔字段一律 boolAsPick（三态弹窗，默认 = 删键），与参考实现一致。"""
    args = [f"path = {kstr(f['path'])}", f"label = {kstr(f['label'])}",
            f"type = FormFieldType.{TYPE_MAP.get(f.get('type', 'text'), 'TEXT')}"]
    if f.get("options"):
        if isinstance(f["options"], dict) and "__dynamic__" in f["options"]:
            args.append(f"optionsDynamic = {kstr(f['options']['__dynamic__'])}")
        elif isinstance(f["options"], str):
            args.append(f"options = {f['options']}")          # 引用同文件里的选项表（P2：GROUP_TYPES 等）
        else:
            opts = ", ".join(f"FormOption({kstr(o[0])}, {kstr(o[1])})" for o in f["options"])
            args.append(f"options = listOf({opts})")
    if f.get("optionsDynamic"):
        args.append(f"optionsDynamic = {kstr(f['optionsDynamic'])}")
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
    if f.get("boolAs") or (force_pick and f.get("type") == "bool"):
        args.append(f"boolAs = {kstr(f.get('boolAs') or 'pick')}")
    if f.get("join"):
        args.append(f"join = {kstr(f['join'])}")
    if f.get("keyPlaceholder"):
        args.append(f"keyPlaceholder = {kstr(f['keyPlaceholder'])}")
    if f.get("dnsIpOnly"):
        args.append("dnsIpOnly = true")
    if f.get("dns"):
        args.append(f"dns = {str(bool(f['dns'])).lower()}")
    if f.get("only"):
        args.append("only = listOf(" + ", ".join(kstr(t) for t in f["only"]) + ")")
    if f.get("num"):
        args.append("numeric = true")
    if f.get("emptyLabel"):
        args.append(f"emptyLabel = {kstr(f['emptyLabel'])}")
    if f.get("arrayValues"):
        args.append("arrayValues = true")
    body = ",\n".join(f"{indent}    {a}" for a in args)
    return f"FormField(\n{body},\n{indent})"


KT_HEADER = """package top.yukonga.mishka.custom.forms

// 本文件由 tools/forms/gen_specs.py 从参考实现的字段表生成，请勿手改。
// 数据源：tools/forms/fields.json（extract_fields.mjs 从 mihomo_box 的 WebUI 求值得到）。

enum class FormFieldType {
    TEXT, BOOL, NUMBER, SELECT, LIST, NUMLIST, USERLIST, TEXTAREA, MAPTEXT, MAPLIST,
    DNSLIST, APPLIST, HEADERS, FAKEIPRULE, RULESETPICK, CHECKBOX, PASSWORD, FILE, BUTTON,
    PICKLIST,   // P2：从动态候选（节点 / 代理组 / 集合）里多选并排序的字符串列表
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
    /** P2：只在所属项的 type 属于这些值时显示（代理组 / 集合的类型专属字段）；空 = 总是显示。 */
    val only: List<String> = emptyList(),
    /** P2：select 的取值要按数字写回（snell version 等）。 */
    val numeric: Boolean = false,
    /** P2：select 允许留空时「空」那一项的文案。 */
    val emptyLabel: String? = null,
    /** P2：headers 的值是字符串列表（http-opts.headers / provider header）。 */
    val arrayValues: Boolean = false,
    /** P2：列表字段在 YAML 里是一个用该分隔符拼起来的字符串（exclude-type 的 `A|B`），读拆写合。 */
    val join: String? = null,
) : FormRow

sealed interface FormRow
data class FormCustomRow(val kind: String) : FormRow

data class FormSection(val title: String, val fields: List<FormRow>)
"""


def gen_kotlin(data) -> str:
    parts = [KT_HEADER]
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
        lines.append(f"| {i} | {name} | {sub} | {'✅ P1' if name in p1_names else '✅ P2（见 FORMS-P2.md）'} |")
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


# ---------------------------------------------------------------- P2

KT2_HEADER = """package top.yukonga.mishka.custom.forms

// 本文件由 tools/forms/gen_specs.py 生成，请勿手改。
// 数据源（都是从 mihomo_box WebUI 参考实现求值提取的）：
//   * tools/forms/fields.json     —— extract_fields.mjs：PER_TYPE / NET_FIELDS / TLS_FIELDS / SMUX_FIELDS / TAIL_FIELDS /
//                                    PROXY_TEMPLATES / EXCLUDE_TYPE_OPTIONS / EXPR_PRESETS（顶层字面量）
//   * tools/forms/fields_p2.json  —— extract_flow.mjs：pages-flow.js / pages-config.js 各编辑器函数体内的字段表、模板、
//                                    默认值与常量（代理组 / 代理集合 / 规则集合 / 规则类型 / 隧道 / 内置策略…）
// 各表 by lazy：避免把几百个 FormField 塞进同一个静态初始化块。
// 流程页的布尔字段全部 boolAs = "pick"（参考实现 boolAsPick：默认（不覆写）/ 开 / 关，默认 = 删键）。

/** 规则类型（参考实现 RULE_TYPES + hintFor 提示语）。multi：匹配值多行输入（AND / OR / NOT / SUB-RULE）；noPayload：MATCH；
 *  ipRule：参考实现 isIpRule（no-resolve 只对它们有意义）；targetKind 是我们的附加项：RULE-SET 的载荷是规则集合名、
 *  SUB-RULE 的目标是子规则名，编辑器据此给候选。 */
data class RuleTypeSpec(
    val value: String,
    val label: String,
    val placeholder: String? = null,
    val multi: Boolean = false,
    val noPayload: Boolean = false,
    val ipRule: Boolean = false,
    val targetKind: String? = null,
)
"""


def field_list(fields, indent: str, force_pick: bool = True) -> str:
    return "listOf(\n" + "".join(f"{indent}    {kotlin_row(f, indent + '    ', force_pick)},\n" for f in fields) + f"{indent})"


def options_kotlin(pairs) -> str:
    return "listOf(" + ", ".join(f"FormOption({kstr(a)}, {kstr(b)})" for a, b in pairs) + ")"


def sections_kotlin(name: str, secs, doc: str) -> list[str]:
    out = [f"\n/** {doc} */", f"val {name}: List<FormSection> by lazy {{", "    listOf("]
    for title, fields in secs:
        out.append(f"        FormSection({kstr(title)}, listOf(")
        for f in fields:
            out.append("            " + kotlin_row(f, " " * 12, True) + ",")
        out.append("        )),")
    out.append("    )")
    out.append("}")
    return out


def proxy_features(data, p2) -> tuple[list, dict]:
    """协议顺序 = fields.json 的 PROXY_TEMPLATES（更新的 WebUI 版本可能多出协议，如 cns）；
    参考实现流程页没列到的协议按「有 server 模板键 → server + tail，否则只 tail」兜底。"""
    templates = data["pages"]["PROXY_TEMPLATES"]["value"]
    per_type = data["pages"]["PER_TYPE"]["value"]
    if set(templates) != set(per_type):
        raise SystemExit("fields.json 的 PROXY_TEMPLATES 与 PER_TYPE 协议清单对不上")
    order = list(p2["PROXY_TYPE_ORDER"]) + [t for t in templates if t not in p2["PROXY_TYPE_ORDER"]]
    feats = {}
    for t in order:
        if t not in templates:
            raise SystemExit(f"fields_p2.json 里的协议 {t} 在 fields.json 的 PROXY_TEMPLATES 里没有模板")
        feats[t] = p2["PROXY_FEATURES"].get(t) or (["server", "tail"] if "server" in templates[t] else ["tail"])
    return order, feats


def gen_kotlin_p2(data, p2) -> str:
    pages = data["pages"]
    templates = pages["PROXY_TEMPLATES"]["value"]
    per_type = pages["PER_TYPE"]["value"]
    order, feats = proxy_features(data, p2)
    extra_types = [t for t in order if t not in p2["PROXY_TYPE_ORDER"]]
    prov = p2["provenance"]

    def ref(key: str) -> str:
        return f"参考实现 {pages[key]['file']}:{pages[key]['line']}"

    def ref2(key: str) -> str:
        return f"参考实现 {prov[key]['file']}:{prov[key]['line']}"

    parts = [KT2_HEADER]
    parts.append(f"\n/** 出站代理协议清单（新建节点时的协议选择，顺序即菜单顺序；{ref('PROXY_TEMPLATES')}）。 */")
    parts.append("val PROXY_TYPES: List<FormOption> = listOf(")
    for t in order:
        parts.append(f"    FormOption({kstr(t)}, {kstr(t)}),")
    parts.append(")")
    parts.append("\n/** 每种协议在「协议参数」之外显示哪些小节：server / net / tls / tlsbool / smux / tail"
                 f"（{ref2('NO_SERVER')}–{prov['SHOW_HYTLS']['line']} 的 NO_SERVER / SHOW_NET / SHOW_SMUX / SHOW_TLS / SHOW_HYTLS）。"
                 + (f" 参考实现流程页没列到、按模板兜底的协议：{', '.join(extra_types)}。" if extra_types else "") + " */")
    parts.append("val PROXY_FEATURES: Map<String, Set<String>> = mapOf(")
    for t in order:
        parts.append(f"    {kstr(t)} to setOf(" + ", ".join(kstr(x) for x in feats[t]) + "),")
    parts.append(")")
    parts.append(f"\n/** 节点基础字段：服务器 / 端口（{ref2('PROXY_BASE_FIELDS')}；NO_SERVER 的协议不显示）。 */")
    parts.append("val PROXY_BASE_FIELDS: List<FormField> by lazy {")
    parts.append("    " + field_list(p2["PROXY_BASE_FIELDS"], " " * 4))
    parts.append("}")
    parts.append(f"\n/** 传输层 network 的取值（{ref2('NET_TYPES')}）。 */")
    parts.append("val PROXY_NETWORKS: List<FormOption> = " + options_kotlin(p2["PROXY_NETWORKS"]))
    parts.append(f"\n/** 各传输层的 opts 键：切换 network 时删掉其它传输层的 opts（{ref2('NET_OPTS_KEYS')}）。 */")
    parts.append("val NET_OPTS_KEYS: List<String> = listOf(" + ", ".join(kstr(x) for x in p2["NET_OPTS_KEYS"]) + ")")
    parts.append("\n/** 节点详情页各段标题（editProxySheet 的 group-title）。 */")
    parts.append("val PROXY_SECTION_TITLES: Map<String, String> = mapOf(" +
                 ", ".join(f"{kstr(k)} to {kstr(v)}" for k, v in p2["PROXY_SECTION_TITLES"].items()) + ")")
    parts.append(f"\n/** 内置策略 + 说明（{ref2('BUILTIN_POLICIES')}）：规则目标 / 代理组成员 / 空组回退里总是可选。 */")
    parts.append("val BUILTIN_POLICIES: List<FormOption> = " + options_kotlin(p2["BUILTIN_POLICIES"]))
    parts.append(f"\n/** 新建节点模板（{ref('PROXY_TEMPLATES')}）。新节点名按参考实现取 `<协议>-out`。 */")
    parts.append("val PROXY_TEMPLATES: Map<String, Map<String, Any?>> by lazy {")
    parts.append("    linkedMapOf(")
    for t in order:
        parts.append(f"        {kstr(t)} to {kval(templates[t])},")
    parts.append("    )")
    parts.append("}")
    parts.append(f"\n/** 协议专属字段（{ref('PER_TYPE')}）。 */")
    parts.append("val PROXY_PER_TYPE: Map<String, List<FormField>> by lazy {")
    parts.append("    linkedMapOf(")
    for t in order:
        parts.append(f"        {kstr(t)} to {field_list(per_type[t], ' ' * 8)},")
    parts.append("    )")
    parts.append("}")
    parts.append(f"\n/** 传输层字段，按 network 分（{ref('NET_FIELDS')}）。 */")
    parts.append("val PROXY_NET_FIELDS: Map<String, List<FormField>> by lazy {")
    parts.append("    linkedMapOf(")
    for n, fs in pages["NET_FIELDS"]["value"].items():
        parts.append(f"        {kstr(n)} to {field_list(fs, ' ' * 8)},")
    parts.append("    )")
    parts.append("}")
    for key, name, label in (("TLS_FIELDS", "PROXY_TLS_FIELDS", "TLS 字段（SHOW_HYTLS 的协议去掉 `tls` 开关 = 参考实现 HY_TLS_FIELDS）"),
                             ("SMUX_FIELDS", "PROXY_SMUX_FIELDS", "多路复用字段"),
                             ("TAIL_FIELDS", "PROXY_TAIL_FIELDS", "通用字段（UDP / TFO / 链式出口 / 网卡 / IP 版本）")):
        parts.append(f"\n/** {label}（{ref(key)}）。 */")
        parts.append(f"val {name}: List<FormField> by lazy {{")
        parts.append("    " + field_list(pages[key]["value"], " " * 4))
        parts.append("}")
    parts.append(f"\n/** exclude-type 的候选（{ref('EXCLUDE_TYPE_OPTIONS')}）。 */")
    parts.append("val EXCLUDE_TYPE_OPTIONS: List<String> = listOf(" + ", ".join(kstr(x) for x in pages["EXCLUDE_TYPE_OPTIONS"]["value"]) + ")")
    parts.append(f"\n/** override-expr 预设（{ref('EXPR_PRESETS')}）。 */")
    parts.append("val EXPR_PRESETS: List<Pair<String, List<String>>> = listOf(")
    for title, exprs in pages["EXPR_PRESETS"]["value"]:
        parts.append(f"    {kstr(title)} to listOf(" + ", ".join(kstr(e) for e in exprs) + "),")
    parts.append(")")
    parts.append(f"\n/** 代理组类型（{ref2('GROUP_TYPES')}）。 */")
    parts.append("val GROUP_TYPES: List<FormOption> = " + options_kotlin(p2["GROUP_TYPES"]))
    parts.append(f"\n/** 新建代理组模板（{ref2('GROUP_TEMPLATE')}；名字重复时加序号）。 */")
    parts.append(f"val GROUP_TEMPLATE: Map<String, Any?> by lazy {{ {kval(p2['GROUP_TEMPLATE'])} }}")
    parts.append(f"\n/** 代理集合的来源类型（{ref2('SUB_BASIC')}）。 */")
    parts.append("val PROVIDER_TYPES: List<FormOption> = " + options_kotlin(p2["PROVIDER_TYPES"]))
    parts.append(f"\n/** 规则集合的来源类型（{ref2('EP_FIELDS')}）。 */")
    parts.append("val RULE_PROVIDER_TYPES: List<FormOption> = " + options_kotlin(p2["RULE_PROVIDER_TYPES"]))
    parts.append(f"\n/** 新建代理集合的默认值（{ref2('PROVIDER_DEFAULT')}）。 */")
    parts.append(f"val PROVIDER_DEFAULT: Map<String, Any?> by lazy {{ {kval(p2['PROVIDER_DEFAULT'])} }}")
    parts.append(f"\n/** 新建规则集合的默认值（{ref2('RULE_PROVIDER_DEFAULT')}）。 */")
    parts.append(f"val RULE_PROVIDER_DEFAULT: Map<String, Any?> by lazy {{ {kval(p2['RULE_PROVIDER_DEFAULT'])} }}")
    parts.append(f"\n/** file 类型集合不该落盘的远程字段（{ref2('FILE_HIDDEN_KEYS')}）：sub = 代理集合，ep = 规则集合。 */")
    parts.append("val FILE_HIDDEN_KEYS: Map<String, List<String>> = mapOf(" +
                 ", ".join(f"{kstr(k)} to listOf(" + ", ".join(kstr(x) for x in v) + ")" for k, v in p2["FILE_HIDDEN_KEYS"].items()) + ")")
    parts.append(f"\n/** 开启健康检查时自动补的默认值（{ref2('HC_DEFAULTS')}）。 */")
    parts.append(f"val HC_DEFAULTS: Map<String, Any?> by lazy {{ {kval(p2['HC_DEFAULTS'])} }}")
    parts.append(f"\n/** 新建隧道（映射写法）的默认值（{ref2('TUNNEL_TEMPLATE')}）。 */")
    parts.append(f"val TUNNEL_TEMPLATE: Map<String, Any?> by lazy {{ {kval(p2['TUNNEL_TEMPLATE'])} }}")
    for name, label, note in P2_PAGES:
        parts.extend(sections_kotlin(name, section_of(p2[name]), f"{label}：{note}（fields_p2.json，{P2_PROV[name]}）"))
    parts.append(f"\n/** 规则类型（{ref2('RULE_TYPES')} 的 RULE_TYPES；提示语 {ref2('RULE_HINTS')} 的 hintFor）。 */")
    parts.append("val RULE_TYPES: List<RuleTypeSpec> by lazy {")
    parts.append("    listOf(")
    for r in p2["RULE_TYPES"]:
        args = [kstr(r["value"]), kstr(r["label"])]
        if r.get("placeholder"):
            args.append(f"placeholder = {kstr(r['placeholder'])}")
        for flag in ("multi", "noPayload", "ipRule"):
            if r.get(flag):
                args.append(f"{flag} = true")
        if r.get("targetKind"):
            args.append(f"targetKind = {kstr(r['targetKind'])}")
        parts.append("        RuleTypeSpec(" + ", ".join(args) + "),")
    parts.append("    )")
    parts.append("}")
    return "\n".join(parts) + "\n"


# 各 P2 字段表在参考实现里的出处（provenance 键）
P2_PROV = {
    "GROUP_SECTIONS": "editGroupSheet：GROUP_COMMON / GROUP_HEALTH / GROUP_TOLERANCE / GROUP_STRATEGY / GROUP_DEFAULT_SELECTED / GROUP_EMPTY_FALLBACK / GROUP_OTHERS / GROUP_SMART / GROUP_PROXIES / GROUP_USE",
    "PROXY_PROVIDER_SECTIONS": "editSubSheet：SUB_BASIC / SUB_filter / SUB_exclude-filter / SUB_exclude-type / HC_SUB / SUB_OVERRIDE",
    "RULE_PROVIDER_SECTIONS": "editEpSheet：EP_FIELDS",
    "TUNNEL_SECTIONS": "renderTunnels：TUNNEL_FIELDS",
}


def md_field_rows(fields, lines):
    lines.append("| 路径 | 标签 | 类型 | 说明 |")
    lines.append("| --- | --- | --- | --- |")
    for f in fields:
        if "custom" in f and "path" not in f:
            lines.append(f"| — | （专门控件） | `{f['custom']}` | 由代码实现的定制行 |")
            continue
        t = f.get("type", "text")
        extra = []
        if f.get("only"):
            extra.append("仅 " + " / ".join(f["only"]))
        if f.get("options") and isinstance(f["options"], list):
            extra.append("取值：" + " / ".join(str(o[0]) for o in f["options"][:8]))
        if isinstance(f.get("options"), str):
            extra.append(f"取值表：{f['options']}")
        if f.get("optionsDynamic"):
            extra.append(f"候选：{f['optionsDynamic']}（运行时从配置里收集）")
        if f.get("placeholder"):
            extra.append(f"提示：{f['placeholder']}")
        if f.get("hint"):
            extra.append(f"格式：{f['hint']}")
        if f.get("desc"):
            extra.append(str(f["desc"])[:60])
        lines.append(f"| `{f['path']}` | {f['label']} | {t} | {'；'.join(extra)} |")
    lines.append("")


def gen_markdown_p2(data, p2) -> str:
    pages = data["pages"]
    per_type = pages["PER_TYPE"]["value"]
    order, feats = proxy_features(data, p2)
    prov = p2["provenance"]
    src = p2["source"]
    extra_types = [t for t in order if t not in p2["PROXY_TYPE_ORDER"]]
    L = ["# P2 流量页面：界面清单（自动生成，勿手改）", "",
         "来源（两份都是从 mihomo_box WebUI 参考实现求值提取的）：", "",
         "* `tools/forms/fields.json` —— `extract_fields.mjs`：协议专属字段（PER_TYPE）、传输层（NET_FIELDS）、TLS / 多路复用 / 通用字段、新建模板、exclude-type 候选、override-expr 预设（顶层字面量）",
         f"* `tools/forms/fields_p2.json` —— `extract_flow.mjs`（{src['repo']}@{src['commit'][:10]}）：pages-flow.js / pages-config.js 各编辑器函数体内的字段表、模板、默认值与常量；每张表的出处见下方「出处」",
         "", "## hub：7 个 P2 入口", "",
         "| 入口 | 配置键 | 形态 | 列表页 | 详情页 |", "| --- | --- | --- | --- | --- |",
         f"| 出站代理 | `proxies` | 序列 | 名称 · 协议 · 服务器；新建（{len(order)} 种协议模板）/ 上移下移 / 删除（引用保护） | 名称 · 协议（只读）+ 服务器 / 端口 + 协议参数 + 传输层 + TLS / Reality + 多路复用 + 通用链式 / 拨号 + 表单之外的字段 |",
         "| 代理集合 | `proxy-providers` | 映射 | 名称 · 类型 · 来源；新建（名字 + 类型 + 链接）/ 改名（同步 use）/ 删除（引用保护） | 基础 + 请求 / 过滤 + 健康检查（三态开关）+ 覆写 override + override-expr |",
         "| 代理组 | `proxy-groups` | 序列 | 名称 · 类型 · 成员数；新建（模板「手动选择」）/ 上移下移 / 删除（引用保护） | 名称 · 类型 + 成员 + 通用参数（按类型显隐）+ Smart 专属 |",
         "| 路由规则 | `rules` | 序列 | 一行一条；添加 / 编辑 / 上移下移 / 删除 / 文本模式 | 规则编辑（类型 → 匹配值 → 目标策略 → no-resolve → 预览；可切文本编辑） |",
         "| 规则集合 | `rule-providers` | 映射 | 名称 · 类型 · behavior；新建（名字 + 类型 + 链接）/ 改名（同步 RULE-SET）/ 删除（引用保护） | 基础（file 隐藏远程字段；inline 的 payload 复用规则列表页） |",
         "| 子规则 | `sub-rules` | 映射 | 名称 · 条数；新建 / 改名 / 删除 | 复用路由规则列表页 |",
         "| 流量隧道 | `tunnels` | 序列 | 单行写法 / 映射写法都能读；新增（单行 / 映射）/ 上移下移 / 删除 | 映射项按字段表编辑；单行项整行改 |",
         ""]
    L += ["## 出站代理：协议清单与小节", "",
          "| 协议 | 额外小节 | 协议参数字段数 | 模板键 |", "| --- | --- | --- | --- |"]
    for t in order:
        L.append(f"| `{t}` | {' '.join(feats[t]) or '—'} | {len(per_type[t])} | {', '.join(pages['PROXY_TEMPLATES']['value'][t].keys())} |")
    L += ["", "小节含义（参考实现 NO_SERVER / SHOW_NET / SHOW_SMUX / SHOW_TLS / SHOW_HYTLS）：server=服务器/端口；net=传输层（network + 对应 NET_FIELDS，切换时删其它 `*-opts`，http 补 `http-opts.method: GET`）；"
          "tls=TLS / Reality 小节；tlsbool=显示 `tls` 开关（开→补 `servername: example.com`，关→删 servername / sni）；smux=多路复用；tail=通用链式 / 拨号。与协议参数重复的路径由渲染层去重。"
          + (f" 参考实现流程页没列到、按模板兜底的协议：{', '.join(extra_types)}。" if extra_types else ""), ""]
    L += ["### 基础字段", ""]
    md_field_rows(p2["PROXY_BASE_FIELDS"], L)
    L += ["### 协议专属字段（PER_TYPE）", ""]
    for t in order:
        if not per_type[t]:
            continue
        L.append(f"**{t}**（{len(per_type[t])}）")
        L.append("")
        md_field_rows(per_type[t], L)
    L += ["### 传输层（NET_FIELDS）", "", "network 取值：" + "、".join(f"`{v}`（{l}）" for v, l in p2["PROXY_NETWORKS"]), ""]
    for n, fs in pages["NET_FIELDS"]["value"].items():
        L.append(f"**network = {n}**（{len(fs)}）")
        L.append("")
        md_field_rows(fs, L)
    for key, label in (("TLS_FIELDS", "TLS / Reality"), ("SMUX_FIELDS", "多路复用 smux"), ("TAIL_FIELDS", "通用链式 / 拨号")):
        L.append(f"### {label}（{key}，{len(pages[key]['value'])}）")
        L.append("")
        md_field_rows(pages[key]["value"], L)
    L += ["### 新建模板（PROXY_TEMPLATES）", "", "| 协议 | 模板 |", "| --- | --- |"]
    for t in order:
        L.append(f"| `{t}` | `{json.dumps(pages['PROXY_TEMPLATES']['value'][t], ensure_ascii=False)}` |")
    L.append("")
    for name, label, note in P2_PAGES:
        secs = section_of(p2[name])
        total = sum(len(f) for _, f in secs)
        L.append(f"## {label}（{name}，共 {total} 个字段）")
        L.append("")
        L.append(note)
        L.append("")
        for title, fields in secs:
            L.append(f"**{title}**（{len(fields)}）")
            L.append("")
            md_field_rows(fields, L)
    L += [f"## 规则类型（RULE_TYPES，{len(p2['RULE_TYPES'])} 种）", "",
          "拆合照参考实现 parseRule：去掉末尾 `no-resolve`，第一段是类型，最后一段是目标策略，中间整段是匹配值（MATCH 没有匹配值）；"
          "AND / OR / NOT / SUB-RULE 的匹配值用多行输入。", "",
          "| 类型 | 提示语 | 多行 | IP 类（no-resolve） | 目标 |", "| --- | --- | --- | --- | --- |"]
    for r in p2["RULE_TYPES"]:
        tgt = {"sub-rules": "子规则名", "rule-providers": "策略（匹配值为规则集合名）"}.get(r.get("targetKind"), "策略")
        L.append(f"| `{r['value']}` | {r.get('placeholder', '—')} | {'是' if r.get('multi') else ''} | {'是' if r.get('ipRule') else ''} | {tgt} |")
    L.append("")
    L += ["## 模板 / 默认值 / 常量", "",
          f"* 新建代理组：`{json.dumps(p2['GROUP_TEMPLATE'], ensure_ascii=False)}`",
          f"* 新建代理集合：`{json.dumps(p2['PROVIDER_DEFAULT'], ensure_ascii=False)}`（http 必填链接；file 默认路径 `./proxies/<名字>.yaml`；inline 带空 payload）",
          f"* 新建规则集合：`{json.dumps(p2['RULE_PROVIDER_DEFAULT'], ensure_ascii=False)}`（file 默认路径 `./rules/<名字>.<yaml|txt|mrs>`；inline 带空 payload）",
          f"* 新建隧道：`{json.dumps(p2['TUNNEL_TEMPLATE'], ensure_ascii=False)}`（映射写法里 network 写成列表）",
          f"* file 类型不落盘的远程字段：代理集合 {', '.join(p2['FILE_HIDDEN_KEYS']['sub'])}；规则集合 {', '.join(p2['FILE_HIDDEN_KEYS']['ep'])}",
          f"* 健康检查开启时补的默认值：`{json.dumps(p2['HC_DEFAULTS'], ensure_ascii=False)}`",
          f"* exclude-type 候选（{len(pages['EXCLUDE_TYPE_OPTIONS']['value'])}）：" + "、".join(pages["EXCLUDE_TYPE_OPTIONS"]["value"]),
          f"* override-expr 预设（{len(pages['EXPR_PRESETS']['value'])}）：" + "、".join(t for t, _ in pages["EXPR_PRESETS"]["value"]),
          "* 内置策略：" + "、".join(f"{v}（{d}）" for v, d in p2["BUILTIN_POLICIES"]),
          "* 代理组类型：" + "、".join(a for a, _ in p2["GROUP_TYPES"]),
          "* 代理集合类型：" + "、".join(a for a, _ in p2["PROVIDER_TYPES"]) + "；规则集合类型：" + "、".join(a for a, _ in p2["RULE_PROVIDER_TYPES"]),
          ""]
    L += ["## 出处（fields_p2.json 的 provenance）", "", "| 表 | 文件:行 |", "| --- | --- |"]
    for k, v in prov.items():
        L.append(f"| `{k}` | `{v['file']}:{v['line']}` |")
    L.append("")
    return "\n".join(L) + "\n"


def main() -> int:
    data = json.loads(FIELDS.read_text(encoding="utf-8"))
    p2 = json.loads(FIELDS_P2.read_text(encoding="utf-8"))
    kt = gen_kotlin(data)
    md = gen_markdown(data)
    kt2 = gen_kotlin_p2(data, p2)
    md2 = gen_markdown_p2(data, p2)
    n_fields = sum(1 for line in kt.splitlines() if line.strip().startswith("path ="))
    n_sections = kt.count("FormSection(")
    n_fields2 = sum(1 for line in kt2.splitlines() if line.strip().startswith("path ="))
    print(f"生成：FormSpecs.kt {len(kt.splitlines())} 行（{n_sections} 个小节 / {n_fields} 个字段），"
          f"FORMS-P1.md {len(md.splitlines())} 行")
    print(f"      FormSpecsP2.kt {len(kt2.splitlines())} 行（{n_fields2} 个字段 / {len(p2['RULE_TYPES'])} 种规则 / "
          f"{len(proxy_features(data, p2)[0])} 种协议），FORMS-P2.md {len(md2.splitlines())} 行")
    out_kt = DELIVER / "app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormSpecs.kt"
    out_md = DELIVER / "FORMS-P1.md"
    out_kt2 = DELIVER / "app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormSpecsP2.kt"
    out_md2 = DELIVER / "FORMS-P2.md"
    artifacts = ((out_kt, kt), (out_md, md), (out_kt2, kt2), (out_md2, md2))
    if "--write" in sys.argv:
        out_kt.parent.mkdir(parents=True, exist_ok=True)
        print("已写入：")
        for path, text in artifacts:
            path.write_text(text, encoding="utf-8")
            print("  " + str(path))
        return 0
    if "--check" in sys.argv:
        # 产物是生成物：改字段表后必须重生成，否则这里会抓到漂移
        bad = 0
        for path, want in artifacts:
            if not path.exists():
                print(f"FAIL 缺产物 {path}")
                bad += 1
            elif path.read_text(encoding="utf-8") != want:
                print(f"FAIL 产物与字段表不同步：{path}（跑 python3 tools/forms/gen_specs.py --write）")
                bad += 1
            else:
                print(f"ok  {path.name} 与字段表一致")
        return 1 if bad else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
