# Mishka 定制交付包（锚点面板 + 配置表单 + 订阅入口迁移 + jieluojun 内核 + 只出 release）

给你的 Mishka fork 用的定制包。**原则：不改仓库里任何已有文件**，全部以「新增文件 + 可撤销补丁」的形式落地，
随时可以一条命令还原到干净工作区去 `git pull`。

## 三条命令开始用

```bash
# 1) 把本包放进仓库（解压出来的目录名就是 mishka-custom/）
cd <你的 Mishka 仓库>
cp -r /path/to/mishka-custom .

# 2) 一键装配：换内核（jieluojun/mihomo Alpha）+ 打 app/内核补丁 + 生成 go.work
bash mishka-custom/scripts/setup.sh --repo .

# 3) 本地出 release APK（只出 release，debug 变体被 init 脚本挡住）
bash mishka-custom/scripts/build-release.sh --repo .
```

不想在本机构建就用 CI：把 `mishka-custom/ci/build-release.yml` 复制成 `.github/workflows/release.yml` 提交，
然后在 Actions 里手动触发或打 `v*` tag（详见 `BUILD.md`）。

## 交付内容

| 路径 | 内容 |
| --- | --- |
| `scripts/` | `setup.sh`（装配）、`apply-patches.sh` / `revert-patches.sh`（重打/还原）、`build-release.sh`（只出 release）、`gen-keystore.sh`（签名） |
| `patches/app/` | app 侧完整补丁 `0001-anchor-panel.patch`（锚点/表单编辑器、订阅页入口迁移与路由）+ 基线 `BASELINE.txt`；`anchor-panel.seed.patch` + `visual-config-entry.seed.patch` 供可重复导出 |
| `patches/mihomo/` | 内核 4 个补丁（`0001`…`0004`）+ 基线 `BASELINE.txt`（含基线 commit 与逐文件 blob） |
| `kernel/` | `go.work` + `go.work.sum`：让「换了分支的内核」不依赖改仓库 `go.mod`/`go.sum` 就能编译 |
| `init/` | `no-debug.init.gradle`：构建 debug 变体时直接失败（默认只出 release） |
| `ci/` | `build-release.yml`：只出 release 的 GitHub Actions 工作流（新增文件，不动上游 `build.yml`） |
| `app/src/main/kotlin/top/yukonga/mishka/custom/anchor/` | 锚点面板的 5 个 Kotlin 源文件（方便直接阅读；补丁里也含同一份） |
| `app/src/main/kotlin/top/yukonga/mishka/custom/forms/` | 配置表单的 15 个 Kotlin 源文件：保真 YAML 引擎、P1（26 小节 / 154 字段）、P2（266 字段 / 37 种规则 / 27 种协议）、P3 专用编辑器、表单值读取（含锚点继承的展开值回落）、引用检查、MAPLIST 写回逻辑、eBPF listener 编辑逻辑、锚点继承/二级锚点手术与面板、file 类型合集源文件操作 |
| `tools/` | 自检工具：补丁双向校验、语法门、API/具名参数核对、锚点算法三方可对拍、性质测试、模型新鲜度、表单引擎双实现对拍、可复现打包（`pack_deliver.sh`） |
| `README.md` / `CUSTOMIZATION.md` / `BUILD.md` / `INSTALL.md` / `VERIFY.md` / `FORMS.md` / `FORMS-P1.md` / `FORMS-P2.md` | 交付说明、定制详解、构建、安装与回滚、已验证事实、配置表单设计与 P1 / P2 字段清单 |

## 四块定制做了什么事（细节见 `CUSTOMIZATION.md`）

1. **锚点可视化面板**（对齐 mihomo_box 的「配置页 → 锚点面板」）：锚点总览（`&定义` / `*引用` / 悬空告警）、
   定位跳转、定义块可视化编辑、引用行改绑/清除继承/删行、新建顶层定义块、重命名（含顶层键级联）、删除定义
   （mihomo 配置段只摘 `&名`）。所有手术都是**行级、字节保真**的原文替换，改动只落在编辑器草稿里，
   写盘仍走 Mishka 原有的保存路径（内核校验 + 失败回滚）。
2. **可视化配置表单与入口**：P1 / P2 / P3 表单读取并写回已有 YAML 值；订阅编辑页在「覆写」下方提供入口，优先打开 `config.yaml`，否则选择首个 YAML 文件；YAML 编辑器工具栏也恢复表单快捷按钮，位于锚点面板按钮左侧。
3. **内核换成 `jieluojun/mihomo`（`Alpha` 分支）**：Mishka 应用依赖的 4 处内核行为被移植到 Alpha 之上
   （`--override-json`、`mishka` build tag、DNS/TUN 的 Android 适配、fd TUN 的 forwarder 绑定），
   并解决「Alpha 多出来的依赖没有 go.sum 哈希」的问题（`go.work` + `go.work.sum`）。
4. **只构建 release**：本地脚本与 CI 都只跑 `:app:assembleRelease`；`init/no-debug.init.gradle`
   在命令行点名 debug 任务时直接失败。签名支持仓库 secrets，也支持没有 secrets 时用固定参数的
   debug 风格密钥兜底（能装、能覆盖升级）。

## 提交到仓库的文件清单（用 CI 出包时才需要）

本地装配不需要提交任何东西；**只有在 GitHub Actions 上出包时**，才需要把这些推到仓库。
分三类记：

### ✅ 必须提交（CI 构建时会读它们）

| 路径 | 为什么必须 |
| --- | --- |
| `.github/workflows/release.yml` | 由 `mishka-custom/ci/build-release.yml` 复制而来。**唯一需要放到 `mishka-custom/` 之外的文件**；GitHub 只运行仓库里已提交的工作流，所以要出现在 Actions 页面并手动触发，它必须在默认分支上 |
| `mishka-custom/scripts/setup.sh`、`scripts/lib.sh` | 工作流第 3 步就是执行 `setup.sh --ci`（换内核、打补丁、生成 go.work） |
| `mishka-custom/patches/app/0001-anchor-panel.patch` + `patches/app/BASELINE.txt` | app 完整补丁与逐文件校验基线；`anchor-panel.seed.patch` / `visual-config-entry.seed.patch` 是导出过程的稳定种子补丁 |
| `mishka-custom/patches/mihomo/*.patch`（4 个）+ `patches/mihomo/BASELINE.txt` | 内核补丁与基线（`setup.sh` 预检会读基线里的 `base_commit`/`patched_tree`） |
| `mishka-custom/kernel/go.work.sum` | 少了它，换内核后依赖哈希不全，Gradle 里的 Go 编译会直接失败（这是整套方案的关键文件） |
| `mishka-custom/init/no-debug.init.gradle` | CI 的构建命令 `-I mishka-custom/init/no-debug.init.gradle` 指向它，负责挡掉 debug 变体 |
| `mishka-custom/tools/verify_mihomo_patches.sh` | `setup.sh` 在打内核补丁前会调它做预检 |

### 👍 建议一起提交（不参与 CI 构建，但方便自检、阅读、回滚）

`README.md` / `CUSTOMIZATION.md` / `BUILD.md` / `INSTALL.md` / `VERIFY.md`、
`scripts/` 里其余脚本（`build-release.sh`、`apply-patches.sh`、`revert-patches.sh`、`gen-keystore.sh`、`verify.sh`）、
`tools/` 其余（`check_kotlin.py`、`check_api.py`、`api_paths.json`、`export_app_patch.sh`、`verify_app_patch.sh`、`equiv/` 全套）、
`app/src/main/kotlin/top/yukonga/mishka/custom/`（anchor 5 个 + forms 12 个源文件，供阅读；注意 `tools/` 的离线自检读的正是这里，两个目录要一起传）、
`kernel/go.work`（模板）、`kernel/README.md`、`ci/build-release.yml`。

以上文档、脚本、种子补丁与源码建议一并传，最省心。

### ❌ 不要提交（生成的、机密的、巨大的）

| 路径 | 原因 |
| --- | --- |
| `mihomo/` | 换过的内核目录（克隆下来几百 MB）。CI 里由 `setup.sh` 现场克隆并 pin 到基线 commit |
| `go.work`、`go.work.sum`（仓库根） | `setup.sh` 每次都会按实际内核路径重新生成；提交了反而每次构建都产生改动 |
| `app/src/main/kotlin/top/yukonga/mishka/custom/` 与改过的 `FileManagerEditorScreen.kt` | CI 用补丁现场生成。提交了就没法再用 `revert-patches.sh` 一键回到干净状态，也就失去「随时 `git pull`」的意义 |
| `local.properties` | 含 `sdk.dir` 与**签名口令** |
| `mishka-custom/keystore/`（`.jks` + `keystore.properties`） | 私钥与口令；CI 用仓库 secrets，本地留在自己机器上 |
| `build/`、`.gradle/`、`app/build/`、`mishka-custom/build.log`、`*.apk` | 构建产物与缓存 |

### 三条容易踩的坑（都会让 CI 报错，工作流里有自检与修复提示）

0. **别把 `mishka-custom/` 提交成「子模块指针」**：如果你的本地 `mishka-custom/` 里带着 `.git` 目录，
   `git add mishka-custom` 只会记下一个指针，GitHub 上是个空目录，CI 里就是
   `setup.sh: No such file or directory`。先删掉里面的 `.git` 再 `git add`。
   同理：**`gradlew` 必须有可执行位**（`git ls-files -s gradlew` 应以 `100755` 开头，丢了就
   `chmod +x gradlew && git update-index --chmod=+x gradlew`）；**`scripta` 子模块指针别丢**，
   丢了就用 `scripts/repair-fork.sh` 修。

1. **`mishka-custom/` 被本地 exclude 忽略**：`setup.sh` 为了不让 `git status` 变脏，会把 `/mishka-custom/`
   写进 `.git/info/exclude`（这只影响本机，不改 `.gitignore`）。如果你后来决定把它提交上去，
   要么先删掉那行（`sed -i '/^\/mishka-custom\/$/d' .git/info/exclude`），要么 `git add -f mishka-custom`。
   新版 `setup.sh` 已经会检查：**当 `mishka-custom/` 已经被跟踪时就不再登记 exclude**，并会在结尾打印上传提示。
2. **fork 里的 Actions 首次需要手动启用**：打开 fork 的 Actions 页面点一次
   「I understand my workflows, go ahead and enable them」，之后 `workflow_dispatch` 才会出现。

## 随时回到干净状态

```bash
# 只撤 app 补丁（还原订阅入口、路由与编辑器改动 + 删掉 custom/ 源码目录）
bash mishka-custom/scripts/revert-patches.sh --repo .

# 连内核一起还原（mihomo 子模块回到上游、删掉 go.work / go.work.sum）
bash mishka-custom/scripts/revert-patches.sh --repo . --all

# 拉完上游更新，再装回来
git pull && bash mishka-custom/scripts/setup.sh --repo .
```

`mishka-custom/` 自身、`go.work`、`go.work.sum`、`app/src/main/kotlin/.../custom/` 都在 `.git/info/exclude` 里登记过
（改的是本地 exclude，不动 `.gitignore`）。安装 app 补丁后，`git status` 会显示订阅页、路由、编辑器入口与多语言资源文件的改动；
如需还原，使用 `revert-patches.sh`，不要手动只还原其中一个文件。

## 已验证的事实（摘要，完整命令与输出见 `VERIFY.md`）

* app 侧补丁**双向可逆**：`apply --check` → `apply` → 逐文件哈希与基线一致 → `apply -R` → `git status` 为空。
* 内核 4 个补丁在 `fc45379e`（Alpha 尖端）上**可累积干净应用**，结果树哈希与基线一致。
* **内核能编译过**：用 `tags=cmfa,mishka,with_gvisor` 把 `mishka_core` 编译成 c-shared 成功（沙箱内 linux/amd64，
  产物 80.5 MB）；`GOOS=android GOARCH=arm64` 下 `config` / `listener/sing_tun` 两个包类型检查通过，
  并确认 `mishka` 标签确实切换到了 `server_android.go`、`config/patch_mishka.go` 只在带 `mishka` 标签时编译。
* 锚点算法与 mihomo_box 参考实现**三方可对拍一致**（13/13 样本），并在真实 YAML 解析器上跑过性质测试
  （改名级联、摘定义、改绑、块替换、新建块、值/键规范化；覆盖 25 个定义块、24 次改名、14 条引用改绑…）。
* 「为什么必须用 go.work」有反证记录：关掉工作区后 `go build` 直接要求 `go mod tidy`（即要改仓库文件）。

## 已知限制与风险（**请先读这一节**）

* **锚点面板不做「多层展开值编辑器」**：嵌套多行的值、缩进后的 flow 根、形态特殊的块，只提供「文本」模式改原文。
  这是刻意的边界——拆不动就不猜，宁可让你看原文。
* **面板有意不拦的两件事由保存路径兜底**：删掉定义后引用会悬空、把被 `<<:` 继承的块改成非映射，
  这些在改动后会被面板即时点名提示，但最终安全性依赖 Mishka 原有的保存校验（内核拒绝非法配置 → 保存被回滚）。
  「删除定义」按钮在还有引用时是禁用的；「改绑引用」的候选列表只列**定义在这一行之前**的锚点，且 `<<:` 只允许指向映射。
* **面板文案是硬编码中文**（参考实现 mihomo_box 也是中文），没走多语言资源；要本地化需要把这些字符串搬进
  `app/src/main/res/values*/strings.xml`。
* **换内核对 Mishka 是「跨了 747 个提交」的大版本**：Alpha 比 Mishka 内核基点新 747 个提交（多了 Smart 组、
  eBPF、CNS 协议、mipstack 等）。补丁保证的是**能编译、Mishka 依赖的行为都还在**；运行时行为差异（例如
  Alpha 默认 TUN 栈是 mips）需要你实机验证。要退回官方内核：`revert-patches.sh --all`。
* **签名**：与官方 Mishka 的签名不同，无法覆盖安装官方版（先卸载或用不同 `applicationId`）；
  CI 在没配 secrets 时用的 debug 风格密钥是公开的，只适合自用。
* 本包**尚未完成 Android Gradle/APK 构建验证**：当前沙箱没有 Android SDK；用户提供的 CI 曾在 `ConfigFormPanel.kt` 的 `IntRange?` 空值检查处编译失败，已修复并更新 app 补丁，等待重跑 CI 确认。Android 端最终结果请以新的 CI 或本地 `build-release.sh` 为准；离线引擎与表单逻辑测试此前已通过。

## 来源与许可

* Mishka：<https://github.com/YuKongA/Mishka>（app 侧补丁基于 `e855709c476c8f82635b3bb6f751975e1319f391`）
* 内核：<https://github.com/jieluojun/mihomo>（`Alpha`），移植内容来自 <https://github.com/YuKongA/mihomo> 的 `Mishka` 分支
* 锚点算法参考：`mihomo_box` 模块的 WebUI（`webroot/ui/js/core.js` / `app.js`）
* mihomo 及其分支均以 GPLv3 发布；本交付只包含补丁与脚本，不重新分发内核二进制。请遵守上游许可。
