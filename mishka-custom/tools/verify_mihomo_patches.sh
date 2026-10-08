#!/usr/bin/env bash
# 校验内核补丁：把 patches/mihomo/*.patch 依次（累积）应用到一份干净的 jieluojun/Alpha checkout，
# 再比对结果树哈希与逐文件 blob 哈希是否与交付基线一致。
#
# 用法：
#   tools/verify_mihomo_patches.sh --kernel-dir <干净的内核 clone>
#
# 要求：--kernel-dir 是一个 git 工作树，HEAD 与 BASELINE.txt 的 base_commit 一致，且工作区干净。
# 本脚本只读该工作树：所有改动都写在临时索引里，不动工作区、不动分支。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELIVER_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH_DIR="$DELIVER_ROOT/patches/mihomo"
BASELINE_FILE="$PATCH_DIR/BASELINE.txt"

KERNEL_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --kernel-dir) KERNEL_DIR="$2"; shift 2 ;;
    -h|--help)    sed -n '2,9p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$KERNEL_DIR" ]] || { echo "错误：必须给 --kernel-dir <干净的内核 clone>" >&2; exit 2; }
KERNEL_DIR="$(cd "$KERNEL_DIR" && pwd)"
[[ -f "$BASELINE_FILE" ]] || { echo "错误：找不到 $BASELINE_FILE" >&2; exit 2; }

base_commit="$(sed -n 's/^base_commit=//p' "$BASELINE_FILE" | head -1)"
expected_tree="$(sed -n 's/^patched_tree=//p' "$BASELINE_FILE" | head -1)"

head_commit="$(git -C "$KERNEL_DIR" rev-parse HEAD)"
if [[ "$head_commit" != "$base_commit" ]]; then
  echo "错误：内核 HEAD=$head_commit，基线 base_commit=$base_commit" >&2
  echo "      请先： git -C \"$KERNEL_DIR\" checkout $base_commit" >&2
  exit 1
fi
if [[ -n "$(git -C "$KERNEL_DIR" status --porcelain)" ]]; then
  echo "错误：内核工作区不干净（$(git -C "$KERNEL_DIR" status --porcelain | wc -l) 个改动），请先还原" >&2
  exit 1
fi

shopt -s nullglob
patches=("$PATCH_DIR"/[0-9]*.patch)
[[ ${#patches[@]} -gt 0 ]] || { echo "错误：$PATCH_DIR 下没有补丁" >&2; exit 2; }

tmp_index="$(mktemp -u)"
rm -f "$tmp_index"
trap 'rm -f "$tmp_index"' EXIT
export GIT_INDEX_FILE="$tmp_index"
git -C "$KERNEL_DIR" read-tree HEAD

for p in "${patches[@]}"; do
  git -C "$KERNEL_DIR" apply --cached "$p"
  echo "ok  $(basename "$p")"
done

actual_tree="$(git -C "$KERNEL_DIR" write-tree)"
echo

fail=0
while read -r _ expected_blob path; do
  actual_blob="$(git -C "$KERNEL_DIR" ls-tree "$actual_tree" -- "$path" | awk '{print $3}')"
  if [[ "$actual_blob" == "$expected_blob" ]]; then
    echo "ok  blob $path"
  else
    echo "FAIL blob $path" >&2
    echo "      期望 $expected_blob" >&2
    echo "      实际 $actual_blob" >&2
    fail=1
  fi
done < <(grep '^blob ' "$BASELINE_FILE")
unset GIT_INDEX_FILE

echo
echo "期望 tree: $expected_tree"
echo "实际 tree: $actual_tree"
if [[ "$actual_tree" != "$expected_tree" ]]; then
  echo "FAIL: 补丁应用结果与基线不一致" >&2
  exit 1
fi
[[ $fail -eq 0 ]] || exit 1
echo "PASS: 内核补丁在 $base_commit 上可干净累积应用，结果与基线逐文件一致"
