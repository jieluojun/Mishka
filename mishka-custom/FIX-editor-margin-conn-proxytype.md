# 修复：规则编辑按钮边距 + 连接页代理类型标签

补丁：`patches/app/0006-fixes-editor-margin-conn-proxytype.patch`（在 0001–0005 之上应用；
应用方式：`git apply <本包路径>/patches/app/0006-fixes-editor-margin-conn-proxytype.patch`，
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

---

## 验证

- 补丁在 0001–0005 之上 `git apply --check` 通过，`--series` 里还能双向可逆地应用 / 回滚；
- 两处修改不新增 import 以外的符号：`Spacer`/`height` 是 Compose foundation 现有 API，
  `MiuixTheme.colorScheme.primary` 已在同文件内使用；
- 连接页代理类型标签只在 `meta.type` 非空且与 `meta.network` 不同的时候渲染，空数据不
  占空间、不报错；
- `verify_app_patch.sh` 的 0006 段断言覆盖：`Spacer(Modifier.height(8.dp))` 与
  `val proxyType = meta.type.trim().uppercase()`。

**未验证**：沙箱没有 Android SDK 与真机，未实际安装 APK；以上修复仅做静态检查 + 补丁可应用
性检查。面板（Web 界面）相关的修复**不在本补丁范围内**——历史上有过三版面板加载修补
（缓存复用 → 重进先刷新 → WebView 常驻），都已从交付包中移除，现行方案见
`FIX-panel-standalone-activity.md`。
