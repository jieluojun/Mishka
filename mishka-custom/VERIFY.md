# 已验证的事实（命令 + 结果）

本文件记的是**实际跑过的验证**，不是设计意图。所有命令都可以在你机器上重跑；
离线部分用 `bash mishka-custom/scripts/verify.sh` 一条命令全跑。

最近一次全量自检（2026-10-02，使用干净的 Mishka 基线副本；未提供内核目录）：

```
$ bash scripts/verify.sh --repo /path/to/clean/mishka
通过 11 项，失败 0 项，跳过 1 项（内核补丁：未提供 --kernel-dir）
```

已单独跑 `tools/verify_app_patch.sh` 及 `scripts/apply-patches.sh` / `scripts/revert-patches.sh` 往返，app 补丁能应用、逐文件哈希一致、撤销后工作区干净。Android Gradle 编译因当前环境缺少 Android SDK 而无法进入 app 编译阶段。

---

## 1. app 侧补丁：双向可逆、结果与基线逐文件一致

`tools/verify_app_patch.sh --repo <仓库>` 要求仓库处于补丁基线 commit 且工作区干净。最近一次结果：

```
ok  补丁文件 sha256 与基线一致
ok  仓库 HEAD 与基线 commit 一致
--- apply --check ---
ok  补丁可应用
--- apply（正向） ---
25 个受影响文件的 blob 全部与 BASELINE.txt 一致
--- apply -R（反向） ---
ok  新增源码已移除
ok  工作区已回到干净状态
PASS: app 侧补丁双向可逆、结果与基线逐文件一致
```

当前 app 补丁：25 files changed / 13152 insertions / 1 deletion，包括 17 个新 Kotlin 源文件，以及 8 个上游文件（路由、订阅编辑页、YAML 编辑器和 4 份 strings.xml）。订阅页入口在「覆写」下方；锚点面板仍在 YAML 编辑器工具栏。

基线（`patches/app/BASELINE.txt`）：`upstream_commit=e855709c476c8f82635b3bb6f751975e1319f391`，
`patch_sha256=d92bd55faf2eb1124ba7f751a58d9912c0b05fc33c8a9c386915831a39bfc0c7`。导出脚本使用稳定的
`anchor-panel.seed.patch` + `visual-config-entry.seed.patch`，连续导出两次得到相同 SHA。`scripts/apply-patches.sh`
与 `scripts/revert-patches.sh` 已在干净克隆上往返验证，撤销后 `git status` 为空。

导出时须在干净仓库运行；脚本会拒绝已有 `custom/` 目录，避免 `.git/info/exclude` 隐藏现存源码。源码入补丁使用 `git add -f -A`，
反向应用后的残留检查不只依赖 `git status`（被忽略的文件不可见），而会检查 `find … -name '*.kt'`。

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

## 7. 配置表单引擎：Kotlin 与 Python 逐字节对拍 802/802

表单的写回是「手术式改字节」，光做语法检查证明不了行为正确，所以做了双实现对拍：
`tools/forms/forms_model.py`（规范，用真 PyYAML 交叉验证过）与 `YamlEngine.kt`（上线的那份）
吃同一份语料、同一串操作，输出必须逐字节相同。

```
$ python3 tools/forms/engine_diff.py --jar /tmp/engine.jar
语料 17 个文件，操作 802 条
逐字节一致 802 条，不一致 0 条
```

* 语料：`tools/forms/corpus/f1…f10`（真实配置 / 带锚点 / 扁平 / CRLF / 深层嵌套 / flow 全形态 /
  P2 流程页语料 / 无缩进序列 / flow + CRLF / 引用检查语料：锚点、别名、带冒号的键、块标量混在流程页里）
  + 7 条内置的「括号不配对」文本（PyYAML 读不了，进不了语料文件），操作覆盖标量改值、删键、嵌套改值、新增键、整块替换、
  flow 集合读写，以及 P2 的序列级操作（改项 / 插项 / 删项 / 删光 / 挪项 / 项内改与新建嵌套键 / 项内删键 /
  嵌套列表插挪删 / 映射改名）和多行字符串（顶层 / 深层 / 列表项 / 新项里的字段）。
* 性质测试 `tools/forms/forms_props.py`（真 PyYAML 交叉验证）：T1–T16 共 **697 项通过、0 失败**；
  T15 是多行字符串必须按 `|` 块写、读回逐字相同（flow 项里则拒绝）——这是 P2 实测抓到的一个真 bug，
  之前会写成带裸换行的引号串，YAML 读回时换行被折叠成空格；T16 是括号不配对的 flow 写法必须解析得完、
  坏行原样保留。
* 对齐参考实现时新加的 `f10-refs.yaml` 又抓到 **6 个引擎 bug**（两边同步修，全部进了语料 / 用例）：
  (a) `KEY_RE` 拒绝裸键里带冒号（`geosite:cn: …`），还把 `- "a: b"` 当成键；(b) 整块重写丢掉 `&anchor`；
  (c) Python 侧把 `key: &x [a, b]` 当纯标量；(d) Kotlin 侧在 flow 项里改 `|` 多行 / `*alias` 节点会写坏；
  (e) 块标量结尾吞掉后面的空行；(f) **`[1, 2}` 这类括号不配对的写法让两边解析器死循环**（引擎对 `{` / `[`
  的容错是读到行尾，内层扫描停在不配对的闭合符上原地打转）——表单面板开着时编辑器每次改动都会重新解析，
  这一条在真机上会直接卡死界面，现在按解析失败处理成不透明节点，邻居照改、坏行照留。
* P2 收尾时发现 **CRLF 这一项从 P0 起一直是空跑**，三处叠加：仓库根的 `* text=auto` 把 `f4-crlf.yaml` 提交成了 LF；
  `forms_props.py` / `engine_diff.py` 用 `read_text()` 读语料会把 CRLF 翻成 LF；对拍脚本 `subprocess.run(text=True)`
  又把 Kotlin 输出里的 CRLF 翻回 LF。现在 `.gitattributes` 给 f4 / f9 加了 `-text`，两个脚本全部按字节读写，
  覆盖统计里能看到 `T6 CRLF 2 次`，f4 / f9 的 CRLF 实打实穿过两边引擎并逐字节一致（引擎本身没改，之前也是对的）。
* 一键跑（`verify.sh` 里已接，缺 kotlinc 自动跳过）：

```bash
bash tools/forms/run_engine_diff.sh          # 现编译 + 对拍
bash tools/forms/run_engine_diff.sh --keep    # 复用上次的 jar
```

* kotlinc 不在交付包里（100 MB 级），需要时自取：
  `https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`
  → 解压到 `~/.cache/kt/`（JRE 11+ 即可，无需 Android SDK）；GitHub release 下载不通的环境可以
  `npm i kotlin-compiler`（包里就是官方 kotlinc 2.4.x）+ `pip install jdk4py`（自带 JRE），把 `kotlinc` / `java` 放进 PATH 即可。
* **改过 `YamlEngine.kt` 就必须重跑这一项**：语法门和 API 核对都抓不出行为回归。

对拍真的抓到了 3 个引擎 bug（局部函数作用域、局部函数互递归、flow 列区间），都已修在引擎代码里，
`check_kotlin.py` 全绿但行为是错的——这就是为什么这一项不能省。

### 7.1 删除前引用检查 / 改名级联：与参考实现 JS 对拍 153 例 0 差异

P2 的删除保护与改名级联（`ConfigRefs.kt`）照抄的是 mihomo_box 的 `config-references.js` / `reference-delete.js`
与各编辑器里的同步片段。参考实现是 JS 模块，可以直接跑，所以做了三方对拍：

```
$ bash tools/forms/run_refs_diff.sh          # 现编译 ConfigRefs + 对拍 CLI，再跑 node
refs_diff：153 例，0 处差异，3 次改名被 Kotlin 拒绝（引用在别名 / 锚点里）
```

* 参考实现片段原样放在 `tools/forms/ref/`（`config-references.js` + 它自带的 `vendor/js-yaml.min.js`，来源见
  `ref/SOURCE.txt`），`refs_diff.mjs` 对每份语料里每个代理 / 集合 / 组 / 规则集名字（外加几个不存在的名字）
  跑参考实现的 `deletionState()`，与 `java -jar refs.jar refs` 的输出**逐行**比对（blocked / reason / 每处位置文案）；
* 改名：JS 侧按参考实现各编辑器的同步片段改展开后的对象，Kotlin 侧 `RenameSync` 产出 YAML 再用 js-yaml 读回，
  两边深比较。3 次「拒绝」是 `proxies: *members` 这类引用落在别名 / 锚点里的改名——参考实现对展开后的对象改会把
  共享块一起改掉，Kotlin 侧明确拒绝并提示去编辑器改（「不写坏 YAML」优先于「和参考实现一样」），不算差异；
* `verify.sh` 里已接（要 kotlinc + node + java，缺任一自动跳过）。

## 7.2 表单值读取、eBPF listener 与 MAPLIST 定向测试

运行 `bash tools/forms/run_editor_logic_tests.sh`（Kotlin 纯逻辑测试编译后执行）：

```
eBPF role, FormValues reader, and MAPLIST rename/swap tests passed
```

覆盖现有 YAML 值摘要与读入、listener-specific 的 `listeners[index]` 状态解析（默认 local、mode 与角色 `enable` / 旧 `enabled`）、
切换角色时保留 listener 其它参数、MAPLIST 单键改名/冲突检查/键交换与删除目标键，以及 `fakeip-icmp: reply` 的前置条件：
要么 local 启用且 `local.data-plane=tc`，要么 shared 启用且 `shared.interface` 非空。这里验证的是配置条件判断与表单状态，
不是目标设备上的 TC hook 实际挂载结果。

## 8. app 补丁脚本与 Android 构建状态

* 在干净的 Mishka 克隆上运行 `scripts/apply-patches.sh --repo <repo>`，可应用完整 app 补丁；随后运行
  `scripts/revert-patches.sh --repo <repo>`，所有 8 个 tracked 文件恢复、custom/ 删除，`git status` 为空。
* `tools/export_app_patch.sh` 连跑两次，`0001-anchor-panel.patch` 的 SHA 都是 `d92bd55f…bfc0c7`，验证 seed 流程可重复。
* 已尝试 `./gradlew :app:compileDebugKotlin`（无 daemon、单 worker）。Gradle 在配置 `:app` 时停止：
  `SDK location not found`，环境没有 `ANDROID_HOME`，也没有有效的 `local.properties/sdk.dir`；因此不是 Kotlin/Compose 编译结果，不能声称 Android 编译通过。
* 4 个修改的上游 Kotlin 文件经 `check_kotlin.py` 语法检查通过；4 份 `strings.xml` 均可由 XML parser 解析。`check_api.py --root <上游克隆>` 核对了 80 条 import，
  但只有 Mishka app 源码可用、调用点核对数为 0；另有 5 个外部依赖源码路径缺失，故这是有限的 import 检查，不等价于完整 API 编译。
* `scripts/verify.sh --repo <clean repo>`：11 项通过、0 失败、1 项跳过（未提供 `--kernel-dir`）。

## 9. 静态门（每次改完都跑）

```
$ python3 tools/check_kotlin.py
17 file(s), 0 with syntax errors   # custom/ 下的锚点与表单源码

$ python3 tools/check_kotlin.py <4 个修改的上游 Kotlin 文件>
4 file(s), 0 with syntax errors

$ python3 tools/check_api.py --root /home/user/mishka-upstream
核对 import 80 条、调用点 0 个
Mishka app 源码可用；5 个外部依赖源码路径缺失

$ python3 tools/forms/gen_specs.py --check
ok  FormSpecs.kt 与字段表一致
ok  FORMS-P1.md 与字段表一致
ok  FormSpecsP2.kt 与字段表一致
ok  FORMS-P2.md 与字段表一致

$ python3 tools/equiv/model_freshness.py
模型与 Kotlin 源码的基线一致
```

`check_api.py` 当前仅能对 80 条 import 做部分符号检查；miuix UI / preference、scripta-editor、miuix-icons 与 icons-base 源码路径缺失，
且调用点核对数为 0。不能替代真正的 Kotlin 编译；当前输出不能当作依赖 API 已完整验证。

tree-sitter 的 Kotlin 语法把 `open` / `dynamic` 这类**软关键字**当成修饰符，拿它们做局部变量 / 函数名 / 具名实参会被误报
（P2 实测踩到三次，`dynamic` 参数因此改名 `dynamicOptions`）；`if … else while …`、`if (…) for (…) if (…)` 这类不加花括号的
连写也会误报。代码里已避开，不是编译器的意见。沙箱里装不到 `tree_sitter_language_pack` 时，用 PyPI 的 `tree-sitter-kotlin`
写一个只提供 `get_language("kotlin")` 的替身模块放进 `PYTHONPATH` 即可（本次就是这么跑的）。

历史上的 P2 界面桩编译结果不覆盖本次 P3 / eBPF 变更。纯 Kotlin 的 `YamlEngine.kt` / `FormSpecs*.kt` / `FlowText.kt` /
`FormValues.kt` / `ConfigRefs.kt` 有真实编译和逻辑测试（§7 / §7.1）；`EditorLogicProps.kt` 也由
`bash tools/forms/run_editor_logic_tests.sh` 编译运行。Compose 集成与 Android app 编译仍需 CI 或配置完整 Android SDK 的机器确认（§10）。

## 10. 没能在沙箱里验证的部分（明确列出）

| 项 | 原因 | 建议 |
| --- | --- | --- |
| Android app 编译 / 完整 APK（Gradle + AGP + R8） | 最近一次 `:app:compileDebugKotlin` 在配置 `:app` 时因 `SDK location not found` 失败；未进入 Kotlin 编译 | 配置 Android SDK/`ANDROID_HOME` 或 `local.properties/sdk.dir` 后重跑，或用 CI 工作流 |
| Android cgo 链接（NDK clang） | 沙箱没有 NDK | 同上；已补齐到「只差 NDK」并确认依赖层无问题 |
| 表单与 eBPF 面板真机交互（点按、导航、写回、真实 hook） | 需要设备/模拟器及目标内核；定向纯逻辑测试不能证明设备上的 TC 挂载成功 | 安装成功构建的 APK，在支持的内核 / 设备上验证；eBPF 集成测试结果不代表生产路由或吞吐基准 |
| Alpha 与 Mishka 官方内核的运行时行为差异 | 需要实机跑流量 | 重点看 VPN/ROOT 模式连通性与 TUN 栈（Alpha 默认 `mips`） |
