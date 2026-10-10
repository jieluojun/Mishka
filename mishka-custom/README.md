# mishka-custom —— Mishka Android 定制补丁交付包

上游 app：YuKongA/Mishka（app 补丁基线 `dd21ee4`）；内核：jieluojun/mihomo @ Alpha（基线见
`patches/mihomo/BASELINE.txt`）。本包只含「打补丁 + 编译」用得上的东西。

app 补丁是**按功能切分**的系列：每个补丁对应一个功能，原先「修正上一个补丁」的改动已并入它所修正的功能。
编号 0001–0010 与旧系列编号不对应，旧系列的补丁不再随包发布（见下文「从旧系列升级」）。

## 包里有什么

| 路径 | 用途 |
| --- | --- |
| `patches/app/0001…0012-*.patch` | app 侧补丁，按序号依次叠加（前置关系见 `patches/app/BASELINE.txt`） |
| `patches/app/BASELINE.txt` | app 补丁的基线 commit、每个补丁的 sha256、前置与涉及的 blob |
| `patches/mihomo/0001…0007-*.patch` | 内核补丁，按序号依次应用 |
| `patches/mihomo/BASELINE.txt` | 内核补丁的基线 commit 与 sha256 |
| `scripts/setup.sh` | 一键装配（幂等，可重复跑）：内核 → go.work → 全套 app 补丁 |
| `scripts/lib.sh` | `setup.sh` 的公共函数与路径常量 |
| `scripts/prepare-scripta.sh` | scripta（编辑器模块）准备脚本 |
| `init/no-debug.init.gradle` | 编译注入脚本：`gradlew -I` 传进去，去掉 debug 相关配置 |
| `kernel/go.work`、`kernel/go.work.sum` | 内核换成 Alpha 分支后缺的依赖哈希（仓库自带的 go.mod/go.sum 不动） |
| `README.md` | 本文件 |

**不在这份包里**：变更说明（`FIX-*.md` / `FEATURE-*.md`）与校验工具（`tools/`），都不随本包发布。

## 用法 A：一键装配

```bash
bash mishka-custom/scripts/setup.sh --repo <Mishka 仓库> [--kernel-dir <内核目录>] [--skip-kernel] [--ci]
```

按顺序做完（每步都有 `git apply --check` / 已应用判据，重复跑不会重复应用）：

1. 把 `mishka-custom/` 放进仓库，并登记到 `.git/info/exclude`（不动 `.gitignore`）；
2. 内核：准备一份 jieluojun/mihomo(Alpha) 到补丁基线 commit，应用 `patches/mihomo/*.patch`
   （`--kernel-dir` 可以放仓库外）；
3. 写 `go.work` + `go.work.sum`；
4. 依次应用 app 补丁 `0001 → 0012`（其中 0002「内置免流」可选，打不上只警告）。

常用参数：`--skip-kernel` 只打 app 补丁、`--ci` 非交互（配 Actions）、`--force` 脏工作区也继续、
`--refresh-sum` 重算 go.work.sum、`--help` 看全部。

## 用法 B：手工打补丁

app（仓库处在基线 commit `dd21ee4`、工作区干净时）：

```bash
for p in mishka-custom/patches/app/[0-9][0-9][0-9][0-9]-*.patch; do git apply "$p" || break; done
```

内核：先 `git checkout <patches/mihomo/BASELINE.txt 里的 base_commit>`，再按序号 `git apply`。
打不上时 `git apply --check -v <补丁>` 看原因，并拿 sha256 与 BASELINE.txt 对一下，确认补丁本身没被改过。

## 编译

```bash
./gradlew :app:downloadGeoFiles
sha="$(git -C mihomo rev-parse --short=8 HEAD)"
./gradlew -I mishka-custom/init/no-debug.init.gradle "-Pmihomo.version=alpha-smart-${sha}-with-at" :app:assembleRelease
```

不注入 `-Pmihomo.version` 会沿用上游 `gradle.properties` 里的 `v1.19.31+`（metacubex 的版本号），
主页「内核版本」会显示成错的那个。

## 在仓库里重建 mishka-custom/

仓库里若已跟踪旧的 `mishka-custom/`，按下面做（已验证）：

1. 删除旧目录并提交：`git rm -r mishka-custom && git commit -m "删除旧的 mishka-custom"`。
2. 运行装配：`bash <本包>/scripts/setup.sh --repo <Mishka 仓库> --skip-kernel`。包可以放在仓库外，脚本会把它复制进 `mishka-custom/`；去掉 `--skip-kernel` 才会装内核。
3. 确认 `git status` 与预期一致后，提交新目录：`git add -f mishka-custom && git commit -m "mishka-custom 重建"`。

如果不先删旧目录：旧快照被复制过程覆盖后会显示为改动，`setup.sh` 的工作区检查会中止，需要加 `--force`。

## 提交到仓库的文件清单

要提交（用 Actions 出包需要）：

- `mishka-custom/` 整个目录（就是本包内容）
- `.github/workflows/release.yml`

不要提交：内核源码目录、`go.work` / `go.work.sum`（由 setup.sh 现写）、keystore、app 构建产物。

⚠️ `setup.sh` 会把 `mishka-custom/` 登记进 `.git/info/exclude`，提交前先删掉那一行，或 `git add -f`。

## 回滚 / 拉上游更新

逆序还原，再拉更新、重跑装配：

```bash
# app 补丁：0012 → 0001（用 [0-9][0-9][0-9][0-9]-* 匹配，与 setup.sh 一致）
for p in $(ls -r mishka-custom/patches/app/[0-9][0-9][0-9][0-9]-*.patch); do git apply -R "$p"; done
# 内核补丁：0007 → 0001（在内核目录里执行）
git pull
bash mishka-custom/scripts/setup.sh --repo <Mishka 仓库>
```

## 补丁一览

| # | 内容 |
| --- | --- |
| 0001 | 锚点面板 + 可视化编辑器：自定义编辑器与订阅页入口迁移；编辑器修复（拖动落点 / 回弹、开关误报、挪动末尾规则残留字符）；规则编辑对话框的按钮间距；maplist 拖动排序（DNS「按域名分流解析」等）；路由规则序号；路由规则匹配值省略号；应用选择器：搜索框兼作手动添加（输入完整包名后点「添加」或回车，未安装的包名同样保留），已选应用置顶为「已选择 (N)」、其余为「其他应用 (M)」，标题显示清单计数，确定按钮显示已选数量 |
| 0002 | 内置「免流」配置（`res/raw/builtin_mianliu.yaml`，可选） |
| 0003 | 字段整理（`ConfigTidy`，按官方字段顺序重排，注释与块式写法原样保留）+ 编辑器工具栏：字段整理按钮放到文件名左侧，右侧「回退修改」按钮（仅内容有改动时显示） |
| 0004 | 代理页整组测速对齐 mihomo_box 的 `testGroupAll`（组接口的 0 值结论不重测、不可测策略不发请求、每路结果当场回写） |
| 0005 | 主页面板 / Web 界面（box.app 同款）：主页「工具」下方入口，内嵌 WebView 打开内核 external-controller 的面板；独立 `PanelActivity` 承载；WebView 预热 |
| 0006 | 连接列表代理类型标签（TUN/TPROXY/EBPF，取 `metadata.type`） |
| 0007 | TPROXY / eBPF 子模式：把活动配置里的 `tun.enable` 写死 `false`（与运行时一致） |
| 0008 | 面板外网请求改走 mihomo mixed-port（修国外地址测出国内 IP、YouTube 测不出延迟）：WebView 代理用 `androidx.webkit` 的 `ProxyController` 整体覆盖（GET/POST/WebSocket/CONNECT 都经 mihomo，域名由 mihomo 解析）；页面在覆盖生效后才加载；代理没接上时顶部给出提示。新增依赖 |
| 0009 | Tproxy 分应用名单与 TUN 对齐（mihomo_box 语义）：TPROXY 模式改用 TUN 页的「仅代理以下应用 / 排除以下应用」（白名单优先，都未设置则全部代理；未设置时沿用分应用代理页），UID 按 `tun.include-android-user` 展开，名单应用的 UID 每 10 分钟重解析，变化才重装规则 |
| 0010 | ROOT 设置新增「系统」分组，下设「系统 IPv6」开关（移植 mihomo_box 的系统 IPv6 逻辑）：默认关闭；ROOT 可用即立即禁用 Wi-Fi 等非蜂窝网卡的 IPv6（lo / 蜂窝 / 热点下游豁免），上网 APN（跳过 IMS）协议改为 IPv4；开启时恢复 IPv6 并还原 APN；关态下每 5 秒对账一次（60 秒冷却）；打开设置页时读取一次首选 APN 的协议，切换开关成功后再读一次。 |
| 0011 | 支持 32 位 `armeabi-v7a`：内核与 APK 在 arm64-v8a 之外同时构建 armeabi-v7a（Go `GOARCH=arm`、`GOARM=7`，NDK `armv7a-linux-androideabi` 工具链；`splits.abi` 每个 ABI 各出一个 APK）。CI 构建耗时约翻倍 |
| 0012 | 最低支持 Android 8（API 26，原 Android 12 / API 31）：`MIN_SDK` 31 → 26，同时给 API 29/30/31+ 的调用加 `SDK_INT` 守卫：`VpnService.Builder.setMetered` / `setHttpProxy`（API 29，低版本跳过系统代理）、`Os.fcntlInt`（API 30，低版本改用 JNI `nativeClearCloexec`）、三参 `startForeground`（API 29）、`setForegroundServiceBehavior`（API 31）、`canScheduleExactAlarms`（API 31，低版本直接精确调度）、`NetworkCallback(flags)` 与 `transportInfo`（API 31，低版本用无参回调 + WifiManager 回退取 SSID）、`POST_NOTIFICATIONS`（API 33，已有守卫）。Go / NDK 的 clang wrapper 随 minSdk 变为 `android26`。 |

## 系统 IPv6 开关（0010）

位置：ROOT 设置 → 「系统」分组 → 「系统 IPv6」。逻辑对齐 mihomo_box 的 `system_ipv6_sync` / APN 子系统：

| 状态 | 行为 |
| --- | --- |
| 关（默认） | 非蜂窝网卡（wlan 等）写 `disable_ipv6=1`，并关闭 RA / autoconf；上网 APN（`preferapn`，跳过 IMS 承载）的 `protocol` / `roaming_protocol` 改为 `IP`，原值记入 `files/system_ipv6/apn.state` |
| 开 | `disable_ipv6` 归 0，恢复 RA / autoconf，按状态文件还原 APN 协议，并停止常驻循环 |

- **立即执行**：应用启动时若 ROOT 可用，按开关期望值执行（默认即立即禁用）；开机 / 应用升级由 `BootReceiver` 再执行一次。
- **常驻对账**：关态下 root 进程每 5 秒检查一次，飞行模式或切网后被 netd 放回的网卡会被重新禁用；同一网卡 60 秒内最多追打一次。
- **APN 状态**：打开设置页时经 root 读取一次首选 APN，显示协议（仅 IPv4 / IPv4/IPv6 等）与漫游协议；切换开关成功后会重新读取。离开页面不再查询，也没有手动刷新按钮。
- **不碰 IMS**：蜂窝网卡不写内核 `disable_ipv6`，只改 APN 协议，VoLTE 与短信不受影响。
- **eBPF 豁免（与 mihomo_box 的差异）**：mihomo_box 只在「eBPF 角色 ipv6 生效」时保持 IPv6；本模块在 ROOT eBPF 模式下一律保持系统 IPv6 开启，不解析配置。
- **未覆盖**：脚本依赖 APN 数据库可写与 `svc` 命令；多卡 / 定制 ROM 上查不到首选 APN 时，设置页会提示，可手动把 APN 协议设为 IPv4。

源码：`app/src/main/kotlin/top/yukonga/mishka/service/SystemIpv6.kt`、`app/src/main/res/raw/system_ipv6.sh`。

## 依赖变更

**0008 新增依赖** `androidx.webkit:webkit:1.17.1`（`gradle/libs.versions.toml` 加版本与库条目，
`app/build.gradle.kts` 加一行 `implementation`）。0001–0007 没有新增依赖；0008 是第一个。
回滚 0008 会把这一行一并撤掉。

**0009、0010、0011、0012 不新增依赖**：0009 只改 Kotlin 与运行时逻辑；0010 只用 Android 自带的 `su`、`content`、`svc`、`ndc` 命令，不引入新库。

## 从旧系列升级

旧系列（旧编号 0001–0010）的补丁不再随包发布，原件在原始交付包 `mishka-custom-20261009.zip` 里。
本系列原第 0010 号补丁（应用选择器）已并入第 0001 号，所以升级前先看仓库现在是什么状态：

| 仓库里装的是 | 怎么办 |
| --- | --- |
| 上一版交付（本系列 0001–0009 全部装上） | 直接重跑 `setup.sh`：0001–0009 判定为「已在仓库里」，只追加 0010、0011、0012。 |
| 本系列 0001–0012 全部装上 | 与最终态逐字节相同，直接重跑 `setup.sh`，12 个补丁都会判定为「已在仓库里」。 |
| 其它任何中间状态（只装到 0001–0008 之一、或旧系列） | 先还原到 dd21ee4 基线，再装本系列。 |

还原前先确认仓库里没有要保留的改动（`git status`）。还原到基线的做法：

```bash
git -C <Mishka 仓库> checkout dd21ee4    # 或先 git stash 保存改动
bash mishka-custom/scripts/setup.sh --repo <Mishka 仓库>
```

`git apply -R` 失败，说明仓库里的改动不是原样的，同样请先还原到 dd21ee4 基线，不要硬做。
