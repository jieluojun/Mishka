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

## 6. 修「返回重进面板页闪烁、卡片缺失」

**症状**：每次返回退出外部面板、再重进，页面整块白闪一次，代理卡片要重新冒，偶尔像缺卡。

**根因**：返回会把 `AndroidView` 连同 WebView 一起从组合里拆掉，重进再 `WebView(ctx)` + `loadUrl`
冷启动一次——白闪就是重载的空白帧，「卡片缺失」是面板 SPA 重新初始化、重新拉数据的
中间态。另外 `refreshing` / `webError` 用 `rememberSaveable` 跨离开保存，重进时可能恢复出
一个转不完的圈（加载结束事件在离开期间已经发给死组合了）和滞留的错误横幅。

**改动**（`PanelWebView.kt` / `PanelScreen.kt` / `PanelChrome.kt`）：

1. **WebView 实例跨「返回 / 重进」保留**（新增 `PanelWebViewCache`）：退出面板页只拆视图树，
   重进把**同一个实例**挂回去——不重载、不白闪、面板 SPA 的路由 / 卡片 / 滚动位置原样在。
   只有两个销毁点：切面板（`sessionKey` 变，防串 history / 登录态）与 Activity 销毁
   （`ActivityLifecycleCallbacks` 盯 `recreate()` / finish，外加 acquire 时 context 判活兜底）。
2. **复用实例绝不再 `loadUrl`**：`factory` 只对新建实例加载；URL 变化 / reload / goBack 仍由
   `update` 里的 tag 差值判定兜着。
3. **每次组合重新接线**：WebViewClient / WebChromeClient / JS bridge / 下载监听的闭包都指向
   「当前组合」的回调与 `rememberLauncherForActivityResult`。复用实例若只在 factory 挂一次，
   重进后标题、错误横幅、转圈、SAF 存盘全会失联（收到的是上一次进页面那批死 lambda）。
4. **入场对齐**：重进后从 WebView 现值读回标题 / `canGoBack`（离开期间页面可能已变），
   快照过期不再显示错。`refreshing` / `webError` 改成不跨离开保存的 `remember`。
5. **页面暂挂**：离开面板页 `WebView.onPause()`（只停 DOM 定时器 / 动画，加载与 WebSocket
   不受影响），重进 `onResume()`——SPA 留在后台不该一直烧电。

## 7. 顶栏颜色改用主题色；重进固定回面板首页

用户反馈两点：① web 界面顶栏颜色没有使用主题颜色（固定 `#F7F7F7`，深色主题下白得刺眼、
和 App 其它页面脱节）；② 要求重进总是回面板首页，而不是停在上次浏览到的子页。

**改动**：

- `PanelTopBar` 颜色全部改从 `MiuixTheme.colorScheme` 取：底色 `surface`、标题与图标
  `onSurface`——和 `AdaptiveTopAppBar` 同一套 token，深浅色主题自动跟随（box.app 的
  单行布局与像素间距保留）。删掉 `#F7F7F7` / 纯黑常量。
- 状态栏不再强制深色图标：删掉 `PanelChrome.forceLightStatusBars` 整套机制（PanelScreen
  的挂钩 / `MainActivity.enforceSystemBarsAppearance` 里的 `||` / 整个 `PanelChrome`
  object），外观交给 MainActivity 按主题 enforce——顶栏已随主题，状态栏图标天然配套。
- **重进固定回面板首页**：新增 `WebView.goHome(homeUrl)`——优先 `history.go()` 一步退回
  历史里的入口项（SPA 走 popstate 把路由切回首页，**不整页重载、不白闪**）；历史里找不到
  入口项（整页刷新截断过历史、入口被服务器重定向到别处等）才退化为整页加载入口，
  保证「回首页」永远成立。复用实例在 factory 挂回视图树时调用。
- 顺手修一个遮蔽 bug：`factory` 里 `webView.apply { loadUrl(url) }` 的 `url` 会被
  WebView 自己的 `getUrl()` 属性遮蔽，整段加载逻辑实际一直空转（页面能载全靠 `update`
  差值兜底）。入口 URL 改为先取 `entryUrl` 再用，「只有新建实例才整页 loadUrl」从此名实相符。

## 8. 返回进入逻辑对齐 box.app（新实例 + 入口加载，预热器秒建）

用户要求照 https://github.com/MiChongs/box.app 的 web 界面返回进入逻辑实现。box.app 的机制
（`AppScaffold.openSubpage/exitSubpage` + `ThemedWebView`，已逐行核对）：

- **进入**：`openSubpage` 清零 `backRequestKey` / `canGoBack` → **新 WebView + `loadUrl(入口 URL)`**
  —— 每次进都是面板首页；
- **返回**：系统返回先退页内历史（`backRequestKey += 1` → `goBack`），退无可退才退出页面；
  顶栏返回键直接退出；
- **退出**：WebView 实例**不保留**；重建靠 `WebViewPreloader`（两阶段预热：后台线程触发
  Chromium provider 加载 + 主线程空闲预建实例）把 `new WebView()` 的 ~500ms 冷启压到近 0；
- **配套**：`resetHistoryOnUrlChange` —— 换 URL 加载完 `clearHistory()`，返回不会走进
  上一个面板的历史。

**本版改动**（§6 的「实例保留 + goHome 历史回退」方案按要求整体换成 box.app 方案）：

- 删 `PanelWebViewCache` 与 `WebView.goHome`；新增 `WebViewPreloader`（box.app 同款），
  `MainActivity.onCreate` 调 `preload`；
- factory 每次进入 `WebViewPreloader.take() ?: WebView(ctx)` + `loadUrl(入口 URL)`——
  重进总是面板首页，天然成立；
- `onRelease` 销毁实例（box.app 是丢给 GC，这里顺手 destroy 防泄漏，对外行为一致）；
- 换 URL（切面板）加载完 `clearHistory()` + 回报 `canGoBack = false`
  （`resetHistoryOnUrlChange` 等价）；
- `canGoBack` 改为不跨进入保存（等价 box.app `openSubpage` 清零）；返回分发保持与 box.app
  相同（系统返回页内历史优先、顶栏返回直接退出）；
- 删 `PanelChrome.kt`（`findActivityOrNull` 随缓存移除失去用途），补丁文件数 72 → 71；
- **行为说明**：重进会重新加载面板入口（box.app 原生行为，§6 的「重进保留页面状态」就此
  撤销），加载期显示 WebView 底色。

## 9. 修「重进仍闪烁、卡片缺失」：重进不整页重载（box.app 语义 + 实例保留实现）

用户问为什么返回重进还是闪烁和卡片缺失。**根因就是 §8 的方案本身**：box.app 的返回进入
实现是「每次进入 = 新 WebView + `loadUrl(入口)`」，即**重进整页重新加载**——闪烁=重载空白帧，
卡片缺失=面板 SPA 重新初始化拉数据的中间态。box.app 重进面板同样如此，它并没有解决这两个
症状（`WebViewPreloader` 只压掉 WebView 构造耗时，压不掉页面加载）。另外 §7 的 goHome 用
**精确 URL 匹配**找历史入口项，面板服务器一重定向（`…/ui` → `…/ui/`、补 `#/`）就匹配不到、
退化成整页重载——这是当时仍见闪烁的另一半原因。

**方案**：box.app 的返回进入**语义**保留（进入=面板首页、系统返回先退页内历史、退无可退退出
页面），但实现改为**实例保留 + 重进不重载**——这是同时满足「重进=首页」和「不闪、卡片不丢」
的唯一解：

- 恢复 `PanelWebViewCache`：WebView 跨「返回 / 重进」保留复用，重进**绝不**整页 load；
  销毁点仍只有切面板（sessionKey）与 Activity 销毁（ActivityLifecycleCallbacks 盯 recreate）。
- `goHome()` 改为**按历史深度回退** `history.go(-currentIndex)`：一步退回会话第一个入口项，
  SPA 走 popstate 切回首页——不重新下载、不白闪、卡片在内存里原样在。不再做 URL 匹配
  （重定向使入口项实际 URL 与配置串对不上是 §7 失败的原因）；跳过开头可能残留的
  `about:blank` 项。
- `doUpdateVisitedHistory` 补上 canGoBack 转发（公开方法名就是 `do*`，不是 `on*`）：
  SPA 的 pushState / popstate 不触发 `onPageFinished`，没有它「系统返回先退页内历史」
  在面板子页里永远不生效（box.app 漏了这个回调，其返回分发的意图是历史优先）。
- 保留 §8 引入的配套：`WebViewPreloader`（新实例秒建）、换 URL 加载完 `clearHistory`、
  `canGoBack` 不跨进入保存、顶栏返回直接退出 / 系统返回历史优先。
- `onRelease` 回到「暂挂」语义（`onPause()` 停 DOM 定时器），不再销毁。

## 10. 修「杀后台重进：第一次进正常、返回再进闪烁」

用户报告：杀后台重进 App，**第一次**进 web 界面正常，返回后再进就闪烁复现。

**根因**：`WebViewPreloader` 按 box.app 用 `applicationContext` 预建 WebView，而
`PanelWebViewCache.acquire` 的复用判定拿 `webView.context.findActivityOrNull()` 反查宿主
Activity——app context 反查**永远是 null**，被判成「宿主已死」→ 销毁好实例 →
`WebViewPreloader.take()` 此时已是一次性取空 → 冷建 + `loadUrl(入口)` = 整页重载闪烁。
第一次进恰好是纯加载（用户预期之内）才显得正常；每次杀后台测试都固定复现「一进正常、
再进闪烁」。

**改动**（`PanelWebView.kt`）：

- 复用判活改为 **acquire 时记下的宿主 Activity 身份**（`cachedHost: WeakReference<Activity>`，
  `cachedHost?.get() === host`），watcher 的销毁匹配同步改身份比对；不再拿
  `webView.context` 反查（预热器的 app context 实例从此可正常复用）。
- 连带修 pop 式导航重进的差值误触发：重进时 `reloadKey`/`backRequestKey` 是新零值，
  上一次会话留在 tag 里的旧值会误触发 `reload()`/`goBack()`——复用分支现在把 tag 对齐
  当前键值。
- 重进没有任何加载事件，标题会卡在占位符：复用分支 `post` 从实例现值同步一次标题。

## 11. web 面板整体重做：整套照搬 box.app `ThemedWebView`（防白闪 = hideUntilCommitVisible）

用户反馈修完仍闪烁，要求「完全去除 web 面板，参考 box.app 重做」。§7-§10 在「实例保留 /
goHome / 判活修复」里兜圈，始终没搬 box.app 真正的防闪机制——**`hideUntilCommitVisible`**：
内容提交（`onPageCommitVisible`）前 WebView 保持隐藏、只露底色，加载期永远不会露出白帧。
本轮把 `PanelWebView.kt` 整文件推倒，按 box.app `ThemedWebView` 逐块重写：

- **进入**：每次进入都是新 WebView（`WebViewPreloader.take() ?: WebView(ctx)` 秒建）+
  `loadUrl(入口 URL)`——重进总是面板首页（box.app 进入逻辑）；
- **防白闪**：`hideUntilCommitVisible = true`（组件默认；box.app 自带的三个 web 页传 false，
  但这个机制就是为去白闪而生，我们启用）——内容提交前隐藏 WebView 只露底色，提交才露真身；
- **返回**：系统返回先退页内历史（`backRequestKey` → `goBack`），退无可退退出页面；
  `doUpdateVisitedHistory` 转发 canGoBack（box.app 漏了这个回调）；
- **换面板 / 换深浅色**：`key(isDark, sessionKey)` 重建实例（box.app 同款）+
  `resetHistoryOnUrlChange` 加载完 `clearHistory()`；
- **退出**：`onRelease` 销毁实例（box.app 丢 GC，这里 destroy 防泄漏，对外行为一致）；
- 外层容器手势防抢（`requestDisallowInterceptTouchEvent`）、不透明底色等 box.app 细节一并照搬；
- **删除 §6-§10 的全部保留型机制**：`PanelWebViewCache` / `goHome` / `cachedHost` 判活等。

与 box.app 的三处差异（都为修 bug，行为只强不弱）：`doUpdateVisitedHistory` 转发；
http(s) 导航交回 WebView 保留 POST（box.app 一律 `loadUrl` 重放会丢 POST）；
`onRelease` 里 destroy。

另：本轮开工时工作区快照曾损坏（Mishka 的 .git/源码、补丁目录、kotlin-compiler 与若干
依赖 jar 丢失），已从成品 zip、上游基线 `5e6743592b9c` 与 Maven / Google Maven 全量恢复，
verify 复核通过后再动的代码。

## 验证

- `mishka-custom/tools/verify_app_patch.sh --repo <仓库>` → **PASS**：补丁双向可逆，
  应用结果与 `BASELINE.txt` 的 71 个 blob 逐文件一致（面板相关断言共 15 条：布局 2 +
  主题顶栏 / 状态栏 4（含 2 条反向「不再强制状态栏」）+ box.app web 生命周期 / 防白闪 9）。
- **Kotlin 类型检查通过**：kotlinc 2.4.20 + 真实依赖（miuix 0.9.4 / JetBrains Compose
  1.12.0 族（miuix 0.9.4 的实际传递版本）/ androidx.activity 1.13.0 / lifecycle 2.11.0 /
  navigationevent 1.1.2 / Robolectric `android-all` 当 android.jar），对 `custom/panel/`
  五个文件（PanelChrome.kt 已删）做完整前端类型检查，**0 错误**（本轮改的 PanelWebView /
  PanelScreen 都在内；MainActivity 的一行 preload 调用不在检查范围，改动极小）。
  项目侧引用（`ProxyServiceBridge` / `StatusColors` / `sheetContentSafePadding` /
  `R`）用的是照真实声明写的桩。
  历史轮次的类型检查各抓出过必炸的编译错误（`LocalView` 当 `Context` 用等），本轮 0。
  Compose 编译器插件没装，后端 IR lowering 依旧会崩——缺插件的已知表现，不是代码问题。
- `BASELINE.txt` 的 `patch_sha256` 随新补丁更新（完整值见该文件）。
- 沙箱内存只有 2GB，`:app:compileDebugKotlin` 跑不动，**本次没有跑通 Gradle 编译验证**。
  落地后请先跑一次：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```
