# 修复：可视化编辑器的路由规则拖动与开关提示

补丁：`patches/app/0003-visual-editor-fixes.patch`（在 0001、0002 之上应用；`scripts/setup.sh` 已接入）。

手动应用（仓库已应用 0001、0002）：在仓库根目录执行 `git apply <本包路径>/patches/app/0003-visual-editor-fixes.patch`。

## 1. 挪动末尾规则时，上一条规则被追加 `CT`

**现象**：把最底部的 `- MATCH,REJECT` 拖到上面，倒数第二条 `- MATCH,国外出口` 变成 `- MATCH,国外出口CT`。

**根因**：整篇替换 `controller.replaceRange(0,0 → 终点, 新文本)` 的终点取自**新文本**的末位置。scripta 的 `TextBuffer.replace` 会按**旧文档**钳制两端：新文本末行（`MATCH,国外出口`，14 字符）比旧末行（`MATCH,REJECT`，16 字符）短，于是旧末行的最后 2 个字符 `CT` 残留在文末。锚点面板的 `apply()` 是同一写法。

**修复**：终点改为编辑器里旧文本的末位置（`controller.getText()` 取得）。`ConfigFormPanel.kt`、`AnchorPanel.kt` 两处。

## 2. 拖到顶部越界、落点错误、来回回弹

**现象**：从底部往上拖到规则列表顶部之外，被拖行看不见；松手落到错误的档位；按住不动时行上下回弹、虚线框闪烁。

**根因**（`DragSort.kt`）：
- 行的身段用 `onGloballyPositioned` 写进 `tops/heights` 缓存，但滚出视口、被 lazy 列表回收的行不再回调。缓存里的旧位置与屏上现在的行重叠，命中判定会匹配到它们，被拖行就落到看不见的地方。
- 自动翻滚是「先滚动、再命中」，而滚动要到下一帧布局后才反映在行的位置上，命中用的是滚动前的位置，每帧偏一截，表现为在相邻两档之间来回跳。

**修复**：
- 命中时现读行的 `LayoutCoordinates`（`isAttached` 为假的直接跳过），不再缓存位置。
- 落点只在与可见区相交的行里选，指尖先钳到容器可见范围内；越过顶 / 底边两端对称（与「拖到底部」一致）。
- 自动翻滚改为先按当前布局命中、再滚动。
- 同一布局节点被 lazy 列表复用给别的下标时，坐标只归属最近一次上报它的下标（`claimCoords`），
  旧下标不再映射到屏上另一行的位置。行与把手两处都做了这个处理（防御性修复，未在真机上复现）。
- 命中判定抽成纯函数 `dragInsertIndex`（`DragSortGeometry.kt`），不依赖 Compose。

## 3. 开关：先开后关 / 首次就报「没有变化 / 本来就没有设置」

**现象**：配置里没有该字段时，开启后再关闭，弹出「本来就没有设置」，开关仍是开的；另一个字段则弹「没有变化」、开关仍是关的。关闭面板后重新打开，状态恢复正常。

**根因**（`ConfigFormPanel.kt` 的 `FieldRow`）：Miuix `Switch` 用 `rememberUpdatedState(onCheckedChange)` 持有回调；`mutableStateOf` 默认按 `equals` 决定是否替换。局部函数引用 `::setSwitchValue` 跨组合 `equals` 恒为真（同一声明，捕获的值不参与比较），所以 Switch 一直调用**首次组合**时的闭包，拿打开面板时的旧文档判断与写回。

**修复**：`host / field / path / intercept` 改用 `rememberUpdatedState` 持有；`setSwitchValue`、`write`、`openEditor` 与行点击都在点击时读最新值。显示值的规则抽成 `switchShownOf`，组合期和点击期共用。

## 验证

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 挪动末尾规则 | 真实 `YamlPatch.moveItem` + scripta 钳制语义模拟 | 旧：`MATCH,国外出口CT`；新：与期望文本逐字节一致 |
| 拖动落点 / 回弹 | 布局模型（LazyColumn 语义、缓存残留、滚动滞后）逐帧模拟 | 旧：37 帧被拖行不可见、14 次方向反转；新：0 帧不可见、0 次反转；普通短距离拖动新旧结果一致 |
| 开关提示 | 截图两组操作序列重放 | 旧闭包语义精确复现截图的两条提示与开关终态；新语义下每次都正常写入 |
| 闭包语义 | Compose 运行时（desktop 1.13.0-alpha02）真实 `mutableStateOf` | 旧写法点击时调用首次组合的闭包；新写法读到最新状态 |
| 编译 | kotlinc 2.4.20 + Compose 编译器插件，Compose 1.13.0-alpha02 / Miuix 0.9.4 桌面版 | `DragSort.kt`、`DragSortGeometry.kt` 编译通过；`FieldRow` 头部（从 `ConfigFormPanel.kt` 原样抽取，不含对话框段）编译通过，均 0 错误 |

**未验证**：未跑 Gradle（`:app:compileDebugKotlin`），沙箱没有 Android SDK 且只有 2GB 内存；未在真机上操作。上面的编译用的是 Compose 桌面版，不是项目锁定的 AndroidX 版本。`AnchorPanel.kt` 与 `ConfigFormPanel.kt` 的 `apply()` 改动只做了逻辑层面的模拟，没有做类型编译。

## 仍然保留的行为

直接关闭一个配置里不存在的可选开关（没有任何开启步骤），仍提示「本来就没有设置」，且不写入文件。这是真正的空操作，提示本身是准确的；如果希望这种情况也静默，需要另行确认。
