#!/usr/bin/env python3
"""尾随 lambda 结构检查（无编译器环境下的防回归工具）。

用法: python3 tools/check_trailing_lambda.py --repo <Mishka 仓库>

背景：Kotlin 的尾随 lambda 永远绑给**声明里最后一个参数**。如果某个帮助函数的最后一个
参数是 `modifier: Modifier = Modifier`，那么 `Foo(icon, desc) { … }` 会把 lambda 当
`Modifier` 传，K2 报 "No value passed for parameter 'onClick'" 与
"Argument type mismatch: actual type is '() -> Unit', but 'Modifier' was expected"。

这个错误在 CI 上要 3 分半钟才暴露（`:app:compileReleaseKotlin`），本地没有 Kotlin 工具链
时也看不出来，所以用一个纯文本检查兜住：扫描 custom/ 下的函数声明与调用点，凡是
「以尾随 lambda 形式调用」的函数，其声明的最后一个参数必须是函数类型（含 `->` 或
`@Composable`）。

只检查 `custom/`（本定制的自研代码）；上游代码不归本工具管。
退出码：0 = 全部匹配；1 = 存在不匹配（CI 必定编译失败）。
"""

import argparse
import os
import re
import sys

DECL = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:internal|private|public|inline|suspend|override)\s+)*'
    r'fun\s+([A-Za-z0-9_]+)\s*\(',
    re.M,
)


def scan_parens(t, i):
    """t[i] == '('；返回与之配对的 ')' 下标（跳过字符串字面量里的括号）。"""
    depth = 0
    quote = None
    while i < len(t):
        c = t[i]
        if quote:
            if c == '\\':
                i += 2
                continue
            if c == quote:
                quote = None
        else:
            if c in '"\'`':
                quote = c
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    return -1


def strip_comments(params):
    """按行剥掉 `//` 注释：注释里的反引号 / 引号（如 `MiniIconButton(icon, desc) { … }`）
    会把引号状态机带偏，导致后面的逗号不再切分——负测试曾因此漏报。"""
    out = []
    for line in params.splitlines():
        i = line.find('//')
        if i >= 0:
            # 只在 `//` 前没有引号时才当注释（参数默认值里的 "http://..." 要留住）
            head = line[:i]
            if head.count('"') % 2 == 0 and head.count("'") % 2 == 0:
                line = head
        out.append(line)
    return '\n'.join(out)


def split_top_level(params):
    """把参数表按「顶层逗号」切成参数块。

    两个坑（都真的踩过）：
      1. `->` 里的 `>` 曾被当成泛型收尾，把深度减成负数，之后的逗号不再切分，
         `onClick` 与 `modifier` 被并成一个参数——负测试因此漏报；
      2. 注释里的反引号 / 引号会带偏引号状态，所以先按行剥注释（strip_comments）。
    """
    params = strip_comments(params)
    depth = 0
    quote = None
    cur = []
    parts = []
    i = 0
    n = len(params)
    while i < n:
        c = params[i]
        nxt = params[i + 1] if i + 1 < n else ''
        if quote:
            if c == '\\':
                cur.append(params[i:i + 2])
                i += 2
                continue
            if c == quote:
                quote = None
            cur.append(c)
            i += 1
            continue
        if c in '"\'`':
            quote = c
            cur.append(c)
            i += 1
            continue
        if c == '-' and nxt == '>':  # 箭头整体跳过，`>` 不是泛型收尾
            cur.append('->')
            i += 2
            continue
        if c in '(<[':
            depth += 1
        elif c in ')]':
            depth -= 1
        elif c == '>' and depth > 0:  # 只在这里才把 `>` 当泛型收尾
            depth -= 1
        elif c == ',' and depth == 0:
            parts.append(''.join(cur))
            cur = []
            i += 1
            continue
        cur.append(c)
        i += 1
    parts.append(''.join(cur))
    return [p.strip() for p in parts if p.strip()]


def last_param(params):
    """参数表里最后一个顶层参数。"""
    parts = split_top_level(params)
    return parts[-1] if parts else ''


def blank_comments(text):
    """把 `//` 注释替换成空格（保留换行与列数）。

    注释里出现的调用形状（如 KDoc 写的 `MiniIconButton(icon, desc) { … }`）曾被当成真的
    调用点报出来；扫调用前先置空注释，行号不受影响。
    """
    out = []
    for line in text.splitlines(keepends=True):
        i = line.find('//')
        if i < 0:
            out.append(line)
            continue
        head = line[:i]
        if head.count('"') % 2 or head.count("'") % 2:
            out.append(line)  # `//` 在字符串里（如 "http://…"），不是注释
            continue
        nl = '\n' if line.endswith('\n') else ''
        out.append(head + ' ' * (len(line) - i - len(nl)) + nl)
    return ''.join(out)


def collect(files):
    decls = {}
    decl_parens = set()
    for f in files:
        text = open(f, encoding='utf-8').read()
        for m in DECL.finditer(text):
            op = text.find('(', m.end() - 1)
            cl = scan_parens(text, op)
            if cl < 0:
                continue
            decl_parens.add((f, op))
            lp = last_param(text[op + 1:cl])
            is_lambda = '->' in lp
            decls.setdefault(m.group(1), []).append((f, is_lambda, lp))
    return decls, decl_parens


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo', required=True, help='Mishka 仓库根目录')
    args = ap.parse_args()

    root = os.path.join(args.repo, 'app/src/main/kotlin/top/yukonga/mishka/custom')
    if not os.path.isdir(root):
        print(f'错误：找不到 {root}', file=sys.stderr)
        return 2

    files = []
    for dp, _, fns in os.walk(root):
        files += [os.path.join(dp, f) for f in fns if f.endswith('.kt')]

    decls, decl_parens = collect(files)

    trailing = 0
    bad = []
    for f in files:
        raw = open(f, encoding='utf-8').read()
        text = blank_comments(raw)  # 注释不参与调用点识别
        for m in re.finditer(r'(?<![\w.])([A-Za-z0-9_]+)\s*\(', text):
            name = m.group(1)
            if name not in decls:
                continue
            op = text.find('(', m.end() - 1)
            if (f, op) in decl_parens:  # 函数声明自身，不是调用
                continue
            cl = scan_parens(text, op)
            if cl < 0:
                continue
            j = cl + 1
            while j < len(text) and text[j] in ' \t\r\n':
                j += 1
            if j < len(text) and text[j] == '{':  # 尾随 lambda 调用
                trailing += 1
                if not any(d[1] for d in decls[name]):
                    bad.append((os.path.relpath(f, args.repo), text[:m.start()].count('\n') + 1,
                                name, decls[name][0][2]))

    print(f'扫描 {len(files)} 个文件，{len(decls)} 个函数声明，{trailing} 处尾随 lambda 调用')
    if bad:
        print('FAIL 以下调用的函数，声明里最后一个参数不是函数类型：')
        for f, ln, name, last in sorted(set(bad)):
            print(f'  {f}:{ln}  {name}()  最后参数 = {last[:70]!r}')
        print('（尾随 lambda 会绑到最后一个参数上，K2 将报 No value passed for parameter …）')
        return 1
    print('ok  所有尾随 lambda 调用对应的声明，最后一个参数都是函数类型')
    return 0


if __name__ == '__main__':
    sys.exit(main())
