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

## 4. 面板顶栏改成单行（地址与按钮并排）

上一版顶栏用的是 `AdaptiveTopAppBar`，手机上走的是 miuix 的**可折叠大标题** `TopAppBar`：
大标题自己占一行压在按钮下面，地址只剩两三行的宽度还被折叠动画吃掉。

改成 `PanelTopBar`——照 box.app 的面板顶栏来，一条固定 52dp 的单行：

```
[返回] [地址，weight(1f)，单行截断] [刷新] [面板清单] [清缓存]
```

- 地址文字 **15sp**（原来走的是 `main` 17sp，跟三个 40dp 按钮挤一行时会被截得只剩域名开头）。
- 顶栏自己吃掉状态栏/刘海高度：Scaffold 只在**没有** topBar 时才补 inset，有 topBar 时
  `innerPadding` 直接等于 topBar 实测高度，所以 inset 得在顶栏里加。
- 顶栏文字改成**地址优先**（`panelUrl ?: pageTitle ?: R.string.panel_title`）：页面没给
  `<title>` 时 WebView 会把 URL 当 title 报上来，但各家面板的 title 写得五花八门，
  这一行本来就是给「我现在连的是哪个面板」看的。

## 5. 修 CI 首轮报错（上一版打包后的构建日志）

日志：`app/src/main/res/values/strings.xml:142` 合并资源时挂掉，
`panel_clear_cache_message` 里那个 `panel's` 的**裸撇号**没转义，AAPT2 报
「Invalid unicode escape sequence」。已改成 `panel\'s`（与仓库里既有的
`external_control_controller_hint` 同一写法）。四个语言共 17 条面板文案重新扫过一遍，
没有别的裸 `'` / `&` / `<`。

顺带把这一版真正跑了一次类型检查（见下），又抓出两个必炸的编译错误，都已修：

- `PanelWebView.kt` 少 `import androidx.compose.runtime.getValue` —— `var x by remember { mutableStateOf(...) }`
  读取走的是 `State.getValue` 扩展，只导 `setValue` 不够，委托直接解析失败。
- `PanelSheet.kt` 清理未用 import 时把 `Column` 一起删了，而外层容器还在用。

## 6. 主页测延迟：不再把「国外节点的延迟」当成 Baidu 的延迟

**现象**：主页三个延迟探测（Baidu / Cloudflare / Google）里，Baidu 的延迟明显偏高。

**根因**（读代码确认，不是节点慢）：`HomeViewModel.testLatency()` 先让
`RuleLatencyTester` 走 `mixed-port` 拨测——这是唯一真正经过规则引擎的测法。但
`SubscriptionProxyResolver` 要拿到 `mixed-port`，得
`overrideStore.load().mixedPort ?: MihomoApiClient.getConfig().mixedPort` 大于 0；
而 `ConfigGenerator` 只是**读**订阅里本来就有的 `mixed-port`（`readSubscriptionMixedPort`），
从不注入。典型订阅不带这一项 → 解析成 0 → `resolve()` 返回 null → 测速降级到

```kotlin
// 改动前
val result = repository?.getProxyDelay("GLOBAL", probe.url, ...)
```

`GLOBAL` 组当前选中的几乎总是国外节点，于是 `www.baidu.com` 被绕到国外出口再测一次，
测出来的是「绕地球一圈的 RTT」，不是 Baidu 的真实延迟。

**改动**：不动内核、不动 CLI（按你的要求，没有注入 `mixed-port`、也没有加
`--mixed-port` 启动参数），只改降级策略。

- 新增 `domain/rule/RuleMatchResolver.kt`：纯字符串层面的 mihomo 规则匹配器。
  拿内核 `GET /rules` 返回的规则表，按 `DOMAIN` / `DOMAIN-SUFFIX` / `DOMAIN-KEYWORD` /
  `DOMAIN-REGEX` / `HOST*` / `MATCH` 逐条判定该 URL 会命中哪条规则、走哪个出口。
  `IP-CIDR` 等规则对域名必然不匹配，可以安全跳过。
- `HomeViewModel.fallbackProbe()` 从「拨 GLOBAL」改成「拨规则真正指定的出口」：
  - 规则能在字符串层面判定 → 测那个出口（配置用 `DOMAIN-SUFFIX,baidu.com,DIRECT`
    这类的，Baidu 现在会直接测 `DIRECT`，数字才对得上）；
  - 中途遇到 `GEOSITE` / `GEOIP` / `RULE-SET` / `SUB-RULE` / 脚本等**只有内核能判定**的
    规则 → 判定不出，返回 `RuleLatencyTester.Unavailable`，UI 显示 `—`，
    **不再给一个看似精确、实则误导的数字**。
- `LatencySection.kt` 把原来的「未走规则」拆成两种：只是没走规则引擎显示 `未走规则`；
  三个站点全都判不出路由时显示 `路由不可判定`（新增
  `home_latency_route_undecided` / `home_latency_not_via_rules` 两处文案，中英已对齐）。

> **需要你知道的边界**：按你选的「不动内核」方案，只要订阅的规则靠 `GEOSITE`/`GEOIP`
> 分流（绝大多数机场订阅都是），字符串层面就判不出来，结果会是 `路由不可判定`。
> 想要 Baidu 显示真实的、确实走过规则的延迟，唯一办法还是让内核开 `mixed-port`——
> 你自己的配置编辑器里已经有 `mixed-port` 这一项（`FormSpecs.kt`，在「覆盖」里填也行），
> 填上后主页探测会自动走规则引擎，无需改代码。

## 7. 代理页批量测速：对齐 mihomo_box

参照 `mihomo_box` 的 `webroot/ui/js/page-proxies.js`（`testGroupAll`），把常量与流程整套搬过来：

| 项 | 改前 | 改后（对齐 mihomo_box） |
| --- | --- | --- |
| 并发数 | 5 | **8**（`BATCH_CONCURRENCY`） |
| 批量单节点超时 | 5000ms | **2000ms**（`BATCH_TIMEOUT`；单节点手点测速仍是 5000ms） |
| 组接口优先 | 无 | **有**：Selector 组先打一次 `/group/{name}/delay`，预算 7s（`GROUP_DELAY_BUDGET`） |
| 滑动让路 | 无 | **有**：滑动/触摸后 900ms 内暂停发起新探测，最多让 3s（`waitForBatchIdle`） |
| 结果刷新 | 整组跑完才刷一次 | **每 700ms 刷一次**，边测边出（对齐其 rAF 合并绘制） |

- 新增 `MihomoApiClient.getGroupProxyDelay()`（`GET /group/{name}/delay`），并补到
  `MihomoRepository` / `MihomoRepositoryImpl`。
- **组接口只对 Selector 组用**：mihomo 的 `hub/route/groups.go` 在处理该端点时，对非
  Selector 组会先 `ForceSet("")` 解除固定——那会把用户手动选中的节点冲掉。所以
  URLTest / Fallback / LoadBalance 等组直接走并发池逐节点测，行为与改前一致。
  （mihomo_box 对所有组都打组接口，这里是有意保留的差异。）
- `ProxyViewModel` 新增 `noteUserInteraction()`，`ProxyScreen.kt` 用
  `rememberLazyListState()` + `snapshotFlow { isScrollInProgress }` 在滑动时通知它。

## 8. 外部面板：进入时闪烁 / 反复进出后卡片缺失

两个问题同一次修，都出在 WebView 的生命周期上。

### 8.1 卡片缺失 —— WebView 泄漏

`AndroidView` 只把 view 从视图树上摘下来，**不会** `destroy()` WebView。每进出一次面板就漏
一个带着渲染进程的实例，连着进几次 Chromium 的渲染进程被挤满，新页面就只能渲染出一半——
这就是「卡片缺失」。

改动：`PanelWebView.kt` 在 `key(isDark, sessionKey)` 里加 `DisposableEffect`，离开组合时
`stopLoading()` → 摘掉 client → `removeView` → `destroy()`。引用用数组存（`factory` 在组合
期间跑，写快照状态会多出重组）。

### 8.2 进入时闪烁几下 —— 地址抖动触发整页重灌

`ProxyServiceBridge.status` 在服务起停 / 重启期间会在 `Stopped ↔ Starting ↔ Running` 之间
来回抖，端口也可能先给默认值。而 `PanelWebView` 的 `update` 里 `urlChanged` 就 `loadUrl`，
于是：

1. 首帧 `status` 还不是 Running → `panelUrl` 回落到缓存地址（第一次甚至为 `null`）；
2. 下一帧拿到真实地址 → `urlChanged` → 整页重灌一次；
3. `panelUrl` 变 `null` 的那一下更糟：`PanelWebView` 直接被摘出组合（销毁），地址回来再建
   一个 —— 一次销毁 + 重建 + 重灌，看到的就是「闪一下 / 闪几下」。

改动（`PanelScreen.kt`）：

- 新增 `settledUrl`：**已经有可用地址时**，新地址要先稳定 `PANEL_URL_SETTLE_MS = 400ms`
  才换；首次取值（手上还没有任何地址）不用等，直接上，不增加首次进面板的等待。
- 地址暂时算不出时**沿用上一个**，不再回落到 `null`。WebView 因此不会被拆掉重建。
- 「代理未运行」提示从「和 WebView 二选一」改成**盖在 WebView 上**的覆盖层：视觉不变，
  但 WebView 不再因为提示的出现 / 消失被销毁重建。未运行时也不再叠一条「连不上」的报错横幅。

## 9. 外部面板顶栏：改回 box.app 的样式（显示页面标题）

顶栏文字从「常驻 URL」改成 box.app 的写法：

```kotlin
text = pageTitle?.takeIf { it.isNotBlank() } ?: panelTitleFallback
```

`pageTitle` 是面板页面自己报的 `<title>`，随面板内导航变化——mihomo_box 这类面板会把它设成
「地址 \| 代理」这样的「当前 top 页」，比常驻一条 URL 更能说明「我现在在哪一页」；拿不到
（还没加载完、或压根不设 title）才退回「面板」。字号 15sp → 17sp（对齐 box.app 的
`MiuixTheme.textStyles.title2`；这里不直接引 `textStyles`，Mishka 用 miuix 0.9.4、box.app
是 0.9.0，字段名不敢跨小版本保证，字号写死视觉等价）。

`PanelTopBar` 本身没动：仍是 52dp 单行、自己吃掉状态栏 / 刘海 inset。

## 10. 批量测速的汇总 toast 回来了（组接口快路径漏统计失败数）

上一版把整组测速改成「Selector 组先打 `/group/{name}/delay`」后，失败汇总提示没了。

原因：快路径只判断了接口本身成不成功（`.isSuccess`），**没有去看返回的延迟表**，
`failed` 恒为 0，`DelayTestEvent.GroupSummary` 于是一条都不发。而绝大多数手动切换的组
都是 Selector，正好全走快路径，于是「批量测速完了一声不吭」。

改动（`ProxyViewModel.testGroupDelay`）：把「接口成功」换成「拿到延迟表」，自己数失败数：

```kotlin
val nestedGroups = _uiState.value.groups.mapTo(HashSet()) { it.name }
failed = nodes.count { name -> name !in nestedGroups && (delays[name] ?: 0) <= 0 }
```

两个细节：

- 内核对测不通的节点**不写正数**——要么不出现在表里、要么记 0，所以 `<= 0` 和「表里没有」
  都得算失败，只看 `isSuccess` 是数不出来的。
- 组接口只测真正的节点，**组里嵌的子组名不会出现在表里**。这类名字既不算成功也不算失败，
  否则「组套组」的配置会被误报成一堆失败。
- 拿不到表（非 Selector 组 / 接口不支持 / 7s 超时 / 返回空表）仍退回并发池逐节点测，
  那条路径的统计逻辑不变。

## 验证

- `mishka-custom/tools/verify_app_patch.sh --repo <仓库>` → **PASS**：补丁双向可逆，
  应用结果与 `BASELINE.txt` 的 74 个 blob 逐文件一致（新增 25 条断言，覆盖外部面板、主页延迟、批量测速、面板 WebView 生命周期）。
- **Kotlin 类型检查通过（本版新增）**：沙箱里装了 kotlinc，把真实依赖拉齐
  （Compose 1.9.4 / miuix 0.9.4 / androidx.activity / lifecycle / navigationevent /
  Robolectric 的 `android-all` 当 android.jar），对 `custom/panel/` 五个文件做了完整
  前端类型检查，**0 错误**。上面的两个编译错误就是这么找出来的。
  项目侧引用（`ProxyServiceBridge` / `AdaptiveTopAppBar` / `StatusColors` /
  `sheetContentSafePadding` / `R`）用的是照真实声明写的桩。
  Compose 编译器插件没装，所以后端 IR lowering 会崩——这是缺插件的已知表现，
  不是代码问题（一个 6 行的正确 Composable 同样崩）。
- 资源侧额外做了 AAPT 敏感字符扫描（裸撇号 / `&` / 尖括号），四个语言全部干净。
- `BASELINE.txt` 的 `patch_sha256` 已随新补丁更新为
  `329b8836815eb17cfa94b7174362f9faf6c0bacbe6e940a8e6f4f3d001c3d64f`。
- 这一轮类型检查又抓到一个必炸的编译错误（也已修）：`PanelScreen.kt` 用了
  `WindowInsets.systemBars.union(...)` 但没导 `androidx.compose.foundation.layout.union`
  —— `union` 是顶层中缀扩展函数，跟 `only` 一样得单独 import。
- 沙箱内存只有 2GB，`:app:compileDebugKotlin` 跑到配置阶段就被 OOM 掉了，**本次没有跑通
  编译验证**。落地后请先跑一次：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```
