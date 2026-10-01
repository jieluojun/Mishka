# 配置表单编辑器（对齐 mihomo_box 配置页）——设计与落地计划

范围已确认：**做 mihomo_box 那一套「配置页表单」，全量对齐**（不是只做锚点面板）。
参考实现是 WebUI，我们把它移植成 Mishka 编辑器里的原生面板（Kotlin + miuix）。

---

## 1. 参考实现的全貌（已机械提取，不靠人眼抄）

`tools/forms/extract_fields.mjs` 从参考实现里把字段表求值成 `tools/forms/fields.json`：
以“整条语句”为单位抓 `const X = [...] / {...} / () => ({...})`，按依赖顺序多轮求值后注入上下文，
所以 `IN_TEMPLATES` 这类**引用其它常量**的表也能完整取出。结果：

* 28 个声明/表，**467 条字段**，类型分布：
  `text 144 · bool 91 · select 67 · number 67 · list 51 · numlist 11 · userlist 6 · textarea 6 ·
   dnslist 5 · maptext 5 · applist 4 · headers 4 · maplist 3 · fakeiprule 1 · rulesetpick 1 · password 1`
* 关键表：`GENERAL_SECTIONS 43`、`DNS_SECTIONS 23`、`TUN_SECTIONS 28`、`EBPF_SECTIONS`、`SNIFF_SECTIONS`、
  `INBOUND_PORTS 5`、`NTP_SECTIONS 6`、`EXPERIMENTAL_SECTIONS 3`、`IN_TEMPLATES`（入站模板 22 种）、
  `IN_EXTRA/IN_TLS_FIELDS/IN_REALITY_FIELDS/IN_TRANSPORT_FIELDS/IN_MUX_FIELDS/IN_DISGUISE_FIELDS`、
  `PROXY_TEMPLATES`（出站模板）、`PER_TYPE`（每种出站协议的字段表，最大的一张）、
  `NET_FIELDS/TLS_FIELDS/SMUX_FIELDS/TAIL_FIELDS`、`RULE_TYPES/GROUP_TYPES/EXCLUDE_TYPE_OPTIONS`、
  `DNS_PRESETS/DNS_PROTOS`（DNS 服务器构建器）、`EXPR_PRESETS`（override-expr 预设）。
* 代码量（要移植的逻辑，不含字段表）：`pages-config.js 1736 行`、`pages-flow.js 2223 行`、
  `fields.js 1542 行`（控件 + 专用编辑器）、`pages-core.js 666 行`（内核/工具页，**不在本次范围**）。

## 2. 落盘（写回 YAML）的保真契约 —— 这是整个功能的地基

参考实现是三层策略（`dumpConfigKeepLayout`）：**手术式补丁 → 顶层块拼接 → 全量重排**。
我们用同样的分层，但把「格式保真」写成可测试的契约：

| 层 | 触发条件 | 保证 |
| --- | --- | --- |
| A 手术式（逐值替换） | 标量/短 flow 值的改、删 | 只替换该值的字符区间；同行尾部注释保留；其它字节完全不动 |
| B 块级重排 | 整个列表/映射被替换 | 只重排该键的块；**其它顶层块逐字节不变**；块头注释/空行保留 |
| C 全量重排 | 文档无法解析或结构过于异常 | 明确告警（“已整体重排，注释可能丢失”），不静默 |

可测试的性质（Python 双胞胎先跑，Kotlin 1:1 转写后同套用例跑）：

1. `set(path, v)` 之后 `yaml.safe_load(text)` 在 `path` 上等于 `v`；
2. 除被编辑的顶层键外，**其它顶层块的原文逐字节相同**；
3. **往返还原**：把值改回原样 → 文本与原文逐字节一致；
4. 幂等：同一个 `set` 连做两次，第二次不产生任何差异；
5. CRLF 文档不被转成 LF，行尾注释不被吞；
6. 不存在的键 → 在父块末尾插入，父块不存在则按需创建（只创建必要层级）。

## 3. 不引入新依赖

Mishka 里**没有任何 YAML 库**（`grep` 过 `app/build.gradle.kts`、`libs.versions.toml`、全部 Kotlin 源码），
且我们要保持补丁极小 —— 所以**不添加 snakeyaml 等依赖**，自己写一个「够用」的引擎：

* `YamlDoc`：行树解析（块映射、块序列、内联 `- key: v` 续行、flow 标量、多行 `|`/`>` 视为不透明叶子、
  `&锚点`/`*别名`/`<<:` 原样保留为标记），每个节点带 `[startLine, endLine)` 与列偏移；
* `YamlPatch`：A/B/C 三层写回；
* `YamlValue`：标量渲染（何时需要加引号的规则集）、列表/映射渲染。

这套东西**只服务于表单编辑**：读值、改值、增删键；锚点语义仍归锚点面板。

## 4. 界面结构

* 入口：编辑器顶栏的「调节」图标（`MiuixIcons.Tune`），与「锚点」图标（`MiuixIcons.Link`）并排，
  只在 YAML 文件上出现；两个面板共用同一份草稿与保存流程（改动进编辑器缓冲区，仍是一个撤销单元，
  顶栏「确定」才写盘）。
* 配置面板首页：**13 个格子的 hub**，与参考实现同构（全局配置 / DNS / 域名嗅探 / 入站 / 出站代理 /
  代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道 / NTP / 实验性），每格显示子标题计数
  （`N 个节点`、`N 条规则`…）与当前开关态（DNS/嗅探/NTP 显示「已启用 / 未启用」）。
* 每个分区页：小节标题 + 字段行（miuix 控件：开关、下拉、输入框、数字、列表编辑器…）；
  改动即时落草稿并跳到被改的那一行，写盘仍走编辑器顶栏「确定」。
* 只对 **YAML 文件**显示入口（复用现有 `isYamlFile` 判定）。
* **不假装能改的就不让改**：路径落在序列/标量下面（`canSet` 为假）时，控件禁用 + 行摘要里说明原因，
  点按提示「请在编辑器里直接改」——宁可不动，也不写坏配置。

## 5. 分阶段落地（每阶段都是可用的完整切片）

| 阶段 | 内容 | 大致规模 | 状态 |
| --- | --- | --- | --- |
| **P0 引擎** | `YamlDoc` 解析 + `YamlPatch` 三层写回 + 值渲染 + flow 集合读写；Python 规范（性质测试 71 项）+ Kotlin 1:1 转写，**两边逐字节对拍 71/71 一致** | Python 780 行 / Kotlin 876 行 | ✅ |
| **P1 配置主页** | hub 13 格 + 全局配置(43) + DNS(23) + 域名嗅探 + NTP(6) + 实验性(3) + 入站（端口 5 + TUN 28 + EBPF + listeners 模板）：`ConfigFormPanel.kt` 710 行，开关/下拉/文本/数字/多行/列表编辑器全部接通 | 移植 ~1000 行 JS | ✅ |
| **P2 流量页** | 出站代理（`PER_TYPE` 全协议）、代理集合、代理组（含 Smart 专属字段）、路由规则（含各类型值编辑器）、规则集合、子规则、流量隧道；hub 里这 7 格已就位，点按提示「P2 落地」 | 移植 ~1400 行 JS | ⏳ |
| **P3 专用编辑器** | DNS 服务器构建器、fake-ip 规则、应用多选（applist）、规则集选择器（rulesetpick）、headers/maptext/maplist、override-expr 可视化；P1 页里这些控件先显示「P3」标记 | 移植 ~700 行 JS | ⏳ |
| **P4 收尾** | 与参考实现的**对拍**（用 `fields.json` 逐字段核对：路径、标签、类型、选项、默认值、校验规则一致） | 工具 + 修漏 | ⏳ |

交付方式与现在一致：Kotlin 进 `app/src/main/kotlin/.../custom/forms/`，补丁重新导出，
`patches/app/BASELINE.txt` 更新，`tools/verify_app_patch.sh` 必须仍然 PASS。

## 5.1 P1 已落地（`custom/forms/ConfigFormPanel.kt`，710 行）

渲染层与引擎的分工：引擎只管「怎么安全地改一行」，面板只管「怎么让用户改」。

| 控件 | 覆盖字段 | 行为 |
| --- | --- | --- |
| 开关 | 37 个 bool 字段（`tun.enable`、`dns.enable`、`sniffer.enable`…） | 明确写 `true`/`false`（不省键，避免「用默认值」和「显式关」混淆） |
| 下拉 | 19 个枚举字段（`mode`、`log-level`、`tun.stack`、`dns.enhanced-mode`…） | 选项来自 P1 字段表；`allowEmpty` 的字段额外给「（未设置）」= 删键回默认 |
| 文本 / 数字 / 密码 | 24 + 19 + 其余 | `WindowDialog` + `TextField`；数字框只在能解析成数字时才写数值，否则原样写文本（交给内核校验） |
| 多行文本 | `tun.dns-hijack` 之类的 TEXTAREA | 按 `|` 块写入，换行在 YAML 里保留 |
| 列表编辑 | 28 个 list + 11 个 numlist + 4 个 userlist | 逐项增/删/上移，确定时整键重写为块序列（flow 序列会被规范化成块式，这是 A/B 层写回的既有行为） |
| 运行时选项 | `options` 是函数值的字段（代理名/策略名） | P1 先退化成文本框；P2 起从模型现算（`FormValues` 已经能读全部集合） |

写入路径全部经过同三道闸门：

1. **`YamlDoc.canSet(path)`** —— 路径下面是序列或纯值就直接拒绝（`mergeTun` 那种 `/0/name` 索引路径不在表单里出现）；
2. **`YamlPatch.setValue/removeKey`** —— A（手术式改行）/ B（顶层块级拼接）/ C（拒绝）三层，
   C 层不是「尽力而为」，是真的什么都不做；
3. **Mishka 原有保存路径** —— 内核 `fetchAndValid` 校验不过会回滚并提示，表单写坏配置的唯一后果就是保存被拒。

写回成功后 `jumpToLine` 到被改的那一行；清空/删除类操作（`removeKey`）不跳转（行号已失效）。

## 5.2 P0 已落地的引擎行为（性质测试实测）

* 标量改值 / 删键 / 嵌套改值 / 新增键（含逐级新建父级）/ 整块替换：**未触及的顶层块逐字节不变**，
  改回原值可**逐字节还原**，重复设置幂等；
* 锚点定义（`dns: &dnsbase` 这种）与 `<<: *merge` 原样保留；删除「被别处引用的锚点定义」会被性质测试标为
  「需要 UI 拦截」的情形（锚点面板已有该告警）；
* `listeners:` 这类**序列**下面的路径一律拒绝编辑（`can_set=false`），宁可不动也不写出非法 YAML；
* 行内 flow 集合（`geox-url: {geoip: …, geosite: …}`）可读可写：改值只替换行内那一段，
  新增/删除键在同一行完成，行尾注释保留；
* CRLF 文档不被转成 LF；`'geosites:cn'` 这类**带冒号的引号键**正确解析（这是实测抓到的一个真 bug）。

## 6. 对拍（怎么证明“和参考实现一样”）

* 字段级：`fields.json` 是唯一事实来源，Kotlin 侧的字段表由它校对（脚本逐条比对路径/标签/类型/选项条数）。
* 编辑级：同一批 YAML 语料 + 同一批操作（set/remove/list 编辑）分别跑参考实现的 `dumpConfigKeepLayout`
  （node 侧抽取）与我们的引擎，比较**语义等价**（PyYAML 深比较）与**保真度**（未触及块逐字节一致）。
