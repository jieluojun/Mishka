# 定制详解

四块定制的实现方式、边界与取舍。

---

## 一、锚点可视化面板

### 解决什么问题

订阅配置里的 `&定义` / `*引用` 只存在于**原文**，YAML 一展开就看不见了；而 mihomo 对悬空别名
（`*x` 找不到 `&x`）是直接拒绝加载的。面板把这些关系摊开在手机上，并给出行级手术工具。

### 功能对照（与 `mihomo_box` 的「工具页 → 锚点面板」等价）

| 能力 | 参考实现 | 本面板 |
| --- | --- | --- |
| 锚点总览：`&名` / 定义位置 / 引用清单 / 悬空告警 | ✅ | ✅ 卡片式，`Badge` 显示「继承 N · 引用 M」 |
| 定位跳转（点行号跳到编辑器对应行） | ✅ | ✅ 关面板 + `jumpToLine` |
| 定义块可视化编辑（键值对增删改 + 写回预览） | ✅ | ✅ 映射/序列逐行编辑 + 实时校验 + 预览；标量值单框；拆不动的形态转「文本」 |
| 引用行改绑 / 清除继承（`<<:` 的「不继承」）/ 删行 | ✅ | ✅ 候选表只列合法目标 |
| 新建顶层定义块（插到文件头） | ✅ | ✅ 顶层键 + 锚点名 + 参数行，>160 字符自动退回块式 |
| 重命名（锚点名级联 + 顶层键） | ✅ | ✅ 级联只动含 token 的裸文本行 |
| 删除定义（mihomo 配置段只摘 `&名`） | ✅ | ✅ 自建容器整块删、`MIHOMO_TOP_KEYS` 段只摘 token、多处定义时拒绝 |
| 多层「展开值编辑器」 | ✅ | ❌ **不做**（已确认的取舍）：嵌套多行的值改用「文本」模式直接编辑原文 |

### 文件与入口

app 补丁共 37 个文件：app 侧 28 个（20 个新 custom Kotlin 源文件 = anchor 5 + forms 15；修改 8 个上游文件：导航 / 订阅页 / 编辑器入口 / 四份多语言资源），mishka-custom 侧 9 个（交付源码与工具的同步副本）。

| 文件 | 职责 |
| --- | --- |
| `custom/anchor/AnchorScan.kt` | 锚点图扫描（裸文本判定、路径回溯、块边界、悬空检测），是 `mihomo_box core.js` 的等价移植 |
| `custom/anchor/AnchorEdit.kt` | 行级手术：改名级联、定义行键改名、删行、改绑、摘/删定义块；`YamlValue` 值规范化 |
| `custom/anchor/AnchorBlock.kt` | 定义块模型：块的形态判定（Map/Seq/Scalar/Unknown）、项解析、按行渲染、新建块 |
| `custom/anchor/AnchorDialogs.kt` | 5 个子对话框（定义编辑 / 引用编辑 / 新建 / 改名 / 删除确认） |
| `custom/anchor/AnchorPanel.kt` | 面板本体：总览卡片、悬空卡片、子对话框编排、**唯一落地通道** `apply()` |
| `custom/forms/YamlEngine.kt` | 配置表单的保真写回引擎（行树解析 + 三层写回 + 值渲染），与 `tools/forms/forms_model.py` 逐字节对拍 |
| `custom/forms/FormSpecs.kt` | P1 的 154 个字段声明表（由 `tools/forms/gen_specs.py` 从 `fields.json` 生成，勿手改） |
| `custom/forms/FormSpecsP2.kt` | P2 的字段表：27 种协议的参数 / 传输层 / TLS / 多路复用 / 通用字段与新建模板、代理组 / 代理集合 / 规则集合 / 隧道小节、37 种规则类型、内置策略与 override-expr 预设（`tools/forms/extract_flow.mjs` 从 mihomo_box `657e799778` 的 `pages-flow.js` / `pages-config.js` 求值提取成 `fields_p2.json`，再由 `gen_specs.py` 生成，勿手改） |
| `custom/forms/ConfigFormPanel.kt` | 配置表单面板：hub 13 格、导航栈、写回宿主 `FormHost`（统一走 `YamlPatch`，含批量写回 `batch` 与候选池 `candidates`）、P1 的 6 个分区页、两期共用的字段行（开关 / 三态 / 下拉 / 多选 / 文本 / 数字 / 多行 / 列表 / 键值表 / 映射列表） |
| `custom/forms/FormDialogs.kt` | 两期共用的弹层：下拉 / 三态 / 文本 / 列表 / 键值表 / 映射列表 / 改名 / 选项 / 项菜单 / 确认 / 「无法删除」说明 |
| `custom/forms/FormValues.kt` | 从行树读值（原文 / 布尔 / 列表 / 映射 / 计数 / 摘要），表单与引用检查共用 |
| `custom/forms/FlowFormPages.kt` | P2 的七个流程页：出站代理（含 inline payload、传输层 / TLS 连带处理、其他参数 YAML）、代理集合（健康检查三态、override-expr 可视化）、代理组、路由规则（规则对话框 + 文本模式）、规则集合、子规则、流量隧道；删除前引用检查 + 改名级联的编排 |
| `custom/forms/ConfigRefs.kt` | `PlainYaml`（行树 → 展开后的普通对象，`<<:` / 锚点按 YAML 语义合并）、`ConfigRefs`（删除前引用检查，照 `config-references.js`）、`RenameSync`（改名级联计划，照各编辑器的同步片段），与参考 JS 对拍 153 例 0 差异 |
| `custom/forms/FlowText.kt` | 规则串与隧道串的拆合（照内核 `ParseRulePayload` / `tunnel.UnmarshalText`），纯 Kotlin，可用 kotlinc 单独编译对拍 |
| `custom/forms/P3FormEditors.kt` | DNS / headers / MAPLIST / FakeIP / APPLIST 等专用编辑对话框 |
| `custom/forms/FormMapListLogic.kt` | MAPLIST 当前值校验与逐键安全写回计划 |
| `custom/forms/EbpfFormLogic.kt` | listener 角色状态、兼容旧字段、保留非角色参数的写回与 FakeIP ICMP hook 条件 |
| `custom/forms/AnchorInheritance.kt` | 一级（条目 `<<:`）/ 二级（字段级 `<<:` 与 `键: *`）锚点现状读取与行级手术，纯 Kotlin，`AnchorInheritanceProps` 性质测试覆盖 |
| `custom/forms/AnchorSectionPanel.kt` | 代理合集 / 代理组 / 规则合集详情页的「YAML 锚点」区：继承换绑 / 清除 / 新挂 + 字段级挂 / 摘（`&定义` 动作下线，存量只摘不挂） |
| `custom/forms/ProviderFileOps.kt` | file 类型合集的「源文件」操作：SAF 上传（二进制安全，.mrs 自动切 format）/ 在线编辑；相对 path 按本订阅 imported/ 目录解析 |
| `custom/forms/ProxyUri.kt` | 出站代理「解析节点」：分享链接（SS / VMess / VLESS / Trojan / Hysteria2 / TUIC）与 YAML 节点的离线解析器 + `ensureProxiesRoot` 兜底，纯 Kotlin，`ProxyUriProps` 性质测试覆盖 |
| `custom/forms/ListenerSpecs.kt` | 入站 listeners 的 22 种协议类型 / 新建模板 / 按协议能力组合的字段表（协议参数 / TLS / REALITY / 传输层 / 伪装 / Mux），`ListenerSpecsProps` 性质测试覆盖 |

### 本轮修复与优化（对齐 mihomo_box 20261001-1620 参考实现）

1. **规则合集可视化读配置参数**：列表摘要与字段行在源码无本地行时回落到展开视图
   （`FormValues.effectiveValue`，别名解开 + `<<:` 合并后的值），继承来的 type / behavior / format /
   url / path 不再显示成「未设置」，尾标「继承」；代理集合同款。
2. **锚点继承与二级锚点**：三个合集详情页新增「YAML 锚点」区（上表两文件），语义子集对齐
   `anchorSection`：一级换绑 / 清除 / 新挂，二级字段挂 `<<:` / `*`、存量 `&` 摘除；多合并来源锁定；
   候选只列定义在引用行之前的锚点，`<<:` 只列映射。
3. **入站监听器按钮**：行内「↑ / 编辑 / 删」三按钮换成整行点按进详情 + 「⋯」菜单（上移 / 下移 /
   删除两步确认），与其它列表页同款，窄屏不再截断。
4. **file 类型合集源文件**：代理合集 / 规则合集详情页在 type=file 时显示「源文件」卡（上传 / 编辑内容），
   编辑器经 `fileBaseDir`（本订阅 imported/ 目录）落盘；上传 .mrs 自动把 format 切成 mrs。

### 本轮补充（对齐 mihomo_box `proxy-uri.js` / `pages-config.js` 入站编辑器）

5. **出站代理「解析节点」**：出站代理 / 内联 payload 列表页的表头新增「解析」按钮，粘贴分享链接
   （SS / VMess / VLESS / Trojan / Hysteria2 / TUIC）或 Clash/Mihomo YAML 节点，本地离线解析后一次批量写入草稿
   （`ProxyUri.kt`，逐行报错、同名自动加序号、重复跳过；解析不了的行留在输入框方便重试）；只加节点、不进代理组。
   file 类型订阅源文件保存前经 `ensureProxiesRoot` 兜底：只贴节点列表 / 单节点时自动补 `proxies:` 根。
6. **入站监听器类型补全**：新建监听器的类型从 8 种补到参考实现的全量 22 种（新增 shadowsocks / vmess / vless /
   trojan / anytls / mieru / sudoku / tuic / shadowquic / hysteria2 / hysteria2-realm / trusttunnel / snell / cns），
   详情页按协议能力分节渲染（基础 / 协议参数 / 传输层 / TLS 证书 / REALITY / TLS 伪装 / Multiplex），
   `users` 对象列表走简版映射列表编辑器（vmess 的 alterId 按数字写回）。字段表见 `ListenerSpecs.kt`。
7. **file 源文件卡的位置与按钮**：代理合集 / 规则合集的「源文件」卡从页脚移到「保存路径」字段正下方
   （`provider-file-ops` / `rule-provider-file-ops` 专门控件行），「编辑内容」按钮改名「编辑」，两个按钮收进一行、
   压紧最小宽度，窄屏不再把摘要挤换行。
8. **代理合集表达式读继承**：`override-expr` 读取在本地无行时回落到展开视图（别名解开 + `<<:` 合并），
   `override: { …, <<: *host }` 这类挂在锚点里的表达式不再显示「未设置表达式」，而是「N 条表达式（继承）」；
   编辑器打开即预填继承内容，编辑后写入本条目成为本地覆写。
9. **快捷表达式能写进 flow override**：`override: { …, <<: *host }` 这类单行 flow 映射此前点快捷预设只弹
   「这个位置不能这样写」（引擎不肯把装不下一行的列表塞进 flow）。现在写入前先把 flow 展开成块式映射
   ——`<<: *锚点`、既有键、行尾注释逐字保留——再按块式序列写入（`AnchorInheritance.setFlowMapListField`），
   列表项统一单引号转义；跨多行 flow 才拒绝并提示去编辑器。清空纯继承的表达式会提示去「YAML 锚点」区解除继承。
10. **源文件「保存文件」修复**：源文件卡整行点击此前绕过「编辑」按钮的守卫，订阅目录未知时弹层带着空目标
    打开，「保存文件」静默无反应——现在两条入口走同一守卫；保存时目标为空 / 内容没读到都有明确提示
    （后者不再可能把原文件写空）。内容框与按钮之间补 12dp 边距，按钮右对齐，内容框给 8–16 行高度。
11. **按钮间距统一**：列表页表头多按钮由 `ListHeader` 统一 `spacedBy(8.dp)`；行内小按钮对（✎/×、上移/删、
    编辑/删）补 6dp 间隔；规则编辑弹层底部按钮行补间距。
12. **锚点面板可视化**：新增「锚点定义」一览卡——每个 `&名字` 的定义行号、映射/标量形态、全文引用次数，
    本条目继承中 / 字段在用 / 本条目定义的直接标出；「继承锚点」摘要带定义行号；字段级弹层的每个在用字段
    标行号。引用计数由 `AnchorInheritance.anchorRefCounts` 提供。
13. **源文件「缺少订阅目录」修复（app 侧）**：编辑器工具栏打开配置表单时一直没把订阅的 `imported/{uuid}/`
    目录传进表单（`fileBaseDir` 恒为 null），file 型合集的源文件上传 / 在线编辑对相对 `path` 全部无法定位。
    现在 `FileManagerEditorScreen` 调 `MishkaConfigFormPanel` 时传
    `fileBaseDir = fileManager?.getImportedDir(uuid)`（改动在 `anchor-panel.seed.patch`，随补丁导出）。
14. **快捷表达式填入立即可见**：表达式弹层改为「本地镜像」显示——`onCommit` 返回是否真的写入草稿，
    成功即同步更新弹层列表，不再依赖整篇替换后的重组时机；写被拒时列表保持原状（提示原因）。
    行尾 ✎ / × 两个按钮装进 38dp 等宽格子，占位比例一致。
15. **锚点面板键值分离**：字段级锚点从一条 chips 串改为每字段一行——标题只放键名，摘要放状态
    （定义 &x / 继承 <<: *y / 引用 *z + 行号）；锚点定义一览的摘要标出承载锚点的 YAML 键
    （「定义于「host-def」」，`AnchorDefInfo.ownerKey`），锚点名与宿主键不再混在一起。
16. **独立锚点面板键值分离**：`键: &名 { … }` 这类行内 flow 值此前被当「标量值」整团显示 / 编辑。
    现在 `AnchorBlock` 把单行 flow 映射 / 序列拆成键值行——面板定义卡左列键名右列值（超 6 项折叠），
    编辑弹层按「映射 N 项」逐行改键值；写回仍是同一行 flow，未改动的键值段与括号留白、分隔符逐字保留，
    行尾注释也保住（顺带修了 `AnchorScan.commentIndex` 永远查不到注释的既有 bug）。拆不动的形态仍回落标量 / 文本模式。
17. **表达式 ✎/× 按钮形状**：上一版用 38dp 等宽格子把两个按钮挤成了变形的大圆块；改回与弹层其它按钮
    （「＋ 添加表达式」/「完成」）同款的常规 TextButton + 8dp 间距，大小形状与模块一致。

### 本轮 app 侧修复（主页 / 代理页 / 隧道模式，seed：`patches/app/home-proxy-root-fixes.seed.patch`）

18. **主页内核版本号不更新**：版本号原先只在每次建立连接时取一次 `/version`，内核升级重启后
    同一 app 会话里永远显示旧值。现在并入首页 2s 可见轮询（`refreshRuntimeConfig`），
    限流 10s 拉一次 `/version`，值变化才更新状态；repository 换代后旧响应被丢弃。
19. **代理页单节点测速点不动**：命中区只有延迟角标 ~28×16dp，基本点不中。角标外框改用
    `sizeIn(minWidth = 48.dp, minHeight = 48.dp)`，角标文字 / 图标尺寸不变，触控区至少 48×48dp
    （必要时该行卡片增高）；节点卡改 `combinedClickable`——短按选中、**长按测速**，与 mihomo_box 参考实现交互一致。
20. **主页代理模式显示中文**：模式卡片与选择弹窗的取值 `Rule / Global / Direct` 改为
    `规则 / 全局 / 直连`（`StatusSection.MODE_OPTIONS`；发给内核的值仍是小写协议标识）。
21. **隧道模式新增 ROOT EBPF**：`TunMode.RootEbpf`（存储值 `root_ebpf`，submode `ebpf`）复用
    ROOT 启动链，但 override 注入 `tun.enable=false`、不写 tproxy-port / dns.listen，启动与
    attach 两处 netfilter apply 均为空分支——流量劫持完全由配置里的 ebpf listener 自理。
    设置页第四项「ROOT EBPF（eBPF 自动重定向）」；ROOT 设置页对其隐藏热点 BYPASS/PROXY 下拉；
    主页 TUN 卡显示 Inbound=EBPF 并禁点 stack 切换。约束已写进 `docs/root-mode.md`。

### 本轮新增与修复（主页外部面板 + 锚点序列项编辑）

22. **主页「外部面板」入口（app 侧，seed：`patches/app/external-panel.seed.patch`）**：主页「工具」
    网格下方新增整行卡片，点击在**应用内 WebView** 打开内核 `external-ui` 提供的网页控制台
    （zashboard 等）。地址由 `externalPanelUrl()` 从运行态 external-controller 推导
    （`0.0.0.0`/`*` 自动换 127.0.0.1；URL 契约已在条目 27 更新为
    `http://h:p/ui/?hostname=…&port=…&secret=…`）；新增
    `Route.ExternalPanel(url)` 二级页 `ExternalPanelScreen`（JS + localStorage 开启、返回键先在
    面板内回退历史、主文档加载失败时显示排查提示：确认代理已启动且配置了 external-ui）。
23. **锚点序列项参数编辑修复（custom 侧 `YamlEngine.parseSeqItem`）**：`- &锚点 { … }` /
    `- &锚点`（块式）此前不被识别——dash 行的 `&锚点`/`!!标签` 前缀没被跳过，flow 映射被
    KEY_RE 匹出垃圾键，写参数时在 flow 行下面**追加块行**（重复键全量覆盖原值、锚点本身没变、
    整个文件变成非法 YAML），块式项则被解析成空标量、整块缩进内容丢失且不可编辑。现在先剥
    前缀再判形态：flow 原地改、块式正常解析编辑，锚点逐字保留；`EditorLogicProps.testSeqItemAnchors`
    覆盖 5 种形态（flow/块式/内联首键/标签别名/映射条目头）回归。

### 本轮用户反馈 4 bug 修复（2026-10-03：版本号 / 测速反馈 / 锚点写穿 / 外部面板）

24. **主页内核版本号显示 `1.19.31+`（构建层根因）**：主页「内核版本」显示的是内核 `/version`
    的返回（ldflags 注入 `constant.Version`），但上游 `gradle.properties` 里 pin 的
    `mihomo.version=v1.19.31+` 是 metacubex/mihomo 的版本号、与本定制内核无关，且从未被覆盖。
    现在 `scripts/lib.sh` 新增 `kernel_version_string()`：取 `<仓库>/mihomo` 当前提交的 8 位短哈希
    （无 git 信息时回落 `patches/mihomo/BASELINE.txt` 的 `base_commit` 前 8 位），按 jieluojun/mihomo
    官方 CI 的同一命名规则拼出 `alpha-smart-<hash>-with-at`；`build-release.sh` 与
    `ci/build-release.yml` 构建时以 `-Pmihomo.version=…` 注入，`MIHOMO_VERSION` 环境变量可手动覆盖。
    实测（真实构建 + 启动内核）：`/version` 返回 `{"version":"alpha-smart-fc45379e-with-at"}`。
25. **代理页单节点测速「点了没反应」**：上一轮修的是命中区（条目 19）；本轮实测内核后确认
    delay API 本身完全正常（含中文节点名、分组整体测速、失败节点返回 HTTP 503
    `{"message":"An error occurred in the delay test"}`），真正断的是 app 侧的失败反馈链：
    ① `MihomoApiClient` 没开 `expectSuccess`，delay 端点也不查状态码——503 错误体被反序列化成
    `DelayResult(delay = 0)` 的**默认值**、当成「成功」；② `ProxyViewModel` 把 `Result` 直接丢弃，
    成败都无声，用户看到的只有转圈停下。修复：客户端新增 `delayBodyOrThrow()`，非 2xx 抛
    `DelayTestFailedException(node, status, reason)`（复用 `extractErrorMessage` 提取内核 message），
    默认测速地址统一为 `https://www.gstatic.com/generate_204`（与参考实现一致，http 会被部分节点
    拒绝）；ViewModel 把失败发成 `DelayTestEvent` 事件——单节点失败按异常链分类
    `Node / Timeout / Network / Unknown` 四种（`NodeFailed`），整组测速只汇总失败数
    （`GroupSummary`，成功时延迟数字原地刷新本身就是反馈）；`ProxyScreen` 收集事件后按分类
    toast（新增 5 条 × 4 语言字符串）。主动取消（离开页面）不提示。fixes seed 因此新增 3 个
    上游文件：`data/api/MihomoApiClient.kt`、`domain/repository/MihomoRepository.kt`、
    `viewmodel/ProxyViewModel.kt`。
26. **编辑带锚点继承的配置会把 `<<: *anchor` 小节整体物化覆盖**：此前在继承了锚点的小节里改
    任一字段，写回会把整节重排成展开后的字面键值——merge key 引用消失、锚点定义再变化也不会
    传导到这一节（用户报「锚点继承的段被完全覆盖」）。现在 `AnchorInheritance.applySetAware`
    做**写穿（write-through）**：被编辑键的生效值来自 `<<: *anchor` 时，补丁直接落到锚点定义块
    （provider），原小节逐字保留 `<<: *anchor` 引用；被多个小节引用的定义块同步更新（这正是锚点
    语义）。`ConfigFormPanel` / `FlowFormPages` 的提交链路全部接入（`commitExprList` 写穿优先；
    FormHost 递归进定义块时传 chaining 参数跳过 provider 门，修复了锚点链递归写不进去的问题）；
    健康检查等编辑器的占位值改读**继承生效值**（`hasEffective` / `effectiveText` / `effectiveBool`，
    区分本地值与继承值）；DNS 小节相关字段补了告警提示。性质测试
    `tools/forms/kotlin/AnchorWriteThroughProps.kt` 16 组全过；引擎对拍 802/802、引用对拍
    153 例 0 差异、三方锚点对拍 13/0、forms 性质测试 697 项全部保持绿。
27. **外部面板打开空白**：两层根因，都已修并且都在真实内核上实测过。
    **内核层**：mihomo 只在配置 `external-ui` 非空时才挂载 `/ui` 路由——订阅配置基本都不带这个
    字段，`GET /ui` 返回 404 text/plain，WebView 里就是一片空白。`patch_mishka.go` 现在在
    `external-ui` 与 `external-ui-name` 均未配置时注入默认值 `external-ui: ui`（配置目录下的相对
    路径，能过 `IsSafePath`），且当下载地址仍是内置默认（直连 github.com，国内网络基本不可达）
    时换成 gh-proxy 镜像的 zashboard：
    `https://v6.gh-proxy.org/https://github.com/Zephyruso/zashboard/archive/refs/heads/gh-pages-no-fonts.zip`
    （与作者 mihomo_box 自带配置同源）。`AutoDownloadUI` 在 `ui/` 目录为空时启动自动下载一次，
    失败仅记日志、不影响内核运行；用户显式配置过 `external-ui` / `external-ui-name` /
    `external-ui-url` 时一律尊重、不动。实测：注入后启动即下载成功，`/ui/` 200 伺服 zashboard。
    **app 层**：`externalPanelUrl()` 改为 `http://h:p/ui/?hostname=h&port=p&secret=…`。两个实测
    约束：**尾部斜杠必须带**——内核对 `/ui`（无斜杠）回 307，`Location: /ui/` 会丢掉全部 query
    参数；**参数名按 zashboard 的自动登录契约**——解析面板 JS 确认 gate 参数是 `hostname`（缺失
    时返回 null → 停在手动设置页），配合 `port` / `secret`，没有 `host` / `token` 兼容分支。
    secret 从运行态 `ProxyServiceBridge.state` 取。WebView 补 `onReceivedHttpError`：HTTP 4xx/5xx
    不触发 `onReceivedError`（那个只报网络层错误），此前 404 时白屏且没有任何提示，现在会显示
    排查提示文案。

### r3：外部面板残留空白的诊断内嵌（2026-10-03）

28. **外部面板「顶栏渲染但列表区空白」（条目 27 残留，诊断内嵌 + 浏览器兜底）**：设备上 zashboard
    顶栏（代理/代理提供商 tab 与计数、筛选行）渲染正常，代理组卡片区域与底部导航空白。沙箱
    headless Chrome（chrome-for-testing 154）以同形数据（`type: smart` 组 + file 型
    proxy-provider + 中文节点名）复现**渲染完全正常、console 零 JS 错误** → 排除内核 API/数据形
    状根因；bundle 特性下限扫描（oklch×836、color-mix×331、`:has(`×171、dvh×23、toSorted×2，
    最低 ≈ Chrome 111）对照设备截图（oklch 主题 tab 渲染正常）排除「引擎过旧整页不可解析」；
    DOM 结构显示卡片区是**虚拟列表的绝对定位行**（`.proxy-group-card`）、底栏是
    `nav.tab-bar absolute`，残留差异收敛到 WebView 的视口度量/虚拟列表挂载环节。设备侧拿不到
    console 与布局量，本轮把诊断内嵌进面板页：顶栏 Info 按钮打开诊断抽屉，环形缓冲记录
    console / 网络错误（含子资源 4xx/5xx）/ 页面事件；页面加载后 +2.5s / +8s 注入只读探针量取
    根容器（`.h-dvh`）高度、滚动容器高度、`.proxy-group-card` 数量与首行 top、`.tab-bar` 几何、
    dvh/oklch/`:has()` 支持度、localStorage 可用性、引擎版本；抽屉提供「复制」（回传分析）与
    「浏览器打开」（系统浏览器同址兜底，可立即看到完整面板）。**r4 实测分流**：系统浏览器同址
    **完整渲染**、WebView 版本 ≥ 120 → 引擎与数据形状均排除，差异收敛到 app 内 WebView 环节，
    加三条缓释：① 本地面板禁 WebView HTTP 缓存（`LOAD_NO_CACHE`——浏览器缓存独立而表现正常，
    坏缓存副本是「顶栏在、列表空」可持续复现的最简解释）；② 等到 WebView 第一次非零布局再
    `loadUrl`（Compose interop 首帧前 `innerHeight` 可能为 0，虚拟列表按 0 量行且不再重算）；
    ③ 首帧后 +600ms 与每次高度变化补发 `resize`/`scroll` 事件触发虚拟列表与底栏重算。探针保留，
    用于在设备上验证缓释命中（`[layout]`/`[nudge]`/`[probe#]` 行）。

### r5：继承锚点的 override-expr 快捷编辑被拒写（2026-10-03）

29. **「override-expr 未改动：这个位置不能这样写」**：锚点继承来的表达式列表（如订阅条目
    `<<: *锚点`、锚点定义是单行 flow 映射 `&host { override-expr: [ … ] }`）在快捷编辑对话框里
    增/删/改任一条都会触发该提示且不落盘。根因在写穿链路：写穿目标（锚点定义里的
    `override-expr`）的父节点是**单行 flow 映射**，引擎 `flowSet` 对「一行装不下的集合」保守地
    返回原文（设计期宁可不动），于是写穿 / 物化兜底 / 直写三路全部拒写，`commit()` 看到
    `out === doc` 弹「不能这样写」。修复：`AnchorInheritance.applySetAware` 的写穿拒写分支里，
    当写入值是字符串列表且锚点侧父节点是单行 flow 映射时，用既有的 `setFlowMapListField`
    把 flow 映射**展开成块式**（其余键值段与 `<<: *别名` 逐字保留、行尾注释挪到 `键:` 行）再
    写穿——与本地非继承路径（`commitExprList`）早已使用的展开逻辑同源。性质测试新增第 17 组
    （flow 锚点 + 4 条含引号/管道符的长表达式：写穿成功、展开块式、回读等于写入、未继承条目
    不受影响、本地兄弟键保留）；引擎对拍 802/802、引用对拍 153/0、三方锚点对拍与 forms
    性质测试全绿保持。

### r6：外部面板空白真根因——WebView 的 dvh 解析为 0（2026-10-03）

30. **探针回传定位真根因**：r5 诊断抽屉回传显示该设备 WebView 引擎为 **Chrome 154**（最新）、
    `localStorage ok`、`innerHeight=776` 正常、`CSS.supports(…100dvh)` 为 true，但根容器
    `.h-dvh` 的**计算高度为 0px**、`body` 高 0——即 dvh 单位「支持但解析值为 0」
    （Chromium WebView visual-viewport 缺陷）。后果与截图逐条对上：高度链
    （`.h-dvh`→`size-full`→`flex-1`→`h-full` 滚动容器）整体塌成 0，代理卡片**存在于 DOM**
    （探针 `cards:12`、首卡 160×75）但被 0 高 `overflow-y-auto` 容器裁掉；底栏
    `nav.tab-bar absolute; bottom:28px` 相对 0 高定位祖先 → `top:-90` 顶出视口；只有
    `fixed top-0` 的顶栏（tab + 筛选行）幸存 → 「顶栏在、下面全空白」。系统浏览器同址正常
    是因为独立 Chrome 的 dvh 解析正常。**修复**：面板页在页面加载后 400ms/1.5s/4s 按需注入
    dvh→vh 等效替代样式表（覆盖 bundle 全部 dvh 规则：根容器 `.h-dvh`、`70dvh`/`calc(100dvh-3rem)`
    高度档、`max-h-[50/65/70dvh]`、`max-md:`/`md:` 各档、`.custom-background .table-glass:before`
    的 `100dvh/-100dvh` 对、select picker；类名转义逐字对应、`!important` 压过同特异性规则）。
    **仅当 `.h-dvh` 实测 0 高且 `innerHeight>0` 才注入**（dvh 正常的引擎返回 `not-needed`
    完全不干预），样式表留在 head、对 SPA 全部路由生效，幂等（`#mishka-dvh-fix`）。
    沙箱四维对照验证（chrome-for-testing 154 headless，dvh→0px 模拟坏引擎）：
    正常 root=915 / 模拟坏 root=0 / 模拟坏+注入 root=915(`injected`) / 正常+注入 root=915(`not-needed`)。
    诊断探针与缓释保留：`[dvhfix]` 行回传注入结果。

### r7：dvh 修复 v2——像素值方案（设备证明 vh 系单位同样为 0）（2026-10-03）

31. **r6 的 dvh→vh 注入在设备上无效**（回传 `[dvhfix] "injected"` 但布局仍空白）：证明该
    WebView 的 visual-viewport 缺陷影响**全部视口单位**（dvh 与 vh 都解析为 0），只有 JS 的
    `window.innerHeight` 是真实值（776）。v2 改**像素值**方案：以 `innerHeight` 为基准动态生成
    bundle 全部 dvh 规则的等效替代样式表（`.h-dvh`=ih、`70dvh`=0.7·ih、`calc(100dvh-3rem)`=ih−48、
    `max/min-h` 各档按比例、背景玻璃 ±ih），并**直接给根容器写内联 `!important` 像素高度**
    （内联样式不依赖样式表生命周期、优先级最高），`resize` 时重算覆盖（监听器挂一次）。
    生效条件不变：`.h-dvh` 实测 0 高且 `innerHeight>0` 才注入（好引擎 `not-needed` 零干预），
    返回值守改为 `injected-px:<ih>` 便于回传核对。探针同步扩展：`vhPx` / `dvhPx`（100vh/100dvh
    测试 div 的实测像素）、`inlineH`（根容器内联高度）、`fixLen`（注入样式表长度）——下一轮回传
    可直接确认设备单位病理与修复落点。沙箱四维复验（dvh→0px 模拟坏引擎）：
    坏 root=0 / 坏+v2 root=915 `injected-px:915` / 好+v2 root=915 `not-needed`。

### r8：dvh 修复 v3——高度链逐环显式像素（2026-10-03）

32. **r7 回传证明像素高度只修好了根容器**（`root h=776 / inlineH=776px / bodyH=776`），但
    **中间链仍断**：滚动容器 `scroller h=0`、底栏仍 `top:-90`（`vhPx:0 dvhPx:0` 坐实全部视口
    单位为 0；该 WebView 上 %/flex 的高度传递也不可靠）。v3 不再赌级联语义：给高度链每一环
    **显式像素高度**——根容器 `.h-dvh`、wrapper（`.h-dvh>.flex.w-screen`）、`.home-page`、
    `.home-page>.relative.flex-1`（底栏的定位祖先）、滚动容器（`.h-dvh .flex-col.h-full.
    overflow-y-auto`）——样式表与内联 `!important` 双保险，`resize` 重算；dvh 档位映射
    （弹窗/背景玻璃）保留。选择器已对照真实 DOM 转储逐环核对。生效条件不变（好引擎
    `not-needed` 零干预）。探针新增 `w1/hp/rf` 三环高度：若仍有残留，回传能直接指出断点环。
    沙箱复验：坏引擎 root 0→915 `injected-px:915`、好引擎 `not-needed`（中间环需连接态 SPA，
    以设备探针为准）。

### r9：几何全对仍空白 → 纯绘制问题，切软件渲染层（2026-10-03）

33. **r8 回传 + 截图：几何 100% 正确仍空白**（root/hp/rf/scroller 全 776、nav t=686 在视口内、
    12 张卡片已排布 t=107）——布局已无任何问题，缺的是**像素**：唯独 fixed 顶栏（独立合成层）
    渲染、普通文档层不渲染 = 该设备 WebView 的 GPU 光栅/合成缺陷。r9 三管齐下（仍仅坏引擎生效）：
    ① 探针确认 `injected-px` 生效后把 WebView 切到 `LAYER_TYPE_SOFTWARE` 强制全量重绘
    （此类缺陷的标准缓解；每会话一次，`[paint]` 行回传）；② 注入样式追加
    `*{transition/animation-duration:0s!important}`（任何停在起始帧的过渡/动画直接跳终态）；
    ③ 卡片内容/卡片/底栏/页面过渡类强制 `opacity:1!important;visibility:visible!important`
    （覆盖「停在起始不透明度」变体）。探针新增 `ccOp/cardOp/navOp/pageOp` 四个 computed opacity：
    若软件层仍未解决，回传能区分「不透明度卡住」与「光栅失败」。

### r10：BoxProxy 同机对照实锤 → 宿主重做为传统 View Activity（2026-10-03）

34. **r9 回传截图仍空白 + BoxProxy 对照截图（决定性证据）**：同一台设备上，BoxProxy
    （`com.boxproxy.box`）用**传统 View 宿主**的 WebView 完整渲染同一个 zashboard
    （卡片、底栏、几何全部正常），而 Mishka 把 WebView 放进 Compose `AndroidView` interop
    容器后出现「dvh/vh 解析为 0」与「几何全对但文档层不绘制」两个症状——引擎无恙，
    **interop 渲染路径才是根因**，r5–r9 全部 CSS/绘制层修复方向就此关闭。r10 参照 BoxProxy
    的 web 界面实现方式整体重做：新建 `ui/screen/panel/ExternalPanelActivity.kt`
    （普通 `Activity`，XML 布局 `activity_external_panel.xml` 直接声明 WebView，Compose 完全
    退出渲染路径）；导航路由进入即 `startActivity` 并立刻出栈，返回键 `canGoBack()` 优先、
    否则关页。顶栏与 BoxProxy 一致（去掉「外部面板」大标题）：返回箭头 +
    `host:port | 路由名` 标题（路由名从 URL hash 推导，`#/proxies`→「代理」等 6 个路由 ×4 语言）
    + 右侧刷新 / 诊断（调节图标，传统 AlertDialog 抽屉：可复制、浏览器打开、关闭）/
    清缓存重载（垃圾桶图标）三个按钮。r3–r9 的全部防线原样保留在 Activity 内
    （console/HTTP 错误诊断环、布局探针、dvh 像素修复注入、禁 HTTP 缓存、非零布局后加载、
    resize/scroll 补发）；好引擎下修复脚本 `not-needed` 零干预，软件层兜底仅在坏引擎触发。
    `external-panel.seed.patch` 相应重生成：不再包含 `ExternalPanelScreen.kt`，新增
    Activity、布局、4 个顶栏矢量图标与 manifest 注册（`exported=false`，沿用 `Theme.Mishka`
    与 MainActivity 同款 `configChanges`）。
    r10 修订版补齐两处 targetSdk 37 行为适配：系统返回键改走 `OnBackInvokedCallback`
    （API 33+；31–32 保留 legacy `onBackPressed` 兜底），语义与顶栏返回一致——WebView
    有历史先回退网页，否则关页；根布局 `fitsSystemWindows="true"` 适配 Android 15+
    强制 edge-to-edge，避免顶栏顶进状态栏。

### r10 二轮：端口冲突自愈 + 顶栏标题 query 解析（2026-10-04）

35. **回传截图 + toast：`mihomo 进程运行中但 API 无响应 / External controller listen
    error: listen tcp 127.0.0.1:9090: bind: address already in use`，代理起不来**。
    `RootHelper.cleanupOrphanedMihomo` 只清 Mishka 自己的 `libmihomo_runner.so` 孤儿；
    为对照测试安装的第三方 clash 系应用（BoxProxy 等，默认同样监听 127.0.0.1:9090）的内核
    占住首选端口时，mihomo 的 external-controller bind 失败 → 进程活着但 API 死 →
    主页「已停止」、面板连不上只能停 setup 页。新增 `service/ExtCtlPortGuard.kt`：
    启动点装配 `--ext-ctl` 时（孤儿清理之后、ROOT 与 VPN 两条路径都过）先探测回环端口，
    空闲→尊重用户配置原样使用；被占→备用段 `39090–39099` 取第一个空闲者；全占→保留首选
    走原报错路径不静默乱跳。仅对本机回环生效，远程 controller 不动。bridge state 的
    externalController 随 fallback 更新，API 客户端 / 订阅解析 / 面板 URL 全部自动一致。
36. **面板顶栏标题显示成 `zashboard.pages.dev`**：r10 首版从 URL authority 取 host:port，
    但面板 URL 的真实控制器地址在 **query**（`?host=…&port=…&secret=…`，
    zashboard.pages.dev 只是 SPA 载体）。改为 `Uri.getQueryParameter("host"/"port")`
    还原，顶栏恢复 BoxProxy 式 `127.0.0.1:9090 | 代理`（端口 fallback 后同样如实显示）。
37. **r11 的 CI 构建错误修复**（`compileReleaseKotlin` ARGUMENT_TYPE_MISMATCH，
    `ExternalPanelActivity.kt:204`）：`onDestroy` 里 `runCatching { … }` lambda 内直接使用
    可空成员属性 `backInvokedCallback`——Kotlin 的可空 smart cast **不跨 lambda 边界**，
    实参类型仍是 `OnBackInvokedCallback?`。改为局部 `val cb = backInvokedCallback` 承接后
    判空传入（注册点同写法加固）。沙箱用 kotlinc 2.1.21 + android.window 桩复现了旧写法的
    同款报错、并验证新写法编译通过且运行正常。
### r12：listen 失败自愈重试 + 面板地址源修正 + 更新按钮与诊断环（2026-10-04）

38. **对照上游的结论与 listen 失败自愈**：逐文件 diff 上游 `b66e844a`，启动机制
    （`MihomoRunner.kt` / `ConfigGenerator.kt`，secret 与 `--ext-ctl` 的 CLI 通道）与上游
    **零差异**；启动链差异仅 eBPF submode、`ExtCtlPortGuard` 与模式枚举三处，均不碰监听
    装配。故 `External controller listen error` 的成因是 **bind 时刻 9090 已被活进程占用**
    （面板截图为证：`127.0.0.1:9090 | 设置` 连上的正是占用者——配置无 secret 时 API
    免鉴权，zashboard 把对方的实时统计当真面板渲染）。r11 的端口探针与 bind 之间存在
    竞态窗口（或清理未净）时仍会失败，r12 在 ROOT / VPN 两条路径加**自愈重试**：
    `runner.errorMessage` 含 `already in use` 时取备用段 39090–39099 第一个空闲端口再
    `runner.start` 一次，成功即以新地址更新 bridge state；诊断行进 StartDiag。
39. **面板地址源修正**：启动失败后 bridge state 留默认 `127.0.0.1:9090`，面板 URL 因而
    连到占用者（「面板没用配置里的地址」的真相）。新增持久化键 `PANEL_LAST_EXT_CTL` /
    `PANEL_LAST_SECRET`（每次启动尝试与成功后写入），`externalPanelUrlFrom(context)`
    优先读它、state 兜底；AppNavigation 的 `onOpenPanel` 改用它。
40. **面板「更新面板」按钮与诊断回传**：zashboard 设置页「更新面板」在 WebView 里触发
    下载流时，无 `DownloadListener` 会被静默丢弃（「点了没反应」）；r12 设
    DownloadListener 交外部浏览器处理并记 `[download]` 诊断行。顶栏垃圾桶图标
    （清缓存 + 重载）即等价的手动更新路径。诊断抽屉（调节图标）改为合并展示
    **启动诊断环 `StartDiag`**（guard 决策 / cleanup 退出码 / attach 结果 / 完整
    listen 错误 / 重试结果，80 行环）+ 面板自身诊断，一键复制回传，下一轮定位不再靠猜。
41. **r12 首包的两处 CI 编译错误修复**：① `RootHelper.cleanupOrphanedMihomo` 改返回
    `Int` 后 catch 分支漏 return（`NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY`，RootHelper.kt:249）
    ——补 `return -2` 并记 `[cleanup] exception (no su?)` 诊断行；② `onOpenPanel` 是普通
    lambda 而非 @Composable，其中调用 `LocalContext.current` 违反可组合上下文约束
    （AppNavigation.kt:610）——改为在外层 `pagerContent` 可组合 lambda 开头
    `val panelContext = LocalContext.current` 捕获后传入。教训已入流程：改返回类型的函数
    逐分支核 return；非 composable lambda 内禁用可组合 API。

表单入口有两处：YAML 编辑器工具栏在锚点 `MiuixIcons.Link` 左侧提供表单快捷按钮，打开当前 YAML；导入型订阅的「编辑配置 → 覆写」下方也保留入口，优先选 `config.yaml`，否则选首个 `.yaml` / `.yml` 并在内容载入后自动打开表单。两处共用同一编辑器草稿、撤销与保存路径；锚点按钮本身仍留在原位。所有路由、订阅页、编辑器和多语言资源的变化都由 app 补丁统一管理，反向应用即可还原。

### 三条安全边界（这也是它敢写盘的依据）

1. **只动锚点语法**：`&` / `*` 只在「裸文本」区间（不在引号内、不在 `#` 注释后）才算锚点；
   手术只重写目标行/目标块的字符范围，其余字节一个都不动（含 CRLF、行尾空格、注释）。
2. **单一写入点**：所有子对话框都只回传「用户意图文本」，落地统一走面板的 `apply()` →
   `controller.replaceRange(...)` 一次整篇替换（一个撤销单元）→ 同回调里跳转 + toast。
   写盘仍走 Mishka 原有的保存路径（`saveWithValidation()`，YAML 走内核 `fetchAndValid`），
   所以面板写坏配置的唯一后果是「保存被拒绝」，文件不会被改坏。
3. **改动后立刻重扫**：出现悬空引用、或 `<<:` 指向了不再是映射的锚点，都会在 toast 里点名。

### 两条由 YAML 语义决定的硬规则

性质测试（`tools/equiv/edit_props.py`，用真 PyYAML 解析）抓出来的，面板已按此实现：

* **改绑目标必须定义在引用行之前** —— YAML 没有前向别名，`*x` 写在 `&x` 前面会直接解析失败。
  面板的候选列表只列 `defs.any { it.line < ref.line }` 的锚点（`AnchorPanel.refCandidates`）。
* **`<<:` 合并继承只能指向映射** —— 指到标量/序列上，解析器会判非法合并。候选列表对 merge 行额外过滤：
  只排除「能确定不是映射」的形态（序列、正文非 flow 映射的单值标量），
  `d1: &d1 {a: 1, b: 2}` 这种行内映射仍然可选（`AnchorPanel.isMergeableAnchor`）。

### 已知边界（刻意的）

* 嵌套多行的值、缩进后的 flow 根（`k: &u` + 缩进一行 `{a: 1}`）、混合形态块 → 一律判 `Unknown`，
  面板只给「文本」模式。拆不动就不猜。
* 面板文案硬编码中文（同参考实现），未走 `strings.xml` 多语言。
* 「删除定义」在有引用时按钮禁用；如需强删，先改绑/删掉引用行。

### 相关自检工具

| 工具 | 作用 |
| --- | --- |
| `tools/check_kotlin.py <目录>` | tree-sitter 语法门（本仓 5 个文件全过） |
| `tools/check_api.py [--root]` | 可用时检查 app 源码符号与具名参数；当前上游副本核对 80 条 import、调用点 0 个，5 个外部依赖源码缺失，不能替代 Gradle 编译 |
| `tools/equiv/compare.py` | 锚点扫描三方可对拍：参考实现(JS) == Kotlin 转写(Python) == 手写期望，13 个样本 |
| `tools/equiv/edit_props.py` | 编辑层性质测试（PYAML 真解析）：块渲染逐字还原、改名往返/数据不变、摘定义数据等价、改绑类型安全、新建块可解析… |
| `tools/equiv/model_freshness.py` | 盯「Kotlin 源码 ↔ Python 转写模型」哈希，防止测试模型悄悄过期 |
| `tools/export_app_patch.sh` | 改完 Kotlin 后重新导出补丁 + 刷新基线 |

---

## 二、可视化配置表单与已有值回读

配置表单不是一个“打开即空白”的新编辑器：`FormValues` 从当前 YAML 行树读取已有标量、布尔、枚举、序列、映射及 eBPF listener 值，
摘要与弹窗初始态均由这份当前草稿计算。P1 覆盖全局/DNS/TUN/eBPF 等配置；P2 覆盖代理、代理组、规则集合、路由规则和流量隧道；
P3 为 DNS server、应用多选、FakeIP 规则、规则集选择、headers、MAPLIST 与 `override.proxy-name` 等字段提供专用编辑器。
未实现的罕见字段类型保持禁用/提示，不会伪装成可编辑。

专用编辑器按子键写回，尽量不重排未触及的 YAML。MAPLIST 改名在暂存完成后提交，覆盖 A↔B 键交换、改名到刚删除的键等冲突情况；
对嵌套映射或别名值无法安全保留时拒绝重写。eBPF 表单按 `listeners[index]` 绑定单个 listener，兼容 `mode` 与角色 `enable` / 旧 `enabled` 写法，
切换 local/shared 角色时保留其它 listener 参数。`fakeip-icmp: reply` 会提示 FakeIP 段及 TC hook 前置条件：启用的 local + `data-plane=tc`，
或启用且配置 `shared.interface` 的 shared。此项只验证配置条件，不代表目标设备上的 TC hook 实际挂载成功。

入口保留两处：导入型订阅编辑页的「覆写」下方按 `config.yaml` 优先、否则首个 YAML 选择文件并在加载后自动展开表单；YAML 编辑器工具栏也恢复表单快捷按钮，位于锚点面板按钮左侧。锚点面板仍留在原位置。P1/P2/P3 明细与 P4 字段级对拍待办见 [`FORMS.md`](FORMS.md)。

---

## 三、内核换成 `jieluojun/mihomo`（`Alpha` 分支）

### 为什么不能直接换

Mishka 的内核（YuKongA/mihomo `Mishka` 分支）与 jieluojun/mihomo `Alpha` 的关系是：
**同一祖先，Alpha 前沿 747 个提交，Mishka 分支只多 6 个提交**。其中 5 个主题是 Mishka 应用要用到的，
Alpha 里没有；另外 `mishka_core/go.sum` 里也没有 Alpha 新增依赖的哈希。所以「换内核」= 移植补丁 + 解决依赖哈希。

### 移植的 4 个补丁（`patches/mihomo/`）

| 补丁 | 内容要点 | 上游来源 |
| --- | --- | --- |
| `0001-config-override-json` | `config.OverrideJSONPath` + `Parse()` 中合并 JSON（失败仅告警）。`mishka_core/runtime.go` 的 `--override-json` 依赖它。 | `ceafd04` |
| `0002-sing-tun-mishka-build-tag` | `server_android.go` 的构建约束改成 `android && (!cmfa \|\| mishka)`、`server_notandroid.go` 改成 `!android \|\| (cmfa && !mishka)`；`mishka` 下无条件启用 uid→包名解析；fd 模式跳过重复建路由/无过滤时早返回。 | `2b7de8f` |
| `0003-config-mishka-tun-dns-patch` | `mishkaPatch` 钩子 + `config/patch_mishka.go`（`//go:build mishka`）：DNS 关闭时注入 fake-ip 默认值（国内外 nameserver + `28.0.0.0/8` + STUN/主机/门禁过滤表）；VPN 模式追加 `system://` 兜底；VPN 模式按白名单重建 `tun` 段，丢掉 Linux-only 字段；`external-ui`/`external-ui-name` 均未配置时注入 `external-ui: ui` 并把内置默认下载地址换成 gh-proxy 镜像的 zashboard（供应用内外部面板伺服 `/ui`，条目 27，本条为定制自加、非 YuKongA 移植）。 | `addd66b` + `4c724df` |
| `0004-sing-tun-forwarder-bind-interface` | fd 模式恢复 `forwarderBindInterface = true`（上游删掉后 Android VPN 下延迟测试通、真实流量不通）。 | `523fc3e` |

未移植的两项（有意）：

* `ab405ba`（`stack: mips`）：**Alpha 已具备**（`constant/tun.go` 的 `TunMips` + 映射；`mishka_core` 里
  `Const.TunMips` 就是它们的用法）。编译实测通过。
* `4c724df` 里的 `--prefetch` CLI 与内核 `main.go` 改动：Android 构建不编译内核的 `main` 包
  （入口是 `mishka_core` 的 `mihomoEntry()`），`mishka_core/fetch.go` 自带进程内 prefetch。故省略。

### 依赖哈希：`go.work` + `go.work.sum`

不解这个问题，构建会在「要么改仓库里的 `go.mod`/`go.sum`，要么失败」之间二选一。
工作区模式让编译器改看 `go.work.sum`，仓库文件一个字节不动 —— 机制、生成命令与反证见 `kernel/README.md`。

### 风险与回退

* Alpha 比 Mishka 内核基点新 747 个提交（Smart 组、eBPF、CNS、mipstack…），补丁保证的是**编译通过 +
  Mishka 依赖的内核行为齐全**；运行时差异请实机验证。
* 退回官方内核：`scripts/revert-patches.sh --all`（子模块回到上游、删掉 go.work）。

---

## 四、只出 release 的构建集成

1. **本地脚本**：`scripts/build-release.sh` 只跑 `:app:downloadGeoFiles` + `:app:assembleRelease`；
   没有签名配置时自动调 `gen-keystore.sh` 生成（否则 release APK 装不上）。
2. **Gradle init 脚本**：`init/no-debug.init.gradle` 在命令行点名 debug 任务时直接抛错
   （只看命令行请求的任务，不去翻任务图——release 图里本来就有 `stripReleaseDebugSymbols` 这类名字）。
   确实要 debug 包时加 `-PallowDebugBuild=true`。
3. **CI**：`ci/build-release.yml`（复制成 `.github/workflows/release.yml`）。要点：
   * 只 checkout `scripta` 子模块（`mihomo` 子模块用不到，会被 `setup.sh` 换成 jieluojun 内核）；
   * 跑 `mishka-custom/scripts/setup.sh --ci` 完成内核 + 补丁 + `go.work`；
   * secrets 里有 keystore 就用它，没有就用固定参数的 debug 风格密钥兜底并在 Summary 里提示；
   * 只跑 `:app:assembleRelease`；打 `v*` tag 时把 APK 挂到 Release。
4. **为什么执着于 release**：release 打开 R8/资源剥离（`optimization.enable = true`、
   `packaging.resources.excludes.add("**")`），debug 包体积大得多且不是要发的产物。

---

## 五、构建配置改动的落点（只通过可撤销 app 补丁修改上游文件）

| 需要的东西 | 落点 | 是否新增文件 |
| --- | --- | --- |
| 自定义源码 | `app/src/main/kotlin/.../custom/{anchor,forms}/*.kt`（17 个文件） | ✅ 新增（补丁） |
| 配置入口与路由 | `AppNavigation.kt`、`Route.kt`、`SubscriptionEditScreen.kt`、`FileManagerEditorScreen.kt` 与 4 份 `strings.xml` | ⚠️ 8 个上游文件由 app 补丁可逆修改；锚点仍在工具栏，表单入口在「覆写」下方 |
| 主页/代理页修复与 ROOT EBPF（条目 18–21、24–26 的 app 侧） | `HomeViewModel.kt`、`StatusSection.kt`、`ProxyScreen.kt`、`SettingsScreen.kt`、`RootSettingsScreen.kt`、`ProxyServiceController.kt`、`MishkaRootService.kt`、`RuntimeOverrideBuilder.kt`、`DynamicNotificationManager.kt`、`MainActivity.kt`、`MihomoApiClient.kt`、`MihomoRepository.kt`、`ProxyViewModel.kt`、4 份 `strings.xml` 与 `docs/root-mode.md` | ⚠️ 上游文件由 app 补丁可逆修改（seed：`home-proxy-root-fixes.seed.patch`） |
| 主页外部面板（条目 22、27 的 app 侧） | `ui/screen/panel/ExternalPanelScreen.kt`（新增）、`Route.kt`、`AppNavigation.kt`、`HomeScreen.kt`、`QuickEntriesSection.kt` 与 4 份 `strings.xml` | ⚠️ 上游文件由 app 补丁可逆修改（seed：`external-panel.seed.patch`） |
| 内核替换 | `<仓库>/mihomo`（原子上游子模块目录） | 目录内容替换 + `submodule.mihomo.ignore=all`（可还原） |
| 依赖哈希 | `<仓库>/go.work`、`go.work.sum` | ✅ 新增 |
| 只出 release | `mishka-custom/init/no-debug.init.gradle` | ✅ 新增 |
| CI | `.github/workflows/release.yml`（由 `ci/build-release.yml` 复制） | ✅ 新增 |
| 签名 | `local.properties` + `mishka-custom/keystore/` | ✅ 新增（都在 gitignore / exclude 里） |
| 上游 `.github/workflows/build.yml`、`build.gradle.kts`、`gradle.properties`、`mishka_core/go.mod`、`go.sum` | — | ❌ 一律不动 |
