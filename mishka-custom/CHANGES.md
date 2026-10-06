# 本次改动（2026-10-06）

基线：`upstream_commit=5e6743592b9c465eb015db7b05c588c50cd2b874`（未变）
补丁：`mishka-custom/patches/app/0001-anchor-panel.patch`（已重新导出）

## 1. 隐藏不可用：排除 mihomo 内置策略

`代理组` 页的「隐藏不可用」按 `delay == -1`（healthcheck 超时）过滤节点。mihomo 的内置
出站 `DIRECT` / `REJECT` / `REJECT-DROP` / `PASS` / `COMPATIBLE` / `GLOBAL` **从不参与拨测**，
`history` 里的延迟恒为 0，于是被一并判定成「超时」——一开开关它们就整组消失，偏偏这几个是
任何配置都得留着的兜底出口。

改动：`ProxyViewModel.kt` 新增 `isBuiltInOutbound(name, type)`，`ProxyScreen.kt` 的过滤条件
改为「内置出站 **或** delay != -1」，内置出站一律豁免，只滤真正测不通的节点。

**判定只按名字走**，类型仅作兜底：`/proxies` 的 `type` 在内置出站上经常拿不到（`GLOBAL` 的
类型是 `Selector`，与用户自建的代理组无从区分；provider 视角下也可能为空）。之前那版如果挂在
类型上，就会表现为「改了没生效」——名字是唯一在每条数据路径上都存在的信号。

## 2. 锚点面板：编辑 / 删行 之间补间距

miuix 的 `BasicComponent` 把 `endActions` 直接塞进一个**没有 arrangement 的 Row**，两个实心
按钮各带 12dp 内边距，贴在一起时看着像一整块胶囊、也容易误触。

改动：`AnchorPanel.kt` 在「编辑」与「删行」之间插入 `Spacer(Modifier.width(RefActionGap))`，
取 8dp，与卡片底部「定位 / 改名 / 删除」那一行的 `spacedBy(8.dp)` 对齐。锚点卡片与悬空引用
卡片两处同步。

## 3. 新增：外部面板（Web 界面），移植自 box.app

主页「工具」区多一个**面板**入口，点进去是一个承载 mihomo 面板的 WebView。按你回的
「与 box.app 一致」，清单与取值都照 box.app 来：

| 内置面板 | 地址 |
| --- | --- |
| 本地 | `http://<external-controller>/ui`（端口取运行时 `ProxyServiceBridge`，只在代理 Running 时才算得出；停了退回上次缓存的地址） |
| Zashboard | `http://board.zash.run.place` |
| MetaCubeXD | `https://metacubex.github.io/metacubexd` |

自定义面板可增删，存在 SharedPreferences `panel_cache`（与 box.app 同名，不上 Room——
就几行展示偏好，丢了大不了重填）。清单从顶栏右二的图标打开。

**不代填控制器地址与密钥**：box.app 也是让面板自己在首次进入时问一次并存 localStorage，
所以这里不拼 `?hostname=&secret=`。各家面板对这两个 query 的支持并不一致，代填错了
比不填更难排查。

新增文件（均在 `app/src/main/kotlin/top/yukonga/mishka/custom/panel/`）：

- `PanelEntry.kt` / `PanelStore.kt`：条目模型 + 持久化
- `PanelWebView.kt`：WebView 封装。三个不能省的点——
  1. `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`：面板多为 https 站点，控制器在
     `http://127.0.0.1:<port>`，默认值会把面板调 API 的请求全掐了，表现为「页面能开、
     数据全空」且只在 logcat 里留一行。
  2. 切面板时用 `key(sessionKey)` 整个重建 WebView，只 `loadUrl` 会带上一家的登录态。
  3. 面板的「导出配置」是 `URL.createObjectURL` + 点隐藏 `<a download>`，WebView 的
     DownloadListener **收不到 blob:/data:** 地址，所以注入了一段 JS 改成
     fetch → base64 → JS bridge → SAF 存盘；真正的 http 下载仍走 DownloadListener。
- `PanelSheet.kt` / `PanelScreen.kt`：面板清单底栏 + 页面壳（刷新 / 清缓存 / 返回）

系统返回键在网页还能后退时先给网页（`NavigationBackHandler`，`isBackEnabled = canGoBack`），
退到底才退出页面；这一屏因此关掉了横滑返回（`NavSwipeDirection.None`）——边缘侧滑走的是
同一条 NavigationEvent 分发，会被子级 BackHandler 截走变成「网页后退」，看着像卡住。

顶栏的「清除面板数据」= WebView 缓存 / Cookie / Storage / 表单 / SSL 偏好全清，面板里
保存的设置与登录态会一起没，弹窗确认后再执行。

## 4. 修 CI 首轮报错（上一版打包后的构建日志）

日志：`app/src/main/res/values/strings.xml:142` 合并资源时挂掉，
`panel_clear_cache_message` 里那个 `panel's` 的**裸撇号**没转义，AAPT2 报
「Invalid unicode escape sequence」。已改成 `panel\'s`（与仓库里既有的
`external_control_controller_hint` 同一写法）。四个语言共 17 条面板文案重新扫过一遍，
没有别的裸 `'` / `&` / `<`。

顺带把这一版真正跑了一次类型检查（见下），又抓出两个必炸的编译错误，都已修：

- `PanelWebView.kt` 少 `import androidx.compose.runtime.getValue` —— `var x by remember { mutableStateOf(...) }`
  读取走的是 `State.getValue` 扩展，只导 `setValue` 不够，委托直接解析失败。
- `PanelSheet.kt` 清理未用 import 时把 `Column` 一起删了，而外层容器还在用。

## 5. 外部面板顶栏对齐 box.app（浅色单行）

此前面板页顶栏走 miuix `AdaptiveTopAppBar`：手机上是**深色大标题两行**布局（标题 32sp
独占一行），返回用 `Back` 箭头、清单入口是 `GridView` 田字格，配色随 App 主题——App
深色主题下面板内容是浅色网页，顶栏却是一整块黑色，和 box.app 的浅色顶栏放一起违和。

按 box.app 截图实测像素复刻（1440px 宽、density 3.5，图标槽位两图本来就重合）：

| 项 | box.app 实测 | 现在 |
| --- | --- | --- |
| 底色 | `#F7F7F7`，铺到状态栏后面 | 同 |
| 前景 | 纯黑 `#000000` | 同 |
| 布局 | 单行：`‹` 返回 / 标题 / 刷新 · 滑杆 · 清除 | 同 |
| 返回图标 | 细尖角 chevron | `MiuixIcons.ChevronBackward` |
| 清单图标（右二） | 双横线滑杆（上线钮偏右、下线钮偏左、空心圆钮） | `MiuixIcons.Tune`（路径逐段核过，与截图同形） |
| 标题 | 18sp 常规字重、左对齐（距返回槽 12dp）、单行省略 | 同 |
| 图标槽 | 40dp、外沿 16dp | 同（miuix `IconButton` 默认值，实测即 40dp 节距） |

改动落在 `PanelScreen.kt`：弃用 `AdaptiveTopAppBar` 与 `MiuixScrollBehavior`，新增私有
`PanelTopBar`（`#F7F7F7` 底 + 56dp 标题行，WindowInsets 处理与 miuix 顶栏同款）。
标题仍取网页 `document.title`（如「127.0.0.1:9090 | 代理」），取值逻辑不变。
**顶栏不随 App 深浅色主题走，恒为浅色**——这是与 box.app 浅色顶栏「一致」的直接要求。

**状态栏图标跟着顶栏走**：顶栏铺到状态栏后面，App 深色主题下白色图标会直接消失在
`#F7F7F7` 上。新增 `custom/panel/PanelChrome.kt` 持有 `forceLightStatusBars` 开关，
`MainActivity.enforceSystemBarsAppearance` 尊重它。为什么不能只在进页面时改一次：
MainActivity 在 `onWindowFocusChanged` / `onResume` / 配置变化 / 主题 recomposition 时
都会重排系统栏外观，面板页自己的「清除数据」确认框一关（弹窗收回焦点）就会把图标
翻回白色。进面板置 true，退面板复位并按当前主题恢复。

## 验证

- `mishka-custom/tools/verify_app_patch.sh --repo <仓库>` → **PASS**：补丁双向可逆，
  应用结果与 `BASELINE.txt` 的 72 个 blob 逐文件一致（较上版 +1：`PanelChrome.kt`；
  顶栏相关断言新增 5 条）。
- **Kotlin 类型检查通过**：kotlinc 2.4.20 + 真实依赖（miuix 0.9.4 / JetBrains Compose
  1.12.0 族（miuix 0.9.4 的实际传递版本）/ androidx.activity 1.13.0 / lifecycle 2.11.0 /
  navigationevent 1.1.2 / Robolectric `android-all` 当 android.jar），对 `custom/panel/`
  六个文件（含新的 `PanelChrome.kt`）做完整前端类型检查，**0 错误**。
  项目侧引用（`ProxyServiceBridge` / `StatusColors` / `sheetContentSafePadding` / `R`）
  用的是照真实声明写的桩。
  **本轮类型检查真抓出一个必炸的编译错误**：状态栏外观覆盖最初写成
  `panelView.findActivityOrNull()`——`LocalView.current` 是 `View` 不是 `Context`，
  扩展解析不了，已改为 `panelView.context.findActivityOrNull()`。
  Compose 编译器插件没装，后端 IR lowering 依旧会崩——缺插件的已知表现，不是代码问题。
- `BASELINE.txt` 的 `patch_sha256` 已随新补丁更新为
  `46705cf03ee6276f...`（完整值见该文件）。
- 沙箱内存只有 2GB，`:app:compileDebugKotlin` 跑不动，**本次没有跑通 Gradle 编译验证**。
  落地后请先跑一次：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```
