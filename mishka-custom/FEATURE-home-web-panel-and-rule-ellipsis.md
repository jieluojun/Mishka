# 新功能：主页「工具」下方的面板 / Web 界面（对齐 box.app）+ 路由规则匹配值省略号

补丁：`patches/app/0005-home-web-panel-and-rule-ellipsis.patch`（在 0001、0002、0003、0004 之上应用；`scripts/setup.sh` 的步骤 5e 已接入）。

手动应用（仓库已应用 0001–0004）：在仓库根目录执行 `git apply <本包路径>/patches/app/0005-home-web-panel-and-rule-ellipsis.patch`。

自检：`bash <本包路径>/tools/verify_app_patch.sh --repo <仓库> --series`（目前会校验 0001–0007 完整补丁序列的 sha256、断言与可逆性）。

---

## 1. 主页「工具」下方新增「面板 / Web 界面」

**需求**：参照 `MiChongs/box.app` 的 web 界面，在主页「工具」分组（`home_tools`）下方加一个与它一致的入口——进去是全屏内嵌 WebView，打开 mihomo 的 external-controller 面板，本地面板地址是 `http://127.0.0.1:{port}/ui`。

**参考实现**：`box.app` 的 `ui/screens/PanelScreen.kt` + `ui/web/ThemedWebView.kt` + `ui/components/home/HomeActions.kt`（快速入口卡片 `home_quick_panel_title` 面板 / `home_quick_panel_subtitle` Web 界面）。它做四件事：把本地面板地址算出来（控制器地址 + `/ui`）、用配置好的 WebView 承载、管住面板清单（本地 + Zashboard + MetaCubeXD + 自定义增删）、顶栏返回/刷新/面板清单/清除数据。

**实现**：`app/src/main/kotlin/top/yukonga/mishka/custom/panel/`（5 个文件，与该 fork 仓库历史里的面板实现同源——就是作者照 box.app 移植的那套，本次恢复并接回主页 / 导航）：

| 文件 | 职责 |
| --- | --- |
| `PanelEntry.kt` | 面板条目 + 三个内置 id / 地址常量（`local` / `zashboard` / `metacubexd`） |
| `PanelStore.kt` | SharedPreferences `panel_cache`：自定义列表 `panel_list_v1`、选中项 `panel_selected_id_v1`、上次解析到的本地地址 `panel_url_v1` |
| `PanelScreen.kt` | 页面：顶栏 + WebView + 面板清单弹层 + 清除数据确认；本地面板地址随内核状态解析 |
| `PanelSheet.kt` | 面板清单（选中打勾、自定义可删）+ 添加面板表单（名称 / 链接，空名与非法链接就地报错） |
| `PanelWebView.kt` | WebView 承载：随主题深色、防白闪、下载与文件选择、预热器、隔离会话 |

要点（与 box.app 逐条对应）：

- **入口排在「工具」下方**：`QuickEntriesSection.kt` 的 2×2 网格（节点 / 连接 / 日志 / DNS）之后新增一行卡片（`Modifier.weight(1f)` + `Spacer` 占右半，与网格里的卡片同尺寸）。文案 `home_panel` = 面板 / `home_panel_subtitle` = Web 界面（box.app 同款）；
- **本地面板 = 内核 `/ui`**：`PanelScreen` 只在 `ProxyState.Running` 时用 `status.externalController` 现算 `http://<控制器>/ui`，并把结果写进 `panel_url_v1`；内核停止时退回这个缓存地址（否则点是死端口，首屏空白）；
- **三个内置面板 + 自定义**：Zashboard（`http://board.zash.run.place`）、MetaCubeXD（`https://metacubex.github.io/metacubexd`）、本地；自定义面板可增可删，内置项不可删；
- **换面板即换会话**：`sessionKey` 自增触发 WebView 重建，登录态与页内历史不跨面板串；
- **防白闪**：内容提交（`onPageCommitVisible`）前 WebView 保持隐藏、只露底色（`hideUntilCommitVisible`）；换 URL 加载完清 history（`resetHistoryOnUrlChange`）；
- **返回语义**：`NavigationBackHandler(isBackEnabled = canGoBack)`——页内能后退就先退网页，退无可退才退出本页；这一屏的横滑返回关掉（`entry<Route.Panel>(swipeDismiss = NavSwipeDirection.None)`），否则边缘手势会被子级返回处理器截成「网页后退」，看着像卡住；
- **WebView 能力**：JS / DOM storage / Cookie（第三方 Cookie 也接受，面板登录态可持久）、混合内容放行（https 面板拉 http 控制器接口）、`textZoom = 100`（系统字体缩放不撑爆桌面端面板布局）、深色跟随 App（API 33+ `isAlgorithmicDarkeningAllowed`，29–32 `forceDark`）、`<a download>` 的 `blob:` / `data:` 走 JS 桥 → SAF 存盘、`http(s)` 下载走 `ACTION_CREATE_DOCUMENT`、`<input type=file>` 打开系统选择器、`onPageCommitVisible` 前不露白帧、触摸事件不被外层容器抢走（`requestDisallowInterceptTouchEvent`）、`WebViewPreloader` 两阶段预热（`MainActivity.onCreate` 触发：后台加载 Chromium provider + 主线程空闲预建实例），每次进入新实例但近乎零延迟；
- **清除数据**：顶栏 → 确认弹窗 → `clearWebViewAppData()`（缓存 / Cookie / Storage / 历史 / 表单 / SSL 偏好）→ toast 提示并重载。

**为什么是「恢复」**：这套面板在 `patches/app/0001` 的那一版里被下线过（`tools/verify_app_patch.sh` 原先有一组「web 界面已完全移除」的否定断言守着）。0005 按这次需求把它加回来，那组否定断言同步换成了肯定断言（见 `tools/verify_app_patch.sh` 的 0005 段）。

**与 box.app 的差异**（都是 box.app 有、Mishka 没有对应功能的地方，不是行为差异）：

1. box.app 面板里还有一个「Simpana 网页版 API」动作（`panel_enable_simpana_api` 系列），Mishka 没有 Simpana 这套东西，不移植；
2. box.app 的其它快速入口卡片（订阅等）位置/文案照 Mishka 现有布局，只有「面板 / Web 界面」这一项按 box.app 的形态补进来；
3. 面板地址解析用的仍是 Mishka 的控制器地址（`ProxyServiceBridge`），不额外读 box.app 的 `home_repository` 轮询（Mishka 的状态流本来就在跑）。

## 2. 路由规则匹配值过长用省略号显示

**需求**：路由规则的匹配值一长（一个 `DOMAIN-SUFFIX` 挂几十个域名、`GEOIP` 带一堆参数）整行会被撑成好几行，要求改成省略号。

**实现**：

- `custom/forms/ConfigFormPanel.kt` 的 `RowCard` 新增两个可选参数 `titleMaxLines` / `summaryMaxLines`（默认 `Int.MAX_VALUE`，老调用点渲染一字不变）。传了限行时走 `BasicComponent` 的 content 版本，标题 / 副标题用 `BasicComponentDefaults` 与 `MiuixTheme.textStyles.headline1 / body2` 对齐原字号字色，`overflow = TextOverflow.Ellipsis`；
- `custom/forms/FlowFormPages.kt` 的 `SeqListPage` 新增 `rowMaxLines`（默认不限行）并转发给 `RowCard`；**只有规则列表**（顶层「路由规则」+ 子规则页）传 `rowMaxLines = 1`——最长的那条决定整列表行高太丑。子规则页在参考实现里是「子规则集合」maplist，也跟着收成一行，但**不显示**序号（`.rule-no` 只出现在顶层规则页，0004 已按此实现）；
- 其它列表（provider / rulesets / 各 maplist / 锚点面板等）调用点一个没动。

**为什么不用改 BasicComponent**：miuix 0.9.4 的 `BasicComponent(title, summary)` 内部 Text 没有 `maxLines` / `overflow` 出口，只有 content 版本能挂——所以这一步是「按需换重载」，不是给整个组件加开关。

---

## 验证

```bash
# 整序列（干净仓库 → 0001 → 0002 → 0003 → 0004 → 0005 → 逆序还原）
bash <本包路径>/tools/verify_app_patch.sh --repo <干净仓库> --series

# 面板资源引用静态检查（字符串键 × 4 locale、图标名对 0.9.4 清单）
python3 <本包路径>/tools/panel/check_panel_refs.py --repo <仓库>

# 跨包 import / 尾随 lambda 全树检查
python3 <本包路径>/tools/check_cross_package_imports.py --repo <仓库>
python3 <本包路径>/tools/check_trailing_lambda.py --repo <仓库>
```

0005 段断言覆盖：五个面板文件的关键行为、`Route.Panel` 注册（含 `swipeDismiss = NavSwipeDirection.None`）、主页入口排在「工具」网格之后且不受 `isRunning` 门控、`MainActivity` 的预热调用、四个 locale 的 `home_panel*` / `panel_*` 字符串、`RowCard` 的限行参数与 `rowMaxLines = 1` 只出现在规则页这一处。

## 仍然保留的行为

- 0001–0004 的全部功能与断言不变（字段整理 / 列表拖动 / 序号 / 批量测速 / 锚点面板 / 免流配置）；
- 面板不代填控制器地址与密钥：与 box.app 一致，面板自己首次进入时让用户填一次（MIUI/HyperOS 上各家面板对 `?hostname=&secret=` 的支持不一致，代填错了反而更难排查）；
- 面板是纯前端页面，代理没起也能进（拿不到数据而已），所以这个入口不跟其它入口一起受「代理运行中」门控；
- 未引入任何新依赖：`android.webkit` 属系统框架，`AndroidView` 来自已在用的 compose-ui。
