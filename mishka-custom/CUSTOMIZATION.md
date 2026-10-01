# 定制详解

三块定制的实现方式、边界与取舍。

---

## 一、锚点可视化面板

### 解决什么问题

订阅配置里的 `&定义` / `*引用` 只存在于**原文**，YAML 一展开就看不见了；而 mihomo 对悬空别名
（`*x` 找不到 `&x`）是直接拒绝加载的。面板把这些关系摊开在手机上，并给出行级手术工具。

### 功能对照（与 `mihomo_box` 的「工具页 → 锚点面板」等价）

| 能力 | 参考实现 | 本面板 |
| --- | --- | --- |
| 锚点总览：`&名` / 定义位置 / 引用清单 / 悬空告警 | ✅ | ✅ 卡片式，`Badge` 显示「继承 N · 引用 M」 |
| 定位跳转（点行号跳到编辑器对应行） | ✅ | ✅ 关面板 + `jumpToLine` |
| 定义块可视化编辑（键值对增删改 + 写回预览） | ✅ | ✅ 映射/序列逐行编辑 + 实时校验 + 预览；标量值单框；拆不动的形态转「文本」 |
| 引用行改绑 / 清除继承（`<<:` 的「不继承」）/ 删行 | ✅ | ✅ 候选表只列合法目标 |
| 新建顶层定义块（插到文件头） | ✅ | ✅ 顶层键 + 锚点名 + 参数行，>160 字符自动退回块式 |
| 重命名（锚点名级联 + 顶层键） | ✅ | ✅ 级联只动含 token 的裸文本行 |
| 删除定义（mihomo 配置段只摘 `&名`） | ✅ | ✅ 自建容器整块删、`MIHOMO_TOP_KEYS` 段只摘 token、多处定义时拒绝 |
| 多层「展开值编辑器」 | ✅ | ❌ **不做**（已确认的取舍）：嵌套多行的值改用「文本」模式直接编辑原文 |

### 文件与入口

新增 8 个文件（`patches/app/0001-anchor-panel.patch`，共 9 files / 5202 insertions）：

| 文件 | 职责 |
| --- | --- |
| `custom/anchor/AnchorScan.kt` | 锚点图扫描（裸文本判定、路径回溯、块边界、悬空检测），是 `mihomo_box core.js` 的等价移植 |
| `custom/anchor/AnchorEdit.kt` | 行级手术：改名级联、定义行键改名、删行、改绑、摘/删定义块；`YamlValue` 值规范化 |
| `custom/anchor/AnchorBlock.kt` | 定义块模型：块的形态判定（Map/Seq/Scalar/Unknown）、项解析、按行渲染、新建块 |
| `custom/anchor/AnchorDialogs.kt` | 5 个子对话框（定义编辑 / 引用编辑 / 新建 / 改名 / 删除确认） |
| `custom/anchor/AnchorPanel.kt` | 面板本体：总览卡片、悬空卡片、子对话框编排、**唯一落地通道** `apply()` |
| `custom/forms/YamlEngine.kt` | 配置表单的保真写回引擎（行树解析 + 三层写回 + 值渲染），与 `tools/forms/forms_model.py` 逐字节对拍 |
| `custom/forms/FormSpecs.kt` | 152 个字段的声明表（由 `tools/forms/gen_specs.py` 从 `fields.json` 生成，勿手改） |
| `custom/forms/ConfigFormPanel.kt` | 配置表单面板：hub 13 格 + 6 个分区页 + 开关/下拉/文本/数字/多行/列表编辑 |

入口只改一处（39 行，全部集中在 `ui/screen/settings/FileManagerEditorScreen.kt`）：
顶栏 actions 里加两个图标按钮，仅 YAML 文件显示 —— `MiuixIcons.Link`（锚点面板）、`MiuixIcons.Tune`（配置表单）——
外加两个 `remember { mutableStateOf(false) }` 状态，文件末尾挂 `MishkaAnchorPanel(...)` 与 `MishkaConfigFormPanel(...)`。
补丁反向应用即可完整还原。

### 三条安全边界（这也是它敢写盘的依据）

1. **只动锚点语法**：`&` / `*` 只在「裸文本」区间（不在引号内、不在 `#` 注释后）才算锚点；
   手术只重写目标行/目标块的字符范围，其余字节一个都不动（含 CRLF、行尾空格、注释）。
2. **单一写入点**：所有子对话框都只回传「用户意图文本」，落地统一走面板的 `apply()` →
   `controller.replaceRange(...)` 一次整篇替换（一个撤销单元）→ 同回调里跳转 + toast。
   写盘仍走 Mishka 原有的保存路径（`saveWithValidation()`，YAML 走内核 `fetchAndValid`），
   所以面板写坏配置的唯一后果是「保存被拒绝」，文件不会被改坏。
3. **改动后立刻重扫**：出现悬空引用、或 `<<:` 指向了不再是映射的锚点，都会在 toast 里点名。

### 两条由 YAML 语义决定的硬规则

性质测试（`tools/equiv/edit_props.py`，用真 PyYAML 解析）抓出来的，面板已按此实现：

* **改绑目标必须定义在引用行之前** —— YAML 没有前向别名，`*x` 写在 `&x` 前面会直接解析失败。
  面板的候选列表只列 `defs.any { it.line < ref.line }` 的锚点（`AnchorPanel.refCandidates`）。
* **`<<:` 合并继承只能指向映射** —— 指到标量/序列上，解析器会判非法合并。候选列表对 merge 行额外过滤：
  只排除「能确定不是映射」的形态（序列、正文非 flow 映射的单值标量），
  `d1: &d1 {a: 1, b: 2}` 这种行内映射仍然可选（`AnchorPanel.isMergeableAnchor`）。

### 已知边界（刻意的）

* 嵌套多行的值、缩进后的 flow 根（`k: &u` + 缩进一行 `{a: 1}`）、混合形态块 → 一律判 `Unknown`，
  面板只给「文本」模式。拆不动就不猜。
* 面板文案硬编码中文（同参考实现），未走 `strings.xml` 多语言。
* 「删除定义」在有引用时按钮禁用；如需强删，先改绑/删掉引用行。

### 相关自检工具

| 工具 | 作用 |
| --- | --- |
| `tools/check_kotlin.py <目录>` | tree-sitter 语法门（本仓 5 个文件全过） |
| `tools/check_api.py [--root]` | 逐个 `import` 核对符号存在性 + 具名实参在对应重载里是否存在（33 条 import / 58 个调用点） |
| `tools/equiv/compare.py` | 锚点扫描三方可对拍：参考实现(JS) == Kotlin 转写(Python) == 手写期望，13 个样本 |
| `tools/equiv/edit_props.py` | 编辑层性质测试（PYAML 真解析）：块渲染逐字还原、改名往返/数据不变、摘定义数据等价、改绑类型安全、新建块可解析… |
| `tools/equiv/model_freshness.py` | 盯「Kotlin 源码 ↔ Python 转写模型」哈希，防止测试模型悄悄过期 |
| `tools/export_app_patch.sh` | 改完 Kotlin 后重新导出补丁 + 刷新基线 |

---

## 二、内核换成 `jieluojun/mihomo`（`Alpha` 分支）

### 为什么不能直接换

Mishka 的内核（YuKongA/mihomo `Mishka` 分支）与 jieluojun/mihomo `Alpha` 的关系是：
**同一祖先，Alpha 前沿 747 个提交，Mishka 分支只多 6 个提交**。其中 5 个主题是 Mishka 应用要用到的，
Alpha 里没有；另外 `mishka_core/go.sum` 里也没有 Alpha 新增依赖的哈希。所以「换内核」= 移植补丁 + 解决依赖哈希。

### 移植的 4 个补丁（`patches/mihomo/`）

| 补丁 | 内容要点 | 上游来源 |
| --- | --- | --- |
| `0001-config-override-json` | `config.OverrideJSONPath` + `Parse()` 中合并 JSON（失败仅告警）。`mishka_core/runtime.go` 的 `--override-json` 依赖它。 | `ceafd04` |
| `0002-sing-tun-mishka-build-tag` | `server_android.go` 的构建约束改成 `android && (!cmfa \|\| mishka)`、`server_notandroid.go` 改成 `!android \|\| (cmfa && !mishka)`；`mishka` 下无条件启用 uid→包名解析；fd 模式跳过重复建路由/无过滤时早返回。 | `2b7de8f` |
| `0003-config-mishka-tun-dns-patch` | `mishkaPatch` 钩子 + `config/patch_mishka.go`（`//go:build mishka`）：DNS 关闭时注入 fake-ip 默认值（国内外 nameserver + `28.0.0.0/8` + STUN/主机/门禁过滤表）；VPN 模式追加 `system://` 兜底；VPN 模式按白名单重建 `tun` 段，丢掉 Linux-only 字段。 | `addd66b` + `4c724df` |
| `0004-sing-tun-forwarder-bind-interface` | fd 模式恢复 `forwarderBindInterface = true`（上游删掉后 Android VPN 下延迟测试通、真实流量不通）。 | `523fc3e` |

未移植的两项（有意）：

* `ab405ba`（`stack: mips`）：**Alpha 已具备**（`constant/tun.go` 的 `TunMips` + 映射；`mishka_core` 里
  `Const.TunMips` 就是它们的用法）。编译实测通过。
* `4c724df` 里的 `--prefetch` CLI 与内核 `main.go` 改动：Android 构建不编译内核的 `main` 包
  （入口是 `mishka_core` 的 `mihomoEntry()`），`mishka_core/fetch.go` 自带进程内 prefetch。故省略。

### 依赖哈希：`go.work` + `go.work.sum`

不解这个问题，构建会在「要么改仓库里的 `go.mod`/`go.sum`，要么失败」之间二选一。
工作区模式让编译器改看 `go.work.sum`，仓库文件一个字节不动 —— 机制、生成命令与反证见 `kernel/README.md`。

### 风险与回退

* Alpha 比 Mishka 内核基点新 747 个提交（Smart 组、eBPF、CNS、mipstack…），补丁保证的是**编译通过 +
  Mishka 依赖的内核行为齐全**；运行时差异请实机验证。
* 退回官方内核：`scripts/revert-patches.sh --all`（子模块回到上游、删掉 go.work）。

---

## 三、只出 release 的构建集成

1. **本地脚本**：`scripts/build-release.sh` 只跑 `:app:downloadGeoFiles` + `:app:assembleRelease`；
   没有签名配置时自动调 `gen-keystore.sh` 生成（否则 release APK 装不上）。
2. **Gradle init 脚本**：`init/no-debug.init.gradle` 在命令行点名 debug 任务时直接抛错
   （只看命令行请求的任务，不去翻任务图——release 图里本来就有 `stripReleaseDebugSymbols` 这类名字）。
   确实要 debug 包时加 `-PallowDebugBuild=true`。
3. **CI**：`ci/build-release.yml`（复制成 `.github/workflows/release.yml`）。要点：
   * 只 checkout `scripta` 子模块（`mihomo` 子模块用不到，会被 `setup.sh` 换成 jieluojun 内核）；
   * 跑 `mishka-custom/scripts/setup.sh --ci` 完成内核 + 补丁 + `go.work`；
   * secrets 里有 keystore 就用它，没有就用固定参数的 debug 风格密钥兜底并在 Summary 里提示；
   * 只跑 `:app:assembleRelease`；打 `v*` tag 时把 APK 挂到 Release。
4. **为什么执着于 release**：release 打开 R8/资源剥离（`optimization.enable = true`、
   `packaging.resources.excludes.add("**")`），debug 包体积大得多且不是要发的产物。

---

## 四、构建配置改动的落点（遵守「不动已有文件」）

| 需要的东西 | 落点 | 是否新增文件 |
| --- | --- | --- |
| 自定义源码 | `app/src/main/kotlin/.../custom/anchor/*.kt` | ✅ 新增（补丁） |
| 面板入口 | `FileManagerEditorScreen.kt` +21 行 | ⚠️ 唯一被改的已有文件（可一键还原） |
| 内核替换 | `<仓库>/mihomo`（原子上游子模块目录） | 目录内容替换 + `submodule.mihomo.ignore=all`（可还原） |
| 依赖哈希 | `<仓库>/go.work`、`go.work.sum` | ✅ 新增 |
| 只出 release | `mishka-custom/init/no-debug.init.gradle` | ✅ 新增 |
| CI | `.github/workflows/release.yml`（由 `ci/build-release.yml` 复制） | ✅ 新增 |
| 签名 | `local.properties` + `mishka-custom/keystore/` | ✅ 新增（都在 gitignore / exclude 里） |
| 上游 `.github/workflows/build.yml`、`build.gradle.kts`、`gradle.properties`、`mishka_core/go.mod`、`go.sum` | — | ❌ 一律不动 |
