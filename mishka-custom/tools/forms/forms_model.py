#!/usr/bin/env python3
"""配置表单编辑器的 YAML 引擎原型（Kotlin 侧 1:1 转写的规范）。

设计要点
--------
* 行树解析：只解析表单需要的东西——块映射、块序列、`- key: v` 内联续行、flow 标量与
  多行 `|`/`>`；锚点 / 别名 / `<<:` 作为标记原样保留，多行标量作为不透明叶子。
  P2 起额外支持「无缩进序列」（`rules:` 下一行直接 `- …`，PyYAML 风格）。
* 三层写回：
    A 手术式：只替换某个值的字符区间（保留同行尾部注释）
    B 块级：重排某个键的整块；P2 起细化到「序列里的一项」——只重排那一项的行区间
    C 全量：文档不可解析时的兜底（调用方负责告警）
* 路径：`a.b.c` 走映射；`a[3].c` 里 `[3]` 走序列第 3 项（0 起）。拆成段后字符串段=键、整数段=下标。
* 序列项操作（P2）：set_item / insert_item / remove_item / move_item 只动该项的行区间
  （flow 序列则只动行内那一段），其它项逐字节不变；move 是行区间的重排，不重新渲染任何一项。
* 保真契约（由 tests/forms_props.py 用 PyYAML 验证）：
    - 除编辑过的键 / 项，其它顶层块与其它项逐字节不变
    - 改回原值 → 文本逐字节还原
    - 幂等；CRLF 保留；行尾注释保留
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Optional, Union

PathSeg = Union[str, int]

# ---------------------------------------------------------------- 标量渲染

_LOOKS_NUM = re.compile(r"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$")
_LOOKS_BOOL = {"true", "false", "yes", "no", "on", "off", "null", "~",
               "True", "False", "Null", "TRUE", "FALSE", "NULL"}
_FORBIDDEN_START = "-?:,[]{}#&*!|>'\"%@`"


def needs_quote(s: str) -> bool:
    """YAML plain 标量能在「不跟空格的冒号/井号」等场景下裸写，尽量不加引号（保真）。"""
    if s == "":
        return True
    if s != s.strip():
        return True
    if s in _LOOKS_BOOL or _LOOKS_NUM.match(s):
        return True
    if s[0] in _FORBIDDEN_START:
        return True
    if ": " in s or s.endswith(":") or " #" in s:
        return True
    if any(ord(c) < 0x20 for c in s):
        return True
    return False


def render_scalar(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v) if isinstance(v, float) else str(v)
    s = str(v)
    if needs_quote(s):
        return "'" + s.replace("'", "''") + "'"
    return s


def is_multiline(v: Any) -> bool:
    """含换行的字符串只能写成 `|` 块（引号里的裸换行会被 YAML 折叠成空格，证书这类内容会坏）。"""
    return isinstance(v, str) and "\n" in v


def render_multiline(head: str, v: str, indent: int) -> list[str]:
    """`head: |` / `head: |-` + 缩进的正文行。末尾有换行用 `|`（保留一个），否则 `|-`。"""
    keep = v.endswith("\n")
    body = (v[:-1] if keep else v).split("\n")
    pad = " " * indent
    return [head + (" |" if keep else " |-")] + [(pad + ln) if ln else "" for ln in body]


def render_scalar_flow(v: Any) -> str:
    """flow 上下文（`[...]` / `{...}` 里）的标量：逗号与括号在这里是分隔符，必须加引号。"""
    if isinstance(v, str) and not needs_quote(v) and any(c in v for c in ",[]{}"):
        return "'" + v.replace("'", "''") + "'"
    return render_scalar(v)


def render_flow(v: Any) -> Optional[str]:
    """简单值渲染成 flow：只在“短、无嵌套、无引号风险”时可用，否则 None。"""
    if isinstance(v, (list, tuple)):
        parts = [render_flow(x) for x in v]
        if any(p is None for p in parts):
            return None
        out = "[" + ", ".join(parts) + "]"
        return out if len(out) <= 160 else None
    if isinstance(v, dict):
        parts = []
        for k, x in v.items():
            xs = render_flow(x)
            if xs is None:
                return None
            parts.append(f"{_render_key_flow(str(k))}: {xs}")
        out = "{" + ", ".join(parts) + "}"
        return out if len(out) <= 160 else None
    if is_multiline(v):
        return None
    return render_scalar_flow(v)


def render_block(v: Any, indent: int, lines: list[str]) -> None:
    """把一个值按块式风格渲染进 lines（缩进 indent 空格）。"""
    pad = " " * indent
    if isinstance(v, dict):
        if not v:
            lines.append(pad + "{}")
            return
        for k, x in v.items():
            key = _render_key(str(k))
            leaf = _render_leaf(x)
            if is_multiline(x):
                lines.extend(render_multiline(f"{pad}{key}:", x, indent + 2))
            elif leaf is not None:
                lines.append(f"{pad}{key}: {leaf}")
            else:
                lines.append(f"{pad}{key}:")
                render_block(x, indent + 2, lines)
    elif isinstance(v, (list, tuple)):
        if not v:
            lines.append(pad + "[]")
            return
        for x in v:
            # 映射项一律块式（`- name: x` + 续行）：这是配置里代理/代理组的惯用写法，也最好读
            leaf = None if (isinstance(x, dict) and x) else _render_leaf(x)
            if is_multiline(x):
                lines.extend(render_multiline(f"{pad}-", x, indent + 2))
            elif leaf is not None:
                lines.append(f"{pad}- {leaf}")
            elif isinstance(x, dict) and x:
                sub = []
                render_block(x, indent + 2, sub)
                first = sub[0].lstrip()
                lines.append(f"{pad}- {first}")
                lines.extend(sub[1:])
            else:
                lines.append(f"{pad}-")
                render_block(x, indent + 2, lines)
    else:
        # 多行字符串不会走到这里：有键/项前缀的调用方都已按 `|` 块写（见 render_multiline）
        lines.append(pad + render_scalar(v))


def _render_leaf(v: Any) -> Optional[str]:
    if isinstance(v, (dict, list, tuple)):
        return render_flow(v)
    if is_multiline(v):
        return None
    return render_scalar(v)


def _flow_leaf(v: Any) -> Optional[str]:
    """flow 序列里的一项：标量按 flow 规则加引号，集合渲染成 flow。"""
    if isinstance(v, (dict, list, tuple)):
        return render_flow(v)
    if is_multiline(v):
        return None
    return render_scalar_flow(v)


def _inline_leaf(v: Any) -> Optional[str]:
    """写成「key: 值」单行时用的叶子：标量、空集合（`[]` / `{}`）；非空集合与多行字符串一律走块式。"""
    if isinstance(v, (dict, list, tuple)):
        return "[]" if isinstance(v, (list, tuple)) and not v else ("{}" if isinstance(v, dict) and not v else None)
    if is_multiline(v):
        return None
    return render_scalar(v)


def _render_key_value(prefix: str, key: str, value: Any, indent: int, anchor: Optional[str] = None) -> list[str]:
    """渲染一个键及其值：`prefix` 是键前面的原文（缩进或 `- `），标量/空集合单行，否则块式。

    `anchor` 是原值上的 `&锚点`：整块重写时必须带回去（`key: &a []` / `key: &a |` / `key: &a` + 块体），
    否则后面的 `*a` 别名会悬空，写出无效 YAML。
    """
    head = f"{prefix}{_render_key(key)}:" + (f" &{anchor}" if anchor else "")
    leaf = _inline_leaf(value)
    if leaf is not None:
        return [f"{head} {leaf}"]
    if is_multiline(value):
        return render_multiline(head, value, indent + 2)
    out = [head]
    render_block(value, indent + 2, out)
    return out


# ---------------------------------------------------------------- flow 集合

_FLOW_TOKENS = {}


def _scan_quoted(text: str, i: int) -> int:
    """text[i] 是引号，返回闭合引号后的位置（未闭合则返回 len）。"""
    q = text[i]
    i += 1
    while i < len(text):
        if q == "'" and text[i] == "'":
            if i + 1 < len(text) and text[i + 1] == "'":   # '' 转义
                i += 2
                continue
            return i + 1
        if q == '"' and text[i] == "\\":
            i += 2
            continue
        if q == '"' and text[i] == '"':
            return i + 1
        i += 1
    return len(text)


def _scan_flow_value(text: str, i: int) -> int:
    """从 text[i] 起扫一个 flow 值（可嵌套），返回结束位置（不含分隔逗号）。"""
    depth = 0
    while i < len(text):
        c = text[i]
        if c in "'\"":
            i = _scan_quoted(text, i)
            continue
        if c in "[{":
            depth += 1
        elif c in "]}":
            if depth == 0:
                return i
            depth -= 1
        elif c == "," and depth == 0:
            return i
        i += 1
    return i


@dataclass
class FlowEntry:
    key: str
    key_start: int
    key_end: int
    val_start: int
    val_end: int


def parse_flow_entries(text: str, start: int) -> tuple[list["FlowEntry"], int]:
    """解析 flow 映射：text[start] 必须是 '{'，返回 (条目, 闭合位置+1)。"""
    entries: list[FlowEntry] = []
    i = start + 1
    while i < len(text):
        while i < len(text) and text[i] in " \t\n,":
            i += 1
        if i >= len(text) or text[i] == "}":
            return entries, i + 1
        key_start = i
        if text[i] in "'\"":
            i = _scan_quoted(text, i)
        else:
            while i < len(text) and text[i] not in ":,}":
                i += 1
        key_end = i
        key_raw = text[key_start:key_end].strip()
        key = key_raw[1:-1] if (key_raw[:1] in "'\"" and key_raw[-1:] == key_raw[:1]) else key_raw
        while i < len(text) and text[i] != ":":
            if text[i] == "}":
                return entries, i + 1
            i += 1
        i += 1
        while i < len(text) and text[i] in " \t":
            i += 1
        val_start = i
        i = _scan_flow_value(text, i)
        if i < len(text) and text[i] == "]":
            # `{k: ]`：括号不配对，扫描停在别人的闭合符上，继续扫只会原地打转 —— 按解析失败处理
            raise ValueError("flow 映射括号不配对")
        entries.append(FlowEntry(key=key, key_start=key_start, key_end=key_end,
                                 val_start=val_start, val_end=i))
    return entries, i + 1


def parse_flow_seq_items(text: str, start: int) -> tuple[list[tuple[int, int]], int]:
    """解析 flow 序列：返回 [(item_start, item_end)], 闭合位置+1。"""
    items = []
    i = start + 1
    while i < len(text):
        while i < len(text) and text[i] in " \t\n,":
            i += 1
        if i >= len(text) or text[i] == "]":
            return items, i + 1
        a = i
        i = _scan_flow_value(text, i)
        if i == a:
            # `[1, 2}`：扫描停在不配对的 `}` 上没有前进，再循环就是死循环 —— 按解析失败处理
            raise ValueError("flow 序列括号不配对")
        items.append((a, i))
    return items, i + 1


def flow_set(text: str, key: str, rendered: str) -> str:
    """在 flow 映射文本里设置 key（存在则替换值，不存在则追加），返回新文本。"""
    entries, close = parse_flow_entries(text, 0)
    for e in entries:
        if e.key == key:
            return text[:e.val_start] + rendered + text[e.val_end:]
    inner = text[1:close - 1].rstrip()
    head, tail = text[:close - 1], text[close - 1:]
    if inner.strip() == "":
        return "{" + f"{_render_key_flow(key)}: {rendered}" + "}"
    sep = "" if inner.endswith(",") else ","
    return head + sep + f" {_render_key_flow(key)}: {rendered}" + tail


def flow_remove(text: str, key: str) -> str:
    entries, close = parse_flow_entries(text, 0)
    for idx, e in enumerate(entries):
        if e.key != key:
            continue
        start, end = e.key_start, e.val_end
        # 连带吃掉一个分隔逗号与相邻空白
        j = end
        while j < len(text) and text[j] in " \t":
            j += 1
        if j < len(text) and text[j] == ",":
            end = j + 1
            while end < len(text) and text[end] == " ":
                end += 1
        else:
            k = start - 1
            while k >= 0 and text[k] in " \t":
                k -= 1
            if k >= 0 and text[k] == ",":
                start = k
        return text[:start] + text[end:]
    return text


def flow_rename(text: str, key: str, new_key: str) -> str:
    """flow 映射里改键名：只替换键那一段文本。"""
    entries, _close = parse_flow_entries(text, 0)
    for e in entries:
        if e.key == key:
            return text[:e.key_start] + _render_key_flow(new_key) + text[e.key_end:]
    return text


# ---------------------------------------------------------------- 行树

# 引号键必须排在通用键之前：`'geosite:cn':` 里的冒号不能当成键值分隔符
# 冒号后面必须是空白或行尾（YAML 的键值分隔规则）：`IP-CIDR,2001:db8::/32,DIRECT` 这类带冒号的标量不是键
# 裸键里允许夹冒号（`geosite:cn: 223.5.5.5`、`rule-set:a,b: [...]`）：懒匹配到第一个「冒号 + 空白 / 行尾」才算分隔符；
# `#` 不许出现在裸键里（否则 `foo # c: x` 会把注释吞进键）；裸键不能以引号开头（`"a: b"` 是带冒号的引号标量，不是键 `"a`）。
# 与 YamlEngine.kt 的 KEY_RE 必须同步。
KEY_RE = re.compile(
    r"^(?P<indent>[ \t]*)(?P<key>(?:'[^']*')|(?:\"[^\"]*\")|(?:[^#:\s'\"][^#]*?))\s*:(?P<rest>\s.*|$)"
)
SEQ_RE = re.compile(r"^(?P<indent>[ \t]*)-(?P<rest>\s.*|$)")


def strip_comment(s: str) -> tuple[str, str]:
    """返回 (内容, 注释)。注释 = 空白+# 起的部分（不处理引号内 #，够用）。"""
    out = []
    in_s = in_d = False
    i = 0
    while i < len(s):
        c = s[i]
        if in_s:
            if c == "'":
                in_s = False
            out.append(c)
        elif in_d:
            if c == '"':
                in_d = False
            out.append(c)
        elif c == "'":
            in_s = True
            out.append(c)
        elif c == '"':
            in_d = True
            out.append(c)
        elif c == "#" and (i == 0 or s[i - 1] in " \t"):
            return "".join(out).rstrip(), s[len("".join(out).rstrip()):]
        else:
            out.append(c)
        i += 1
    return s.rstrip(), ""


def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" \t"))


def _blank_or_comment(line: str) -> bool:
    t = line.strip()
    return t == "" or t.startswith("#")


@dataclass
class Node:
    kind: str                     # 'map' | 'seq' | 'scalar' | 'flow' | 'raw'
    start: int                    # 起始行（含）
    end: int                      # 结束行（不含）
    indent: int = 0
    inline: bool = False          # 值写在同一行（key: value）
    value_start: Optional[int] = None   # 同值文本在行内的起止列（inline 时）
    value_end: Optional[int] = None
    anchor: Optional[str] = None  # &名字
    alias: Optional[str] = None   # *名字
    merge: bool = False           # <<: 键
    multi: bool = False           # | 或 > 多行标量（不透明）
    flow: bool = False            # flow 集合（同一行内的 { } / [ ]，可读写）
    flow_span: Optional[tuple[int, int]] = None   # 在行内的起止列
    dash: int = -1                # 作为块序列的一项时，`-` 所在行（项的行区间从这里起算）
    entries: list["Entry"] = field(default_factory=list)   # map
    items: list["Node"] = field(default_factory=list)      # seq

    def raw(self, lines: list[str]) -> str:
        return "\n".join(lines[self.start:self.end])


@dataclass
class Entry:
    key: str
    key_indent: int
    node: Optional[Node]      # 值为 None 表示 `key:` 后面什么都没有（空值）
    line: int                 # 键所在行

    @property
    def is_merge(self) -> bool:
        return self.key == "<<"


@dataclass
class Document:
    text: str
    lines: list[str]
    eol: str
    root: Node
    has_bom: bool = False

    def dump(self, lines: Optional[list[str]] = None) -> str:
        body = self.eol.join(self.lines if lines is None else lines)
        return ("\ufeff" if self.has_bom else "") + body


def parse(text: str) -> Document:
    eol = "\r\n" if "\r\n" in text else "\n"
    has_bom = text.startswith("\ufeff")
    body = text[1:] if has_bom else text
    lines = body.split("\r\n") if eol == "\r\n" else body.split("\n")
    root = Node("map", 0, len(lines))

    def parse_block(i: int, indent: int, parent: Optional[Node] = None) -> tuple[Node, int]:
        """解析 [i, ...) 里缩进为 indent 的块，返回 (节点, 下一个未消费行号)。"""
        # 跳过空行与注释（它们不属于任何节点，但会被块边界吞掉——由调用方处理）
        node: Optional[Node] = None
        last_end = i          # 最后一个「内容行」的结束位置：块不吸收尾随空行/注释
        while i < len(lines):
            line = lines[i]
            if _blank_or_comment(line):
                i += 1
                continue
            ind = _indent_of(line)
            if ind < indent:
                break
            if ind > indent:
                # 不该发生（调用方按 indent 递归）；按缩进提升处理，避免死循环
                break
            m_seq = SEQ_RE.match(line)
            m_key = KEY_RE.match(line) if not m_seq else None
            if m_seq:
                if node is not None and node.kind != "seq":
                    break        # 映射块后面跟同缩进的 `- `：不是这个块的内容（无缩进序列由上层接管）
                if node is None:
                    node = Node("seq", i, i, indent=indent)
                item, after = parse_seq_item(i, indent)
                node.items.append(item)
                i = last_end = after
                node.end = after
            elif m_key:
                if node is not None and node.kind != "map":
                    break        # 无缩进序列到头了：同缩进的下一个键属于上层映射
                if node is None:
                    node = Node("map", i, i, indent=indent)
                entry, after = parse_map_entry(i, indent)
                node.entries.append(entry)
                i = last_end = after
                node.end = after
            else:
                # 裸标量块（例如 seq 里的段落）：当作 scalar
                if node is None:
                    node = Node("scalar", i, i + 1, indent=indent, inline=False)
                    node.value_start = ind
                    node.value_end = len(line)
                    i = last_end = i + 1
                else:
                    break
        if node is None:
            return Node("map", last_end, last_end, indent=indent), last_end   # 空块
        return node, last_end

    def parse_seq_item(i: int, indent: int) -> tuple[Node, int]:
        line = lines[i]
        m = SEQ_RE.match(line)
        rest = m.group("rest")
        content_col = indent + 1 + (len(rest) - len(rest.lstrip(" \t")) if rest.strip() else 0)
        if rest.strip() == "":
            # "-" 独占一行：值在下一行
            nxt = i + 1
            while nxt < len(lines) and _blank_or_comment(lines[nxt]):
                nxt += 1
            if nxt < len(lines) and _indent_of(lines[nxt]) > indent:
                inner, after = parse_block(nxt, _indent_of(lines[nxt]))
                inner.dash = i
                return inner, after
            empty = Node("scalar", i, i + 1, indent=indent, inline=False, value_start=indent, value_end=len(line))
            empty.dash = i
            return empty, i + 1
        body = rest.strip()
        # 内联映射：- key: value（flow 集合 `- {…}` / `- […]` 与多行标量不算）
        m_key = KEY_RE.match(body) if body[:1] not in "{[|>" else None
        if m_key:
            # 造一个内联 map 节点：首行与后续更深缩进的行
            sub_indent = content_col
            node = Node("map", i, i + 1, indent=sub_indent)
            node.dash = i
            entry, after = parse_map_entry(i, sub_indent, slice_from=content_col)
            node.entries.append(entry)
            node.end = after          # 首键的值可能是多行块，项的结束行必须跟着走
            # 继续吃同缩进的后续键
            j = after
            while j < len(lines):
                if _blank_or_comment(lines[j]):
                    j += 1
                    continue
                if _indent_of(lines[j]) != sub_indent:
                    break
                if SEQ_RE.match(lines[j]):
                    break
                if not KEY_RE.match(lines[j]):
                    break
                e2, j2 = parse_map_entry(j, sub_indent)
                node.entries.append(e2)
                j = j2
                node.end = j
            return node, node.end
        # 标量 / flow / 多行
        n = _scalar_node(i, content_col, len(line))
        n.dash = i
        return n, n.end

    def parse_map_entry(i: int, indent: int, slice_from: Optional[int] = None) -> tuple[Entry, int]:
        line = lines[i]
        m = KEY_RE.match(line if slice_from is None else line[slice_from:])
        prefix = "" if slice_from is None else line[:slice_from]
        key_raw = m.group("key").strip()
        key = key_raw[1:-1] if (key_raw[:1] in "'\"" and key_raw[-1:] == key_raw[:1]) else key_raw
        rest = m.group("rest")
        # 值在行内的起始列
        content, _comment = strip_comment(rest)
        val_col = (len(prefix) + m.start("rest")) + (len(rest) - len(rest.lstrip()))
        # 值可能是 `&锚点`（块体在后续行）或 `!!tag 值`
        anchor = None
        stripped = content.strip()
        while True:
            m_anchor = re.match(r"^&(\S+)\s*(.*)$", stripped)
            m_tag = re.match(r"^!!\S+\s*(.*)$", stripped)
            if m_anchor:
                anchor = m_anchor.group(1)
                stripped = m_anchor.group(2).strip()
                content = content[:content.index(stripped)] if stripped else ""
            elif m_tag:
                stripped = m_tag.group(1).strip()
                content = content[:content.index(stripped)] if stripped else ""
            else:
                break
        if stripped == "":
            # 值在后续更深缩进的行；`key: # 注释` 也算空值
            j = i + 1
            while j < len(lines) and _blank_or_comment(lines[j]):
                j += 1
            if j < len(lines) and _indent_of(lines[j]) > indent:
                inner, after = parse_block(j, _indent_of(lines[j]))
                inner.anchor = anchor
                entry = Entry(key=key, key_indent=indent, node=inner, line=i)
                return entry, after
            # 无缩进序列：`key:` 的下一行是同缩进的 `- …`（PyYAML 默认输出就是这样）
            if j < len(lines) and _indent_of(lines[j]) == indent and SEQ_RE.match(lines[j]):
                inner, after = parse_block(j, indent)
                inner.anchor = anchor
                return Entry(key=key, key_indent=indent, node=inner, line=i), after
            empty = Node("scalar", i, i + 1, indent=indent, inline=False,
                         value_start=len(line), value_end=len(line), anchor=anchor)
            return Entry(key=key, key_indent=indent, node=empty, line=i), i + 1
        node = _scalar_node(i, val_col, len(line))
        return Entry(key=key, key_indent=indent, node=node, line=i), node.end

    def _scalar_node(line_no: int, col: int, end_col: int) -> Node:
        line = lines[line_no]
        raw = line[col:end_col]
        content, _ = strip_comment(raw)
        body = content.strip()
        n = Node("scalar", line_no, line_no + 1, inline=True, value_start=col + (len(raw) - len(raw.lstrip())), value_end=col + len(content))
        # `&锚点` / `!!tag` 前缀剥掉后再判断值的形态：`key: &a [x, y]` 是带锚点的 flow 序列（与 YamlEngine.kt 一致）
        am = re.match(r"^&(\S+)\s*(.*)$", body)
        if am:
            n.anchor = am.group(1)
            body = am.group(2).strip()
            if body:
                n.value_start = line.index(body, n.value_start)
                n.value_end = n.value_start + len(body)
        tm = re.match(r"^!!\S+\s*(.*)$", body)
        if tm:
            body = tm.group(1).strip()
            if body:
                n.value_start = line.index(body, n.value_start)
                n.value_end = n.value_start + len(body)
        if re.match(r"^\*[^\s,{}\[\]]+", body):
            n.kind = "raw"
            n.alias = body.lstrip("*")
        elif body[:1] in "{[":
            # flow 集合：解析成可读条目（带列区间），写回时按区间做文本手术
            base = n.value_start
            try:
                if body[:1] == "{":
                    fe, close = parse_flow_entries(line, base)
                    n.kind = "map"
                    n.flow = True
                    n.flow_span = (base, close)
                    for e in fe:
                        sub = _scalar_node(line_no, e.val_start, e.val_end)
                        n.entries.append(Entry(key=e.key, key_indent=base,
                                               node=sub, line=line_no))
                else:
                    items, close = parse_flow_seq_items(line, base)
                    n.kind = "seq"
                    n.flow = True
                    n.flow_span = (base, close)
                    for a, b in items:
                        n.items.append(_scalar_node(line_no, a, b))
            except Exception:
                n.kind = "flow"      # 解析不了就当不透明值（只读）
        elif body[:1] in "|>":
            n.kind = "raw"
            n.multi = True
            # 吃掉续行
            j = line_no + 1
            base = _indent_of(line)
            while j < len(lines):
                if lines[j].strip() == "":
                    j += 1
                    continue
                if _indent_of(lines[j]) > base:
                    j += 1
                    continue
                break
            # 块不吸收尾随空行（和块式映射 / 序列一致）：整块重写或删除时，和下一个键之间的空行留在原地
            while j - 1 > line_no and lines[j - 1].strip() == "":
                j -= 1
            n.end = j
        return n

    i = 0
    root.entries = []
    while i < len(lines):
        if _blank_or_comment(lines[i]):
            i += 1
            continue
        ind = _indent_of(lines[i])
        node, after = parse_block(i, ind)
        if node.kind == "map":
            root.entries.extend(node.entries)
        elif node.kind == "seq":
            root.entries.append(Entry(key="__seq__", key_indent=0, node=node, line=i))
        else:
            root.entries.append(Entry(key="__scalar__", key_indent=0, node=node, line=i))
        root.end = after
        i = after if after > i else i + 1
    return Document(text=text, lines=lines, eol=eol, root=root, has_bom=has_bom)


# ---------------------------------------------------------------- 查询

def _split_path(path: str) -> list[PathSeg]:
    """`a.b.c` → ['a','b','c']；`a[3].c` → ['a', 3, 'c']；引号段可含 `.` / `[`。"""
    out: list[PathSeg] = []
    buf, in_s, has_buf = "", None, False
    i = 0
    while i < len(path):
        ch = path[i]
        if in_s:
            if ch == in_s:
                in_s = None
            else:
                buf += ch
        elif ch in "'\"":
            in_s = ch
            has_buf = True
        elif ch == "[":
            if buf or has_buf:
                out.append(buf)
                buf, has_buf = "", False
            j = path.find("]", i)
            if j < 0:
                j = len(path)
            out.append(int(path[i + 1:j]))
            i = j
        elif ch == ".":
            if buf or has_buf:
                out.append(buf)
            buf, has_buf = "", False
        else:
            buf += ch
        i += 1
    if buf or has_buf:
        out.append(buf)
    return out


def quote_seg(name: str) -> str:
    """把用户起的名字（代理集合名 / 子规则名…）变成路径段：含 `.` `[` 引号时加引号。"""
    if name == "" or any(c in name for c in ".[]'\""):
        q = '"' if "'" in name and '"' not in name else "'"
        return q + name + q
    return name


def find_entry(node: Optional[Node], key: str) -> Optional[Entry]:
    if node is None or node.kind != "map":
        return None
    for e in node.entries:
        if e.key == key and not e.is_merge:
            return e
    return None


def _child(node: Optional[Node], seg: PathSeg) -> Optional[Node]:
    """沿一段路径下钻：整数段进序列（下标越界 → None），字符串段进映射。"""
    if node is None:
        return None
    if isinstance(seg, int):
        if node.kind != "seq" or seg < 0 or seg >= len(node.items):
            return None
        return node.items[seg]
    e = find_entry(node, seg)
    return None if e is None else e.node


def get_path(doc: Document, path: Union[str, list]) -> Optional[Node]:
    cur: Optional[Node] = doc.root
    for part in (_split_path(path) if isinstance(path, str) else path):
        cur = _child(cur, part)
        if cur is None:
            return None
    return cur


# ---------------------------------------------------------------- 写回

def _line_bounds_for_entry(doc: Document, entry: Entry) -> tuple[int, int]:
    """一个键（含其值）占据的行区间 [start, end)。"""
    start = entry.line
    if entry.node is None:
        return start, start + 1
    if entry.node.inline:
        return start, entry.node.end
    return entry.line, entry.node.end


def item_bounds(item: Node) -> tuple[int, int]:
    """块序列里一项占据的行区间 [start, end)：从 `-` 那行起到值结束。"""
    start = item.dash if item.dash >= 0 else item.start
    return start, max(item.end, start + 1)


def _block_end_after(doc: Document, index: int, indent: int) -> int:
    """从 index 行往后，找到缩进 <= indent 的第一行（跳过空白/注释）。"""
    j = index
    while j < len(doc.lines):
        line = doc.lines[j]
        if _blank_or_comment(line):
            j += 1
            continue
        if _indent_of(line) <= indent:
            break
        j += 1
    return j


def _is_null_value(node: Optional["Node"]) -> bool:
    """`key:` 后没有值（行内为空且没有更深的块）→ 可以当成空映射继续下钻。"""
    if node is None:
        return True
    if node.kind == "map":
        return len(node.entries) == 0
    if node.kind == "seq":
        return len(node.items) == 0
    if node.kind == "scalar" and not node.multi:
        if (node.value_start or 0) >= (node.value_end or 0):
            return True
        return False          # `key: null` 由调用方按需处理（见 _is_null_text）
    return False


def _is_null_text(doc: "Document", node: Optional["Node"]) -> bool:
    if _is_null_value(node):
        return True
    if node is not None and node.kind == "scalar":
        text = doc.lines[node.start][node.value_start or 0: node.value_end or 0].strip()
        return text in ("null", "~", "Null", "NULL")
    return False


def resolve_parent(doc: Document, parent_path: list, *, allow_null: bool = False) -> Optional[Node]:
    """沿路径走到父节点：字符串段走映射、整数段走序列项；终点必须是映射（表单只往映射里写键）。

    allow_null=True 时，`key:`（空值）视为空映射继续下钻——插入新键时用得到。
    """
    if not parent_path:
        return doc.root if doc.root.kind == "map" else None
    parent: Optional[Node] = doc.root
    for part in parent_path:
        parent = _child(parent, part)
        if parent is None:
            return None
    if parent.kind == "map":
        return parent
    if allow_null and _is_null_text(doc, parent):
        return parent
    return None


def can_set(doc: Document, path: Union[str, list]) -> bool:
    parts = _split_path(path) if isinstance(path, str) else list(path)
    parent_path, key = parts[:-1], parts[-1]
    if isinstance(key, int):
        # 改序列里的一项：序列必须存在且下标在范围内（追加走 insert_item）
        seq = get_path(doc, parent_path)
        return seq is not None and seq.kind == "seq" and 0 <= key < len(seq.items)
    parent = resolve_parent(doc, parent_path)
    if parent is not None:
        return True
    # 允许「逐级新建」：父路径上每一级要么不存在、要么是映射；序列项不会凭空新建
    cur: Optional[Node] = doc.root
    for part in parent_path:
        if cur is None:
            return False
        if isinstance(part, int):
            cur = _child(cur, part)
            if cur is None:
                return False
            continue
        if cur.kind != "map":
            return False
        e = find_entry(cur, part)
        if e is None:
            return True          # 后面的层级都可以新建
        cur = e.node
    return cur is not None and cur.kind == "map"


def set_value(doc: Document, path: Union[str, list], value: Any, *, force_block: bool = False) -> Document:
    """改值（A 层）/ 插入新键。路径的父级不是映射时**原样返回**（绝不写出非法 YAML）。"""
    parts = _split_path(path) if isinstance(path, str) else list(path)
    parent_path, key = parts[:-1], parts[-1]
    if isinstance(key, int):
        return set_item(doc, parent_path, key, value)
    lines = list(doc.lines)

    parent = resolve_parent(doc, parent_path)

    if parent is not None and parent.flow:
        return _flow_set_in_parent(doc, parent, key, value)
    if parent is not None:
        entry = find_entry(parent, key)
        if entry is not None and entry.node is not None and entry.node.flow:
            # 值本身是 flow 集合：整段替换（保持行内）；太长渲染不下就退回块式
            rendered = render_flow(value)
            if rendered is None:
                return _replace_entry_block(doc, parent, entry, value, lines)
            lines2 = list(doc.lines)
            line = lines2[entry.node.start]
            a, b = entry.node.flow_span
            lines2[entry.node.start] = line[:a] + rendered + line[b:]
            return _reparse(doc, lines2)
        if entry is not None and entry.node is not None and entry.node.inline and entry.node.kind in ("scalar", "flow"):
            n = entry.node
            leaf = None if (isinstance(value, (dict, list)) and force_block) else _render_leaf(value)
            if leaf is not None:
                line = lines[n.start]
                before, after = line[:n.value_start], line[n.value_end:]
                prefix = ""
                if n.anchor:
                    prefix = f"&{n.anchor} "
                lines[n.start] = before + prefix + leaf + after
                return Document(doc.text, lines, doc.eol, parse(doc.dump(lines)).root, doc.has_bom)
            # 需要块式：整块替换
            return _replace_entry_block(doc, parent, entry, value, lines)
        if entry is not None:
            return _replace_entry_block(doc, parent, entry, value, lines)

    # 没有这个键：插入（父块不存在则按需创建，空值键当空映射用）
    if parent is None:
        inserted = _insert_key(doc, parent_path, key, value)
        return doc if inserted is None else inserted

    insert_at = _parent_block_end(doc, parent, lines)
    indent = parent.indent
    render = _render_key_value(" " * indent, key, value, indent)
    for off, ln in enumerate(render):
        lines.insert(insert_at + off, ln)
    return _reparse(doc, lines)


def _render_key(key: str) -> str:
    """块上下文的键：只在 YAML 真需要时加引号（中文名不加，和手写配置一致）。"""
    return "'" + key.replace("'", "''") + "'" if needs_quote(key) else key


def _render_key_flow(key: str) -> str:
    """flow 上下文（`{…}` 里）的键：逗号与括号也得引起来。"""
    return render_scalar_flow(key)


def _parent_block_end(doc: Document, parent: Node, lines: list[str]) -> int:
    if not parent.entries:
        return parent.start + 1 if parent.start < len(lines) else len(lines)
    last = parent.entries[-1]
    _, end = _line_bounds_for_entry(doc, last)
    # 值块后面可能跟着该块内的空行/注释：留在原位，插到它们之前更自然
    return end


def _key_prefix(doc: Document, entry: Entry) -> str:
    """键前面的原文：普通键是缩进空白；序列项首键是 `- `（必须原样保留，否则把项的 `-` 吃掉）。"""
    return doc.lines[entry.line][:entry.key_indent]


def _replace_entry_block(doc: Document, parent: Node, entry: Entry, value: Any, lines: list[str]) -> Document:
    start, end = _line_bounds_for_entry(doc, entry)
    anchor = entry.node.anchor if entry.node is not None else None
    render = _render_key_value(_key_prefix(doc, entry), entry.key, value, entry.key_indent, anchor)
    lines[start:end] = render
    return _reparse(doc, lines)


def _flow_set_in_parent(doc: Document, parent: Node, key: str, value: Any) -> Document:
    """父节点是 flow 映射：只在行内那一段文本上做手术。"""
    rendered = _flow_leaf(value)
    if rendered is None:
        return doc            # 装不进一行的集合 / 多行字符串：不动（UI 会提示未改动），绝不写成折叠的引号串
    lines = list(doc.lines)
    line = lines[parent.start]
    a, b = parent.flow_span
    new_flow = flow_set(line[a:b], key, rendered)
    lines[parent.start] = line[:a] + new_flow + line[b:]
    return _reparse(doc, lines)


def remove_key(doc: Document, path: Union[str, list]) -> Document:
    parts = _split_path(path) if isinstance(path, str) else list(path)
    parent_path, key = parts[:-1], parts[-1]
    if isinstance(key, int):
        return remove_item(doc, parent_path, key)
    parent: Optional[Node] = doc.root
    for part in parent_path:
        parent = _child(parent, part)
        if parent is None:
            return doc
    if parent.flow:
        lines = list(doc.lines)
        line = lines[parent.start]
        a, b = parent.flow_span
        lines[parent.start] = line[:a] + flow_remove(line[a:b], key) + line[b:]
        return _reparse(doc, lines)
    entry = find_entry(parent, key)
    if entry is None:
        return doc
    lines = list(doc.lines)
    start, end = _line_bounds_for_entry(doc, entry)
    prefix = _key_prefix(doc, entry)
    if prefix.strip() != "":
        # 序列项的首键（`- name: x`）：删掉这行会连 `-` 一起删掉。把下一个键提到 `-` 这一行来。
        idx = parent.entries.index(entry)
        if idx + 1 >= len(parent.entries):
            return doc           # 项里只剩这一个键：不删（删项请用 remove_item）
        nxt = parent.entries[idx + 1]
        nxt_start, _ = _line_bounds_for_entry(doc, nxt)
        promoted = prefix + lines[nxt_start][nxt.key_indent:]
        del lines[start:nxt_start]
        lines[start] = promoted
        return _reparse(doc, lines)
    del lines[start:end]
    return _reparse(doc, lines)


def rename_key(doc: Document, path: Union[str, list], new_key: str) -> Document:
    """改键名（代理集合 / 规则集合 / 子规则改名）：只替换键那一段文本，值与注释不动。"""
    parts = _split_path(path) if isinstance(path, str) else list(path)
    parent_path, key = parts[:-1], parts[-1]
    if isinstance(key, int) or new_key == key:
        return doc
    parent: Optional[Node] = doc.root
    for part in parent_path:
        parent = _child(parent, part)
        if parent is None:
            return doc
    if parent.kind != "map" or find_entry(parent, new_key) is not None:
        return doc               # 新名字已存在：拒绝（否则产生重复键）
    lines = list(doc.lines)
    if parent.flow:
        line = lines[parent.start]
        a, b = parent.flow_span
        lines[parent.start] = line[:a] + flow_rename(line[a:b], key, new_key) + line[b:]
        return _reparse(doc, lines)
    entry = find_entry(parent, key)
    if entry is None:
        return doc
    line = lines[entry.line]
    m = KEY_RE.match(line[entry.key_indent:])
    if m is None:
        return doc
    ks = entry.key_indent + m.start("key")
    ke = entry.key_indent + m.end("key")
    lines[entry.line] = line[:ks] + _render_key(new_key) + line[ke:]
    return _reparse(doc, lines)


# ---------------------------------------------------------------- 序列项（P2）

def _seq_entry(doc: Document, seq_path: list) -> Optional[tuple[Node, Entry]]:
    """序列所在的 (父映射, 键条目)，找不到或父级不是映射返回 None。"""
    if not seq_path or isinstance(seq_path[-1], int):
        return None
    parent = resolve_parent(doc, seq_path[:-1])
    if parent is None:
        return None
    entry = find_entry(parent, seq_path[-1])
    if entry is None:
        return None
    return parent, entry


def _flow_raw_items(doc: Document, seq: Node) -> list[str]:
    line = doc.lines[seq.start]
    return [line[it.value_start:it.value_end] for it in seq.items]


def _flow_seq_rewrite(doc: Document, seq_path: list, seq: Node, raws: list[str]) -> Document:
    """用新的项文本重写 flow 序列：装得下就留在行内，否则整键转块式（每项原文直接做 `- 项`）。"""
    lines = list(doc.lines)
    rendered = "[" + ", ".join(raws) + "]"
    a, b = seq.flow_span
    line = lines[seq.start]
    if len(rendered) <= 160 or not raws:
        lines[seq.start] = line[:a] + rendered + line[b:]
        return _reparse(doc, lines)
    found = _seq_entry(doc, seq_path)
    if found is None:
        return doc
    _parent, entry = found
    start, end = _line_bounds_for_entry(doc, entry)
    pad = " " * (entry.key_indent + 2)
    render = [f"{_key_prefix(doc, entry)}{_render_key(entry.key)}:"] + [f"{pad}- {r}" for r in raws]
    lines[start:end] = render
    return _reparse(doc, lines)


def set_item(doc: Document, seq_path: list, index: int, value: Any) -> Document:
    """改序列第 index 项：行内标量 → 手术式只换值；否则只重排这一项的行区间。"""
    seq = get_path(doc, seq_path)
    if seq is None or seq.kind != "seq" or not (0 <= index < len(seq.items)):
        return doc
    item = seq.items[index]
    lines = list(doc.lines)
    if seq.flow:
        rendered = _flow_leaf(value)
        if rendered is None:
            return doc
        raws = _flow_raw_items(doc, seq)
        raws[index] = rendered
        return _flow_seq_rewrite(doc, seq_path, seq, raws)
    scalar_like = item.kind in ("scalar", "raw", "flow") and item.inline and not item.multi
    if scalar_like and not isinstance(value, (dict, list, tuple)):
        line = lines[item.start]
        prefix = f"&{item.anchor} " if item.anchor else ""
        lines[item.start] = line[:item.value_start] + prefix + render_scalar(value) + line[item.value_end:]
        return _reparse(doc, lines)
    start, end = item_bounds(item)
    render: list[str] = []
    render_block([value], seq.indent, render)
    lines[start:end] = render
    return _reparse(doc, lines)


def insert_item(doc: Document, seq_path: list, index: int, value: Any) -> Document:
    """在序列第 index 项之前插入（index == 项数 → 追加）。序列不存在/为空值时整键新建。"""
    seq = get_path(doc, seq_path)
    if seq is None or (seq.kind != "seq" and _is_null_text(doc, seq)):
        return set_value(doc, seq_path, [value])
    if seq.kind != "seq":
        return doc
    index = max(0, min(index, len(seq.items)))
    if seq.flow:
        rendered = _flow_leaf(value)
        raws = _flow_raw_items(doc, seq)
        if rendered is None:
            if not raws:
                return set_value(doc, seq_path, [value])
            return doc
        raws.insert(index, rendered)
        if not seq.items:
            return set_value(doc, seq_path, [value])
        return _flow_seq_rewrite(doc, seq_path, seq, raws)
    lines = list(doc.lines)
    # 插在前一项结束之后（而不是第 index 项的 `-` 之前）：夹在中间的注释继续跟着它原来描述的那一项
    at = item_bounds(seq.items[index - 1])[1] if index > 0 else item_bounds(seq.items[0])[0]
    render: list[str] = []
    render_block([value], seq.indent, render)
    lines[at:at] = render
    return _reparse(doc, lines)


def remove_item(doc: Document, seq_path: list, index: int) -> Document:
    """删掉序列第 index 项（只删它的行区间）；删到空就写成 `key: []`。"""
    seq = get_path(doc, seq_path)
    if seq is None or seq.kind != "seq" or not (0 <= index < len(seq.items)):
        return doc
    if len(seq.items) == 1:
        return set_value(doc, seq_path, [])
    if seq.flow:
        raws = _flow_raw_items(doc, seq)
        del raws[index]
        return _flow_seq_rewrite(doc, seq_path, seq, raws)
    lines = list(doc.lines)
    start, end = item_bounds(seq.items[index])
    del lines[start:end]
    return _reparse(doc, lines)


def move_item(doc: Document, seq_path: list, frm: int, to: int) -> Document:
    """把第 frm 项挪到第 to 位：纯粹的行区间重排，不重新渲染任何一项（项之间的注释跟着前一项走）。"""
    seq = get_path(doc, seq_path)
    if seq is None or seq.kind != "seq":
        return doc
    n = len(seq.items)
    if not (0 <= frm < n and 0 <= to < n) or frm == to:
        return doc
    if seq.flow:
        raws = _flow_raw_items(doc, seq)
        raws.insert(to, raws.pop(frm))
        return _flow_seq_rewrite(doc, seq_path, seq, raws)
    starts = [item_bounds(it)[0] for it in seq.items]
    ends = starts[1:] + [seq.end]
    lines = list(doc.lines)
    chunks = [lines[s:e] for s, e in zip(starts, ends)]
    chunks.insert(to, chunks.pop(frm))
    body: list[str] = []
    for c in chunks:
        body.extend(c)
    lines[starts[0]:seq.end] = body
    return _reparse(doc, lines)


def _key_line_indent(doc: Document, parent_path: list, lines: list[str]) -> Optional[int]:
    """插入点：(行号, 缩进)。父级是空值键时插在它后面并多缩进两格。"""
    if not parent_path:
        root = doc.root
        return (root.end, 0)
    parent = resolve_parent(doc, parent_path, allow_null=True)
    if parent is None:
        return None
    if parent.kind == "map" and len(parent.entries) > 0:
        return (_parent_block_end(doc, parent, lines), parent.indent)
    if parent.kind == "map":
        return (parent.start, parent.indent)
    # 空值键：插在它自己那行之后，缩进 +2
    if isinstance(parent_path[-1], int):
        return None
    entry = None
    grand = resolve_parent(doc, parent_path[:-1], allow_null=True)
    if grand is not None:
        entry = find_entry(grand, parent_path[-1])
    if entry is None:
        return None
    return (entry.line + 1, entry.key_indent + 2)


def _insert_key(doc: Document, parent_path: list, key: Any, value: Any,
                *, as_empty: bool = False) -> Optional[Document]:
    """在 parent_path 下插入 key。父级缺失就新建（只建映射）；中间是序列/标量则返回 None。"""
    if isinstance(key, int):
        return None
    cur = doc
    # 逐级确保父级存在：缺的建成空映射；存在但是序列/标量（不是空值）就不能往下建
    for i in range(1, len(parent_path) + 1):
        prefix = parent_path[:i]
        node = get_path(cur, prefix)
        if node is None:
            if isinstance(prefix[-1], int):
                return None                       # 序列项不凭空新建
            built = _insert_key(cur, prefix[:-1], prefix[-1], None, as_empty=True)
            if built is None:
                return None
            cur = built
        elif node.kind == "scalar" and not _is_null_text(cur, node):
            return None                           # 中间是纯值：不能往下建
        elif node.kind == "seq" and not (i < len(parent_path) and isinstance(parent_path[i], int)):
            return None                           # 中间是序列：只能按下标走进项里
    parent = resolve_parent(cur, parent_path, allow_null=True) if parent_path else cur.root
    if parent is None:
        return None
    if parent.flow:
        # 父级是 flow 映射（`- {name: a, …}` 这类项）：没有「行」可插，直接在行内那段文本上加键
        return _flow_set_in_parent(cur, parent, key, {} if as_empty else value)
    lines = list(cur.lines)
    where = _key_line_indent(cur, parent_path, lines)
    if where is None:
        return None
    at, indent = where
    if as_empty:
        lines.insert(at, " " * indent + f"{_render_key(key)}:")
        return _reparse(cur, lines)
    if value is None:
        render = [" " * indent + f"{_render_key(key)}:"]
    else:
        render = _render_key_value(" " * indent, key, value, indent)
    for off, ln in enumerate(render):
        lines.insert(at + off, ln)
    return _reparse(cur, lines)


def _reparse(doc: Document, lines: list[str]) -> Document:
    new = Document(doc.text, lines, doc.eol, doc.root, doc.has_bom)
    parsed = parse(new.dump())
    return parsed


# ---------------------------------------------------------------- 便捷封装

def set_many(text: str, ops: list[tuple[str, Any]]) -> str:
    doc = parse(text)
    for path, value in ops:
        doc = set_value(doc, path, value)
    return doc.dump()


def remove_many(text: str, paths: list[str]) -> str:
    doc = parse(text)
    for p in paths:
        doc = remove_key(doc, p)
    return doc.dump()


def apply_ops(text: str, ops: list[tuple]) -> str:
    """通用操作序列（对拍用）：
    ("set", path, value) / ("del", path) / ("ins", seq_path, index, value) /
    ("rmi", seq_path, index) / ("mov", seq_path, frm, to) / ("ren", path, new_key)
    路径都是字符串写法（`a.b[2].c`）。"""
    doc = parse(text)
    for op in ops:
        kind = op[0]
        if kind == "set":
            doc = set_value(doc, op[1], op[2])
        elif kind == "del":
            doc = remove_key(doc, op[1])
        elif kind == "ins":
            doc = insert_item(doc, _split_path(op[1]), op[2], op[3])
        elif kind == "rmi":
            doc = remove_item(doc, _split_path(op[1]), op[2])
        elif kind == "mov":
            doc = move_item(doc, _split_path(op[1]), op[2], op[3])
        elif kind == "ren":
            doc = rename_key(doc, op[1], op[2])
    return doc.dump()
