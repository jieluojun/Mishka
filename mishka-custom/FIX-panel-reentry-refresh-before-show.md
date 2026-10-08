# 修复：面板（Web 界面）重进「先刷新、再露内容」

补丁：`patches/app/0008-panel-reentry-refresh-before-show.patch`（在 0001–0007 之上应用；
`scripts/setup.sh` 的步骤 5h 已接入，`tools/verify_app_patch.sh --series` 已校验到 0008）。

手动应用（仓库已应用 0001–0007）：在仓库根目录执行
`git apply <本包路径>/patches/app/0008-panel-reentry-refresh-before-show.patch`。

---

## 现象

重启 App 后**第一次**进「面板 / Web 界面」一切都正常；**之后**再进入（返回主页再进、切到别页再回来）：

1. 内容会「闪」一下——先出现一帧不对的画面，再跳成正常/半截的页面；
2. 页面内容缺失、不完整：侧栏 / 卡片只画出一部分，有时候要手动点顶栏刷新才补全。

## 根因

0006 为了让面板「不闪、不冷启动」，把 WebView 实例缓存起来复用（`onRelease` 不 destroy，
下次同 `sessionKey` 直接 `takeCached`）；0007 又修了「缓存页被 Compose 瞬态状态判成未提交、
重进一直透明」的黑屏。缓存本身是对的，但留下一个新问题：

**重新挂回窗口的 WebView，手上只有 detach 前那一帧合成结果。**

- WebView 从窗口摘下来（`onRelease` → `AndroidView` detach）之后，Chromium 的合成 / 动画
  处理处于暂停态，detach 期间也不保证 SPA 首屏资源还在；
- 重新挂回去时，DOM/JS 在内存里，但**合成器不会自动重绘一整屏**——于是你看到的就是
  「上一次的帧 + 缺一块」，再加上 0007 的 alpha 由 0 跳 1，观感就是闪一下 + 内容残缺；
- 第一次进入没有缓存实例，走的是「新建实例 + `loadUrl(入口)`」，天然会完整渲染，所以首次
  永远正常、只有重进才出问题——和现象完全对上。

## 修复：内容露出来之前先刷新一次

改动集中在 `custom/panel/PanelWebView.kt`（两处 API 扩展）+ `PanelScreen.kt`（接线）。

| 环节 | 做法 |
| --- | --- |
| 复用实例先恢复处理 | factory 里 `isReused` 时调 `self.onResume()`，与 `onRelease` 里新增的 `released.onPause()` 配对；detach 期间停掉动画 / 合成处理，重新挂回时是一次干净的恢复 |
| 强制重排重绘 | `self.post { self.requestLayout(); self.invalidate() }`——合成器还在用 detach 前的旧尺寸 / 旧帧时，光靠刷新不一定触发重排 |
| 刷新排到 attach 之后 | 刷新用 `post{}` 发出去（View.post 在 attach 之后才执行），detach 状态下调 `reload()` 走不完渲染管线 |
| 刷新目标分情况 | 已经 commit 过的页面 `reload()`（保住当前子页地址）；上次没 commit 就离开的「半截页」`loadUrl(入口 URL)`，不去刷新一个半截页面 |
| 刷新期间不露残帧 | 复用实例一律先把 `alpha` 归 0（只露 App 底色），`TagState.contentCommitted = false` + 新增 `refreshPending = true`；新页面 `onPageCommitVisible`（最迟 `onPageFinished`）才把 alpha 恢复到 1 |
| 顶栏给用户反馈 | 新增 `onRefreshStart` 回调，宿主 `onRefreshStart = { refreshing = true }`，顶栏刷新图标转圈，`onPageFinished` 自动收尾 |
| 只通知一次 | `update` 分支 `else if (last?.refreshPending == true)` 触发回调后立刻清标记，并在 `markContentCommitted` 里同步清掉，避免重组重复通知 |
| URL 变了不抢 | 入口 URL / `reloadKey` 变了仍走原来的 `urlChanged` / `needsReload` 分支（清 history + 重新 load），不在这条路上做刷新 |

新增 / 变化的公开参数（都在 `PanelWebView`）：

- `refreshBeforeShow: Boolean = true`——复用缓存实例时是否「先刷新再显形」，关掉就退回 0007
  的「直接显示缓存页」行为；
- `onRefreshStart: (() -> Unit)? = null`——自动刷新开始（顶栏转圈用）。

首次进入（没有缓存实例）仍然是新建实例 + `loadUrl(入口)`，行为不变；现在重进也是「先加载
再显形」，两次进入的观感一致。

**取舍**：重进多了一次页面重新加载（面板资源有缓存，通常只有几百毫秒），换来的是每次进入
都是完整的一屏，不再出现残帧。代价换来的是行为一致，也比「让用户手动点刷新」可靠。

**依赖**：仍然只用平台 `android.webkit` 与项目已有的 Compose API（`post` / `onResume` /
`onPause` / `reload` / `loadUrl` 全是 `WebView` 自己的方法），没有引入 `androidx.webkit`
或任何新依赖。

---

## 验证

```bash
bash <本包路径>/tools/verify_app_patch.sh --repo <干净仓库> --series   # 0001–0008
python3 <本包路径>/tools/check_cross_package_imports.py --repo <仓库>
python3 <本包路径>/tools/check_trailing_lambda.py --repo <仓库>
python3 <本包路径>/tools/panel/check_panel_refs.py --repo <仓库>
```

已跑通的项：

- 0001–0008 顺序 `git apply --check` / `apply` 全部通过，逆序 `apply -R` 后工作区回到干净；
- 0008 的 sha256 已写进 `patches/app/BASELINE.txt`（`patch0008_sha256`），`--series` 会比对；
- `verify_app_patch.sh` 的 0008 段断言覆盖：`refreshBeforeShow` 开关、`willRefreshBeforeShow`
  判定、`onResume` / `requestLayout` / `post` 排队、pending 页与已提交页的不同刷新目标、
  `refreshPending` 标记与一次性通知、`onPause` 配对、顶栏转圈接线，以及仍然不含
  `androidx.webkit`；
- 两个改动文件用 Kotlin 2.0.21 编译器做过「改前 / 改后错误集合对比」：没有新增任何语法类
  错误，新增的 unresolved 引用全部来自沙箱里缺失的 Android / Compose classpath
  （`post` / `invalidate` / `requestLayout` / `reload` / `Handler` / `Looper` 等）。

**未验证**：沙箱没有 Android SDK 与真机，未出 APK 实机验收。需要在真机上确认的两点：
① 重进面板时顶栏转圈 → 内容一次到位，不再闪、不再缺块；
② 面板内的登录态 / 已填的控制器地址在刷新后仍在（走的是 `reload()`，localStorage 保留）。
