# 修复：面板重进时改用 WebView 视觉帧确认后再显示

补丁：`patches/app/0009-panel-visual-state-loading.patch`（依赖 0001–0008；由 `scripts/setup.sh` 自动应用）。

## 变更

此前 WebView 在 `onPageCommitVisible` 就把 alpha 恢复为 1。对 React / Vue 等 SPA 来说，这个回调只说明主文档已提交，首屏 JS、布局和资源可能还没准备好，因此会短暂露出空壳或半帧，看起来像闪烁 / 内容跳变。

现在改为：

- 页面保持 `VISIBLE`，加载期间只用 `alpha = 0` 遮住未完成画面，不暂停 Chromium 渲染。
- `onPageCommitVisible` 不再触发显露；主文档 `onPageFinished` 后调用 Android WebView 的 `postVisualStateCallback`，等渲染器确认当前页面可绘制后才恢复 alpha。
- 为每次入口加载、地址切换和刷新分配唯一 `loadId`。若回调迟到、期间已开始新一轮加载，旧回调会因 token 不匹配而被忽略，不能错误地显露旧页面。
- Android 6.0 及以上使用系统视觉状态回调；更低版本退化为下一次 UI 绘制周期后显示。
- 0008 的“每次进入回到所选面板入口 URL、清空旧 history”行为保留；Cookie / localStorage 与面板来源选择仍保留。

## 验证

- `tools/verify_app_patch.sh --repo <Mishka 仓库> --series` 现校验 0001–0009 的哈希、补丁顺序、断言与逆序回滚。
- 当前交付环境只有补丁包，没有完整 Mishka 仓库、Android SDK 和设备；已对模拟的 0008 后 WebView 源码检查补丁正反向应用与脚本语法，但未声称完成 Android 编译或真机验收。
