# mishka-custom —— Mishka Android 定制补丁交付包

上游 app：YuKongA/Mishka（app 补丁基线 `dd21ee4`）；内核：jieluojun/mihomo @ Alpha（基线见
`patches/mihomo/BASELINE.txt`）。本包只含「打补丁 + 编译」用得上的东西。

## 包里有什么

| 路径 | 用途 |
| --- | --- |
| `patches/app/0001…0011-*.patch` | app 侧补丁，按序号依次叠加（前置关系见 `patches/app/BASELINE.txt` 的 `patchXXXX_prereq`） |
| `patches/app/BASELINE.txt` | app 补丁的基线 commit、每个补丁的 sha256 与前置 |
| `patches/mihomo/0001…0007-*.patch` | 内核补丁，按序号依次应用 |
| `patches/mihomo/BASELINE.txt` | 内核补丁的基线 commit 与 sha256 |
| `scripts/setup.sh` | 一键装配（幂等，可重复跑）：内核 → go.work → 全套 app 补丁 |
| `scripts/lib.sh` | `setup.sh` 的公共函数 |
| `scripts/prepare-scripta.sh` | scripta（编辑器模块）准备脚本 |
| `init/no-debug.init.gradle` | 编译注入脚本：`gradlew -I` 传进去，去掉 debug 相关配置 |
| `kernel/go.work`、`kernel/go.work.sum` | 内核换成 Alpha 分支后缺的依赖哈希（仓库自带的 go.mod/go.sum 不动） |
| `README.md` | 本文件 |

**不在这份包里**：变更说明（`FIX-*.md` / `FEATURE-*.md`）与校验工具（`tools/`：补丁可逆性校验、
跨包 import 检查、面板字符串/图标引用检查、字段整理对拍）。需要时另发。

## 用法 A：一键装配

```bash
bash mishka-custom/scripts/setup.sh --repo <Mishka 仓库> [--kernel-dir <内核目录>] [--skip-kernel] [--ci]
```

按顺序做完（每步都有 `git apply --check` / 幂等标记，重复跑不会重复应用）：

1. 把 `mishka-custom/` 放进仓库，并登记到 `.git/info/exclude`（不动 `.gitignore`）；
2. 内核：准备一份 jieluojun/mihomo(Alpha) 到补丁基线 commit，应用 `patches/mihomo/*.patch`
   （`--kernel-dir` 可以放仓库外）；
3. 写 `go.work` + `go.work.sum`；
4. 依次应用 app 补丁 `0001 → 0011`（0010 已被 0011 取代，但 0011 以它为前置，两者都要按序打上）。

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

## 提交到仓库的文件清单

要提交（用 Actions 出包需要）：

- `mishka-custom/` 整个目录（就是本包内容）
- `.github/workflows/release.yml`

不要提交：内核源码目录、`go.work` / `go.work.sum`（由 setup.sh 现写）、keystore、app 构建产物。

⚠️ `setup.sh` 会把 `mishka-custom/` 登记进 `.git/info/exclude`，提交前先删掉那一行，或 `git add -f`。

## 回滚 / 拉上游更新

逆序还原，再拉更新、重跑装配：

```bash
# app 补丁：0011 → 0001（用 [0-9][0-9][0-9][0-9]-* 而不是 000*：后者漏掉 0010 及以后的补丁）
for p in $(ls -r mishka-custom/patches/app/[0-9][0-9][0-9][0-9]-*.patch); do git apply -R "$p"; done
# 内核补丁：0007 → 0001（在内核目录里执行）
git pull
bash mishka-custom/scripts/setup.sh --repo <Mishka 仓库>
```

## 补丁一览

| # | 内容 |
| --- | --- |
| 0001 | 锚点面板：自定义编辑器 + 订阅页可视化配置入口迁移 |
| 0002 | 内置「免流」配置（`res/raw/builtin_mianliu.yaml`） |
| 0003 | 可视化编辑器修复：拖动落点 / 回弹、开关误报、挪动末尾规则残留字符 |
| 0004 | 字段整理 + 列表拖动排序 / 路由规则序号 / 批量测速对齐 |
| 0005 | 主页面板（Web 界面，box.app 同款）+ 路由规则匹配值省略号 |
| 0006 | 规则编辑对话框按钮间距 + 连接列表代理类型标签（TUN/TPROXY/EBPF） |
| 0007 | 字段整理按钮移到文件名左侧，右侧「回退修改」按钮（仅内容有改动时显示） |
| 0008 | ROOT TPROXY / eBPF 子模式：把活动配置里的 `tun.enable` 写死 `false`（与运行时一致） |
| 0009 | 面板改用独立 Activity 承载（换掉原来的加载方式） |
| 0010 | （已由 0011 取代，仍须先打）面板外网请求改走 mihomo mixed-port（修国外地址测出国内 IP、YouTube 测不出延迟） |
| 0011 | 面板 WebView 代理改用 `androidx.webkit` 的 `ProxyController` 整体覆盖（取代 0010 的 `shouldInterceptRequest`）：GET/POST/WebSocket/CONNECT 都经 mihomo mixed-port，域名由 mihomo 解析；页面在覆盖生效后才加载；代理没接上时顶部给出提示。删除 `PanelProxyFetcher.kt` |

## 依赖变更

**0011 新增依赖** `androidx.webkit:webkit:1.17.1`（`gradle/libs.versions.toml` 加版本与库条目，`app/build.gradle.kts` 加一行 `implementation`）。
0001–0010 没有新增依赖；0011 是第一个。回滚 0011 会把这一行一并撤掉。
