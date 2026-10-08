# 更换加载方式：面板（Web 界面）改用独立 Activity 承载

补丁：`patches/app/0009-panel-standalone-activity.patch`（在 0001–0008 之上应用；
应用方式：`git apply <本包路径>/patches/app/0009-panel-standalone-activity.patch`，
或通过 `scripts/setup.sh` 在 0008 之后继续叠加）。

> 本补丁**取代并删除**了此前两版面板加载修补：
> `0008-panel-reentry-refresh-before-show.patch`（复用缓存实例 + 重进先刷新）与
> `0009-panel-resident-webview.patch`（WebView 常驻 App 根布局）。两者在真机上都没有彻底
> 修掉「闪一下 + 内容缺一块」，根因是 WebView 只要跟着页面 `detach / attach`，重新挂回窗口
> 时 Chromium 手上就只有 detach 前的合成帧。既然如此，干脆不再让 WebView 挂在主界面的
> Compose 树里——**整个面板搬到自己的 Activity**。

## 设计

- **宿主**：新增 `PanelActivity`（`ComponentActivity`，`exported=false`、`launchMode="singleTop"`、
  `Theme.Mishka`、`configChanges` 与 MainActivity 同一份、edge-to-edge 按 `ThemeConfig` 应用）。
- **入口**：主页「外部面板」从 `navigator.push(Route.Panel)` 改为
  `context.startActivity(PanelActivity.createIntent(context))`；
  `Route.Panel` 与导航里的 `entry<Route.Panel>` 一并删除（`HomeScreen` / `QuickEntriesSection`
  的 `onNavigatePanel` 回调签名不动）。
- **主题**：`App.kt` 里那段「Miuix 配色 + 缩放密度 + App 级 CompositionLocal」原样抽成
  `ui/theme/MishkaTheme.kt` 的 `MishkaTheme(themeConfig) { … }`，主界面与面板 Activity 共用
  一份实现，配色 / 深浅色 / 密度 / 模糊开关不会两边漂移。
- **WebView 生命周期**：每次进入都是「干净的新 WebView + `loadUrl(入口)`」（box.app 的重进
  语义：回面板首页、页内历史清掉），退出 `finish()` 直接丢弃实例；**登录态不受影响**——
  控制器地址 / 密钥存在面板自己的 localStorage，Cookie 在系统 `CookieManager` 里，都跨实例
  保留。
- **防白闪**：沿用 `PanelWebView` 的 `hideUntilCommitVisible`——内容提交前 `alpha = 0` 只露
  底色，`onPageCommitVisible`（最迟 `onPageFinished`）才显形；全程保持 `VISIBLE`，不用
  `INVISIBLE`（Chromium 在 INVISIBLE 下会暂停合成，SPA 首屏容易残缺）。
- **返回键**：页内历史优先（`PanelScreen` 里的 `NavigationBackHandler`，`canGoBack` 时退网页
  历史），退无可退由系统收尾 Activity —— 即「退出面板」，和 box.app 一致。
- **预热**：`WebViewPreloader.preload()` 仍在 MainActivity.onCreate 里预热（面板 Activity
  里再调一次，是幂等的兜底），新实例优先取预热实例，冷启那 ~500ms 照样被吃掉。

## 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 注册 `.PanelActivity` |
| `app/src/main/kotlin/top/yukonga/mishka/PanelActivity.kt` | **新增**：面板宿主 Activity |
| `app/src/main/kotlin/top/yukonga/mishka/ui/theme/MishkaTheme.kt` | **新增**：抽出共用主题容器 |
| `app/src/main/kotlin/top/yukonga/mishka/App.kt` | 改用 `MishkaTheme` 包住 `AppNavigation`（并补 `import top.yukonga.mishka.ui.theme.MishkaTheme`）|
| `app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt` | 面板入口改起 intent；删 `entry<Route.Panel>` |
| `app/src/main/kotlin/top/yukonga/mishka/ui/navigation/Route.kt` | 删 `data object Panel` |
| `app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt` | 去掉 `isDark` 入参（改读 `LocalAppDarkMode`），`onBack` 由宿主传 `finish()` |

`PanelWebView.kt` 不改动：常驻方案加进去的 `PanelWebViewRuntime` / `PanelWebViewLayer` /
`takeCached` 都是 0009（旧）自己的东西，随那一版一起删掉了，`PanelWebView` 回到自包含的
形态。

## 验证

- `tools/verify_app_patch.sh --series` 的 0009 段断言：`PanelActivity` 的存在与内容、
  `MishkaTheme` 与两个宿主的接线、主页入口改起 intent、`Route.Panel` 与 `PanelWebViewRuntime`
  已消失、仍然没有 `androidx.webkit`（`PanelWebView.kt` / `App.kt` / `build.gradle.kts` /
  `libs.versions.toml`），以及 manifest 里 `.PanelActivity` 的声明
  （`exported=false` / `singleTop` / 自行处理 `uiMode` 等配置变更）；
- 补丁在 0001–0008 之上 `git apply --check` 通过，且可 `git apply -R` 逆序还原；
- `tools/check_cross_package_imports.py`、`tools/check_trailing_lambda.py`、
  `tools/panel/check_panel_refs.py` 在整个序列上继续通过。

## 修订记录

- **修 CI 编译失败**：初版把 `MishkaTheme` 从 `App.kt` 抽到 `ui/theme/MishkaTheme.kt` 后，忘了
  在 `App.kt` 里补 `import top.yukonga.mishka.ui.theme.MishkaTheme`（两个包不同），CI 的
  `:app:compileReleaseKotlin` 报 `Unresolved reference 'MishkaTheme'`，并连带一条
  `@Composable invocations can only happen from the context of a @Composable function`。
  已补 import 并重新生成补丁（sha 见 `BASELINE.txt`）。
- **检查器补强**：`tools/check_cross_package_imports.py` 原来只扫 `top.yukonga.mishka.custom.**`
  下的符号，正好漏掉这种「自定义包之外」的引用。现在扫整个 app 模块的顶层声明，判断
  「调用了别处的顶层符号却没 import」（同包 / 同包同名 / 本文件声明过同名符号 /
  已 import 同名第三方符号 都算合法），只查调用位置以避免类型位置的几十条误报。
  实测：原始 dd21ee4、0001、0001–0008、0001–0009 四棵树均 0 误报；把这版补丁的 import 去掉后
  立刻报出 `App.kt: 调用了 MishkaTheme( 但没有 import top.yukonga.mishka.ui.theme.MishkaTheme`，
  与 CI 的错误一致。

**未验证**：沙箱没有 Android SDK 与真机，未实际安装 APK；新文件只做了 kotlinc 的语法 /
符号级前后对照（无新增错误类别），未做完整类型检查。真机验收建议按下面几条走：

1. 主页点「面板 / Web 界面」→ 面板在独立窗口打开，首屏不闪、内容完整；
2. 返回退出后再进 → 落到面板首页（页内历史可清），登录态 / 控制器地址还在；
3. 切深浅色、旋转屏幕 → 顶栏与系统栏外观跟主界面一致，不黑屏；
4. 面板里的上传 / 下载 / 文件选择、Miuix 弹层（面板清单、清除数据确认）都盖在面板之上；
5. 面板里点返回 → 先退网页历史，退到底退出 Activity 回主页。
