#!/usr/bin/env python3
"""配置表单编辑器的 YAML 引擎原型（Kotlin 侧 1:1 转写的规范）。

设计要点
--------
* 行树解析：只解析表单需要的东西——块映射、块序列、`- key: v` 内联续行、flow 标量与
  多行 `|`/`>`；锚点 / 别名 / `<<:` 作为标记原样保留，多行标量作为不透明叶子。
* 三层写回：
    A 手术式：只替换某个值的字符区间（保留同行尾部注释）
    B 块级：重排某个键的整块
    C 全量：文档不可解析时的兜底（调用方负责告警）
* 保真契约（由 tests/forms_props.py 用 PyYAML 验证）：
    - 除编辑过的键，其它顶层块逐字节不变
    - 改回原值 → 文本逐字节还原
    - 幂等；CRLF 保留；行尾注释保留
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Optional

# ---------------------------------------------------------------- 标量渲染

_PLAIN_SAFE = re.compile(r"^[A-Za-z0-9_./@+=<>~^-]+$")
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
            ks = render_scalar(k)
            if needs_quote(str(k)):
                ks = "'" + str(k).replace("'", "''") + "'"
            xs = render_flow(x)
            if xs is None:
                return None
            parts.append(f"{ks}: {xs}")
        out = "{" + ", ".join(parts) + "}"
        return out if len(out) <= 160 else None
    if isinstance(v, (list, dict)):
        return None
    return render_scalar(v)


def render_block(v: Any, indent: int, lines: list[str]) -> None:
    """把一个值按块式风格渲染进 lines（缩进 indent 空格）。"""
    pad = " " * indent
    if isinstance(v, dict):
        if not v:
            lines.append(pad + "{}")
            return
        for k, x in v.items():
            key = render_scalar(k) if not _PLAIN_SAFE.match(str(k)) else str(k)
            leaf = _render_leaf(x)
            if leaf is not None:
                lines.append(f"{pad}{key}: {leaf}")
            else:
                lines.append(f"{pad}{key}:")
                render_block(x, indent + 2, lines)
    elif isinstance(v, (list, tuple)):
        if not v:
            lines.append(pad + "[]")
            return
        for x in v:
            leaf = _render_leaf(x)
            if leaf is not None:
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
        lines.append(pad + render_scalar(v))


def _render_leaf(v: Any) -> Optional[str]:
    if isinstance(v, (dict, list, tuple)):
        return render_flow(v)
    return render_scalar(v)



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
        entries.append(FlowEntry(key=key, key_start=key_start, key_end=key_end,
                                 val_start=val_start, val_end=i))


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
        items.append((a, i))


def flow_set(text: str, key: str, rendered: str) -> str:
    """在 flow 映射文本里设置 key（存在则替换值，不存在则追加），返回新文本。"""
    entries, close = parse_flow_entries(text, 0)
    for e in entries:
        if e.key == key:
            return text[:e.val_start] + rendered + text[e.val_end:]
    inner = text[1:close - 1].rstrip()
    head, tail = text[:close - 1], text[close - 1:]
    if inner.strip() == "":
        return "{" + f"{_render_key(key)}: {rendered}" + "}"
    sep = "" if inner.endswith(",") else ","
    return head + sep + f" {_render_key(key)}: {rendered}" + tail


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


# ---------------------------------------------------------------- 行树

# 引号键必须排在通用键之前：`'geosite:cn':` 里的冒号不能当成键值分隔符
KEY_RE = re.compile(
    r"^(?P<indent>[ \t]*)(?P<key>(?:'[^']*')|(?:\"[^\"]*\")|(?:[^#:\s][^:]*?))\s*:(?P<rest>.*)$"
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
                if node is None:
                    node = Node("seq", i, i, indent=indent)
                item, after = parse_seq_item(i, indent)
                node.items.append(item)
                i = last_end = after
                node.end = after
            elif m_key:
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
                return inner, after
            return Node("scalar", i, i + 1, indent=indent, inline=False, value_start=indent, value_end=len(line)), i + 1
        body = rest.strip()
        # 内联映射：- key: value
        m_key = KEY_RE.match(body)
        if m_key:
            # 造一个内联 map 节点：首行与后续更深缩进的行
            sub_indent = content_col
            node = Node("map", i, i + 1, indent=sub_indent)
            entry, after = parse_map_entry(i, sub_indent, slice_from=content_col)
            node.entries.append(entry)
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
        return _scalar_node(i, content_col, len(line)), i + 1

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
            empty = Node("scalar", i, i + 1, indent=indent, inline=False,
                         value_start=len(line), value_end=len(line), anchor=anchor)
            return Entry(key=key, key_indent=indent, node=empty, line=i), i + 1
        node = _scalar_node(i, val_col, len(line))
        return Entry(key=key, key_indent=indent, node=node, line=i), i + 1

    def _scalar_node(line_no: int, col: int, end_col: int) -> Node:
        line = lines[line_no]
        raw = line[col:end_col]
        content, _ = strip_comment(raw)
        body = content.strip()
        n = Node("scalar", line_no, line_no + 1, inline=True, value_start=col + (len(raw) - len(raw.lstrip())), value_end=col + len(content))
        am = re.match(r"^&(\S+)\s*(.*)$", body)
        if am:
            n.anchor = am.group(1)
            body2 = am.group(2).strip()
            if body2:
                n.value_start = line.index(body2, n.value_start)
                n.value_end = n.value_start + len(body2)
        if re.match(r"^\*[^\s,{}\[\]]+", body):
            n.kind = "raw"
            n.alias = body.lstrip("*")
        elif body[:1] in "{[":
            # flow 集合：解析成可读条目（带列区间），写回时按区间做文本手术
            base = n.value_start + (len(body) - len(body.lstrip()))
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

def _split_path(path: str) -> list[str]:
    out, buf, in_s = [], "", None
    for ch in path:
        if in_s:
            if ch == in_s:
                in_s = None
            else:
                buf += ch
        elif ch in "'\"":
            in_s = ch
        elif ch == ".":
            out.append(buf)
            buf = ""
        else:
            buf += ch
    if buf:
        out.append(buf)
    return out


def find_entry(node: Optional[Node], key: str) -> Optional[Entry]:
    if node is None or node.kind != "map":
        return None
    for e in node.entries:
        if e.key == key and not e.is_merge:
            return e
    return None


def get_path(doc: Document, path: str) -> Optional[Node]:
    cur: Optional[Node] = doc.root
    for part in _split_path(path):
        e = find_entry(cur, part)
        if e is None:
            return None
        cur = e.node
    return cur


# ---------------------------------------------------------------- 写回

def _line_bounds_for_entry(doc: Document, entry: Entry) -> tuple[int, int]:
    """一个键（含其值）占据的行区间 [start, end)。"""
    start = entry.line
    if entry.node is None:
        return start, start + 1
    if entry.node.inline:
        return start, start + 1
    return entry.line, entry.node.end


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


def resolve_parent(doc: Document, parent_path: list[str], *, allow_null: bool = False) -> Optional[Node]:
    """只沿「映射」走：中途遇到序列/标量就返回 None（表单不编辑这类路径）。空路径 = 根。

    allow_null=True 时，`key:`（空值）视为空映射继续下钻——插入新键时用得到。
    """
    if not parent_path:
        return doc.root if doc.root.kind == "map" else None
    parent: Optional[Node] = doc.root
    for part in parent_path:
        if parent is None or parent.kind != "map":
            return None              # 中途遇到序列/标量 → 不可下钻
        e = find_entry(parent, part)
        if e is None or e.node is None:
            return None
        parent = e.node
    if parent is None:
        return None
    if parent.kind == "map":
        return parent
    if allow_null and _is_null_text(doc, parent):
        return parent
    return None


def can_set(doc: Document, path: str) -> bool:
    parts = _split_path(path)
    parent_path, key = parts[:-1], parts[-1]
    parent = resolve_parent(doc, parent_path)
    if parent is not None:
        return True
    # 允许「逐级新建」：父路径上每一级要么不存在、要么是映射
    cur: Optional[Node] = doc.root
    for part in parent_path:
        if cur is None or cur.kind != "map":
            return False
        e = find_entry(cur, part)
        if e is None:
            return True          # 后面的层级都可以新建
        cur = e.node
    return cur is not None and cur.kind == "map"


def set_value(doc: Document, path: str, value: Any, *, force_block: bool = False) -> Document:
    """改值（A 层）/ 插入新键。路径的父级不是映射时**原样返回**（绝不写出非法 YAML）。"""
    parts = _split_path(path)
    parent_path, key = parts[:-1], parts[-1]
    lines = list(doc.lines)

    parent = resolve_parent(doc, parent_path)

    if parent is not None and parent.flow:
        return _flow_set_in_parent(doc, parent, key, value)
    if parent is not None:
        entry = find_entry(parent, key)
        if entry is not None and entry.node is not None and entry.node.flow:
            # 值本身是 flow 集合：整段替换（保持行内）
            rendered = render_flow(value)
            if rendered is None:
                return doc
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
    render = []
    leaf = None if isinstance(value, (dict, list)) else _render_leaf(value)
    if leaf is not None:
        render.append(" " * indent + f"{_render_key(key)}: {leaf}")
    else:
        render.append(" " * indent + f"{_render_key(key)}:")
        render_block(value, indent + 2, render)
    for off, ln in enumerate(render):
        lines.insert(insert_at + off, ln)
    return _reparse(doc, lines)


def _render_key(key: str) -> str:
    return key if _PLAIN_SAFE.match(key) and not needs_quote(key) else "'" + key.replace("'", "''") + "'"


def _parent_block_end(doc: Document, parent: Node, lines: list[str]) -> int:
    if not parent.entries:
        return parent.start + 1 if parent.start < len(lines) else len(lines)
    last = parent.entries[-1]
    _, end = _line_bounds_for_entry(doc, last)
    # 值块后面可能跟着该块内的空行/注释：留在原位，插到它们之前更自然
    return end


def _replace_entry_block(doc: Document, parent: Node, entry: Entry, value: Any, lines: list[str]) -> Document:
    start, end = _line_bounds_for_entry(doc, entry)
    pad = " " * entry.key_indent
    render = [f"{pad}{_render_key(entry.key)}:"]
    render_block(value, entry.key_indent + 2, render)
    lines[start:end] = render
    return _reparse(doc, lines)



def _flow_set_in_parent(doc: Document, parent: Node, key: str, value: Any) -> Document:
    """父节点是 flow 映射：只在行内那一段文本上做手术。"""
    rendered = render_flow(value) if isinstance(value, (dict, list)) else render_scalar(value)
    if rendered is None:
        rendered = render_scalar(str(value))
    lines = list(doc.lines)
    line = lines[parent.start]
    a, b = parent.flow_span
    new_flow = flow_set(line[a:b], key, rendered)
    lines[parent.start] = line[:a] + new_flow + line[b:]
    return _reparse(doc, lines)


def remove_key(doc: Document, path: str) -> Document:
    parts = _split_path(path)
    parent_path, key = parts[:-1], parts[-1]
    parent: Optional[Node] = doc.root
    for part in parent_path:
        e = find_entry(parent, part)
        if e is None or e.node is None:
            return doc
        parent = e.node
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
    del lines[start:end]
    return _reparse(doc, lines)


def _key_line_indent(doc: Document, parent_path: list[str], lines: list[str]) -> Optional[int]:
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
    entry = None
    grand = resolve_parent(doc, parent_path[:-1], allow_null=True)
    if grand is not None:
        entry = find_entry(grand, parent_path[-1])
    if entry is None:
        return None
    return (entry.line + 1, entry.key_indent + 2)


def _insert_key(doc: Document, parent_path: list[str], key: str, value: Any,
                *, as_empty: bool = False) -> Optional[Document]:
    """在 parent_path 下插入 key。父级缺失就新建（只建映射）；中间是序列/标量则返回 None。"""
    cur = doc
    # 逐级确保父级存在
    for i in range(len(parent_path) + 1):
        prefix = parent_path[:i]
        if prefix and resolve_parent(cur, prefix, allow_null=True) is None:
            # 先建上一级
            built = _insert_key(cur, prefix[:-1], prefix[-1], None, as_empty=True)
            if built is None:
                return None
            cur = built
    lines = list(cur.lines)
    where = _key_line_indent(cur, parent_path, lines)
    if where is None:
        return None
    at, indent = where
    render: list[str] = []
    if as_empty:
        lines.insert(at, " " * indent + f"{_render_key(key)}:")
        return _reparse(cur, lines)
    leaf = None if isinstance(value, (dict, list)) else _render_leaf(value)
    if leaf is not None:
        render.append(" " * indent + f"{_render_key(key)}: {leaf}")
    else:
        render.append(" " * indent + f"{_render_key(key)}:")
        if value is not None:
            render_block(value, indent + 2, render)
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
