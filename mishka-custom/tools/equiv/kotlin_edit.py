#!/usr/bin/env python3
"""AnchorEdit.kt / AnchorBlock.kt / YamlValue 的逐行 Python 转写（性质测试用的模型）。

这是「模型的模型」：Kotlin 是交付物，Python 是把它搬到沙箱里跑得到的等价实现，
用来验证**算法本身**（改名级联 / 摘定义 / 改绑 / 块内编辑 / 值规范化）在边界样本上的性质。
它不会自动跟着 Kotlin 改——改完 Kotlin 要手动同步这里，并由 model_freshness.py 盯住两边哈希。

用法: 由 edit_props.py 导入（也可以 REPL 里手动调）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from scan_kotlin import (  # noqa: E402
    NAME_RE,
    bare_mask,
    block_end_index,
    indent_of,
    is_valid_name,
    path_of,
    tokens_on_line,
    unquote,
)

# ==================== 与 Kotlin 同表的常量 ====================

NAME_PAT = r"[^\s,\[\]{}#]+"
DEF_LINE_RE = re.compile(r"^(\s*)(-\s+)?([^:#]+?)\s*:\s*&(" + NAME_PAT + r")\s*(.*)$")
MERGE_REF_RE = re.compile(r"^(\s*)<<\s*:\s*\*(" + NAME_PAT + r")\s*(#.*)?$")
VALUE_REF_RE = re.compile(r"^(\s*)([^:\s#][^:]*?)\s*:\s*\*(" + NAME_PAT + r")\s*(#.*)?$")
SEQ_ITEM_RE = re.compile(r"^(\s*)-\s*(.*)$")
MAP_ENTRY_RE = re.compile(r"^(\s*)([^:#][^:]*?)\s*:\s*(.*)$")
TOP_KEY_RE = re.compile(r"^([^\s:#][^:]*?)\s*:")
MULTI_BLANK_RE = re.compile(r"\n\n\n+")

MIHOMO_TOP_KEYS = {
    "port", "socks-port", "redir-port", "tproxy-port", "mixed-port", "bind-address",
    "mode", "log-level", "ipv6", "allow-lan", "skip-auth-prefixes", "authentication",
    "unified-delay", "tcp-concurrent", "interface-name", "routing-mark", "find-process-mode",
    "global-client-fingerprint", "keep-alive-idle", "keep-alive-interval",
    "external-controller", "external-controller-cors", "external-controller-pipe",
    "external-controller-unix", "external-doh-server", "external-ui", "external-ui-url",
    "external-ui-name", "secret", "geox-url", "geo-auto-update", "geo-update-interval",
    "geodata-mode", "geodata-loader", "geo-auto-private-network",
    "tun", "ebpf", "dns", "sniffer", "hosts", "ntp", "profile", "script", "experimental", "tls",
    "iptables", "listeners", "proxies", "proxy-groups", "rules", "sub-rules",
    "rule-providers", "proxy-providers", "clash-for-android", "auto-redirect",
}


# ==================== AnchorScan 的注释工具 ====================


def comment_index(line: str) -> int:
    mask = bare_mask(line)
    for i, c in enumerate(line):
        if c == "#" and mask[i]:
            return i
    return -1


def trailing_comment(line: str) -> str:
    at = comment_index(line)
    return "" if at < 0 else line[at:].rstrip()


def without_comment(line: str) -> str:
    at = comment_index(line)
    return line if at < 0 else line[:at]


# ==================== AnchorEdit ====================


def parse_def_line(line: str) -> dict | None:
    m = DEF_LINE_RE.search(line)
    if not m:
        return None
    return {
        "indent": len(m.group(1)),
        "dash": m.group(2) is not None and m.group(2) != "",
        "key": unquote(m.group(3).strip()),
        "key_raw": m.group(3).strip(),
        "name": m.group(4),
        "value": m.group(5).strip(),
    }


def parse_ref_line(line: str) -> dict | None:
    m = MERGE_REF_RE.search(line)
    if m:
        return {"indent": m.group(1), "key": None, "comment": (m.group(3) or "").rstrip()}
    m = VALUE_REF_RE.search(line)
    if m:
        return {"indent": m.group(1), "key": m.group(2).strip(), "comment": (m.group(4) or "").rstrip()}
    return None


def ref_names_on_line(line: str) -> list[str]:
    return tokens_on_line(line)["refs"]


def is_token_end(line: str, index: int) -> bool:
    if index >= len(line):
        return True
    c = line[index]
    if c in " \t,[]{}#":
        return True
    return c.isspace()


def replace_token_in_line(line: str, name: str, to: str) -> str:
    if "&" not in line and "*" not in line:
        return line
    mask = bare_mask(line)
    out = []
    i = 0
    while i < len(line):
        c = line[i]
        if (c == "&" or c == "*") and mask[i]:
            end = i + 1 + len(name)
            if end <= len(line) and line[i + 1:end] == name and is_token_end(line, end):
                out.append(c + to)
                i = end
                continue
        out.append(c)
        i += 1
    return "".join(out)


def rename_anchor(text: str, frm: str, to: str) -> str:
    if frm == to:
        return text
    return "\n".join(replace_token_in_line(l, frm, to) for l in text.split("\n"))


def rename_def_key(text: str, def_line1: int, new_key_raw: str) -> str | None:
    lines = text.split("\n")
    idx = def_line1 - 1
    if not (0 <= idx < len(lines)):
        return None
    shape = parse_def_line(lines[idx])
    if shape is None:
        return None
    comment = trailing_comment(lines[idx])
    value = without_comment(shape["value"]).rstrip()
    sb = " " * shape["indent"]
    if shape["dash"]:
        sb += "- "
    sb += new_key_raw + ": &" + shape["name"]
    if value:
        sb += " " + value
    if comment:
        sb += " " + comment
    lines[idx] = sb
    return "\n".join(lines)


def delete_line(text: str, line1: int) -> str:
    lines = text.split("\n")
    idx = line1 - 1
    if not (0 <= idx < len(lines)):
        return text
    del lines[idx]
    return "\n".join(lines)


def rebind_ref_line(text: str, line1: int, new_name: str | None) -> str | None:
    lines = text.split("\n")
    idx = line1 - 1
    if not (0 <= idx < len(lines)):
        return None
    shape = parse_ref_line(lines[idx])
    if shape is None:
        return None
    if new_name is None:
        if shape["key"] is not None:
            return None
        del lines[idx]
        return "\n".join(lines)
    comment = "" if not shape["comment"] else " " + shape["comment"]
    if shape["key"] is None:
        lines[idx] = shape["indent"] + "<<: *" + new_name + comment
    else:
        lines[idx] = shape["indent"] + shape["key"] + ": *" + new_name + comment
    return "\n".join(lines)


def strip_anchor_token(line: str, name: str) -> str:
    mask = bare_mask(line)
    for i in range(len(line)):
        if line[i] != "&" or not mask[i]:
            continue
        end = i + 1 + len(name)
        if end <= len(line) and line[i + 1:end] == name and is_token_end(line, end):
            head = line[:i]
            tail = line[end:]
            if head.rstrip().endswith(":"):
                rest = tail.lstrip(" ")
                return head.rstrip() if not rest else head.rstrip() + " " + rest
            tail = tail.lstrip(" ")
            return head.rstrip() if not tail else head + tail
    return line


def drop_anchor(text: str, name: str) -> tuple:
    """返回 ("kept", text) / ("removed", text, removed_lines) / ("notfound", None) / ("ambiguous", count)。"""
    lines = text.split("\n")
    def_indexes = [i for i in range(len(lines)) if name in tokens_on_line(lines[i])["defs"]]
    if not def_indexes:
        return ("notfound", None)
    if len(def_indexes) > 1:
        return ("ambiguous", len(def_indexes))
    index = def_indexes[0]
    shape = parse_def_line(lines[index])
    top_key = shape["key"] if (shape and shape["indent"] == 0 and not shape["dash"]) else None
    if top_key is None or top_key in MIHOMO_TOP_KEYS:
        out = lines[:]
        out[index] = strip_anchor_token(lines[index], name)
        return ("kept", "\n".join(out))

    section_start = len(lines)
    for i in range(index + 1, len(lines)):
        line = lines[i]
        if not line.strip():
            continue
        if not line[0].isspace():
            section_start = i
            break
    first_content = index == 0 or all(not l.strip() for l in lines[:index])
    cut = section_start
    if first_content:
        while cut < len(lines) and not lines[cut].strip():
            cut += 1
    else:
        while cut > index + 1 and not lines[cut - 1].strip():
            cut -= 1
    out = lines[:]
    del out[index:cut]
    if first_content:
        while out and not out[0].strip():
            del out[0]
    elif index > 0 and not out[index - 1].strip() and (index >= len(out) or not out[index].strip()):
        del out[index - 1]
    return ("removed", "\n".join(out), len(lines) - len(out))


def replace_lines(text: str, start_line1: int, end_line1: int, replacement: str) -> str:
    lines = text.split("\n")
    start = max(0, min(start_line1 - 1, len(lines)))
    end = max(0, min(end_line1 - 1, len(lines) - 1))
    if end < start:
        return text
    out = list(lines[:start])
    if replacement:
        out += replacement.split("\n")
    if end + 1 <= len(lines) - 1:
        out += lines[end + 1:]
    return "\n".join(out)


def insert_top_block(text: str, block: str) -> str:
    body = block.strip("\n")
    if not body:
        return text
    if not text.strip():
        return body + "\n"
    return body + "\n" + text


def has_top_level_key(text: str, key: str) -> bool:
    regex = re.compile("^" + re.escape(key) + r"\s*:(\s|$|#)")
    return any(regex.search(l) for l in text.split("\n"))


def top_level_keys(text: str) -> list[str]:
    keys = []
    for line in text.split("\n"):
        if not line or line[0] in " \t#":
            continue
        m = TOP_KEY_RE.search(line)
        if not m:
            continue
        k = m.group(1).strip()
        if k.startswith('"') and k.endswith('"') and len(k) >= 2:
            k = k[1:-1]
        if k.startswith("'") and k.endswith("'") and len(k) >= 2:
            k = k[1:-1]
        k = k.strip()
        if k and k not in keys:
            keys.append(k)
    return keys


# ==================== AnchorBlock ====================

DEF_MAP, DEF_SEQ, DEF_SCALAR, DEF_UNKNOWN = "Map", "Seq", "Scalar", "Unknown"


def parse_block(lines: list[str], start_index: int, end_index: int, name: str) -> dict | None:
    if not (0 <= start_index < len(lines)):
        return None
    header = lines[start_index]
    shape = parse_def_line(header)
    if shape is None or shape["name"] != name:
        return None
    block_lines = lines[start_index:end_index + 1]
    header_comment = trailing_comment(header)
    body_start, body_end = 1, len(block_lines) - 1
    header_value = without_comment(shape["value"]).strip()
    base = {
        "name": name,
        "key_display": shape["key"],
        "key_raw": shape["key_raw"],
        "indent": shape["indent"],
        "dash": shape["dash"],
        "header_comment": header_comment,
        "lines": block_lines,
        "start_line1": start_index + 1,
        "path": path_of(lines, start_index),
    }
    if header_value:
        return {**base, "kind": DEF_SCALAR, "header_value": header_value, "entries": []}
    non_blank = [i for i in range(body_start, body_end + 1) if block_lines[i].strip()]
    if not non_blank:
        return {**base, "kind": DEF_SCALAR, "header_value": "", "entries": []}
    first_body = block_lines[non_blank[0]]
    flow_root = first_body.lstrip()
    if SEQ_ITEM_RE.fullmatch(first_body):
        kind = DEF_SEQ
    elif flow_root.startswith("{") or flow_root.startswith("["):
        kind = DEF_UNKNOWN
    elif MAP_ENTRY_RE.fullmatch(first_body):
        kind = DEF_MAP
    else:
        kind = DEF_UNKNOWN
    if kind == DEF_UNKNOWN:
        return {**base, "kind": DEF_UNKNOWN, "header_value": "", "entries": []}

    entries = []
    i = body_start
    while i <= body_end:
        line = block_lines[i]
        if not line.strip() or indent_of(line) <= shape["indent"]:
            i += 1
            continue
        m = SEQ_ITEM_RE.search(line) if kind == DEF_SEQ else MAP_ENTRY_RE.search(line)
        if m is None:
            i += 1
            continue
        end = i
        j = i + 1
        while j <= body_end:
            nxt = block_lines[j]
            if not nxt.strip():
                j += 1
                continue
            if indent_of(nxt) > indent_of(line):
                end = j
                j += 1
            else:
                break
        raw_value = (m.group(2) if kind == DEF_SEQ else m.group(3)).strip()
        entries.append({
            "key_raw": "" if kind == DEF_SEQ else m.group(2).strip(),
            "value": without_comment(raw_value).strip(),
            "comment": trailing_comment(line),
            "indent": m.group(1),
            "start_idx": i,
            "end_idx": end,
            "nested": end > i,
        })
        i = end + 1
    return {**base, "kind": kind, "header_value": "", "entries": entries}


def render_block(block: dict, rows: list[dict]) -> str:
    if block["kind"] == DEF_SCALAR:
        value = rows[0]["value"] if rows else ""
        return _render_header(block, value)
    if block["kind"] == DEF_UNKNOWN:
        return "\n".join(block["lines"])
    out = [block["lines"][0]]
    idx = 1
    while idx < len(block["lines"]):
        seed = next((e for e in block["entries"] if e["start_idx"] == idx), None)
        if seed is None:
            out.append(block["lines"][idx])
            idx += 1
            continue
        row = next((r for r in rows if r["seed"] is seed), None)
        if row is None:
            pass  # 删除
        elif seed["nested"] or row["value"] == seed["value"]:
            out += block["lines"][seed["start_idx"]:seed["end_idx"] + 1]
        else:
            out.append(_render_entry(block, seed["key_raw"], row["value"], seed["comment"], seed["indent"]))
        idx = seed["end_idx"] + 1
    for row in [r for r in rows if r["seed"] is None]:
        out.append(_render_entry(block, row["key"], row["value"], "", _default_indent(block)))
    return "\n".join(out)


def _render_header(block: dict, value: str) -> str:
    sb = " " * block["indent"]
    if block["dash"]:
        sb += "- "
    sb += block["key_raw"] + ": &" + block["name"]
    if value:
        sb += " " + value
    if block["header_comment"]:
        sb += " " + block["header_comment"]
    return sb


def _render_entry(block: dict, key_raw: str, value: str, comment: str, indent: str) -> str:
    sb = indent
    if block["kind"] == DEF_SEQ:
        sb += "-"
        if value:
            sb += " " + value
    else:
        sb += key_raw + ":"
        if value:
            sb += " " + value
    if comment:
        sb += " " + comment
    return sb


def _default_indent(block: dict) -> str:
    existing = next((e["indent"] for e in block["entries"] if len(e["indent"]) > block["indent"]), None)
    return existing if existing is not None else " " * (block["indent"] + 2)


def is_mergeable_anchor(text: str, def_line1: int, name: str) -> bool:
    """镜像 AnchorPanel.isMergeableAnchor：`<<:` 能不能合并到这个锚点上。

    只有「能确定不是映射」的形态才拦：序列、以及正文非 flow 映射的单值标量；
    拆不动的形态（Unknown）不拦——挨个拦会把 `d1: &d1 {a: 1, b: 2}` 这类合法的行内映射挡在外面。
    """
    lines = text.split("\n")
    index = def_line1 - 1
    if not (0 <= index < len(lines)):
        return False
    block = parse_block(lines, index, block_end_index(lines, index, name), name)
    if block is None:
        return False
    if block["kind"] == DEF_MAP:
        return True
    if block["kind"] == DEF_SEQ:
        return False
    if block["kind"] == DEF_SCALAR:
        return block["header_value"].lstrip().startswith("{")
    first_body = next((l for l in block["lines"][1:] if l.strip()), None)
    return bool(first_body) and first_body.lstrip().startswith("{")


def build_top_block(top_key: str, anchor: str, rows: list[dict]) -> str:
    pairs = [(r["key"], r["value"]) for r in rows]
    inline = ", ".join(f"{k}:" if not v else f"{k}: {v}" for k, v in pairs)
    one_line = f"{top_key}: &{anchor} {{{inline}}}"
    if len(one_line) <= 160 and pairs:
        return one_line
    sb = top_key + ": &" + anchor
    for k, v in pairs:
        sb += "\n  " + k + ":"
        if v:
            sb += " " + v
    return sb


def row(seed=None, key="", value="") -> dict:
    return {"seed": seed, "key": key, "value": value}


# ==================== YamlValue ====================


def strip_comment(text: str) -> str:
    quote = None
    i = 0
    while i < len(text):
        c = text[i]
        if quote is not None:
            if c == quote:
                if i + 1 < len(text) and text[i + 1] == quote:
                    i += 2
                    continue
                quote = None
        elif c in "\"'":
            quote = c
        elif c == "#" and (i == 0 or text[i - 1].isspace()):
            return text[:i]
        i += 1
    return text


def bare_key_colon_at(text: str) -> int:
    quote = None
    i = 0
    while i < len(text):
        c = text[i]
        if quote is not None:
            if c == quote:
                if i + 1 < len(text) and text[i + 1] == quote:
                    i += 2
                    continue
                quote = None
        elif c in "\"'":
            quote = c
        elif c == ":" and (i == len(text) - 1 or text[i + 1].isspace()):
            return i
        i += 1
    return -1


def balanced(text: str) -> bool:
    quote = None
    square = curly = 0
    i = 0
    while i < len(text):
        c = text[i]
        if quote is not None:
            if c == quote:
                if i + 1 < len(text) and text[i + 1] == quote:
                    i += 2
                    continue
                quote = None
        elif c in "\"'":
            quote = c
        elif c == "[":
            square += 1
        elif c == "]":
            square -= 1
            if square < 0:
                return False
        elif c == "{":
            curly += 1
        elif c == "}":
            curly -= 1
            if curly < 0:
                return False
        i += 1
    return quote is None and square == 0 and curly == 0


def normalize_value(raw: str) -> str | None:
    trimmed = raw.strip()
    if not trimmed:
        return ""
    if trimmed.startswith("&"):
        return None
    if trimmed.startswith("|") or trimmed.startswith(">"):
        return None
    body = strip_comment(trimmed).strip()
    if not body:
        return ""
    if not balanced(body):
        return None
    flow = body.startswith("{") or body.startswith("[")
    if not flow:
        if bare_key_colon_at(body) >= 0:
            return None
        if body[0] in "!%&":
            return None
    return body


def normalize_key(raw: str) -> str | None:
    key = raw.strip()
    if not key:
        return None
    if any(c in ":#&*!|>" for c in key):
        return None
    return key


def normalize_top_key(raw: str) -> str | None:
    key = raw.strip()
    if not key:
        return None
    if any(c.isspace() or c in "#:&*!|>%@`" for c in key):
        return None
    return key
