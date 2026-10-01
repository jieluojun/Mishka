#!/usr/bin/env bash
# 打包交付 zip（可复现：固定时间戳、路径排序、保留可执行位）。
#
# 用法: tools/pack_deliver.sh [输出路径]
# 默认输出: /home/user/mishka-custom-<今天>.zip，并写同名 .sha256
#
# 只打包交付根里的文件；排除 __pycache__ / *.pyc / .DS_Store 这类噪音。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELIVER="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="${1:-/home/user/mishka-custom-$(date +%Y%m%d).zip}"

STAGE="$(mktemp -d /tmp/pack.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/mishka-custom"
mkdir -p "$DEST"

# 先落到暂存目录，保证 zip 里没有多余文件，也不会因为 __pycache__ 让 sha256 抖动
tar -C "$DELIVER" \
  --exclude='__pycache__' --exclude='*.pyc' --exclude='.DS_Store' \
  -cf - . | tar -C "$DEST" -xf -

python3 - "$DEST" "$OUT" <<'PY'
import os, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
root = os.path.basename(src)  # mishka-custom
entries = []
for base, dirs, files in os.walk(src):
    dirs[:] = sorted(d for d in dirs if d != '__pycache__')
    for f in sorted(files):
        if f.endswith(('.pyc', '.DS_Store')):
            continue
        full = os.path.join(base, f)
        rel = os.path.join(root, os.path.relpath(full, src)).replace(os.sep, '/')
        entries.append((rel, rel[len(root) + 1:]))
entries.sort()
entries = [(full, rel) for full, rel in entries
           if not (full.startswith('mishka-custom/') and rel.endswith('.sha256'))]

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for full, rel in entries:
        zi = zipfile.ZipInfo(filename=full, date_time=(2026, 10, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        src_path = os.path.join(src, rel)
        mode = os.stat(src_path).st_mode
        zi.external_attr = (mode & 0xFFFF) << 16
        with open(src_path, 'rb') as fh:
            z.writestr(zi, fh.read())
print(f"{len(entries)} 个条目")
PY

# 只保留可执行位：脚本目录 + .sh 必须是 755，其余 644
python3 - "$OUT" <<'PY'
import sys, zipfile
path = sys.argv[1]
with zipfile.ZipFile(path) as z:
    execs = [i.filename for i in z.infolist() if (i.external_attr >> 16) & 0o111]
print("可执行位保留：", ", ".join(sorted(f.split('/', 1)[1] for f in execs)) or "（无）")
PY

sha256sum "$OUT" | sed "s|$OUT|$(basename "$OUT")|" > "$OUT.sha256"
echo "写出：$OUT"
cat "$OUT.sha256"
