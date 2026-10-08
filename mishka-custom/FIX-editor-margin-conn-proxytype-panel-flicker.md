# 修复：规则编辑按钮边距 + 连接页代理类型标签 + Web 面板闪烁/内容缺失

补丁：`patches/app/0006-fixes-editor-margin-conn-proxytype-panel-flicker.patch`（在 0001–0005 之上应用；
应用方式：`git apply <本包路径>/patches/app/0006-fixes-editor-margin-conn-proxytype-panel-flicker.patch`，
或通过 `scripts/setup.sh` 在 0005 之后继续叠加）。

---

## 1. 「切换为可视化编辑」按钮与输入框之间的边距

**现象**：规则编辑对话框里，右上角「切换为可视化编辑」按钮贴着下面的「规则原文」输入框，
视觉上像两个控件粘在一起；在深色主题下甚至会有按钮底缘压到输入框 label 顶缘的感觉（参见
`Screenshot_2026-10-08-21-22-50-552_top.yukonga.mishka.jpg`）。

**根因**：`custom/forms/FlowFormPages.kt` 的 `RuleEditDialog` 里，`Row`（放切换按钮）后面
直接跟着 `if (rawMode) { TextField(...) }`，垂直方向没有任何间距。

**修复**：在 `Row` 与 `if (rawMode)` 之间加 `Spacer(Modifier.height(8.dp))`。顺手补了
`androidx.compose.foundation.layout.height` 的 import（之前只用了 `heightIn`，文件里没
`height`）。

## 2. 连接列表 TCP/UDP 后追加代理类型（TUN/TPROXY/EBPF）

**需求**：主页「连接」页每条连接头部当前只显示网络类型（TCP / UDP）小胶囊，看不出这条连接
是从哪种代理入口进来的（VPN TUN / ROOT TUN / ROOT TPROXY / EBPF）。

**实现**：`ui/screen/connection/ConnectionScreen.kt` 的 `ConnectionItem` 在 TCP/UDP 胶囊
**后面**紧接一个同尺寸、主色半透明底 + 主色文字的胶囊，显示 `meta.type.trim().uppercase()`。
数据来自 mihomo `/connections` API 已经返回的 `metadata.type` 字段（内核会填 `tun`/
`tproxy`/`ebpf`/…），不需要任何桥接层修改。
几个细节：

- `meta.type` 为空，或与 `meta.network` 相同（都是 `tcp`/`udp` 这种字符串，极少见）时不显示；
- 胶囊样式和原 TCP/UDP 胶囊完全一致（`squircleBackground` 圆角 3dp、`padding(5dp, 1dp)`、
  9sp Bold Monospace），只是底色用 `primary.copy(alpha = 0.12f)`、文字色用 `primary`，
  这样一眼就能把网络类型（灰）和代理类型（主色）区分开；
- 两个胶囊之间仍沿用外层 `Row` 的 `Arrangement.spacedBy(8.dp)`，与 host 文字的间距不变。

## 3. 反复返回 / 进入 Web 界面面板闪烁 + 内容缺失

**现象**：从主页点「面板 / Web 界面」进去，再返回、再进去，偶现：

1. 顶栏/底色出现约一帧白底（深色主题尤其明显，像闪烁）；
2. 面板（MetaCubeXD / Zashboard 这种 React/Vue SPA）首屏会有内容残缺——侧栏/卡片只画
   出一部分，要么等一会自己刷新回来，要么要手动下拉刷新才正常。

**根因**：`custom/panel/PanelWebView.kt` 里有三个问题叠加：

- **首帧白闪**：factory 里 new 出 WebView 后第一帧就会被 AndroidView 挂上去画，白底
  WebView 立刻出现在屏幕上；而把 `alpha` 置 0 是在后面第一次 `update` 回调里做的，
  这中间已经有一帧把白底画完了。
- **INVISIBLE 伤渲染**：`update` 里把 `visibility` 置成 `INVISIBLE` 而不是只改 `alpha`。
  WebView 处于 INVISIBLE 时 Chromium 会暂停合成 / 渲染管线；SPA 首屏 HTML 已到但资源
  （JS/CSS chunk）还没齐的时候被暂停，等 commit 回调把 VISIBLE 切回来，部分 DOM 状态
  已经丢了，表现为「面板内容残缺」。
- **每次 destroy → new → loadUrl**：每次进入面板都新建 WebView + 重新加载入口 URL，
  离开时 destroy。用户按返回马上又进，等于反复走冷启动路径，闪烁感知被放大。

**修复**（都在 `PanelWebView.kt` 里，不改调用方）：

| 问题 | 修复 |
| --- | --- |
| 首帧白闪 | 在 `factory` 末尾、`loadUrl()` 之前就把 `alpha` 置 0（复用实例不置），第一帧画上去的就是透明的，等 `onPageCommitVisible` 再恢复 1f。 |
| INVISIBLE 致内容残缺 | 全程保持 `visibility = VISIBLE`，仅用 `alpha` 控制显隐。`update` 里不再写 `INVISIBLE`；同时防御性地把任何非 VISIBLE 状态拉回 VISIBLE。 |
| 反复进入闪烁 | 0006 引入 `WebViewPreloader` 实例缓存，避免重复构造 Chromium；叠加 0008 后，缓存命中仍从旧 parent 摘下，但每次重进都会清空旧 history 并重载所选面板入口 URL。缓存只保留 WebView 实例，不保留上次 DOM / SPA tab；`sessionKey` 不匹配时旧实例会 `stopLoading + destroy`，避免跨面板串状态。 |
| 复用实例首次挂上来时 canGoBack 错 | `update` 里加 `justAttached = last == null` 分支，复用实例第一次 update 时同步一次 `canGoBack`，不做 loadUrl/reload。 |

---

> **后续行为更新（0008 / 0009）**：缓存只用于复用 WebView 实例，每次重进都从所选面板入口 URL 重载并清 history；页面加载期间始终 `VISIBLE`、只用 alpha 遮罩，等 `onPageFinished` + Chromium visual-state callback 后再显示。既不保留上次 tab，也避免 SPA 空壳或半帧闪现。详见 `FIX-panel-default-page-reentry.md` 与 `FIX-panel-visual-state-loading.md`。

## 验证

- 补丁在 0001–0005 之上 `git apply --check` 通过；
- 三处修改不新增 import 以外的符号：`Spacer`/`height` 是 Compose foundation 现有 API；
  `MiuixTheme.colorScheme.primary` 已在同文件内使用；`WebViewPreloader` 的新方法
  （`takeCached`/`cache`/`discard`）仅在 `PanelWebView.kt` 内部调用，对外的 `preload()`/
  `take()` 签名保持不变，`MainActivity.onCreate` 的调用点不需要改动；
- 连接页代理类型标签只在 `meta.type` 非空且与 `meta.network` 不同的时候渲染，空数据不
  占空间、不报错；
- WebView 缓存逻辑沿用 WeakReference，静态字段不会持有 Activity context（`prewarmedRef`
  一直用 `applicationContext`，`cachedRef` 里的 WebView 是在面板离开时由 Compose
  `AndroidView` 构造的，context 来自 `LocalContext`，但页面一离开整个 PanelScreen
  从 Nav 栈弹出后没有强引用再拿它，下次 GC 会回收到；`discard()` 里会 `removeAllViews() +
  destroy()`，不会泄漏）。

**未验证**：沙箱没有 Android SDK 与真机，未实际安装 APK；以上修复仅做静态检查 + 补丁可应用
性检查。SPA 面板内容残缺的修复依赖「INVISIBLE 会暂停 Chromium 合成」这一 WebView 公开行为
（AOSP WebView 源码里 `WebView.onWindowVisibilityChanged(INVISIBLE)` 会调用
`AwContents.setWindowVisibility` → 隐藏 `RenderWidgetHostView`，属于长期稳定行为），
首帧白闪和「每次 destroy 重建」是显然会引入闪烁的代码路径，改后应同时消除。
