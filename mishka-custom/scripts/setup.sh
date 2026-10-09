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
#   4. 应用 app 侧补丁 patches/app/0001-anchor-panel.patch（自定义编辑器 + 订阅页可视化配置入口迁移），
#      随后依次叠加 0002 内置「免流」配置、0003 可视化编辑器修复、
#      0004 字段整理 + 列表排序 / 序号 + 批量测速对齐、
#      0005 主页面板 / Web 界面（box.app 同款）+ 路由规则匹配值省略号、
#      0006 规则编辑按钮间距 + 连接页代理类型标签、
#      0007 字段整理按钮移到标题左侧 + 右侧「回退修改」按钮（仅内容有改动时显示，不加依赖）、
#      0008 ROOT TPROXY / eBPF 子模式下把活动配置里的 tun.enable 写死 false（与运行时一致）、
#      0009 面板改用独立 Activity 承载（换加载方式：不再挂在主界面导航栈里）、
#      0010 外部面板的外网请求改走 mihomo mixed-port（修国外地址测出国内 IP / YouTube 测不出延迟；已由 0011 取代）、
#      0011 面板 WebView 代理改用 androidx.webkit ProxyController 整体覆盖（取代 0010 的 shouldInterceptRequest；
#           新增 androidx.webkit 依赖；覆盖生效后才加载面板页）
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
    -h|--help)     sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# ---------------------------------------------------------------- 5. app 侧补丁
step "5/6 app 侧补丁（锚点面板 + 内置免流 + 可视化修复 + 字段整理 + 面板 + 关配置 tun + 面板独立 Activity + 面板代理）"
if app_patch_applied "$REPO"; then
  ok "已应用（$CUSTOM_REL/anchor 与编辑器入口都在）"
else
  if git -C "$REPO" apply --check "$DELIVER/patches/app/0001-anchor-panel.patch" 2>/dev/null; then
    git -C "$REPO" apply "$DELIVER/patches/app/0001-anchor-panel.patch"
    ok "已应用 patches/app/0001-anchor-panel.patch"
  elif git -C "$REPO" apply --check --3way "$DELIVER/patches/app/0001-anchor-panel.patch" 2>/dev/null; then
    git -C "$REPO" apply --3way "$DELIVER/patches/app/0001-anchor-panel.patch"
    ok "已应用（3way 合并，上游可能改过入口文件，注意确认改动）"
  else
    die "app 补丁打不上：上游 $FMES_REL 可能已改动。用 git -C \"$REPO\" apply --check -v \"$DELIVER/patches/app/0001-anchor-panel.patch\" 看详细报错，或按 README.md 手工加入口"
  fi
fi

# ---------------------------------------------------------------- 5b. 内置「免流」配置
# 0002 在 0001 之上：订阅页自动出现一条「免流」文件订阅（res/raw/builtin_mianliu.yaml），首次启动后台导入一次。
# 打不上时只警告、不中断：其余定制照常可用，内置条目需要手动补。
VM_REL="app/src/main/kotlin/top/yukonga/mishka/viewmodel/SubscriptionViewModel.kt"
if grep -q 'importBuiltinMianliuOnce' "$REPO/$VM_REL" 2>/dev/null; then
  ok "内置「免流」配置已应用"
elif git -C "$REPO" apply --check "$DELIVER/patches/app/0002-builtin-mianliu-profile.patch" 2>/dev/null; then
  git -C "$REPO" apply "$DELIVER/patches/app/0002-builtin-mianliu-profile.patch"
  ok "已应用 patches/app/0002-builtin-mianliu-profile.patch（内置「免流」配置）"
elif git -C "$REPO" apply --check --3way "$DELIVER/patches/app/0002-builtin-mianliu-profile.patch" 2>/dev/null; then
  git -C "$REPO" apply --3way "$DELIVER/patches/app/0002-builtin-mianliu-profile.patch"
  ok "已应用（3way 合并，注意确认 SubscriptionViewModel.kt 的 init 改动）"
else
  warn "内置「免流」配置补丁打不上：SubscriptionViewModel.kt 可能已被上游改动。其余定制不受影响。"
fi

# ---------------------------------------------------------------- 5c. 可视化编辑器修复
# 0003 在 0002 之上：路由规则拖动（越过顶 / 底边的落点与回弹）、开关的「没有变化 / 本来就没有设置」误报、
# 挪动末尾规则时残留字符。改动全在自定义编辑器里，与 0001 同样要求打得上，否则中断。
PATCH_0003="$DELIVER/patches/app/0003-visual-editor-fixes.patch"
if [[ -f "$REPO/$CUSTOM_REL/forms/DragSortGeometry.kt" ]]; then
  ok "可视化编辑器修复（0003）已应用"
elif git -C "$REPO" apply --check "$PATCH_0003" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0003"
  ok "已应用 patches/app/0003-visual-editor-fixes.patch（可视化编辑器修复）"
elif git -C "$REPO" apply --check --3way "$PATCH_0003" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0003"
  ok "已应用（3way 合并，注意确认 DragSort.kt / ConfigFormPanel.kt 的改动）"
else
  die "可视化编辑器修复（0003）打不上：custom/forms 或 anchor 下的文件与基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0003\" 看详细原因"
fi

# ---------------------------------------------------------------- 5d. 字段整理 + 列表排序 / 序号 + 批量测速对齐
# 0004 在 0003 之上：配置页源码工具条左侧新增「字段整理」按钮（按官方字段顺序重排，注释 / 空行 /
# 块式流式写法 / 锚点标记原样保留）；DNS「按域名分流解析」（nameserver-policy）与其它 maplist
# 可按住行首把手拖动排序；路由规则页每行显示序号；代理页整组测速与 mihomo_box 的 testGroupAll
# 逐条对齐（组接口的 0 值结论不重测、不可测策略不发请求、每路结果当场回写）。
# 与 0003 同样是硬依赖：打不上就中断（否则「字段整理」按钮会引用不存在的 ConfigTidy）。
PATCH_0004="$DELIVER/patches/app/0004-field-tidy-and-list-parity.patch"
if [[ -f "$REPO/$CUSTOM_REL/forms/ConfigTidy.kt" ]]; then
  ok "字段整理 / 列表排序 / 批量测速对齐（0004）已应用"
elif git -C "$REPO" apply --check "$PATCH_0004" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0004"
  ok "已应用 patches/app/0004-field-tidy-and-list-parity.patch（字段整理 + 列表排序 / 序号 + 批量测速对齐）"
elif git -C "$REPO" apply --check --3way "$PATCH_0004" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0004"
  ok "已应用（3way 合并，注意确认 FlowFormPages.kt / P3FormEditors.kt / ProxyViewModel.kt 的改动）"
else
  die "字段整理补丁（0004）打不上：custom/forms 或 viewmodel/ProxyViewModel.kt 与基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0004\" 看详细原因"
fi

# ---------------------------------------------------------------- 5e. 主页面板 / Web 界面 + 路由规则省略号
# 0005 在 0004 之上：主页「工具」分组下面一格就是「面板 / Web 界面」——照着 box.app 的 PanelScreen，
# 内嵌 WebView 打开 mihomo 的 external-controller 面板（本地面板 = http://<控制器>/ui），另带
# Zashboard / MetaCubeXD 与自定义面板（列表、选中项与上次解析到的本地地址都持久在 panel_cache）；
# 面板里的 http(s) 下载与文件选择走系统 SAF / 选择器，深色跟随 App 主题。同时路由规则列表每行收成
# 一行、末尾省略号（匹配值很长的规则不再把行高撑开）。
# 与 0003 / 0004 同样是硬依赖：打不上就中断（否则主页入口会引用不存在的 custom/panel）。
PATCH_0005="$DELIVER/patches/app/0005-home-web-panel-and-rule-ellipsis.patch"
if [[ -f "$REPO/$CUSTOM_REL/panel/PanelScreen.kt" ]]; then
  ok "主页面板 / Web 界面 + 路由规则省略号（0005）已应用"
elif git -C "$REPO" apply --check "$PATCH_0005" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0005"
  ok "已应用 patches/app/0005-home-web-panel-and-rule-ellipsis.patch（面板 / Web 界面 + 规则省略号）"
elif git -C "$REPO" apply --check --3way "$PATCH_0005" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0005"
  ok "已应用（3way 合并，注意确认 custom/panel、AppNavigation.kt、QuickEntriesSection.kt 的改动）"
else
  die "面板补丁（0005）打不上：custom/panel、navigation 或 home 下的文件与基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0005\" 看详细原因"
fi

# 0006 在 0005 之上：两个小修复——
#  1) 规则编辑对话框「切换为可视化编辑」按钮与输入框之间补 8dp 垂直间距；
#  2) 连接列表每条 TCP/UDP 标签后追加代理类型标签（TUN/TPROXY/EBPF，取 metadata.type）。
PATCH_0006="$DELIVER/patches/app/0006-fixes-editor-margin-conn-proxytype.patch"
FFP_REL="app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt"
CONN_REL="app/src/main/kotlin/top/yukonga/mishka/ui/screen/connection/ConnectionScreen.kt"
if grep -q 'import androidx.compose.foundation.layout.height' "$REPO/$FFP_REL" 2>/dev/null \
    && grep -q 'val proxyType = meta.type.trim().uppercase()' "$REPO/$CONN_REL" 2>/dev/null; then
  ok "规则编辑间距 / 连接代理类型（0006）已应用"
elif git -C "$REPO" apply --check "$PATCH_0006" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0006"
  ok "已应用 patches/app/0006-fixes-editor-margin-conn-proxytype.patch（按钮间距 + 代理类型标签）"
elif git -C "$REPO" apply --check --3way "$PATCH_0006" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0006"
  ok "已应用（3way 合并，注意确认 FlowFormPages / ConnectionScreen 的改动）"
else
  die "修复补丁（0006）打不上：规则编辑 / 连接列表与基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0006\" 看详细原因"
fi

# 0007：把字段整理按钮放到「config.yaml」标题左侧（文件管理器编辑器），右侧加「回退修改」按钮。
PATCH_0007="$DELIVER/patches/app/0007-config-tidy-toolbar.patch"
if grep -q 'fun tidyConfig()' "$REPO/app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt" 2>/dev/null; then
  ok "字段整理工具栏位置（0007）已应用"
elif git -C "$REPO" apply --check "$PATCH_0007" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0007"
  ok "已应用 patches/app/0007-config-tidy-toolbar.patch"
elif git -C "$REPO" apply --check --3way "$PATCH_0007" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0007"
  ok "已应用（3way 合并，注意确认 FileManagerEditorScreen 的改动）"
else
  die "补丁（0007）打不上：FileManagerEditorScreen 与预期基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0007\" 看详细原因"
fi

# ---------------------------------------------------------------- 5h. TPROXY / eBPF 关闭配置里的 tun
# 0008 在 0007 之上：ROOT 的 TPROXY 与 eBPF 两个子模式都不初始化 sing-tun，运行时 override 里
# 也一直是 tun.enable=false，但活动配置（config.yaml / 订阅配置）里的 tun.enable 仍会被补成
# true——导出或手看配置时与运行时不一致，排查极易误导。现在 prepare() 增加 forceTunDisabled：
# 这两个子模式下明文配置把 tun.enable 写死 false（缺 tun 段就按 enable=false 补默认段），age
# 密文配置无法回写，改为在 per-profile overlay 的 tunDefaults 里记 enable=false。TUN 子模式的
# forceTunEnabled 与 VPN 路径完全不变。
PATCH_0008="$DELIVER/patches/app/0008-root-tproxy-ebpf-disable-profile-tun.patch"
APRC_REL="app/src/main/kotlin/top/yukonga/mishka/service/ActiveProfileRuntimeConfig.kt"
ROOTSVC_REL="app/src/main/kotlin/top/yukonga/mishka/service/MishkaRootService.kt"
if grep -q 'forceTunDisabled: Boolean = false' "$REPO/$APRC_REL" 2>/dev/null \
    && grep -q 'forceTunDisabled = submode == Submode.Tproxy || submode == Submode.Ebpf' "$REPO/$ROOTSVC_REL" 2>/dev/null; then
  ok "TPROXY / eBPF 关闭配置 tun（0008）已应用"
elif git -C "$REPO" apply --check "$PATCH_0008" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0008"
  ok "已应用 patches/app/0008-root-tproxy-ebpf-disable-profile-tun.patch（TPROXY / eBPF 关配置 tun）"
elif git -C "$REPO" apply --check --3way "$PATCH_0008" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0008"
  ok "已应用（3way 合并，注意确认 ActiveProfileRuntimeConfig / MishkaRootService 的改动）"
else
  die "补丁（0008）打不上：ActiveProfileRuntimeConfig / MishkaRootService 与预期基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0008\" 看详细原因"
fi

# ---------------------------------------------------------------- 5i. 面板改用独立 Activity 承载
# 0009 在 0008 之上：面板 WebView 原先挂在主界面的导航栈里，重进要跟着转场 / 导航 detach-attach，
# 白闪与内容缺失都出在这一层。现在换掉加载方式——
#   · 新增 PanelActivity（exported=false、singleTop、Theme.Mishka、edge-to-edge 跟随主题），
#     主页「外部面板」改成起 intent，Route.Panel 与导航里的 entry<Route.Panel> 一并删除；
#   · 主题从 App.kt 里原样抽出 MishkaTheme（ui/theme/MishkaTheme.kt）给两个宿主共用，
#     面板的配色 / 缩放密度 / CompositionLocal 与主界面完全一致；
#   · 每次进入都是干净的新 WebView + 入口 URL（重进回面板首页，页内历史清掉），退出 finish
#     即丢弃实例；登录态在 localStorage / CookieManager 里，跨实例保留；
#   · PanelScreen 不再需要 isDark 入参（改读 LocalAppDarkMode），onBack 由宿主传 finish()。
# 仍然只用平台 android.webkit 与项目现有 Compose API，不引入新依赖。
PATCH_0009="$DELIVER/patches/app/0009-panel-standalone-activity.patch"
PANEL_ACT_REL="app/src/main/kotlin/top/yukonga/mishka/PanelActivity.kt"
ROUTE_REL="app/src/main/kotlin/top/yukonga/mishka/ui/navigation/Route.kt"
if [ -f "$REPO/$PANEL_ACT_REL" ] && ! grep -q 'data object Panel : Route' "$REPO/$ROUTE_REL" 2>/dev/null; then
  ok "面板独立 Activity（0009）已应用"
elif git -C "$REPO" apply --check "$PATCH_0009" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0009"
  ok "已应用 patches/app/0009-panel-standalone-activity.patch（面板独立 Activity）"
elif git -C "$REPO" apply --check --3way "$PATCH_0009" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0009"
  ok "已应用（3way 合并，注意确认 PanelActivity / App.kt / AppNavigation.kt 的改动）"
else
  die "补丁（0009）打不上：PanelActivity / App.kt / AppNavigation.kt / Route.kt 与预期基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0009\" 看详细原因"
fi

# 0010 在 0009 之上：外部面板 WebView 的外网请求改走 mihomo mixed-port（应用自身 UID 被代理规则排除，
# 直连会测出国内出口、YouTube 测不出延迟）。新增 PanelProxyFetcher.kt，PanelWebView 挂 shouldInterceptRequest。
# 0011 已取代这套做法（删掉 PanelProxyFetcher.kt）：PanelProxyOverride.kt 在位，说明 0011 已打上，0010 不能再打。
PATCH_0010="$DELIVER/patches/app/0010-panel-webview-proxy.patch"
PANEL_PROXY_REL="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelProxyFetcher.kt"
PANEL_OVERRIDE_REL="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelProxyOverride.kt"
if [ -f "$REPO/$PANEL_PROXY_REL" ] || [ -f "$REPO/$PANEL_OVERRIDE_REL" ]; then
  ok "面板外网请求走代理（0010）已应用（或已被 0011 取代）"
elif git -C "$REPO" apply --check "$PATCH_0010" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0010"
  ok "已应用 patches/app/0010-panel-webview-proxy.patch（面板外网请求走 mihomo mixed-port）"
elif git -C "$REPO" apply --check --3way "$PATCH_0010" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0010"
  ok "已应用（3way 合并，注意确认 PanelWebView 的 shouldInterceptRequest）"
else
  die "补丁（0010）打不上：PanelWebView 与预期基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0010\" 看详细原因"
fi

# 0011 在 0010 之上：面板 WebView 的代理改用 androidx.webkit 的 ProxyController.setProxyOverride，
# 整个 WebView 网络栈指向 127.0.0.1:<mixed-port>（所有方法、CONNECT、WebSocket 都走 mihomo，域名交给 mihomo 解析）；
# 面板页在覆盖生效后才加载。删掉 PanelProxyFetcher.kt 与 shouldInterceptRequest。新增 androidx.webkit 依赖。
PATCH_0011="$DELIVER/patches/app/0011-panel-webview-proxy-override.patch"
if [ -f "$REPO/$PANEL_OVERRIDE_REL" ]; then
  ok "面板代理覆盖（0011）已应用"
elif git -C "$REPO" apply --check "$PATCH_0011" 2>/dev/null; then
  git -C "$REPO" apply "$PATCH_0011"
  ok "已应用 patches/app/0011-panel-webview-proxy-override.patch（面板代理覆盖，取代 0010 的 shouldInterceptRequest）"
elif git -C "$REPO" apply --check --3way "$PATCH_0011" 2>/dev/null; then
  git -C "$REPO" apply --3way "$PATCH_0011"
  ok "已应用（3way 合并，注意确认 PanelWebView / PanelActivity / PanelScreen 的改动）"
else
  die "补丁（0011）打不上：PanelWebView / PanelActivity 与 0010 之后的基线不一致，用 git -C \"$REPO\" apply --check -v \"$PATCH_0011\" 看详细原因"
fi

# ---------------------------------------------------------------- 6. 总结
step "6/6 完成"
say "  仓库状态："
git -C "$REPO" status --short | sed 's/^/    /' || true
cat <<EOF

  下一步（详见 $CUSTOM_DIR_NAME/README.md）：
    ./gradlew :app:downloadGeoFiles
    ./gradlew -I $CUSTOM_DIR_NAME/init/no-debug.init.gradle "-Pmihomo.version=alpha-smart-<sha>-with-at" :app:assembleRelease
  回滚：
    逆序 git apply -R：patches/app/0011 → 0001，再 patches/mihomo/0007 → 0001
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
