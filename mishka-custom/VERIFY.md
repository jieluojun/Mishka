# 已验证的事实（命令 + 结果）

本文件记的是**实际跑过的验证**，不是设计意图。所有命令都可以在你机器上重跑；
离线部分用 `bash mishka-custom/scripts/verify.sh` 一条命令全跑。

一条命令全量自检的结果（在装配好的仓库里）：

```
$ bash mishka-custom/scripts/verify.sh --repo . --kernel-dir /path/to/clean/jieluojun-alpha
通过 7 项，失败 0 项，跳过 0 项
```

---

## 1. app 侧补丁：双向可逆、结果与基线逐文件一致

`tools/verify_app_patch.sh --repo <仓库>`（要求在补丁基线 commit 且工作区干净）：

```
ok  补丁文件 sha256 与基线一致
ok  仓库 HEAD 与基线 commit 一致
--- apply --check ---
ok  补丁可应用
--- apply（正向） ---
ok  app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt
ok  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorBlock.kt
ok  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorDialogs.kt
ok  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorEdit.kt
ok  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorPanel.kt
ok  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorScan.kt
--- apply -R（反向） ---
ok  新增目录已移除
ok  工作区已回到干净状态
PASS: app 侧补丁双向可逆、结果与基线逐文件一致
```

补丁规模：9 files / 5198 insertions（8 个新文件 + 编辑器入口 39 行）。
基线（`patches/app/BASELINE.txt`）：上游 `e855709c476c8f82635b3bb6f751975e1319f391`，
`FileManagerEditorScreen.kt` before `b1b24792…c937` → after `8466deb6…8901`，
补丁 sha256 `a6dbe8bd…a419`（导出幂等：`tools/export_app_patch.sh` 连跑两次逐字节一致）。

导出注意：`setup.sh` 会把 `app/src/main/kotlin/top/yukonga/mishka/custom/` 写进 `.git/info/exclude`
（这样装配后 `git status` 依旧干净），所以导出补丁必须 `git add -f -A`，否则整棵源码树会从补丁里消失。
反向应用后的残留检查也不能信 `git status`（被忽略的文件它看不见），要看 `find … -name '*.kt'`。

## 2. 内核补丁：在 Alpha 尖端上累积干净应用，结果树哈希一致

`tools/verify_mihomo_patches.sh --kernel-dir <干净内核>`（用临时索引，不动工作区）：

```
ok  0001-config-override-json.patch
ok  0002-sing-tun-mishka-build-tag.patch
ok  0003-config-mishka-tun-dns-patch.patch
ok  0004-sing-tun-forwarder-bind-interface.patch
ok  blob config/config.go
ok  blob config/patch_mishka.go
ok  blob listener/sing_tun/server.go
ok  blob listener/sing_tun/server_android.go
ok  blob listener/sing_tun/server_notandroid.go
期望 tree: 6d997de22fbc66a1297571a55f7cc65972558044
实际 tree: 6d997de22fbc66a1297571a55f7cc65972558044
PASS
```

基线：`base_commit=fc45379ef1bdbe358c63cc9eb6134e3691cbb113`（jieluojun/mihomo `Alpha` 尖端），
`config/patch_mishka.go` 与 YuKongA 分支**逐字一致**（`diff` 为空）。

## 3. 内核能编译过（真实构建，不是类型检查）

环境：沙箱 linux/amd64、Go 1.25.6、2 核；被编译的是**装配好的仓库布局**
（`app/src/main/native/mishka_core` + 交付生成的 `go.work`/`go.work.sum` + `mihomo/`）。

```
$ cd <仓库>/app/src/main/native/mishka_core
$ GOOS=linux GOARCH=amd64 CGO_ENABLED=1 go build -p 2 -tags cmfa,mishka,with_gvisor \
    -trimpath -buildvcs=false -ldflags "-s -w -X github.com/metacubex/mihomo/constant.Version=deliver-test" \
    -o libmihomo_deliver.so ./
exit=0        # 产物 80,467,792 字节（c-shared）
```

产物里确认包含补丁代码（`strings` 命中）：

```
*.mcdn.bilivideo.cn            1     # 补丁 0003 的 fake-ip 过滤表
Apply override.json failed     1     # 补丁 0001
Read override.json failed      1
28.0.0.0/8                     1
```

**构建结束后 `go.mod` / `go.sum` / 内核树都没有被改动**（`git status --porcelain` 为空）。

构造文件（Android 目标）也通过：

```
$ GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -tags cmfa,mishka,with_gvisor -o /dev/null \
    ./config/ ./listener/sing_tun/        # exit=0
$ go list -tags cmfa,mishka,with_gvisor ./listener/sing_tun/   # → server_android.go（有 mishka）
$ go list -tags cmfa,with_gvisor        ./listener/sing_tun/   # → server_notandroid.go（无 mishka）
$ go list -tags cmfa,mishka,with_gvisor ./config/ | grep mishka # → patch_mishka.go
$ go list -tags cmfa,with_gvisor        ./config/ | grep mishka # → 空（符合预期）
```

Android 目标的**完整 cgo 链接**没在沙箱里做完：`GOOS=android CGO_ENABLED=1` 需要 NDK 的 clang，
沙箱没有。补齐到「只差 NDK」的位置：`# runtime/cgo` → `fatal error: android/log.h: No such file or directory`。

## 4. 为什么必须引入 go.work（反证）

```
$ GOWORK=off GOPROXY=off GOOS=android GOARCH=arm64 go build -tags cmfa,mishka,with_gvisor ./
go: updates to go.mod needed, disabled by -mod=readonly; to update it:
        go mod tidy
```

也就是：上 Mishka 自带的 `go.sum` 编译 jieluojun 内核时，Go 要求改仓库里的 `go.mod`/`go.sum`
（实测缺 63 条依赖哈希）。加上 `go.work` + `go.work.sum` 之后，同一条命令只能抱怨缺 NDK，
依赖层面已经完全满足。

`go.work.sum` 的生成方式（幂等，可重跑）：在 `mishka_core` 目录里分别用
`GOOS=android GOARCH=arm64` 与 `GOOS=linux GOARCH=amd64` 跑 `go mod download all`（130 行）。

## 5. 锚点算法：与 mihomo_box 参考实现三方可对拍

`tools/equiv/compare.py`：参考实现（`mihomo_box` 的 `core.js`，由 `gen_ref_scan.py` 抽取成 `ref_scan.mjs`，
在 node 里跑）== Kotlin 的逐行 Python 转写（`scan_kotlin.py`）== 手写期望（`corpus/expected.json`）。

```
13 个样本，0 个失败        # 01-basic … 13-first，含引号/注释/flow/路径/悬空/多重定义/CRLF/首块
```

对拍字段：锚点集合与顺序、定义行、引用行、merge 判定、路径文案、悬空引用。

## 6. 编辑层：真 YAML 解析器上的性质测试

`tools/equiv/edit_props.py`（PyYAML 真解析，每次手术都要求「仍可解析 + 锚点图变化符合预期 + 未触及字节原样」）：

```
覆盖: parses() 调用 94 次, T1 块 25 个 (Scalar/Map/Seq/Unknown: 13/9/3/0), T2 改名 24 次,
      T3 kept/removed/ambiguous: 5/18/2 (其中带引用 kept/removed: 3/10),
      T4 引用 14 条 (merge 2, 换绑 8, 清继承 2, 无候选 9), T6 替换 25 次, T7 新建 2 个,
      T8 值/键/顶层键 25/6/6 例
（T9）合并目标形态规则（合成样本）：通过
全部通过
```

其中几条是**测试逼出来的真实修正**（都已落回 Kotlin）：

* `AnchorBlock` 的 flow 根识别：`k: &u` 后面缩进一行 `{a: 1}` 之前会被误判成 Map，逐项编辑会写出 `{a: 1`；
  现在先 `trimStart()` 判 `{`/`[` → 归为 `Unknown`（只给文本模式）。合成样本 T1b 覆盖。
* **改绑目标必须定义在引用行之前**：YAML 没有前向别名（`found undefined alias` 由 PyYAML 报出）。
  面板候选列表已按此过滤（`AnchorPanel.refCandidates`），T4 覆盖。
* **`<<:` 只能合并映射**：指到标量/序列会产出非法合并；面板对 merge 行额外过滤候选，T9 用合成样本证明。
* 摘定义（T3）在「有引用」时确实会把配置写坏 —— 这反过来证明了「删除按钮必须按引用数禁用」以及
  「改完立刻重扫并把悬空引用点名」这两条设计的必要性。

## 7. 配置表单引擎：Kotlin 与 Python 逐字节对拍 71/71

表单的写回是「手术式改字节」，光做语法检查证明不了行为正确，所以做了双实现对拍：
`tools/forms/forms_model.py`（规范，用真 PyYAML 交叉验证过）与 `YamlEngine.kt`（上线的那份）
吃同一份语料、同一串操作，输出必须逐字节相同。

```
$ python3 tools/forms/engine_diff.py --jar /tmp/engine.jar
语料 6 个文件，操作 71 条
逐字节一致 71 条，不一致 0 条
```

* 语料：`tools/forms/corpus/f1…f6`（真实配置 / 带锚点 / 扁平 / CRLF / 深层嵌套 / flow 全形态），
  操作覆盖标量改值、删键、嵌套改值、新增键、整块替换、flow 集合读写。
* 一键跑（`verify.sh` 里已接，缺 kotlinc 自动跳过）：

```bash
bash tools/forms/run_engine_diff.sh          # 现编译 + 对拍
bash tools/forms/run_engine_diff.sh --keep    # 复用上次的 jar
```

* kotlinc 不在交付包里（100 MB 级），需要时自取：
  `https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`
  → 解压到 `~/.cache/kt/`（JRE 11+ 即可，无需 Android SDK）。
* **改过 `YamlEngine.kt` 就必须重跑这一项**：语法门和 API 核对都抓不出行为回归。

对拍真的抓到了 3 个引擎 bug（局部函数作用域、局部函数互递归、flow 列区间），都已修在引擎代码里，
`check_kotlin.py` 全绿但行为是错的——这就是为什么这一项不能省。

## 8. 脚本端到端（在真仓库副本上跑过）

* 从「上游内核子模块」状态直接 `setup.sh`：自动把 `mihomo/` 换成人 jieluojun 内核（原地址是
  `YuKongA/mihomo` 时直接替换并提示）、应用 4 个内核补丁、写 `go.work`/`go.work.sum`、应用 app 补丁；
  结束时 `git status` 只有一行：
  `M app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt`
  （`mishka-custom/`、`go.work*`、`custom/` 都被 `.git/info/exclude` 挡掉了）。
* `revert-patches.sh --all` 之后：`git status` 空、`mihomo/` 回到上游 `523fc3e3`、`go.work` 消失。
* `build-release.sh` 前置检查在缺依赖时会给出明确指引（本次实跑：JDK 11 → 提示要 21+；
  无 Android SDK → 提示需要 Platform 37 + NDK；Go 1.25 ✓），并自动生成了 keystore 与 `local.properties`。
* CI 工作流 `ci/build-release.yml` 通过 YAML 解析（1 job / 12 steps）。

## 9. 静态门（每次改完都跑）

```
$ python3 tools/check_kotlin.py app/src/main/kotlin/top/yukonga/mishka/custom
8 file(s), 0 with syntax errors

$ python3 tools/check_api.py
核对 import 54 条、调用点 96 个
全部通过：import 的符号都存在，具名实参都能在对应重载里找到

$ python3 tools/forms/gen_specs.py --check
ok  FormSpecs.kt 与字段表一致
ok  FORMS-P1.md 与字段表一致

$ python3 tools/equiv/model_freshness.py
模型与 Kotlin 源码的基线一致
```

`check_api.py` 的判据来自**真实依赖源码**（miuix 0.9.4 的 85 + 19 个 .kt 与 156 个图标 class、
Mishka 上游 189 个 .kt、scripta 37 个 .kt）——它只用于核对「符号是否存在、具名参数是否存在」，
不能替代真正的 Kotlin 编译。

## 10. 没能在沙箱里验证的部分（明确列出）

| 项 | 原因 | 建议 |
| --- | --- | --- |
| 完整 APK 构建（Gradle + AGP + R8） | 沙箱 2 vCPU / 1.9 GB 内存、无 Android SDK/NDK、无 JDK 21 | 用 CI 工作流或本地 `build-release.sh` |
| Android cgo 链接（NDK clang） | 沙箱没有 NDK | 同上；已补齐到「只差 NDK」并确认依赖层无问题 |
| 面板真机交互（点按、跳转、写回） | 需要设备/模拟器 | 装 `build-release.sh` 出的 release 包自测；算法层已被 T1–T9 与双引擎对拍覆盖 |
| Alpha 与 Mishka 官方内核的运行时行为差异 | 需要实机跑流量 | 重点看 VPN/ROOT 模式连通性与 TUN 栈（Alpha 默认 `mips`） |
