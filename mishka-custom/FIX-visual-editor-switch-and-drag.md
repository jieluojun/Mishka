# 可视化编辑器修复：开关通知 + 规则拖动排序（状态：部分修复，未上机验证）

## 1. 开关通知（「没有变化」/「本来就没有设置」）— 已修，待上机验证

现象：字段原本不在配置里，开启后再关闭，弹出「…没有变化」或「…本来就没有设置」；
字段已经写进配置后，开关不再出现这类提示。

根因（最可能，已在代码层确认路径，未在真机复现）：同一次点击会派发两次写入
（Miuix `Switch` 的 toggleable + 整行 `BasicComponent` 的 onClick），两次都读的是组合期快照
（`FormHost.doc` / `hasVal`）。第二次派发时要么拿到旧快照（判成「本来就没有设置」），
要么编辑器里已经是目标值却又写一次（`commit` 比对后报「没有变化」）。

修复（`forms/ConfigFormPanel.kt`）：
- `FormHost` 新增 `liveDoc`（每次从编辑器取此刻文本解析），`set / clear / batch / insertItem /
  setItem / removeItem / moveItem / rename / applyRawText` 全部以 `current()` 为基准计算；
  `commit` 比对的也是同一份基准。
- `MishkaConfigFormPanel` 的 `apply` 与编辑器此刻文本比较（不再用组合期的 `text`）。
- `FieldRow.setSwitchValue` 的 `editable` / `hasVal` 改为读 `host.current()`；
  若编辑器里已经是目标值，静默返回（即重复派发，不提示）。

离线验证：把 `FormHost` 原样抽出，在 `kotlinc` 1.9.24 + JDK 11 下编译运行，模拟「字段不存在 → 开 → 开（重复派发）→ 关 → 关 → 开」：
每次点击只写入一次，提示只有「已写入 …」，没有「没有变化」「本来就没有设置」。

## 2. 规则拖动排序 — 部分修复，「CT」后缀问题未能复现

- 已修：`DragSort.kt` 的命中判定原先会用到已经滚出视口 / 被 lazy 列表回收的行的旧位置（`tops` 按下标记录，
  从不清理）。手指越过列表顶边时，离手指最近的可能是这些「幽灵行」，导致落点错位（越过顶边后落到倒数第几条）。
  现在只认仍在布局里的行（`rowCoords[i].isAttached`）。
  未上机验证。
- 未修 / 未复现：「把最后一条往上拖，倒数第二条的目标策略被加上 CT」。
  YAML 层的 `YamlPatch.moveItem` 在块式、flow 式、带引号、带行尾注释、紧跟其它键、无末尾换行等形式下，
  离线测试都只是重排、没有改任何策略文本。需要用户提供出问题时的规则片段（原始 YAML）才能继续定位。

## 文件
- `patches/app/0001-anchor-panel.patch`：`ConfigFormPanel.kt`、`DragSort.kt` 两个新文件段已重新生成；
  其余段不变。`git apply --check` 在空仓库上通过，两个文件的 blob 与 BASELINE 已更新。
- `patches/app/BASELINE.txt`：`patch_sha256` 与两个 `blob_new` 已更新。
- 限制：本环境没有 Android SDK，未编译整个 APK，也未在真机上测试。
