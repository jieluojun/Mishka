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

* 入口有两处：导入型订阅编辑页「覆写」行下方提供「可视化配置」，布局与模块配置页一致；优先选 `config.yaml`，否则选首个 `.yaml` / `.yml`，加载后自动打开表单。
  YAML 编辑器工具栏也保留可视化配置快捷按钮，位于锚点面板按钮左侧，打开当前文件的表单。
  两个入口共用同一编辑器草稿 / 撤销单元；锚点面板仍在原工具栏位置，写盘仍走编辑器顶栏「确定」。
* 配置面板首页：**13 个格子的 hub**，与参考实现同构（全局配置 / DNS / 域名嗅探 / 入站 / 出站代理 /
  代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道 / NTP / 实验性），每格显示子标题计数
  （`N 个节点`、`N 条规则`…）与当前开关态（DNS/嗅探/NTP 显示「已启用 / 未启用」）。
* 每个分区页：小节标题 + 字段行（miuix 控件：开关、下拉、输入框、数字、列表编辑器…）；
  改动即时落草稿并跳到被改的那一行，写盘仍走编辑器顶栏「确定」。
* 仅允许对 **YAML 文件**打开表单（复用现有 `isYamlFile` 判定）；导入订阅没有 YAML 时点击入口会提示，不会打开表单。
* **不假装能改的就不让改**：路径落在序列/标量下面（`canSet` 为假）时，控件禁用 + 行摘要里说明原因，
  点按提示「请在编辑器里直接改」——宁可不动，也不写坏配置。

## 5. 分阶段落地（每阶段都是可用的完整切片）

| 阶段 | 内容 | 大致规模 | 状态 |
| --- | --- | --- | --- |
| **P0 引擎** | `YamlDoc` 解析 + `YamlPatch` 三层写回 + 值渲染 + flow 集合读写；P2 起有**序列级补丁**（改项 / 插项 / 删项 / 挪项 / 映射改名、`a[3].b` 下标路径、flow 项里在行内建嵌套键、多行字符串按 `|` 块写）与**批量补丁**（`FormHost.batch`）。Python 性质测试 **697 项**，Kotlin↔Python 逐字节对拍 **802/802**（含括号不配对 flow 用例） | Python 1276 行 / Kotlin 1124 行 | ✅ |
| **P1 配置主页** | hub 13 格 + 全局配置(43) + DNS(23) + 域名嗅探 + NTP(6) + 实验性(3) + 入站（端口 5 + TUN 28 + eBPF + listeners）。当前 `ConfigFormPanel.kt` 1332 行，`FormDialogs.kt` 660 行，`FormValues.kt` 238 行，生成的 `FormSpecs.kt` 1240 行（154 个字段行）。订阅页入口已移到「覆写」下方；锚点工具栏位置不变 | 移植 ~1000 行 JS | ✅ |
| **P2 流量页** | 出站代理（27 种协议 `PER_TYPE` + 传输层 / TLS / 多路复用 / 通用小节按协议特性拼装、「其他参数」YAML 整块编辑）、代理集合、代理组、路由规则（37 种类型）、规则集合、子规则、流量隧道；**删除前引用检查**与**改名级联**（`ConfigRefs.kt`，参考 JS 对拍 153 例 0 差异）。`FlowFormPages.kt` 2110 行 + `ConfigRefs.kt` 547 行 + `FlowText.kt` 120 行 + `FormSpecsP2.kt` 2188 行；详见 §5.3 与 `FORMS-P2.md` | 移植 ~2200 行 JS | ✅ |
| **P3 专用编辑器** | DNS 服务器构建器、fake-ip 规则、应用多选、规则集选择器、headers、hosts / nameserver-policy maplist、P2 `override.proxy-name` 简表格编辑；eBPF/listener 页绑定 `listeners[index]`，显示并编辑 local/shared 角色。已有值摘要与编辑器、子键级写回、键改名暂存；未支持的嵌套/别名 map 值禁止重写。入口在订阅编辑页「覆写」下方，锚点面板不移动 | `P3FormEditors.kt` 1027 行 + eBPF / maplist 纯逻辑与定向测试 | ✅（实现完成；Android 编译待 SDK 环境验证） |
| **P4 收尾** | 与参考实现的**字段级对拍**（路径、标签、类型、选项、默认值、校验规则）与补漏 | 工具 + 修漏 | ⏳ |

交付方式与现在一致：Kotlin 进 `app/src/main/kotlin/.../custom/forms/`，补丁重新导出，
`patches/app/BASELINE.txt` 更新，`tools/verify_app_patch.sh` 必须仍然 PASS。

## 5.1 P1 已落地（`custom/forms/ConfigFormPanel.kt`，当前 1338 行）

渲染层与引擎的分工：引擎只管「怎么安全地改一行」，面板只管「怎么让用户改」。

| 控件 | 覆盖字段 | 行为 |
| --- | --- | --- |
| 开关 | 38 个 bool 字段（`tun.enable`、`dns.enable`、`sniffer.enable`…） | 明确写 `true`/`false`（不省键，避免「用默认值」和「显式关」混淆） |
| 下拉 | 20 个枚举字段（`mode`、`log-level`、`tun.stack`、`dns.enhanced-mode`…） | 选项来自 P1 字段表；`allowEmpty` 的字段额外给「（未设置）」= 删键回默认 |
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

## 5.3 P2 已落地（`FlowFormPages.kt` 2110 行 + `ConfigRefs.kt` 547 行 + `FlowText.kt` 120 行 + `FormDialogs.kt` 660 行 + 生成的 `FormSpecsP2.kt` 2188 行）

参考实现是 **jieluojun/mihomo_box** 的 WebUI（`openwrt/files/webroot/ui/js/`，源码只在历史提交里，取的是
`657e799778`：`pages-flow.js` 2223 行 + `pages-config.js` 的内置策略表 + `config-references.js` / `reference-delete.js`）。
字段表、新建模板、选项清单、规则类型表全部由 `tools/forms/extract_flow.mjs` 从这份源码**求值提取**成
`tools/forms/fields_p2.json`（文件头记了 commit），再由 `gen_specs.py` 生成 `FormSpecsP2.kt`（266 个字段 / 37 种规则 /
27 种协议模板）与 `FORMS-P2.md`；`gen_specs.py --check` 保证三者同步。页面层则逐个函数对照
`editProxySheet / addSubSheet / editGroupSheet / editRuleSheet / addEpSheet / tunnels` 移植，七个入口都是「列表页 → 详情页」，
全部写回仍只走 `FormHost`（`YamlPatch.setValue / removeKey / setItem / insertItem / removeItem / moveItem / renameKey`
与批量 `batch`），hub 不再显示「P2」占位。

| 页 | 配置键 / 形态 | 列表页 | 详情页（与参考实现逐条对齐的行为） |
| --- | --- | --- | --- |
| 出站代理 | `proxies`（序列） | 名称 · 协议 · `server:port`；新建先选协议（27 种模板，名字重复自动加序号）/ 上移 / 下移 / 删除（**先查引用**） | 协议只在新建时选（编辑态只读）；小节按 `PROXY_FEATURES` 拼：基础（含 `PER_TYPE`）→ 传输层（`network` 选择器停在 tcp 也显式写 `network: tcp`，切换时清掉其它 `*-opts`，http 补 `http-opts.method: GET`）→ TLS（`tls` 开 → 没有 servername / sni 时补 `example.com`；关 → 删掉两者；anytls 只认显式 false）→ 多路复用 → 通用链式 / 拨号（`dialer-proxy` 候选 = 出站池排除自己）；表单之外的键在「其他参数（YAML）」整块编辑（锚点 / 别名 / 括号不配对一律拒绝）；改名同步所有代理组 `proxies` 里的引用 |
| 代理集合 | `proxy-providers`（映射） | 名称 · 类型 · 来源 / inline 节点数；新建（名字 + http / file / inline + 链接，http 必填 url；file 自动 `./proxies/<名>.yaml` 并去掉远程字段；inline 预置 `payload: []`）/ 改名 / 删除（先查引用） | 来源 / 健康检查（三态：默认 = 删整块；开启补 `HC_DEFAULTS` 缺失项）/ 筛选 / 覆写（`override-expr` 可视化 ⇄ 文本，常用表达式一键填入；`proxy-name` 改名表）/ 请求头，按 `type` 显隐，切到 file 时清掉 `FILE_HIDDEN_KEYS`；inline 的 `payload` 点进去就是**同一套节点编辑器**（路径换成 `proxy-providers.<名>.payload[i]`）；改名同步代理组 `use` |
| 代理组 | `proxy-groups`（序列） | 名称 · 类型 · 成员数 · 引用集合数 · include-all；新建选类型 / 上移 / 下移 / 删除（先查引用） | `GROUP_SECTIONS` 按 `type` 显隐（Smart 的 `policy-priority / uselightgbm / collectdata / sample-rate` 等）；类型可选「默认（不覆写）」= 删掉本地 `type`（锚点继承时显示生效值）；`proxies` 候选 = 内置策略 + 代理组 + 节点（排除自己），`use` 只能挑现有集合，留空 = 删键；改名同步其它组的成员 + `rules` / `sub-rules` 的尾部策略 |
| 路由规则 | `rules`（字符串序列） | 每行「类型 匹配值 → 目标 · 参数」；新增 / 点行编辑 / 在上方插入 / 上移 / 下移 / 删除 / **文本模式**（一行一条整列表重写） | 规则对话框：类型（37 种）→ 匹配值（`RULE-SET` 可从规则集合名里选；MATCH / 无载荷类型不填）→ 目标（策略池；`SUB-RULE` 换成子规则名）→ no-resolve 开关 ⇄ 原文编辑互相同步；拆合照内核 `ParseRulePayload`（[`FlowText.kt`]），确定前校验匹配值 / 目标非空；原规则里 `src` 这类附加参数原样保留在末尾 |
| 规则集合 | `rule-providers`（映射） | 名称 · 类型 · behavior/format · 来源；新建（类型 / behavior / format / 链接；file 自动 `./rules/<名>.<yaml|txt|mrs>`）/ 改名 / 删除（先查引用） | `RULE_PROVIDER_SECTIONS` 按 `type` 显隐，切到 file 清掉远程字段；inline 的 `payload` 一行一条编辑；改名同步 `rules` 里的 `RULE-SET,<名>` |
| 子规则 | `sub-rules`（映射 → 字符串序列） | 名称 · 条数；新建（空列表）/ 改名 / 删除 | 复用路由规则列表页，路径 `sub-rules.<名>` |
| 流量隧道 | `tunnels`（序列，项是字符串或映射） | 协议 · 监听 → 目标 · 策略；新建（映射 / 单行两种写法，监听与目标必填，单行按内核 3 / 4 段校验）/ 上移 / 下移 / 删除 | 映射项用 `TUNNEL_SECTIONS`（`network` 多选 tcp / udp，`proxy` 从策略里选，address / target 不许清空）；字符串项展示原文 + 直接改字符串 + 一键「转成映射写法」 |

**删除保护**（`ConfigRefs.kt`，照 `config-references.js` / `reference-delete.js`）：删出站代理 / 代理集合 / 代理组 / 规则集合
前先在当前草稿里找引用（代理组成员与 `use`、`rules` / `sub-rules` 的目标 / `RULE-SET` / 逻辑条件、`dns.fake-ip-filter` 与
`nameserver-policy` 里的 `rule-set:`、DNS 服务器地址 `#策略` 形式的出口、任意位置的 `dialer-proxy` / `proxy` 键、隧道的出口策略，
位置按参考实现的 `path` 文案逐行列出），有引用 → 「无法删除」说明；
没有 → 确认，确认时**再查一次**才真删。改名级联（`RenameSync`）也照各编辑器里的同步片段。两边与参考 JS 用同一批语料
对拍：`tools/forms/refs_diff.mjs` 153 例 0 差异（3 次改名因引用落在别名 / 锚点里被 Kotlin 侧明确拒绝，参考实现对展开后的
对象改会把共享块一起改掉——这是「不写坏 YAML」优先于「和参考实现一样」的取舍）。

引擎为 P2 加的东西（Python 规范与 Kotlin 转写同步，性质测试 T7–T16 覆盖）：

* 路径段可以是下标（`proxies[3].ws-opts.path` → `["proxies", 3, "ws-opts", "path"]`），`YamlDoc.quoteSeg` 处理含 `.` / `[` 的名字；
* 序列级补丁：`setItem / insertItem / removeItem / moveItem`（块式与 flow 序列都行，flow 超过 160 列自动转块式；
  删到只剩空就写 `key: []`；序列缺失或空值时 `insertItem` 直接建整键）；映射改名 `renameKey`（同名拒绝）；
* `- {name: a, …}` 这类 flow 项里新建嵌套键在**同一行**完成（`{…, ws-opts: {path: /ws}}`），装不下的集合与多行文本直接拒绝、不动；
* 含换行的字符串（证书 `ca`、多行 `payload`）按 `|` / `|-` 块写，读回逐字相同；
* 对齐参考实现时新加的 `f10-refs.yaml`（锚点 / 别名 / 带冒号的键 / 块标量混在一起的流程页语料）又抓到并修掉 **6 个引擎 bug**：
  裸键含冒号（`geosite:cn: …`）被拒、`- "a: b"` 被当成键；整块重写丢掉 `&anchor`；`key: &x [a, b]` 被当纯标量；
  flow 项里改 `|` 多行 / `*alias` 节点会写坏；块标量结尾吞掉后面的空行；**`[1, 2}` 这类括号不配对的 flow 写法让两边
  解析器死循环**（表单面板开着时编辑器每次改动都会重新解析，这一条会直接卡死界面）——现在按解析失败处理成不透明节点。

页面层与参考实现**有意不同**的地方（都写在 UI 文案里）：

* 隧道映射写法的 `network` 写成**列表**（参考实现写成 `tcp/udp` 字符串；内核 `listener/config/tunnel.go` 的 `UnmarshalYAML`
  对映射写法要求序列，字符串形态会报错——参考实现这里是个 bug，不照抄）；
* 规则串末尾的 `src` 等附加参数原样保留（参考实现会丢）；节点改名不改 `rules` 里直接写节点名的规则（参考实现同样不改，
  只是文案点明）；规则集合改名不动子规则里的 `RULE-SET`；
* 引用落在别名 / 锚点里（`proxies: *members`）的改名一律拒绝并提示去编辑器改（见上）；
* 新建节点重名自动加序号而不是报错；新建代理组先选类型再建（参考实现先建 select 再在弹层里改类型，语义相同）；
* 「其他参数（YAML）」确定后在原位逐键写回（参考实现整个对象重排），注释与顺序得以保留；
* 规则 / 子规则 / 隧道（没有引用保护的列表）删除走菜单里两步确认；代理集合 inline payload 的节点删除同理；
* 参考实现里把 WebUI 专有的「代理 URI 导入 / 二维码」没有移植（App 里没有对应入口）；
* 参考源码取自 `657e799778`，比 mihomo_box 当前发布版（`20261001-1620`）略旧——发布包里的 `fields.js` 多了一个
  `cns` 选项，本次字段表没有收录（抓不到对应源码，不猜）。

## 5.4 P3 专用编辑器与入口迁移（实现完成）

* `P3FormEditors.kt` 已接入 DNS server 构建器、APPLIST、FAKEIPRULE、RULESETPICK、HEADERS 与 MAPLIST 专用编辑器；
  `override.proxy-name` 继续用 P2 的 pattern/target 表格。字段摘要会读出现有列表项、map/header 键值与布尔 select 的当前值，
  而不是只显示条数或因 YAML `true` / 选项 `True` 大小写不同而丢失选中态。`P3_ONLY` 现在只保留罕见的 CHECKBOX、FILE、BUTTON；其余常用类型由专用编辑器处理。
* `FormMapListLogic.kt` 生成逐键补丁：未编辑条目保持原文，单键改名、改名到被删除的旧键、A↔B 键交换均先暂存再落位；
  重复键及嵌套映射 / 别名值拒绝写入，避免丢结构。`EditorLogicProps.kt` 覆盖这些路径。
* eBPF listener 详情页按 `listeners[index]` 绑定，读 `mode`（默认 local / shared / hybrid）与 `local/shared.enable`，兼容旧 `enabled`；
  总开关在任一角色启用时显示开启，角色开关只改自身 enable 状态并保留其它参数。新建模板不猜 `shared.interface`，安全从 local 开始；
  顶层 `dns-mode` 与 `bypass-private-address` 也纳入 eBPF 字段表，角色级值可以覆盖全局值。scalar 与 sequence 两种 `network` 写法都能读取；
  `fakeip-icmp: reply` 会检查 FakeIP 段及可用 TC hook（启用的 local+tc，或启用且配置网卡的 shared）。
* 订阅编辑页「覆写」下方保留「可视化配置」入口（仅导入型订阅），选 `config.yaml` 或首个 YAML 并自动展开表单；YAML 编辑器 toolbar 同时恢复表单快捷按钮，位于锚点面板按钮左侧。
  锚点面板按钮自身仍在原位置。
* 定向测试脚本：`bash tools/forms/run_editor_logic_tests.sh`（eBPF role state / FakeIP ICMP 的 TC hook 前置条件 / listener 路径 / FormValues readers / MAPLIST rename、swap、删除目标键）
  通过；引擎对拍 802/802、引用对拍 153 例 0 差异。Android Gradle 编译仍需本机 Android SDK。

## 6. 对拍（怎么证明“和参考实现一样”）

* 字段级：`fields.json` 是唯一事实来源，Kotlin 侧的字段表由它校对（脚本逐条比对路径/标签/类型/选项条数）。
* 编辑级：同一批 YAML 语料 + 同一批操作（set/remove/list 编辑）分别跑参考实现的 `dumpConfigKeepLayout`
  （node 侧抽取）与我们的引擎，比较**语义等价**（PyYAML 深比较）与**保真度**（未触及块逐字节一致）。
