# 修复：Web 界面面板重进闪烁 / 内容缺失，并恢复默认入口页

补丁：`patches/app/0008-panel-reentry-default-page.patch`（依赖 0001–0007；由 `scripts/setup.sh` 自动应用）。

## 修复内容

- 同一面板的 WebView 实例仍可缓存复用，避免反复创建 Chromium；但**每次进入都会重新加载所选面板配置的入口 URL**，不再直接展示上次离开时停留的 SPA tab / 子页。
- 缓存实例重进时先清掉旧 WebView history、把 `contentCommitted` 归零并把 WebView 设为透明，然后加载入口页；只有本次入口页 `onPageCommitVisible` / `onPageFinished` 后才显示，避免旧页闪现和白闪。
- WebView 始终保持 `VISIBLE`，只用 `alpha` 隐藏未提交帧，避免 `INVISIBLE` 暂停 Chromium 合成引发的首屏内容缺失。
- 入口加载完成后清一次历史，系统返回不会退回上一次进入前的 tab / 页面；本次进入后新产生的网页历史仍可正常后退。
- 不清除 Cookie、localStorage 或缓存数据，因此面板登录态和设置保留；用户选择的面板（本地 / Zashboard / MetaCubeXD / 自定义）也仍按原有偏好保存，只重置所选面板内部的浏览位置到它的入口 URL。

## 验证

- `tools/verify_app_patch.sh --repo <Mishka 仓库> --series` 校验 0001–0008 的补丁哈希、顺序应用、关键断言及逆序回滚。
- 补丁对 0007 后的 `PanelWebView.kt` / `PanelScreen.kt` 做了 `git apply --check`；不引入新依赖，仅使用 Compose 与系统 `android.webkit` API。
- 完整 Android 编译与设备实测仍需在有 Gradle 缓存 / Android SDK 和设备的环境中完成；本交付环境没有完整 Mishka 源码仓库，无法替用户声称已安装验证。

> 注：如果某个 Web 面板自身把当前 tab 写入 localStorage 并在启动时主动恢复，通用 WebView 层不会清除站点设置（否则会连登录态一起丢失）。本补丁会重开其配置入口 URL 并清空 WebView 导航历史；站点若仍恢复 tab，需要针对该站点另加定制重置逻辑。
