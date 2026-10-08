# 新功能：字段整理按钮 + DNS 列表拖动排序 / 路由规则序号 + 批量测速对齐

补丁：`patches/app/0004-field-tidy-and-list-parity.patch`（在 0001、0002、0003 之上应用；`scripts/setup.sh` 的步骤 5d 已接入）。

手动应用（仓库已应用 0001–0003）：在仓库根目录执行 `git apply <本包路径>/patches/app/0004-field-tidy-and-list-parity.patch`。

自检：`bash <本包路径>/tools/verify_app_patch.sh --repo <仓库> --series`（0001–0004 整序列的 sha256、断言、可逆性）。

---

## 1. 配置表单按钮左侧新增「字段整理」

**需求**：对齐 mihomo_box 配置页源码工具条上的「整理配置字段顺序」——把字段（键）顺序一键换成官方顺序，注释、空行、块式 / 流式写法、锚点标记全部原样保留。

**参考实现**：`webroot/ui/js/core.js` 的 `tidyMihomoConfig`。规则与它逐条一致：59 个顶层键 / 17 个内层键的官方顺序；锚点块提到最前；`<<` 合并键排在 -1（proxy-groups 里是 0.5）；未知键 rank 999 且保持相对顺序、排在最后；块与块之间的空行只在「多值块 / 上一块是多值 / 刚离开锚点区」时插入；开头注释进头部、结尾注释留在尾部；结尾换行习惯保留。

**实现**：新增 `custom/forms/ConfigTidy.kt`（`internal object ConfigTidy`，约 740 行）：

- `tidy(text): String` —— 纯文本进、纯文本出，不依赖编辑器；
- `apply(source): Outcome` —— 解析 → 整理 → 与原文逐字节比较（相同即 `Unchanged`，不写回）→ 重新解析 → 顶层键集合（排序后）比对，一致才 `Tidied`，否则 `VerifyFailed`；
- `SourceInvalid(detail)` —— 源码有 YAML 语法错误时只提示、不改动。

按钮在 `ui/screen/settings/FileManagerEditorScreen.kt` 里摆在「配置表单」（Tune）**左侧**，只在 YAML 配置上出现；改动只落编辑器草稿，保存仍走顶栏 Ok（内核校验 + 失败回滚），与配置表单 / 锚点面板同一套约定。

四类提示（与参考实现同文案）：

| 结果 | 提示 |
| --- | --- |
| 已整理 | `✅ 已按官方规范整理配置字段顺序` |
| 本来就是官方顺序 | `配置字段顺序已符合官方规范` |
| 源码语法错误 | `源码存在 YAML 语法错误，请先修正后再整理`（长提示） |
| 整理后验证失败 | `整理后验证失败：<detail>`（长提示） |

**与参考实现唯一（有意）的差异**：换行符。参考实现按 `\n` 切分、再按 `\n` 拼回，CRLF 文件整理后会出现裸 `\r` 与裸 `\n` 混排的换行；Kotlin 版保留原文的行尾符（CRLF 仍是 CRLF，不产生混排换行）。配置内容与字段顺序完全一致，对拍时按「去掉 `\r` 后逐字节一致」比对，并单独断言 CRLF 输出里没有裸换行。

**对拍工具**：`tools/tidy/`（`README.md` 有口径说明）。`tidy-ref.mjs` 是从 `core.js` 原样抽出的参考实现（只去掉 `export` 前缀），`check_tidy_parity.sh` 用同一批输入跑两份实现、逐字节 diff：

```bash
tools/tidy/check_tidy_parity.sh --repo <Mishka 仓库> [--kotlinc <kotlinc 路径>]
```

## 2. DNS「按域名分流解析」拖动排序 + 路由规则数字序号

**需求**：补上 mihomo_box 可视化编辑器里已有的两处列表能力——maplist 每行按住把手拖动排序；路由规则列表每行带序号。

**参考实现**：`fields.js` 的 `mapListEditor` + `reorderConfigMap`（maplist 行 = 拖动把手 + 键 + 值 + ✎/×，把手拖动即换位）；`pages-flow.js` 的路由规则列表每行是 `拖动把手 + .rule-no 序号 + 规则文本 + ✎/×`，`.rule-no` 的样式在 `style.css`：12px / 650 字重 / 居中 / min-width 22px / 圆角 6 / 淡底（`--fill`）/ 等宽数字。

**实现**：

- `P3FormEditors.kt` 的 `MapListFieldDialog` —— DNS 的 `nameserver-policy`、`proxy-server-nameserver-policy`（以及其它 maplist 字段）都走这个对话框，现在换成 `DragSort.kt` 的拖动排序：`rememberDragSortState` + `containerModifier { dispatchRawDelta }`（拖动中外层不参与滚动仲裁、边缘自动翻滚）+ `verticalScroll(enabled = dragging < 0)` + `DragSortRow` / `DragHandle`；行尾仍是 ✎ 编辑 / × 删除（`BasicComponent(endActions)`）。拖动中按实时顺序渲染、松手才把最终档位落进 `rows`，提交时 `onConfirm(rows)` 一次性写回——与其它列表页一致。
- `FlowFormPages.kt` 的 `SeqListPage` 增加 `numbered` 开关：把手右侧一个 22dp 序号胶囊（12sp SemiBold 等宽、圆角 6、`surfaceContainerHigh` 底、`onSurfaceVariantSummary` 文字），序号 = 第几条（1 起）。**只给顶层「路由规则」页开**（`numbered = seqPath == TOP_RULES_PATH`）：参考实现里 `.rule-no` 只出现在那一个页面；「子规则」在参考实现里是「子规则集合」maplist（键 = 名称、值 = 规则文本行），没有逐行序号，所以子规则列表跟着不显示序号。

## 3. 代理页整组测速与 mihomo_box `testGroupAll` 逐条对齐

**参考实现**（`page-proxies.js` 的 `testGroupAll`）：先收集成员——`isProxySpeedTestable` 过滤掉 REJECT / BLOCK / PASS / DNS 这类内置策略（DIRECT / COMPATIBLE 照测）；**「隐藏不可用」筛掉的节点照样要测**（它们当初就是因为不通才藏起来，不测就永远不知道是否恢复）；正在单测的节点跳过。然后①组接口一次测完整组（内核内部并发，前端只有一个请求在飞），返回的是**逐成员结论**：`>0` 直接显示、`0` 是内核明确测过但不通 → 直接显示失败、**不再丢进补测**；②只有组接口没作答的成员（嵌套组等）进 8 路并发池，每路出结果**当场**回写；③400ms 后仍未作答的可测成员才点亮 loading（`PENDING_HINT_MS`）；④收尾只汇总失败数。

**实现**：

- `data/api/MihomoApiClient.kt` 的 `getGroupDelay` 不再丢掉 `0` / 负值——只保留 `>0` 会让这批「内核已判不通」的节点白补测一轮，且「隐藏不可用」要等下一次刷新才生效。
- `viewmodel/ProxyViewModel.kt` 的 `testGroupDelay`：
  1. 快照正在单测的节点（不重复拨测）；
  2. 组接口（固定了节点的组跳过，避免内核 `ForceSet("")` 拔掉用户固定的节点），只收「是本组成员且不在飞」的结论；
  3. 结果**当场**落账（`applyBatchDelays`：`>0` 直接用、`0` / 负值按 `loadProxies` 的口径落成 -1 显示超时），不等补测；
  4. 待补测 = 组接口没作答 ∧ 没在飞 ∧ `isSpeedTestable`；为空就直接收尾，连节点都不重新加载；
  5. 8 路并发池（provider 节点走 provider 专属 healthcheck，其余走 `/proxies/{name}/delay`；单节点超时 2s），每路出结果当场回写并摘掉自己的 loading，不等 `awaitAll`；
  6. `finally` 摘掉本批所有节点标记（正常结束、repo 切换、被取消都不留转圈）。
- 新增 `isSpeedTestable(name, type)` 与 `NOT_SPEED_TESTABLE = {reject, reject-drop, block, pass, pass-rule, dns}`（名字与类型都按 `_` → `-` 归一再比，DIRECT / COMPATIBLE 恒可测），对齐参考实现的 `isProxySpeedTestable`。
- loading 提示改为与组头**同一时机**（400ms）点亮，且只点亮「那时还没作答的可测成员」：组接口已经作答的成员直接显示结果、不闪 loading；已经出结果的成员用 `settled` 挡住，不会被 400ms 的提示重新点亮。

## 验证

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 字段整理逐字节一致 | node 参考实现（`tools/tidy/tidy-ref.mjs`，从 `core.js` 原样抽出）⇄ Kotlin 版（`tools/tidy/Main.kt`，kotlinc 编译）对拍 | 包内用例 12 个 + CRLF 断言：same=13 diff=0；另跑全量语料 320 个（随机样例 300 + 用例组 20）：same=320 diff=0 |
| 换行符 | 单独断言：CRLF 文件整理后仍全是 CRLF（无裸 LF） | 通过 |
| 补丁可逆 + 断言 | `tools/verify_app_patch.sh --repo <干净检出> --series`（sha256 → 0001 后逐文件 blob 比对 → 叠加 0002–0004 → 全序列静态检查 → 逆序 `apply -R` → 工作区必须干净） | 225 项 ok、0 项 FAIL，`PASS: app 侧补丁双向可逆、结果与基线逐文件一致` |
| 跨包 import / 尾随 lambda | `tools/check_cross_package_imports.py`、`tools/check_trailing_lambda.py`（全序列树上跑） | 169 个符号 / 18 处跨包引用、26 文件 478 个函数声明 87 处尾随 lambda，全部通过 |
| 依赖 API 存在性 | miuix 0.9.4 AAR 反汇编（`javap`）：`BasicComponent` 的 `Modifier` 首参与 `endActions: RowScope` 形参、`MiuixIcons.Sort`、`MiuixTheme.colorScheme.surfaceContainerHigh` | 调用点与锁定的 0.9.4 一致 |
| 参考语义核对 | 逐条读 `page-proxies.js` 的 `testGroupAll` / `isProxySpeedTestable`、`pages-flow.js` 的规则列表、`fields.js` 的 `mapListEditor` / `reorderConfigMap` | 可测过滤、`0` 不补测、隐藏节点照测、逐路当场回写、400ms 提示、序号样式均一一对应 |

**未验证**：沙箱没有 Android SDK / Gradle（只有 JRE 11、2 GB 内存），没有跑 `:app:compileDebugKotlin`，也没有在真机上操作。上面「编译」一列只覆盖了 `ConfigTidy.kt` 的可编译性（用 `tools/tidy/Main.kt` 作驱动、standalone kotlinc 编译并运行），Compose 侧的改动靠静态检查（import、尾随 lambda、API 反汇编）与既有约定保证；序号胶囊的间距、拖动手感只有按参考实现的 CSS / JS 推定的观感。

## 仍然保留的行为

- 「字段整理」只动字段顺序：不删除、不改写任何值 / 注释 / 锚点；源码有 YAML 语法错误时只提示、不修改文件；整理结果只落编辑器草稿，未保存前退出等于放弃。
- 未知键按参考实现的 rank 999 稳定排到最后——整理后位置会有变化，这与 mihomo_box 行为一致（不是丢字段）。
- 子规则列表不显示序号（参考实现那边是 maplist，本来就没有逐行序号）；DNS 的 maplist 行也不显示序号，只有拖动把手——与参考实现一致。
- 整组测速成功时不逐节点弹提示：延迟数字本身就是反馈，失败数由组头汇总（0001 起保留的行为）。
- 单节点测速的既有兜底（失败后用组接口再试一次、`503/504` 视为真结论不重试）不变。
