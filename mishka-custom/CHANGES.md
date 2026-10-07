# 本次改动（2026-10-07）——与 mihomo_box 模块对齐的三处交互

> **追加（CI 构建报错修复）**：首轮 CI 在 `:app:compileReleaseKotlin` 失败（3m37s），**94 条**错误全是同一处——
> `MiniIconButton` 的 `onClick` 被排在 `modifier` **前面**，而 47 处调用全用尾随 lambda
> （`MiniIconButton(icon, desc) { … }`）。Kotlin 的尾随 lambda 永远绑给**声明里最后一个参数**，
> 于是 lambda 被当成 `Modifier`：`No value passed for parameter 'onClick'` +
> `actual type is '() -> Unit', but 'Modifier' was expected`（含少量 `() -> String` /
> `() -> List<String>` / `() -> FormMapEdit` 等变体，都是同一个根因）。
>
> 修法：声明改为 `(icon, contentDescription, modifier = …, enabled = …, onClick)`——`onClick`
> 放最后，47 处调用一字不改。同时补上两道防回归：
> - `tools/check_trailing_lambda.py`（新增，纯文本检查）：扫 `custom/` 下所有「尾随 lambda 调用」，
>   要求被调函数声明的最后一个参数是函数类型；当前 **83 处调用全部匹配**，负测试（把 `onClick`
>   挪回中间）能精确列出 47 处站点并退出 1。工具的切分逻辑踩过两个坑并已修：`->` 里的 `>`
>   曾被当成泛型收尾把深度减成负数（导致漏报）、注释里的反引号/引号会带偏引号状态（先用
>   `blank_comments` 置空注释再扫）。
> - `tools/verify_app_patch.sh` 内联 bash 守护：检查 `MiniIconButton` / `DragSortRow` /
>   `rememberDragSortState` 的最后一个参数是不是函数类型（无 python3 时也生效）。
>
> 补丁与 `BASELINE.txt` 已按修复后的源码重新导出（`patch_sha256` 见该文件）；本条之外
> 的改动与三处交互的实现细节不变。
>
> **追加（2026-10-07 实机反馈二轮）**：真机上「往上/下自动翻滚会错位、拖动卡顿」（附 3 张
> 路由规则页截图），另外代理集合 / 规则集合列表的 ✎ 语义要改成「编辑所有配置」。前者是把
> 拖动排序整段换成参考实现的「列表内实时换位」（§5），后者见 §6 —— **§4 描述的「按实测
> 槽位差让位」与「中途写回保活」两条已被 §5 整段替换，不再是当前实现**。

基线未变：`upstream_commit=5e6743592b9c465eb015db7b05c588c50cd2b874`。参考实现是
mihomo_box 模块（release `mihomo-box-20261001-1620` 的 `webroot/ui/`：`js/core.js` /
`js/page-proxies.js` / `css/style.css`）；三处交互全部落在原生 Compose，不涉及 web 面板
（`custom/panel/` 保持既有移除状态）。

补丁重新导出：文件数 64 → **67**（`patches/app/BASELINE.txt` 的 `patch_sha256` 同步更新）。
新增 / 并入补丁的文件：

- 新增 `custom/forms/DragSort.kt`（拖动排序 + 小图标按钮工具）；
- `data/repository/MihomoRepositoryImpl.kt`（组延迟接口实现，此前未进补丁）；
- `ui/screen/overrides/SubscriptionOverridesScreen.kt`（订阅覆盖列表换拖动排序，此前未进补丁）。

## 1. 排序：所有排序入口换成「按住行首把手拖动」

对齐 `js/core.js` 的 `enableDragSort` 语义（`.drag-handle` 按下即拖、被拖行跟手、
其余行让位、越过中线定落点、松手一次性写回）：

- 新增 `custom/forms/DragSort.kt`：`rememberDragSortState(count, onMove)` / `DragSortRow` /
  `DragHandle`。把手是两列三点图形；被拖行跟手、其它行让出一行的空位；滚动容器（LazyColumn）
  带边缘自动翻滚（96dp 触发区），长列表也能拖到首尾。
- 接入 7 处列表：序列列表（代理组 / 代理 / 隧道 / 规则，`FlowFormPages.kt` 的 `SeqListPage`）、
  映射列表（`MapListPage`，映射无顺序、不给把手）、监听者列表（`ConfigFormPanel.kt`）、
  列表弹层（`FormDialogs.kt`）、DNS / 结构化编辑器（`P3FormEditors.kt`）、订阅覆盖列表
  （`SubscriptionOverridesScreen.kt`，删除原 `MoveButton` 与 `List<String>.swap`）。
- 「⋯」菜单式的上移 / 下移（`FormDialogs.kt` 的 `ItemMenuDialog`）整体删除；「在上方插入」
  随之下线——新增项直接拖到位，与面板「有把手就不给第二套排序入口」的取舍一致。

## 2. 编辑 / 删除：文字按钮换成图标按钮

对齐面板的 `.mini-btn`（小方块、淡底、图标居中）：

- 新增 `MiniIconButton`（`DragSort.kt`）：7 个文件共 47 处调用。行尾「编辑 / 删」文字按钮
  全部替换为 ✎ / × 图标按钮——序列 / 映射列表行、详情页行、锚点面板、DNS 列表、列表弹层、
  Provider 文件操作等。
- `custom/anchor/AnchorPanel.kt`：定义行 / 引用行的 ✎、× 图标化；卡片底部「定位 / 改名 /
  删除」改为三个等宽图标按钮（`MiuixIcons.Location / Rename / Delete`），删除按钮仍在
  有引用时禁用。
- 删掉不再被引用的 `override_move_up` / `override_move_down` 字符串（4 个语言共 8 条）。

## 3. 代理页批量测速：改为并发（对齐面板 `testGroupAll`）

对齐 `js/page-proxies.js`：先打组接口（内核内部并发），残余成员再用 8 路补齐；只有
「有回话」的成员写结果，没回话的不伪造：

- 新增组级接口 `MihomoApiClient.getGroupDelay(group, url, timeout)` →
  `GET /group/{name}/delay`（默认 `timeout=2000`，对应面板 `BATCH_TIMEOUT`）；
  `MihomoRepository` / `MihomoRepositoryImpl` 同步暴露。
- `ProxyViewModel.testGroupDelay()` 重写：
  ① 快路径一次组接口（整体预算 7s，对应 `GROUP_DELAY_BUDGET`）——固定选择的组
  （`isFixed`）跳过组接口，内核会清掉钉住的选择；② 组接口没回话的成员按
  `BATCH_CONCURRENCY=8` 逐节点补测（`BATCH_TIMEOUT_MILLIS=2000`）；③ 只在批次超过
  `PENDING_HINT_MILLIS=400ms` 才点亮「测速中」；④ 504 / 503「An error occurred in the
  delay test」判死，其余失败不判死；⑤ 单节点测速失败时退回组接口取值；⑥ `batchTesting`
  防重入锁与 UI 的 `testingGroups` 解耦（UI 状态要等 400ms 才亮）。
- 与面板的差异（有意）：不生成 `-1` 占位结果；延迟读回仍走 `loadProxies()` 与全局历史。

## 4. 修正（拖动排序）：自动翻滚不再错位，并补上参考实现的虚线描边

第一版把被拖行做成了「跟手漂浮」：位置 = 原槽位 + 累计手指位移。列表自身滚动时原槽位跟着
滚、而累计的手指位移不变，被拖行就沿滚动方向越飘越远 —— 这正是「往上/下自动翻滚时错位」
的来源；另外让位量按「被拖行高度」计算，忽略了列表 8dp 行距与逐行不同的行高，让位与落点
也会差一截。

现在改成参考实现的语义（`js/core.js` 的 `enableDragSort`：「列表内实时换位」「被拖行必须
即换即到位」）：

- **被拖行落在目标槽位上**：手指越过哪一行的中线就落到那一档，不再累计手指位移、不跟手
  漂浮 —— 没有累计量就没有翻滚漂移，所见位置就是松手后的落点。
- **让位量改用实测槽位坐标相减**（`tops[dest] - tops[here]`）：行距、行高不一都准确，
  让位后的行与落点严丝合缝。
- **手指窗口 Y 由把手窗口位置换算**（把手顶 + 把手内局部 Y），不再假设「手指正好按在
  把手中心」——落点判定的系统偏差一并去掉。
- **虚拟化容器保活**：LazyColumn 会回收滚出视口的行，被拖行滚出视口时（`recycleRows = true`，
  用在序列列表页与订阅覆写页这两处 LazyColumn）先把已拖过的这一段提交给宿主、下标前移到
  落点：让位量恰好等于新槽位差，所以**提交瞬间视觉位置不变**，长距离拖动时被拖行不会消失。
  代价是长距离拖动会分几次写回（每次一步，可逐次撤销）；不回收行的容器（弹层、DNS 列表）
  保持默认 `false`，不产生中途写回。
- **拖动中的行补上 `.rule-item.dragging` 的视觉**：整行 `opacity .55` + `--accent-soft` 淡底
  （12% 强调色）+ `1.5px dashed` 虚线描边（内缩 2dp，贴合卡片 16dp 圆角）+ 淡阴影
  `0 4px 16px rgba(52,130,255,.18)`。虚线框与淡底画在内容层之上/之下、保持满不透明度
  （参考实现是整行连边框一起降到 .55，那样虚线框会很难辨认）。
- **未移植**：参考实现给其余行的 0.26s FLIP 缓动（`cubic-bezier(.2,.8,.25,1)`）——我们的
  换位是瞬时的。因为拖动中会分次提交（见上条），动画在提交点必须瞬断，先保持与提交语义
  一致的瞬时换位；需要滑动观感再单独加。

## 5. 再修（拖动排序）：换成参考实现的「列表内实时换位」，错位与卡顿一起解决

实机表现（3 张截图，路由规则页 13 条规则）：上下边缘自动翻滚时，被拖的「RULE-SET 海外域名」
压在高行（多行 `AND (NETWORK,UDP),(PROCESS-NAME-REGEX,…)` 规则）身上、两行的文字与图标叠在
一起；拖动过程发卡。根因两条，都在上一版的做法上：

1. **让位量按「实测槽位差」算**（`slotShift(dest, index) = tops[dest] − tops[index]`）。行高相等
   时才等于「让一行」；行高不等时，被跨过的行只让出了**它自己那一格的高度**，而被拖行要占的
   是**它自己的高度**——两个高度不同就必然错位（被拖行越高 / 邻行越矮，压得越狠）。截图里
   正是「矮行跨过高行」这一侧。
2. **虚拟化容器保活做成「拖动中写回」**：LazyColumn 会回收滚出视口的行，上一版靠拖动期间调
   `onMove` 把已经拖过的距离先提交给宿主。每次提交都是一次文档改写 + 整页重组，自动翻滚
   期间会连续触发 —— 「拖动卡顿」的来源。

这一版不再「在原始布局上模拟让位」，而是把参考实现 `enableDragSort` 的两步原样搬过来
（`elementFromPoint` + `insertBefore`）：

- **命中判定**：手指窗口 Y 落在哪一行的身段里、在该行中线的上半还是下半 → 被拖行就搬到那一档
  （上半 = 换到它前面，下半 = 换到它后面）；手指落在被拖行自己身上 = 不动（不做判定，否则
  换位后手指还在原行身里，会被判成「又越过一行」来回抖）；指到行间缝 / 容器留白时按「离哪一行
  中线最近」兜底（与参考实现的留白投影命中同口径）。
- **列表内实时换位**：`DragSortState.order` = 「原始下标的排列」，命中即 `orderState.removeAt(cur)`
  + `add(at, item)`（`if (cur < at) at -= 1` 是 `insertBefore` 先摘行再插入的口径修正）。宿主按
  `order` 渲染，行内容与「编辑 / 删除」回调一律按原始下标取。换位就是真的换布局 —— **一行槽位
  差都不用算**，行长不等、8dp 行距、任意行高组合都不可能再算错。
- **卡顿一起消失**：被拖行永远在自己命中的那一档里（手指在屏幕内），容器不可能把它回收掉，
  于是「中途写回」连同 `recycleRows` 参数整套删除 —— 拖动过程中宿主数据一次都不动，只在松手时
  `onMove(from, to)` 写一次（`removeAt(from)` + `add(to)`，与 `YamlPatch.moveItem` 同口径）。
- **其余行补上参考实现的 FLIP 滑移**（§4 的「未移植」一条就此作废）：lazy 列表项用
  `Modifier.animateItem(fadeInSpec = null, placementSpec = tween(260ms, CubicBezierEasing(.2,.8,.25,1)),
  fadeOutSpec = null)` —— 与参考实现 `transform .26s cubic-bezier(.2, .8, .25, 1)` 同一条曲线，
  只给**真的挪了位置**的行；**被拖行不给动画**（参考实现原话：给它补滑移动画 = 不跟手，本移植
  用 [dragSortItem] 在拖动中把这一项排除掉）。非 lazy 的宿主（弹层里的 Column 列表）照旧瞬时换位。
- **边缘自动翻滚对齐参考实现**：`E = clamp(容器高 ×15%, 44dp, 88dp)`、`v = 1 + (depth / E) × 13`
  dp/帧（≈1~14px/帧），滚一帧立刻补一轮命中判定。
- 接线覆盖全部 7 处宿主：序列列表页 `items(dragSort.order, key = { it })`、订阅覆写页
  `items(dragSort.order, key = { displayed.getOrNull(it) ?: it })`、监听器列表 / 字符串列表对话框
  （行号列跟着档位重排）/ DNS 服务器列表 / fake-ip 规则列表 / DNS 取值列表 `order.forEach { … }`。
  拖动中的虚线描边视觉不变。

## 6. 修正（编辑按钮）：代理集合 / 规则集合的 ✎ 改为「编辑所有配置」

参考实现里列表行右侧的 `.mini-btn ✎` 是 `editSubSheet(name, cfg)` —— 打开**全字段**编辑弹层
（改名也在弹层里，`pages-flow.js:772/840`）。本移植的「全字段编辑器」是详情页，所以：

- `MapListPage` 行尾 ✎ → `onOpen(name)`（与点整卡同效，进详情页改全部字段）；详情页顶部本来
  就有 ✎ 改名（会同步 `proxy-groups.use` / `RULE-SET,名字` 引用）与 × 删除，改名入口不丢。
- 代理集合、规则集合两页不再往列表页传 `renameNote` / `onRename`（参数改成可空）；子规则没有
  详情页，额外保留一个「改名」图标按钮（`MiuixIcons.Rename`）。

## 7. 修复（编译）：SubscriptionOverridesScreen 漏 `import dragSortItem`

CI `:app:compileReleaseKotlin` 报 `Unresolved reference 'dragSortItem'`
（`ui/screen/overrides/SubscriptionOverridesScreen.kt:181`）。调用点本身是对的：该文件
（`ui.screen.overrides` 包）引用了 `custom.forms` 的 `dragSortItem`，但 import 只补到
`DragSortRow` / `DragSortState` / `rememberDragSortState`，漏了它——`FlowFormPages` 与
定义同包所以不需要 import，全树只有覆写页这一处会报。补上
`import top.yukonga.mishka.custom.forms.dragSortItem` 即可。

防回归：新增 `tools/check_cross_package_imports.py`（解析 `custom/**` 顶层声明 →
逐文件检查「引用了该符号却没 import」），并挂进 `verify_app_patch.sh`。已双向自测：
当前全树 0 误报（166 个符号 / 17 处跨包引用）；把这一行 import 删掉，检查器能精确报出
同一处缺失。

## 8. 三修（拖动排序）：往上拖时列表自己上滑、拖动被打断 —— 指尖位置不再累加局部位移

症状（2026-10-07 10:18，路由规则页）：**往上拖动排序时列表自己上滑（内容向前滚）并打断拖动排序**；
截图里列表停在滚动末尾、首行压在标题栏下，并弹出「已移动 RULE-SET 海外域名」。

根因：`DragSortState` 曾用 `PointerInputChange` 的**节点局部位移**（`dragAmount.y`）累加 `fingerY`，
而 `position` 是相对「把手节点」的坐标 —— 拖动中这个节点自己在动：
1. 被拖行「即换即到位」（换位不给动画，参考实现的口径）：换一次位节点就**整行高瞬跳**，
   局部坐标跟着跳 —— 手指没动，`dragAmount.y` 却是 ±一整行高；
2. 边缘自动翻滚让内容在指下滚动，每帧再注入 ±1~14dp 的假位移。

于是每越过一行 `fingerY` 就被带偏一整行：命中判定立刻落到被拖行身后那一行、把刚换上去的位置
又换回来 —— 来回抖，正是用户说的「拖动被打断」；偏出去的 Y 落进上/下边缘带还会触发**误翻滚**，
列表自己滚起来，就是「触发上滑」。松手时按抖动的最后一格提交 `onMove`，于是有了那张截图
和那条「已移动 RULE-SET 海外域名」提示。

修法：指尖位置改为**每次事件当场换算成窗口坐标** —— `handleModifier` 记下把手节点的
`LayoutCoordinates`，`fingerWindowY()` 返回 `positionInWindow().y + change.position.y`
（节点已解绑 / 还没量过时退回最后一次 `onGloballyPositioned` 记下的窗口顶），`dragTo()`
每次**覆写** `fingerY` 而不是 `+=`。这与参考实现全程只用 `e.clientY` 同口径：行在视口里怎么挪、
内容怎么滚，都不影响指尖位置，误差没有再累积的通道。参考实现踩过同一类坑：它 pointerdown 时
特意从活动指针重新播种 `pendX/pendY`，注释原话是「否则边缘翻滚用的是残留 / 0 的 Y → 点一下
把手页面就滚」——两种表现、同一个错，这次一并掐掉。

防回归：`DragSort.kt` 里**不允许再出现 `dragAmount`**（verify 用 `assert_not_contains` 钉住），
并断言 `fingerWindowY` 存在、每次事件读实时 `positionInWindow()`、`onDrag` 接线为
`dragTo(index, change.position.y)`；另补 2 条 import 断言 —— `positionInWindow` 与
`LayoutCoordinates` 是 `androidx.compose.ui.layout` 的**包级扩展函数 / 类型**（不是接口成员），
漏 import 会重演 §7 那类 CI `Unresolved reference`。

## 9. 四修（第一次尝试）：把手先独占指针流

初步判断是把手与 `LazyColumn` 同时等待 touch slop；因此把 `detectDragGestures` 换为
`awaitEachGesture`，在 Main pass 消费 DOWN / 移动，并把取消从「正常 drop」改成回滚。

**后续复测仍报告上拖会打断排序**，说明仅靠消费事件并不足以隔离列表滚动；本节只记第一次尝试，
不能视为问题已解决。补充硬隔离见 §10。

## 10. 五修（复测后）：拖动时禁用列表触摸滚动，并清除离屏命中坐标

路由规则页实际是 `LazyColumn`，它的 `userScrollEnabled` 此前一直为默认 `true`。把手消费事件不能作为
唯一防线：拖动仍与列表的滚动 / nested-scroll 管线处于同一容器。此次复测后的修正是在整个把手指针生命期
（按下到抬起 / cancel）显式关闭排序宿主的用户触摸滚动；拖动中的边缘翻滚仍由 `dispatchRawDelta` 作为
程序化滚动执行，因此只保留排序器主动发出的滚动，不再让同一手指同时触发列表上滑。

同时修正一个 LazyColumn 坐标缓存风险：已回收条目的 `tops` / `heights` 之前会残留，自动翻滚时可能被当作
仍可见的落点。Lazy 行离开组合时现在清除缓存，命中只考虑与容器视口相交的行；拖动项已到首 / 尾时停止
继续向该方向自动翻滚。覆盖路由规则序列列表和订阅覆写序列列表。

防回归断言新增：拖动全程 `userScrollEnabled = !dragSort.handleGestureActive`、离屏坐标不参与命中、Lazy 行回收
会清除几何缓存，以及活动项到首尾后停止自动滚动。

## 验证

- §8 的 **146 条 ok / 0 FAIL** 和独立副本比对是此前版本的记录；§9 的第一次手势捕获也未能解决实机反馈。
- 本轮更新了补丁、`BASELINE.txt` 中的 patch SHA / 源码 blob；在上游基线 commit `5e6743592b9c465eb015db7b05c588c50cd2b874` 上运行
  `verify_app_patch.sh --repo` → **PASS**：补丁可应用、反向可还原，67 个文件与基线 blob 一致；新增指针捕获 / 滚动硬隔离 / 离屏坐标断言通过。
- 本轮没有运行 Gradle 编译或设备复测；请安装新补丁后实机验证。若仍复现，请发该版本的最新录屏——此前录屏无法判断禁用
  LazyColumn 用户滚动后是否还会出现同一种中断。
- `tools/check_cross_package_imports.py` 跨包 import 全树检查继续挂在 verify（历史自测 0 误报；删掉 §7 的 import
  可精确复现此前 CI 报错点）。

- 上一版因沙箱内存限制未跑通 `:app:compileDebugKotlin`；本轮也未在设备上实测。
- 三处交互与 2026-10-06 已交付的改动（file provider 上传 / 编辑、内置出站豁免、
  `TetherInterfaceEditDialog`、候选池过滤等）已逐文件核对合并，13 个改动文件全部落在
  10-06 基线上，无遗漏、无冲突残留。

# 上一轮改动（2026-10-06）

基线：`upstream_commit=5e6743592b9c465eb015db7b05c588c50cd2b874`（未变）
补丁：`mishka-custom/patches/app/0001-anchor-panel.patch`（已重新导出）

## 1. 隐藏不可用：排除 mihomo 内置策略

`代理组` 页的「隐藏不可用」按 `delay == -1`（healthcheck 超时）过滤节点。mihomo 的内置
出站 `DIRECT` / `REJECT` / `REJECT-DROP` / `PASS` / `COMPATIBLE` / `GLOBAL` **从不参与拨测**，
`history` 里的延迟恒为 0，于是被一并判定成「超时」——一开开关它们就整组消失，偏偏这几个是
任何配置都得留着的兜底出口。

改动：`ProxyViewModel.kt` 新增 `isBuiltInOutbound(name, type)`，`ProxyScreen.kt` 的过滤条件
改为「内置出站 **或** delay != -1」，内置出站一律豁免，只滤真正测不通的节点。

**判定只按名字走**，类型仅作兜底：`/proxies` 的 `type` 在内置出站上经常拿不到（`GLOBAL` 的
类型是 `Selector`，与用户自建的代理组无从区分；provider 视角下也可能为空）。之前那版如果挂在
类型上，就会表现为「改了没生效」——名字是唯一在每条数据路径上都存在的信号。

## 2. 锚点面板：编辑 / 删行 之间补间距

miuix 的 `BasicComponent` 把 `endActions` 直接塞进一个**没有 arrangement 的 Row**，两个实心
按钮各带 12dp 内边距，贴在一起时看着像一整块胶囊、也容易误触。

改动：`AnchorPanel.kt` 在「编辑」与「删行」之间插入 `Spacer(Modifier.width(RefActionGap))`，
取 8dp，与卡片底部「定位 / 改名 / 删除」那一行的 `spacedBy(8.dp)` 对齐。锚点卡片与悬空引用
卡片两处同步。

## 3. 新增：外部面板（Web 界面），移植自 box.app

主页「工具」区多一个**面板**入口，点进去是一个承载 mihomo 面板的 WebView。按你回的
「与 box.app 一致」，清单与取值都照 box.app 来：

| 内置面板 | 地址 |
| --- | --- |
| 本地 | `http://<external-controller>/ui`（端口取运行时 `ProxyServiceBridge`，只在代理 Running 时才算得出；停了退回上次缓存的地址） |
| Zashboard | `http://board.zash.run.place` |
| MetaCubeXD | `https://metacubex.github.io/metacubexd` |

自定义面板可增删，存在 SharedPreferences `panel_cache`（与 box.app 同名，不上 Room——
就几行展示偏好，丢了大不了重填）。清单从顶栏右二的图标打开。

**不代填控制器地址与密钥**：box.app 也是让面板自己在首次进入时问一次并存 localStorage，
所以这里不拼 `?hostname=&secret=`。各家面板对这两个 query 的支持并不一致，代填错了
比不填更难排查。

新增文件（均在 `app/src/main/kotlin/top/yukonga/mishka/custom/panel/`）：

- `PanelEntry.kt` / `PanelStore.kt`：条目模型 + 持久化
- `PanelWebView.kt`：WebView 封装。三个不能省的点——
  1. `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`：面板多为 https 站点，控制器在
     `http://127.0.0.1:<port>`，默认值会把面板调 API 的请求全掐了，表现为「页面能开、
     数据全空」且只在 logcat 里留一行。
  2. 切面板时用 `key(sessionKey)` 整个重建 WebView，只 `loadUrl` 会带上一家的登录态。
  3. 面板的「导出配置」是 `URL.createObjectURL` + 点隐藏 `<a download>`，WebView 的
     DownloadListener **收不到 blob:/data:** 地址，所以注入了一段 JS 改成
     fetch → base64 → JS bridge → SAF 存盘；真正的 http 下载仍走 DownloadListener。
- `PanelSheet.kt` / `PanelScreen.kt`：面板清单底栏 + 页面壳（刷新 / 清缓存 / 返回）

系统返回键在网页还能后退时先给网页（`NavigationBackHandler`，`isBackEnabled = canGoBack`），
退到底才退出页面；这一屏因此关掉了横滑返回（`NavSwipeDirection.None`）——边缘侧滑走的是
同一条 NavigationEvent 分发，会被子级 BackHandler 截走变成「网页后退」，看着像卡住。

顶栏的「清除面板数据」= WebView 缓存 / Cookie / Storage / 表单 / SSL 偏好全清，面板里
保存的设置与登录态会一起没，弹窗确认后再执行。

## 4. 修 CI 首轮报错（上一版打包后的构建日志）

日志：`app/src/main/res/values/strings.xml:142` 合并资源时挂掉，
`panel_clear_cache_message` 里那个 `panel's` 的**裸撇号**没转义，AAPT2 报
「Invalid unicode escape sequence」。已改成 `panel\'s`（与仓库里既有的
`external_control_controller_hint` 同一写法）。四个语言共 17 条面板文案重新扫过一遍，
没有别的裸 `'` / `&` / `<`。

顺带把这一版真正跑了一次类型检查（见下），又抓出两个必炸的编译错误，都已修：

- `PanelWebView.kt` 少 `import androidx.compose.runtime.getValue` —— `var x by remember { mutableStateOf(...) }`
  读取走的是 `State.getValue` 扩展，只导 `setValue` 不够，委托直接解析失败。
- `PanelSheet.kt` 清理未用 import 时把 `Column` 一起删了，而外层容器还在用。

## 5. 外部面板顶栏对齐 box.app（浅色单行）

此前面板页顶栏走 miuix `AdaptiveTopAppBar`：手机上是**深色大标题两行**布局（标题 32sp
独占一行），返回用 `Back` 箭头、清单入口是 `GridView` 田字格，配色随 App 主题——App
深色主题下面板内容是浅色网页，顶栏却是一整块黑色，和 box.app 的浅色顶栏放一起违和。

按 box.app 截图实测像素复刻（1440px 宽、density 3.5，图标槽位两图本来就重合）：

| 项 | box.app 实测 | 现在 |
| --- | --- | --- |
| 底色 | `#F7F7F7`，铺到状态栏后面 | 同 |
| 前景 | 纯黑 `#000000` | 同 |
| 布局 | 单行：`‹` 返回 / 标题 / 刷新 · 滑杆 · 清除 | 同 |
| 返回图标 | 细尖角 chevron | `MiuixIcons.ChevronBackward` |
| 清单图标（右二） | 双横线滑杆（上线钮偏右、下线钮偏左、空心圆钮） | `MiuixIcons.Tune`（路径逐段核过，与截图同形） |
| 标题 | 18sp 常规字重、左对齐（距返回槽 12dp）、单行省略 | 同 |
| 图标槽 | 40dp、外沿 16dp | 同（miuix `IconButton` 默认值，实测即 40dp 节距） |

改动落在 `PanelScreen.kt`：弃用 `AdaptiveTopAppBar` 与 `MiuixScrollBehavior`，新增私有
`PanelTopBar`（`#F7F7F7` 底 + 56dp 标题行，WindowInsets 处理与 miuix 顶栏同款）。
标题仍取网页 `document.title`（如「127.0.0.1:9090 | 代理」），取值逻辑不变。
**顶栏不随 App 深浅色主题走，恒为浅色**——这是与 box.app 浅色顶栏「一致」的直接要求。

**状态栏图标跟着顶栏走**：顶栏铺到状态栏后面，App 深色主题下白色图标会直接消失在
`#F7F7F7` 上。新增 `custom/panel/PanelChrome.kt` 持有 `forceLightStatusBars` 开关，
`MainActivity.enforceSystemBarsAppearance` 尊重它。为什么不能只在进页面时改一次：
MainActivity 在 `onWindowFocusChanged` / `onResume` / 配置变化 / 主题 recomposition 时
都会重排系统栏外观，面板页自己的「清除数据」确认框一关（弹窗收回焦点）就会把图标
翻回白色。进面板置 true，退面板复位并按当前主题恢复。

## 6. 修「返回重进面板页闪烁、卡片缺失」

**症状**：每次返回退出外部面板、再重进，页面整块白闪一次，代理卡片要重新冒，偶尔像缺卡。

**根因**：返回会把 `AndroidView` 连同 WebView 一起从组合里拆掉，重进再 `WebView(ctx)` + `loadUrl`
冷启动一次——白闪就是重载的空白帧，「卡片缺失」是面板 SPA 重新初始化、重新拉数据的
中间态。另外 `refreshing` / `webError` 用 `rememberSaveable` 跨离开保存，重进时可能恢复出
一个转不完的圈（加载结束事件在离开期间已经发给死组合了）和滞留的错误横幅。

**改动**（`PanelWebView.kt` / `PanelScreen.kt` / `PanelChrome.kt`）：

1. **WebView 实例跨「返回 / 重进」保留**（新增 `PanelWebViewCache`）：退出面板页只拆视图树，
   重进把**同一个实例**挂回去——不重载、不白闪、面板 SPA 的路由 / 卡片 / 滚动位置原样在。
   只有两个销毁点：切面板（`sessionKey` 变，防串 history / 登录态）与 Activity 销毁
   （`ActivityLifecycleCallbacks` 盯 `recreate()` / finish，外加 acquire 时 context 判活兜底）。
2. **复用实例绝不再 `loadUrl`**：`factory` 只对新建实例加载；URL 变化 / reload / goBack 仍由
   `update` 里的 tag 差值判定兜着。
3. **每次组合重新接线**：WebViewClient / WebChromeClient / JS bridge / 下载监听的闭包都指向
   「当前组合」的回调与 `rememberLauncherForActivityResult`。复用实例若只在 factory 挂一次，
   重进后标题、错误横幅、转圈、SAF 存盘全会失联（收到的是上一次进页面那批死 lambda）。
4. **入场对齐**：重进后从 WebView 现值读回标题 / `canGoBack`（离开期间页面可能已变），
   快照过期不再显示错。`refreshing` / `webError` 改成不跨离开保存的 `remember`。
5. **页面暂挂**：离开面板页 `WebView.onPause()`（只停 DOM 定时器 / 动画，加载与 WebSocket
   不受影响），重进 `onResume()`——SPA 留在后台不该一直烧电。

## 7. 顶栏颜色改用主题色；重进固定回面板首页

用户反馈两点：① web 界面顶栏颜色没有使用主题颜色（固定 `#F7F7F7`，深色主题下白得刺眼、
和 App 其它页面脱节）；② 要求重进总是回面板首页，而不是停在上次浏览到的子页。

**改动**：

- `PanelTopBar` 颜色全部改从 `MiuixTheme.colorScheme` 取：底色 `surface`、标题与图标
  `onSurface`——和 `AdaptiveTopAppBar` 同一套 token，深浅色主题自动跟随（box.app 的
  单行布局与像素间距保留）。删掉 `#F7F7F7` / 纯黑常量。
- 状态栏不再强制深色图标：删掉 `PanelChrome.forceLightStatusBars` 整套机制（PanelScreen
  的挂钩 / `MainActivity.enforceSystemBarsAppearance` 里的 `||` / 整个 `PanelChrome`
  object），外观交给 MainActivity 按主题 enforce——顶栏已随主题，状态栏图标天然配套。
- **重进固定回面板首页**：新增 `WebView.goHome(homeUrl)`——优先 `history.go()` 一步退回
  历史里的入口项（SPA 走 popstate 把路由切回首页，**不整页重载、不白闪**）；历史里找不到
  入口项（整页刷新截断过历史、入口被服务器重定向到别处等）才退化为整页加载入口，
  保证「回首页」永远成立。复用实例在 factory 挂回视图树时调用。
- 顺手修一个遮蔽 bug：`factory` 里 `webView.apply { loadUrl(url) }` 的 `url` 会被
  WebView 自己的 `getUrl()` 属性遮蔽，整段加载逻辑实际一直空转（页面能载全靠 `update`
  差值兜底）。入口 URL 改为先取 `entryUrl` 再用，「只有新建实例才整页 loadUrl」从此名实相符。

## 8. 返回进入逻辑对齐 box.app（新实例 + 入口加载，预热器秒建）

用户要求照 https://github.com/MiChongs/box.app 的 web 界面返回进入逻辑实现。box.app 的机制
（`AppScaffold.openSubpage/exitSubpage` + `ThemedWebView`，已逐行核对）：

- **进入**：`openSubpage` 清零 `backRequestKey` / `canGoBack` → **新 WebView + `loadUrl(入口 URL)`**
  —— 每次进都是面板首页；
- **返回**：系统返回先退页内历史（`backRequestKey += 1` → `goBack`），退无可退才退出页面；
  顶栏返回键直接退出；
- **退出**：WebView 实例**不保留**；重建靠 `WebViewPreloader`（两阶段预热：后台线程触发
  Chromium provider 加载 + 主线程空闲预建实例）把 `new WebView()` 的 ~500ms 冷启压到近 0；
- **配套**：`resetHistoryOnUrlChange` —— 换 URL 加载完 `clearHistory()`，返回不会走进
  上一个面板的历史。

**本版改动**（§6 的「实例保留 + goHome 历史回退」方案按要求整体换成 box.app 方案）：

- 删 `PanelWebViewCache` 与 `WebView.goHome`；新增 `WebViewPreloader`（box.app 同款），
  `MainActivity.onCreate` 调 `preload`；
- factory 每次进入 `WebViewPreloader.take() ?: WebView(ctx)` + `loadUrl(入口 URL)`——
  重进总是面板首页，天然成立；
- `onRelease` 销毁实例（box.app 是丢给 GC，这里顺手 destroy 防泄漏，对外行为一致）；
- 换 URL（切面板）加载完 `clearHistory()` + 回报 `canGoBack = false`
  （`resetHistoryOnUrlChange` 等价）；
- `canGoBack` 改为不跨进入保存（等价 box.app `openSubpage` 清零）；返回分发保持与 box.app
  相同（系统返回页内历史优先、顶栏返回直接退出）；
- 删 `PanelChrome.kt`（`findActivityOrNull` 随缓存移除失去用途），补丁文件数 72 → 71；
- **行为说明**：重进会重新加载面板入口（box.app 原生行为，§6 的「重进保留页面状态」就此
  撤销），加载期显示 WebView 底色。

## 9. 修「重进仍闪烁、卡片缺失」：重进不整页重载（box.app 语义 + 实例保留实现）

用户问为什么返回重进还是闪烁和卡片缺失。**根因就是 §8 的方案本身**：box.app 的返回进入
实现是「每次进入 = 新 WebView + `loadUrl(入口)`」，即**重进整页重新加载**——闪烁=重载空白帧，
卡片缺失=面板 SPA 重新初始化拉数据的中间态。box.app 重进面板同样如此，它并没有解决这两个
症状（`WebViewPreloader` 只压掉 WebView 构造耗时，压不掉页面加载）。另外 §7 的 goHome 用
**精确 URL 匹配**找历史入口项，面板服务器一重定向（`…/ui` → `…/ui/`、补 `#/`）就匹配不到、
退化成整页重载——这是当时仍见闪烁的另一半原因。

**方案**：box.app 的返回进入**语义**保留（进入=面板首页、系统返回先退页内历史、退无可退退出
页面），但实现改为**实例保留 + 重进不重载**——这是同时满足「重进=首页」和「不闪、卡片不丢」
的唯一解：

- 恢复 `PanelWebViewCache`：WebView 跨「返回 / 重进」保留复用，重进**绝不**整页 load；
  销毁点仍只有切面板（sessionKey）与 Activity 销毁（ActivityLifecycleCallbacks 盯 recreate）。
- `goHome()` 改为**按历史深度回退** `history.go(-currentIndex)`：一步退回会话第一个入口项，
  SPA 走 popstate 切回首页——不重新下载、不白闪、卡片在内存里原样在。不再做 URL 匹配
  （重定向使入口项实际 URL 与配置串对不上是 §7 失败的原因）；跳过开头可能残留的
  `about:blank` 项。
- `doUpdateVisitedHistory` 补上 canGoBack 转发（公开方法名就是 `do*`，不是 `on*`）：
  SPA 的 pushState / popstate 不触发 `onPageFinished`，没有它「系统返回先退页内历史」
  在面板子页里永远不生效（box.app 漏了这个回调，其返回分发的意图是历史优先）。
- 保留 §8 引入的配套：`WebViewPreloader`（新实例秒建）、换 URL 加载完 `clearHistory`、
  `canGoBack` 不跨进入保存、顶栏返回直接退出 / 系统返回历史优先。
- `onRelease` 回到「暂挂」语义（`onPause()` 停 DOM 定时器），不再销毁。

## 10. 修「杀后台重进：第一次进正常、返回再进闪烁」

用户报告：杀后台重进 App，**第一次**进 web 界面正常，返回后再进就闪烁复现。

**根因**：`WebViewPreloader` 按 box.app 用 `applicationContext` 预建 WebView，而
`PanelWebViewCache.acquire` 的复用判定拿 `webView.context.findActivityOrNull()` 反查宿主
Activity——app context 反查**永远是 null**，被判成「宿主已死」→ 销毁好实例 →
`WebViewPreloader.take()` 此时已是一次性取空 → 冷建 + `loadUrl(入口)` = 整页重载闪烁。
第一次进恰好是纯加载（用户预期之内）才显得正常；每次杀后台测试都固定复现「一进正常、
再进闪烁」。

**改动**（`PanelWebView.kt`）：

- 复用判活改为 **acquire 时记下的宿主 Activity 身份**（`cachedHost: WeakReference<Activity>`，
  `cachedHost?.get() === host`），watcher 的销毁匹配同步改身份比对；不再拿
  `webView.context` 反查（预热器的 app context 实例从此可正常复用）。
- 连带修 pop 式导航重进的差值误触发：重进时 `reloadKey`/`backRequestKey` 是新零值，
  上一次会话留在 tag 里的旧值会误触发 `reload()`/`goBack()`——复用分支现在把 tag 对齐
  当前键值。
- 重进没有任何加载事件，标题会卡在占位符：复用分支 `post` 从实例现值同步一次标题。

## 11. web 面板整体重做：整套照搬 box.app `ThemedWebView`（防白闪 = hideUntilCommitVisible）

用户反馈修完仍闪烁，要求「完全去除 web 面板，参考 box.app 重做」。§7-§10 在「实例保留 /
goHome / 判活修复」里兜圈，始终没搬 box.app 真正的防闪机制——**`hideUntilCommitVisible`**：
内容提交（`onPageCommitVisible`）前 WebView 保持隐藏、只露底色，加载期永远不会露出白帧。
本轮把 `PanelWebView.kt` 整文件推倒，按 box.app `ThemedWebView` 逐块重写：

- **进入**：每次进入都是新 WebView（`WebViewPreloader.take() ?: WebView(ctx)` 秒建）+
  `loadUrl(入口 URL)`——重进总是面板首页（box.app 进入逻辑）；
- **防白闪**：`hideUntilCommitVisible = true`（组件默认；box.app 自带的三个 web 页传 false，
  但这个机制就是为去白闪而生，我们启用）——内容提交前隐藏 WebView 只露底色，提交才露真身；
- **返回**：系统返回先退页内历史（`backRequestKey` → `goBack`），退无可退退出页面；
  `doUpdateVisitedHistory` 转发 canGoBack（box.app 漏了这个回调）；
- **换面板 / 换深浅色**：`key(isDark, sessionKey)` 重建实例（box.app 同款）+
  `resetHistoryOnUrlChange` 加载完 `clearHistory()`；
- **退出**：`onRelease` 销毁实例（box.app 丢 GC，这里 destroy 防泄漏，对外行为一致）；
- 外层容器手势防抢（`requestDisallowInterceptTouchEvent`）、不透明底色等 box.app 细节一并照搬；
- **删除 §6-§10 的全部保留型机制**：`PanelWebViewCache` / `goHome` / `cachedHost` 判活等。

与 box.app 的三处差异（都为修 bug，行为只强不弱）：`doUpdateVisitedHistory` 转发；
http(s) 导航交回 WebView 保留 POST（box.app 一律 `loadUrl` 重放会丢 POST）；
`onRelease` 里 destroy。

另：本轮开工时工作区快照曾损坏（Mishka 的 .git/源码、补丁目录、kotlin-compiler 与若干
依赖 jar 丢失），已从成品 zip、上游基线 `5e6743592b9c` 与 Maven / Google Maven 全量恢复，
verify 复核通过后再动的代码。

## 12. 完全移除 web 界面（外部面板）

用户要求「完全移除 web 界面」。§3-§11 的外部面板（Web 界面）功能整体删除，其余改动
（锚点编辑间距、隐藏不可用策略、root/运行时配置、可视化表单等）原样保留：

- 删 `custom/panel/` 整包（PanelEntry / PanelScreen / PanelSheet / PanelStore /
  PanelWebView，含 box.app 版 WebView 与全部返回进入逻辑）；
- 删 `Route.Panel` 路由与 AppNavigation 的 `entry<Route.Panel>` 接线；
- 删首页快捷入口「面板」卡片（QuickEntriesSection / HomeScreen 的 `onNavigatePanel`
  链路随卡片一并移除——这两个文件回归上游原样，移出补丁）；
- 删 `MainActivity` 的 `WebViewPreloader.preload` 预热调用；
- 删 4 个语言共 17 条面板字符串（`home_panel*` 与 `panel_*`）；
- verify 的面板断言（23 条）全部移除，改为 7 条「web 面板已彻底移除」防回归断言
  （导航 / 快捷入口 / 4 语言字符串 / `custom/panel/` 目录不存在）。

补丁文件数 71 → **64**（-5 面板包、-2 回归上游的首页文件）。

## 验证

- `mishka-custom/tools/verify_app_patch.sh --repo <仓库>` → **PASS**：补丁双向可逆，
  应用结果与 `BASELINE.txt` 的 64 个 blob 逐文件一致（运行时配置 / root / 表单 / 隐藏不可用
  断言 26 条 + 「web 面板已彻底移除」防回归断言 7 条）。
- **Kotlin 类型检查**：历史轮次的面板文件类型检查已随 web 界面移除而失去对象（kotlinc 环境
  曾对 `custom/panel/` 全部文件查过 **0 错误**）；本轮改动全为删除与参数清理，无新增代码路径，
  唯一保留的代码改动是 `Route.FileManagerEditor` 的 `showConfigForm` 等既有功能，不受影响。
- `BASELINE.txt` 的 `patch_sha256` 随新补丁更新（完整值见该文件）。
- 沙箱内存只有 2GB，`:app:compileDebugKotlin` 跑不动，**本次没有跑通 Gradle 编译验证**。
  落地后请先跑一次（本轮是纯删除，重点看有没有漏删的引用导致编译不过）：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```
