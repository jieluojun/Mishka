# Bug-2 诊断结论（2026-10-08，rebase 构建，用户真实配置复现）

## 一句话
真 bug 是**极速连点时后一次写回会静默吞掉前一次**（两个 `已写入`，只留下一处改动）；
两个"矛盾" toast 本身**不可能是开关点出来的**，是别处 dialog 点按 + 安卓 toast 排队的陈旧显示。

## 复现证据（/tmp/yamltest，真引擎 + 生产逻辑逐行镜像，用户 244 行配置原字节）
| 实验 | 操作 | 结果 |
|---|---|---|
| E0 | parse→dump | 字节全同，引擎本身无损 |
| E1 | 读值 | respect-rules=true/canSet✓，prefer-h3 未设/canSet✓ |
| E2 | prefer-h3 连点 4 次（每次重组） | 4×`已写入 优先 HTTP/3 DoH`，true→false→true→false，**从未出现**`本来就没有设置` |
| E3 | respect-rules 连点 4 次 | 4×`已写入 按规则分流解析`，**从未出现**`没有变化` |
| **E4** | **rr→OFF 后不重组、紧接着 prefer-h3→ON** | **2×`已写入`，但 rr 的 OFF 丢了（终态 rr=true）——写丢失复现** |
| E5 | 同一行 stale 双点 | 2×`已写入`（第 2 条是 apply early-return 回声，无害） |
| E6 | set(String "true") | 写成 `'true'`（加引号变字符串）+`已写入`（仅 dialog 路径可达） |
| E7 | clear 未设键 | `优先 HTTP/3 DoH 本来就没有设置`（仅 dialog 路径可达） |

## 根因 A（真 bug）：stale host 整篇覆盖
链路：`MishkaConfigFormPanel: version→text→doc→host=remember(doc){…}`，
`apply` 拿 `host.doc` 算好的整篇 dump 做 `replaceRange` 全量替换。
两次点按落在同一个重组窗口里时，第二次基于**旧 doc** 算 dump，把第一次的改动整篇盖掉。
E4 在用户配置上精确复现：prefer-h3 present-true → unset（被另一行的 stale 写回吞掉）即此机制。

## 根因 B（toast"矛盾"）：队列延迟，非写错
- `showToast`= 裸 `Toast.makeText().show()`（Toast.kt），无 single-flight，FIFO 排队约 2 秒/条。
- 代码级 QED：`prefer-h3`/`respect-rules` 是 BOOL 非 asSwitch 非 tri 行，无 ✎、无 dialog，
  开关/行点只走 `setSwitchValue(toggle)` → `host.set`：
  - 1207 行 `本来就没有设置` 要求"发出 OFF 且 !hasVal"，同一次组合里与"显示 ON"互斥——**不可达**；
  - 229 行 `没有变化` 要求 toggle 写回 dump 相等——同一次组合里互斥——**不可达**；
  - 251 行 `clear` 要求 `write(null)`——开关传参恒为 Boolean——**不可达**。
- ∴ 截图里的两条 toast 只能来自 DNS 页其它**有 dialog 的行**（SELECT/TEXT/DNSLIST/MAPLIST：
  空确认→`xxx 本来就没有设置`，等值确认/MAP 无 diff→`xxx 没有变化`），
  显示是之后开关点按的新状态。**要 окончательно 定性需用户补：toast 全文（含前缀标签）+ 点按顺序。**

## 修复设计（待用户确认后实施）
`FormHost` 的写路径改以**新鲜文本**为基准（rebase），读路径不动：
- `FormHost` 新增 `freshDoc: () -> YamlDoc`（面板传入 `{ YamlDoc.parse(controller.getText()) }`）；
- `set/clear/insertItem/setItem/removeItem/moveItem/rename/batch` 全程用 `freshDoc()` 做
  canSet 检查 + applySetAware/patch + commit 对比；点按处理在 UI 线程同步执行，原子性关闭竞态；
- 无竞态时 fresh==composed，行为/toast 与现在**逐字相同**；有竞态时两处改动都保留；
- 唯一 toast 变化：E5 类 stale 双点第 2 下从"已写入（回声）"变为"没有变化"（更诚实）；
- `applyRawText`（锚点手术）调用方均为 modal dialog（串行化，天然安全），不动；
- 不动全局 toast 排队（标准安卓行为；显示不再出"不可能状态"后，残余只是"旧 toast 还没消"）。

实施 = 改 ConfigFormPanel.kt → 重打 patch → verify（182 断言）→ re-zip → 用户重装。
