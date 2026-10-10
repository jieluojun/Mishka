#!/usr/bin/env bash
# 一键装配：把本交付装进 Mishka 仓库，并把内核换成 jieluojun/mihomo(Alpha) + 打齐全套补丁。
#
#   scripts/setup.sh [--repo <Mishka 仓库>] [--kernel-dir <内核目录>]
#                    [--kernel-repo <URL>] [--branch <分支>]
#                    [--force] [--ci] [--skip-kernel] [--refresh-sum]
#
# 做完这些事（幂等，可重复跑）：
#   1. 把 mishka-custom/ 放进仓库，并在 .git/info/exclude 里登记（不需要动 .gitignore）
#   2. 内核：在 <仓库>/mihomo 放一份 jieluojun/mihomo(Alpha)，checkout 到补丁基线 commit，
#      校验并应用 patches/mihomo/*.patch（用 --kernel-dir 可以放到仓库外）
#   3. 写 go.work + go.work.sum（内核换了分支后缺的依赖哈希都在这，仓库自带的 go.mod/go.sum 不动）
#   4. 应用 app 侧补丁 patches/app/0001…0012（按功能切分，依序应用；每个先判断是否已在仓库里）：
#      0001 锚点面板 + 可视化编辑器：自定义编辑器与订阅页入口迁移；编辑器修复、规则编辑间距、
#           maplist 拖动排序、路由规则序号、路由规则匹配值省略号；应用选择器（搜索兼手动添加、已选置顶、显示计数）
#      0002 内置「免流」配置（可选：打不上只警告）
#      0003 字段整理 + 编辑器工具栏（字段整理按钮位置、回退修改；不加依赖）
#      0004 代理页整组测速对齐（mihomo_box 同款）
#      0005 主页面板 / Web 界面（box.app 同款，独立 PanelActivity 承载）
#      0006 连接列表代理类型标签（TUN/TPROXY/EBPF）
#      0007 TPROXY / eBPF 子模式：活动配置里的 tun.enable 写死 false（与运行时一致）
#      0008 面板外网请求改走 mihomo mixed-port（修国外地址测出国内 IP / YouTube 测不出延迟）：
#           WebView 代理用 androidx.webkit 的 ProxyController 整体覆盖；新增 androidx.webkit 依赖
#      0009 Tproxy 分应用名单与 TUN 对齐（mihomo_box 语义）：白名单 / 黑名单取自 TUN 页，UID 周期重解析
#      0010 ROOT 设置新增「系统」分组：系统 IPv6 开关（默认关闭，root 下立即生效，实时显示首选 APN 协议）
#      0011 支持 32 位 armeabi-v7a：与 arm64-v8a 同时构建内核与 APK（GOARCH=arm、GOARM=7）
#      0012 最低支持 Android 8（API 26）：MIN_SDK 31 → 26；API 29/30/31+ 调用加 SDK_INT 守卫，低版本走替代实现
#
# 回滚：逆序 git apply -R（见 README.md「回滚」）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
KERNEL_DIR_ARG=""
KERNEL_REPO="$KERNEL_URL_DEFAULT"
KERNEL_BRANCH="$KERNEL_BRANCH_DEFAULT"
FORCE=0
CI=0
SKIP_KERNEL=0
REFRESH_SUM=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)        REPO_ARG="$2"; shift 2 ;;
    --kernel-dir)  KERNEL_DIR_ARG="$2"; shift 2 ;;
    --kernel-repo) KERNEL_REPO="$2"; shift 2 ;;
    --branch)      KERNEL_BRANCH="$2"; shift 2 ;;
    --force)       FORCE=1; shift ;;
    --ci)          CI=1; FORCE=1; shift ;;
    --skip-kernel) SKIP_KERNEL=1; shift ;;
    --refresh-sum) REFRESH_SUM=1; shift ;;
    -h|--help)     sed -n '2,/^set -euo pipefail/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
done

DELIVER="$(deliver_root)"
REPO="$(find_repo_root "$REPO_ARG")"
BASE_COMMIT="$(kernel_base_commit "$DELIVER")"
[[ -n "$BASE_COMMIT" ]] || die "读不到内核基线 commit（$DELIVER/patches/mihomo/BASELINE.txt）"

say "交付目录：$DELIVER"
say "仓库：    $REPO（$(git -C "$REPO" rev-parse --short HEAD)）"
say "内核：    $KERNEL_REPO @ $KERNEL_BRANCH，基线 $BASE_COMMIT"

# ---------------------------------------------------------------- 1. 装配 mishka-custom/
step "1/6 装配 mishka-custom/"
if [[ "$DELIVER" != "$REPO/$CUSTOM_DIR_NAME" ]]; then
  target="$REPO/$CUSTOM_DIR_NAME"
  mkdir -p "$target"
  for d in scripts patches kernel init; do
    [[ -e "$DELIVER/$d" ]] && cp -r "$DELIVER/$d" "$target/" 2>/dev/null || true
  done
  # 只用得上 README.md（用法 + 提交清单）；包里没有其它 .md，缺了也静默跳过
  cp "$DELIVER"/README.md "$target/" 2>/dev/null || true
  DELIVER="$target"
  ok "已复制到 $DELIVER"
else
  ok "已在仓库内：$DELIVER"
fi

# ---------------------------------------------------------------- 2. 工作区检查 + exclude
step "2/6 工作区检查"
if is_tracked "$REPO" "$CUSTOM_DIR_NAME"; then
  ok "$CUSTOM_DIR_NAME/ 已在版本控制里（CI 要用），不登记 exclude"
else
  exclude_path "$REPO" "/$CUSTOM_DIR_NAME/"
fi
allow_patterns=(
  "?? $CUSTOM_DIR_NAME/"
  " M $FMES_REL"
  "?? $CUSTOM_REL/"
  "?? go.work"
  "?? go.work.sum"
  "?? scripta/"
  " M gradlew"    # 工作流里 chmod +x 造成的模式位变化（无害）
  "M  gradlew"
)
unexpected=""
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  keep=0
  for p in "${allow_patterns[@]}"; do
    [[ "$line" == "$p"* ]] && keep=1
  done
  [[ $keep -eq 0 ]] && unexpected+="$line"$'\n'
done < <(git -C "$REPO" status --porcelain)
if [[ -n "$unexpected" ]]; then
  if [[ $FORCE -eq 1 ]]; then
    warn "工作区有其它改动，--force 继续："
    printf '%s' "$unexpected" >&2
  else
    say "以下改动不属于本定制（不是 mishka-custom/、go.work、锚点补丁），请先处理或加 --force：" >&2
    printf '%s' "$unexpected" >&2
    die "工作区不干净"
  fi
fi
# 把「生成的 / 机密的」东西挡在 git status 之外（mishka-custom/ 是否登记由上面的跟踪状态决定）
# gradlew 丢可执行位（Windows / 网页上传 / 重新提交时很常见）：Gradle 步骤会直接 Permission denied
if [[ -f "$REPO/gradlew" ]] && git -C "$REPO" ls-files -s gradlew 2>/dev/null | grep -q '^100644'; then
  warn "gradlew 在仓库里的权限位是 100644（上游是 100755）——本地与 CI 都会 Permission denied。修复："
  warn "  chmod +x gradlew && git update-index --chmod=+x gradlew && git commit -m 'fix: 恢复 gradlew 可执行位'"
fi
exclude_path "$REPO" "/go.work"
exclude_path "$REPO" "/go.work.sum"
exclude_path "$REPO" "/$CUSTOM_REL/"
# fork 里没有子模块条目时，scripta / mihomo 都是普通目录 → 别让它们脏 status
for sub in scripta mihomo; do
  # 不检查目录是否存在：内核是第 3 步才 clone 的，exclude 得先登记好
  if ! git -C "$REPO" ls-files -s "$sub" 2>/dev/null | grep -q '^160000'; then
    exclude_path "$REPO" "/$sub/"
  fi
done
ok "已登记 .git/info/exclude（go.work、go.work.sum、custom/ 源码目录）"

# ---------------------------------------------------------------- 3. 内核
step "3/6 内核：jieluojun/mihomo @ $KERNEL_BRANCH"
KERNEL_DIR="${KERNEL_DIR_ARG:-$REPO/mihomo}"
if [[ $SKIP_KERNEL -eq 1 ]]; then
  warn "--skip-kernel：跳过内核安装与补丁"
else
  if [[ -e "$KERNEL_DIR" ]] && git -C "$KERNEL_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    origin="$(git -C "$KERNEL_DIR" remote get-url origin 2>/dev/null || echo '')"
    case "$origin" in
      *jieluojun/mihomo*)
        ok "复用已有内核仓库（origin=$origin）" ;;
      *YuKongA/mihomo*|*MetaCubeX/mihomo*)
        # 上游 mihomo 子模块（Mishka 默认状态）：换成本定制的内核正是目的本身，直接替换
        warn "$KERNEL_DIR 是上游内核检查目录（origin=$origin）：换成本定制要求的内核"
        rm -rf "$KERNEL_DIR" ;;
      *)
        if [[ $FORCE -eq 1 ]]; then
          warn "$KERNEL_DIR 的 origin 是 $origin：--force 换成 jieluojun 内核"
          rm -rf "$KERNEL_DIR"
        else
          die "$KERNEL_DIR 是你自己的仓库（origin=$origin）。确认要换成 jieluojun 内核就加 --force（可用 git -C \"$KERNEL_DIR\" remote -v 查看来源）"
        fi ;;
    esac
  elif [[ -e "$KERNEL_DIR" && -n "$(ls -A "$KERNEL_DIR" 2>/dev/null)" ]]; then
    if [[ $FORCE -eq 1 ]]; then
      warn "$KERNEL_DIR 非空且不是 git 仓库：--force 清掉重建"
      rm -rf "$KERNEL_DIR"
    else
      die "$KERNEL_DIR 非空且不是 git 仓库，加 --force 或换个位置（--kernel-dir）"
    fi
  fi

  if ! git -C "$KERNEL_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    say "  克隆 $KERNEL_REPO（--single-branch --branch $KERNEL_BRANCH）…"
    mkdir -p "$(dirname "$KERNEL_DIR")"
    git clone --single-branch --branch "$KERNEL_BRANCH" "$KERNEL_REPO" "$KERNEL_DIR"
  fi

  if [[ $FORCE -eq 1 && -n "$(git -C "$KERNEL_DIR" status --porcelain)" ]]; then
    warn "内核工作区有改动，--force：先清理"
    git -C "$KERNEL_DIR" reset --hard -q
    git -C "$KERNEL_DIR" clean -fdq
  fi
  if ! git -C "$KERNEL_DIR" cat-file -e "$BASE_COMMIT^{commit}" 2>/dev/null; then
    say "  拉取基线 commit $BASE_COMMIT …"
    git -C "$KERNEL_DIR" fetch --quiet origin "+$BASE_COMMIT:refs/mishka-custom/base" || true
  fi
  if git -C "$KERNEL_DIR" cat-file -e "$BASE_COMMIT^{commit}" 2>/dev/null; then
    current="$(git -C "$KERNEL_DIR" rev-parse HEAD)"
    if [[ "$current" != "$BASE_COMMIT" ]]; then
      if [[ -n "$(git -C "$KERNEL_DIR" status --porcelain)" ]]; then
        die "内核 HEAD=$current 与基线不符，且工作区有改动；先还原：git -C \"$KERNEL_DIR\" reset --hard"
      fi
      git -C "$KERNEL_DIR" checkout -q "$BASE_COMMIT"
      ok "内核已切到基线 commit（$(git -C "$KERNEL_DIR" rev-parse --short HEAD)）"
    else
      ok "内核已在基线 commit"
    fi
  else
    warn "拿不到基线 commit $BASE_COMMIT，按当前 HEAD 继续（补丁可能失配）"
  fi

  if kernel_patched "$KERNEL_DIR"; then
    ok "内核补丁已应用（config/patch_mishka.go 在位）"
  else
    if [[ -z "$(git -C "$KERNEL_DIR" status --porcelain)" ]]; then
      say "  预检补丁（临时索引，不动工作区）…"
      if [[ -f "$DELIVER/tools/verify_mihomo_patches.sh" ]]; then
        bash "$DELIVER/tools/verify_mihomo_patches.sh" --kernel-dir "$KERNEL_DIR" >/dev/null \
          || die "内核补丁无法干净应用到 $BASE_COMMIT，请检查内核版本（--kernel-dir/--branch）"
      else
        # 没上传 tools/ 也能跑：内联的累积校验
        kernel_patches_check "$DELIVER" "$KERNEL_DIR" \
          || die "内核补丁无法干净应用到 $BASE_COMMIT，请检查内核版本（--kernel-dir/--branch）"
      fi
      ok "补丁预检通过"
    fi
    for p in "$DELIVER"/patches/mihomo/[0-9]*.patch; do
      git -C "$KERNEL_DIR" apply "$p" || die "应用失败：$(basename "$p")"
      ok "已应用 $(basename "$p")"
    done
  fi

  # 内核目录就是 mihomo 子模块路径时，让 git status 不再盯着它（可在 revert 时还原）
  if [[ "$KERNEL_DIR" == "$REPO/mihomo" ]]; then
    git -C "$REPO" config submodule.mihomo.ignore all
    ok "已设置 submodule.mihomo.ignore=all（status 不再显示内核差异；revert --all 会还原）"
  fi
fi

# ---------------------------------------------------------------- 4. go.work / go.work.sum
step "4/6 Go 工作区（go.work + go.work.sum）"
if [[ $SKIP_KERNEL -eq 1 ]]; then
  warn "跳过（--skip-kernel）"
else
  rel_kernel="$(python3 - "$KERNEL_DIR" "$REPO" <<'PY'
import os, sys
print(os.path.relpath(os.path.realpath(sys.argv[1]), os.path.realpath(sys.argv[2])))
PY
)"
  cat > "$REPO/go.work" <<EOF
go 1.25.0

// 内核换成 jieluojun/mihomo 后，mishka_core 自带的 go.sum 覆盖不到新依赖；
// 工作区模式用本目录的 go.work.sum 补哈希，仓库里的 go.mod / go.sum 一个字节都不用改。
use (
	./$CORE_MODULE_REL
	./$rel_kernel
)
EOF
  ok "写入 go.work（use ./$rel_kernel）"
  if [[ -f "$REPO/go.work.sum" && $REFRESH_SUM -eq 0 ]]; then
    ok "go.work.sum 已存在，保留（要覆盖用 --refresh-sum）"
  else
    cp "$DELIVER/kernel/go.work.sum" "$REPO/go.work.sum"
    ok "写入 go.work.sum（$(wc -l < "$REPO/go.work.sum") 行）"
  fi
fi

# ---------------------------------------------------------------- 5. app 侧补丁（10 个功能补丁，依序）
step "5/6 app 侧补丁（patches/app/0001…0012，按功能切分，依序应用）"
# 按旧系列（旧编号 0001–0010）装过的仓库：面板相关文件停在旧系列的中间状态，和本系列对不上，
# 不能直接叠加。停下来让人先还原到 dd21ee4 基线，不静默保留旧实现。
if [[ -f "$REPO/$CUSTOM_REL/panel/PanelProxyFetcher.kt" ]]; then
  die "检测到旧系列的 0010（PanelProxyFetcher.kt）：本系列按功能重新切分，不能直接叠加。请先撤回旧 0010：
      unzip -p <原始交付包 mishka-custom-20261009.zip> mishka-custom/patches/app/0010-panel-webview-proxy.patch > /tmp/old-0010.patch
      git -C \"$REPO\" apply -R /tmp/old-0010.patch
  撤回后仓库应回到旧 0009 的状态。本系列的 0001 已包含应用选择器改动：若仓库里没有这部分改动，0001 会打不上，届时还原到 dd21ee4 基线。提交（或加 --force）后再重跑本脚本。
  若 git apply -R 失败，说明仓库里的改动不是原样的，请先还原到 dd21ee4 基线。"
fi
APP_PATCHES=(
  0001-anchor-panel.patch
  0002-builtin-mianliu-profile.patch
  0003-config-tidy-toolbar.patch
  0004-batch-latency-parity.patch
  0005-home-web-panel.patch
  0006-conn-proxy-type-label.patch
  0007-root-tproxy-ebpf-disable-profile-tun.patch
  0008-panel-webview-proxy.patch
  0009-tproxy-app-filter.patch
  0010-system-ipv6-switch.patch
  0011-armeabi-v7a.patch
  0012-minsdk-26.patch
)
for pname in "${APP_PATCHES[@]}"; do
  num="${pname:0:4}"
  patch="$DELIVER/patches/app/$pname"
  [[ -f "$patch" ]] || die "缺少补丁文件：patches/app/$pname"
  if app_feature_applied "$REPO" "$num"; then
    ok "已在仓库里：$pname"
  elif git -C "$REPO" apply --check "$patch" 2>/dev/null; then
    git -C "$REPO" apply "$patch"
    ok "已应用 $pname"
  elif git -C "$REPO" apply --check --3way "$patch" 2>/dev/null; then
    git -C "$REPO" apply --3way "$patch"
    ok "已应用 $pname（3way 合并，注意确认改动）"
  elif [[ "$num" == "0002" ]]; then
    # 内置「免流」配置是可选的：打不上只警告、不中断，其余定制照常可用，内置条目需要手动补
    warn "$pname 打不上：SubscriptionViewModel.kt 可能已被上游改动。其余定制不受影响。"
  else
    die "补丁 $pname 打不上：仓库不在它的前一个状态。用 git -C \"$REPO\" apply --check -v \"$patch\" 看详细原因；装过旧系列的话，先还原到 dd21ee4 基线。"
  fi
done

# ---------------------------------------------------------------- 6. 总结
step "6/6 完成"
say "  仓库状态："
git -C "$REPO" status --short | sed 's/^/    /' || true
cat <<EOF

  下一步（详见 $CUSTOM_DIR_NAME/README.md）：
    ./gradlew :app:downloadGeoFiles
    ./gradlew -I $CUSTOM_DIR_NAME/init/no-debug.init.gradle "-Pmihomo.version=alpha-smart-<sha>-with-at" :app:assembleRelease
  回滚：
    逆序 git apply -R：patches/app/0012 → 0001，再 patches/mihomo/0007 → 0001
  拉上游更新：
    先按上面回滚 → git pull → 再跑 setup.sh（每步都用 git apply --check 做过幂等判断）
EOF

if ! is_tracked "$REPO" "$CUSTOM_DIR_NAME"; then
  cat <<EOF

  ⬆️ 要不要上传到 GitHub？（想用 Actions 出包就要）
     git add .github/workflows/release.yml "$CUSTOM_DIR_NAME" && git push
     注意：$CUSTOM_DIR_NAME/ 此刻被 .git/info/exclude 忽略着，先删掉 .git/info/exclude 里那行，或 git add -f。
     清单见 README.md「提交到仓库的文件清单」；mihomo/、go.work*、custom/ 源码、keystore 都不要提交。
EOF
fi
