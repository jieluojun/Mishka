# P1 配置主页：界面清单（自动生成，勿手改）

来源：`tools/forms/fields.json`（从 mihomo_box WebUI 求值提取）。

## hub：13 个入口

| # | 入口 | 子标题（计数） | 本阶段 |
| --- | --- | --- | --- |
| 1 | 全局配置 | 模式 / API / 日志 / Geo / Smart | ✅ P1 |
| 2 | DNS | 已启用 · 增强模式 | ✅ P1 |
| 3 | 域名嗅探 | TLS/HTTP/QUIC 域名恢复 | ✅ P1 |
| 4 | 入站 | N 个端口 · TUN 开/关 · N 个监听器 | ✅ P1 |
| 5 | 出站代理 | N 个节点 | ✅ P2（见 FORMS-P2.md） |
| 6 | 代理集合 | N 个订阅 | ✅ P2（见 FORMS-P2.md） |
| 7 | 代理组 | N 个代理组 | ✅ P2（见 FORMS-P2.md） |
| 8 | 路由规则 | N 条规则 | ✅ P2（见 FORMS-P2.md） |
| 9 | 规则集合 | N 个规则集 | ✅ P2（见 FORMS-P2.md） |
| 10 | 子规则 | N 组子规则 | ✅ P2（见 FORMS-P2.md） |
| 11 | 流量隧道 | TCP/UDP 端口转发 | ✅ P2（见 FORMS-P2.md） |
| 12 | NTP | 时间同步 | ✅ P1 |
| 13 | 实验性配置 | QUIC / 拨号器 | ✅ P1 |

## P1 各页字段

### 全局配置（GENERAL_SECTIONS，共 43 个字段）

**基础**（12）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `mode` | 运行模式 | select | 取值：rule / global / direct；内核启动时的默认模式；主页上的模式切换只走外部面板 API，不会改动这里 |
| `log-level` | 日志等级 | select | 取值：silent / error / warning / info / debug；内核默认 info |
| `ipv6` | IPv6 总开关 | bool | 默认 开；内核默认开启；关闭则阻断所有 IPv6 连接及 AAAA 记录 |
| `unified-delay` | 统一延迟 | bool | 去除握手等额外延迟，更换延迟计算方式 |
| `tcp-concurrent` | TCP 并发 | bool | 并发连接所有 IP，使用最快握手 |
| `allow-lan` | 允许局域网连接 | bool |  |
| `bind-address` | 绑定地址 | text | 提示：仅 allow-lan 为真时生效 |
| `interface-name` | 出口网卡 | text | 提示：如 wlan0 |
| `global-client-fingerprint` | 全局指纹(旧内核) | select | 取值：chrome / firefox / safari / ios / android / edge / random；⚠️ 新版本已移除 |
| `find-process-mode` | 进程匹配模式 | select | 取值：always / strict / off |
| `routing-mark` | 路由标记 routing-mark | number |  |
| `global-ua` | 全局 User-Agent | text | 提示：如 clash.meta |

**局域网访问控制**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `authentication` | 认证用户(用户:密码) | list | 格式：user:password |
| `skip-auth-prefixes` | 跳过认证的 IP 段 | list | 格式：如 127.0.0.1/8 |
| `lan-allowed-ips` | 允许接入的 IP 段 | list | 格式：如 0.0.0.0/0 |
| `lan-disallowed-ips` | 禁止接入的 IP 段 | list | 格式：黑名单优先于白名单 |

**外部控制 / API**（11）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `external-controller` | API 监听地址 | text | 提示：127.0.0.1:9090；改地址后需重启内核 |
| `secret` | API 密钥 | text |  |
| `external-ui` | 面板路径 | text | 提示：如 ui（相对=工作目录下）；外部面板静态文件目录 |
| `external-ui-name` | 面板目录名（URL 路径） | text | 提示：ui；访问路径名，空则直接用 external-ui |
| `external-ui-url` | 面板下载地址 | text | 无本地面板时自动下载。手机建议用无字体版（gh-pages-no-fonts.zip，1.4MB，走系统字体）：带字体版 |
| `external-controller-tls` | API TLS 监听地址 | text | 提示：127.0.0.1:9443 |
| `external-controller-unix` | API Unix Socket | text | 提示：/tmp/mihomo.sock |
| `external-controller-pipe` | API 命名管道 | text | 提示：\\.\pipe\mihomo（Windows） |
| `external-doh-server` | DoH 服务路径 | text | 提示：如 /dns-query |
| `external-controller-cors.allow-origins` | CORS 允许来源 | list | 格式：如 * 或 http://127.0.0.1 |
| `external-controller-cors.allow-private-network` | CORS 允许私有网络 | bool |  |

**Geo 数据**（7）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `geox-url.geoip` | GeoIP 下载地址 | text |  |
| `geox-url.geosite` | GeoSite 下载地址 | text |  |
| `geox-url.mmdb` | GeoIP MMDB 下载地址 | text |  |
| `geox-url.asn` | ASN 数据库地址 | text |  |
| `geo-auto-update` | Geo 数据自动更新 | bool |  |
| `geo-update-interval` | 更新间隔(小时) | number |  |
| `geosite-matcher` | GeoSite 匹配器 | select | 取值：succinct / mph |

**杂项**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `profile.store-selected` | 记住节点选择 | bool | 默认 开；内核默认开启 |
| `profile.store-fake-ip` | 持久化 fake-ip | bool |  |
| `keep-alive-interval` | TCP 保活探测间隔(秒) | number | 提示：15 |
| `keep-alive-idle` | TCP 保活空闲时间(秒) | number | 提示：600 |
| `disable-keep-alive` | 禁用 TCP 保活 | bool |  |

**Smart 内核专属（LightGBM 模型）**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `lgbm-auto-update` | 模型自动更新 | bool | 默认关闭 |
| `lgbm-update-interval` | 更新间隔(小时) | number | 提示：72 |
| `lgbm-url` | 模型下载地址 | text | 提示：模型 bin 文件 URL |
| `profile.smart-collector-size` | 采样数据大小 (MB) | number | 提示：100；Smart 数据采集缓存大小 |

### DNS（DNS_SECTIONS，共 23 个字段）

**基础**（8）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `dns.enable` | 启用内置 DNS | bool |  |
| `dns.cache-algorithm` | 缓存算法 | select | 取值：arc / lru |
| `dns.prefer-h3` | 优先 HTTP/3 DoH | bool |  |
| `dns.use-hosts` | 使用 hosts | select | 取值：True / False；回应配置中的 hosts 映射；内核默认开启 |
| `dns.use-system-hosts` | 使用系统 hosts | select | 取值：True / False；查询系统 hosts；内核默认开启 |
| `dns.respect-rules` | 按规则分流解析 | bool | 代理规则使用对应上游解析 |
| `dns.listen` | 监听地址 | text | 提示：0.0.0.0:7874；内核 DNS 服务器监听 |
| `dns.ipv6` | 解析 IPv6 (AAAA) | bool |  |

**增强模式 / fake-ip**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `dns.enhanced-mode` | 增强模式 | select | 取值：normal / fake-ip / redir-host；fake-ip=虚拟 IP，加速且便于按域名分流；redir-host=返回真实 IP 并记录域名映射 |
| `dns.fake-ip-range` | fake-ip 地址池 | text | 提示：198.18.0.1/16；fake-ip 模式的 IPv4 池，默认 198.18.0.1/16 |
| `dns.fake-ip-range6` | fake-ip v6 地址池 | text | 提示：fd00::/108；可选，未设则不分配 v6 fake-ip |
| `dns.fake-ip-filter-mode` | 过滤模式 | select | 取值：blacklist / whitelist / rule；blacklist=命中走 real-ip；whitelist=仅命中走 fake-ip；rule=按规则逐条匹配 fa |
| `dns.fake-ip-filter` | fake-ip 过滤 | fakeiprule | 按 fake-ip-filter-mode 自动切换：rule 时为结构化规则表（逐条 fake-ip/real-ip， |
| `dns.fake-ip-ttl` | fake-ip TTL | number | 提示：1；fake-ip 响应的 TTL（秒），内核默认 1 |

**上游服务器**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `dns.default-nameserver` | 默认 DNS(解析上游) | dnslist | 引导 DNS：解析下方各服务器域名用，必须为 IP（可为加密 DNS） |
| `dns.nameserver` | 主 DNS 服务器 | dnslist |  |
| `dns.fallback` | fallback（已弃用） | dnslist | ⚠️ 弃用；旧版字段，Alpha 内核已弃用 |
| `dns.proxy-server-nameserver` | 代理服务器解析 DNS | dnslist | 仅用于解析代理节点的域名，建议国内直连 DNS；不填则遵循 nameserver-policy/nameserver/f |
| `dns.direct-nameserver` | 直连规则 DNS | dnslist | direct 出口域名解析用，配合 respect-rules 使用 |
| `dns.direct-nameserver-follow-policy` | 直连 DNS 遵循策略 | bool | direct-nameserver 是否遵循 nameserver-policy，内核默认否；仅「直连规则 DNS」非空 |

**按域名分流解析**（2）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `dns.nameserver-policy` | nameserver-policy | maplist | 域名/geosite → 专用 DNS 列表 |
| `dns.proxy-server-nameserver-policy` | proxy-server-nameserver-policy | maplist | 格式同 nameserver-policy，仅用于代理节点的域名解析；当且仅当「代理服务器解析 DNS」非空时生效 |

**hosts 自定义**（1）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `hosts` | hosts 自定义 | maplist | 键=域名（支持 *. 通配），值=IP，多个逗号分隔；类似 /etc/hosts |

### 域名嗅探（SNIFF_SECTIONS，共 14 个字段）

****（0）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |

**基础**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `sniffer.enable` | 启用嗅探 | bool |  |
| `sniffer.force-dns-mapping` | 强制 DNS 映射 | select | 取值：True / False；内核默认开启；对解析后 IP 的连接映射回域名再匹配规则 |
| `sniffer.parse-pure-ip` | 解析纯 IP 请求 | select | 取值：True / False；内核默认开启；目标是纯 IP 的连接也进行嗅探 |
| `sniffer.override-destination` | 覆盖目标地址 | select | 取值：True / False；内核默认开启；用嗅探出的域名覆盖连接的目标 IP |

**协议端口与覆盖**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `sniffer.sniff.HTTP.ports` | HTTP 监听端口 | numlist | 格式：如 80, 8080 |
| `sniffer.sniff.HTTP.override-destination` | HTTP 覆盖目标地址 | bool |  |
| `sniffer.sniff.TLS.ports` | TLS 监听端口 | numlist | 格式：如 443, 8443 |
| `sniffer.sniff.TLS.override-destination` | TLS 覆盖目标地址 | bool |  |
| `sniffer.sniff.QUIC.ports` | QUIC 监听端口 | numlist | 格式：如 443 |
| `sniffer.sniff.QUIC.override-destination` | QUIC 覆盖目标地址 | bool |  |

**嗅探名单**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `sniffer.force-domain` | 强制嗅探的域名 | list | 格式：如 +.v2ex.com |
| `sniffer.skip-domain` | 跳过嗅探的域名 | list | 格式：如 Mijia Cloud / +.apple.com |
| `sniffer.skip-src-address` | 跳过来源地址段 | list | 格式：CIDR |
| `sniffer.skip-dst-address` | 跳过目标地址段 | list | 格式：CIDR |

### 入站·端口（INBOUND_PORTS，共 5 个字段）

**端口（0 或留空 = 禁用该项）**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `port` | HTTP 代理端口 | number |  |
| `socks-port` | SOCKS5 代理端口 | number |  |
| `mixed-port` | HTTP+SOCKS 混合端口 | number |  |
| `redir-port` | Redir 透明代理端口 | number |  |
| `tproxy-port` | TProxy 端口 (TCP/UDP) | number |  |

### 入站·TUN（TUN_SECTIONS，共 28 个字段）

**基础**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `tun.enable` | 启用 TUN | bool | 开启时自动把 eBPF 入站的 local.enable / shared.enable 置为 false（参数原样保留 |
| `tun.stack` | 协议栈 | select | 取值：system / gvisor / mixed / mips；留空 = 内核默认 gvisor。system=系统协议栈，更稳定全面、占用相对更低；gvisor=用户态实现，隔离性更 |
| `tun.device` | 虚拟网卡名 | text | 提示：默认 Meta |
| `tun.mtu` | MTU | number | 提示：9000 |
| `tun.gso` | GSO 分段卸载 | bool |  |
| `tun.gso-max-size` | GSO 最大包长 | number |  |

**路由**（11）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `tun.auto-route` | 自动配置路由表 | bool |  |
| `tun.auto-redirect` | 自动配置 iptables 重定向 | bool | 本机 TCP 快速重定向（Android 上 sing-tun 仅装 OUTPUT 链，不作用于转发流量）；热点共享的转 |
| `tun.auto-detect-interface` | 自动识别出口网卡 | bool |  |
| `tun.strict-route` | 严格路由 | bool | 防止泄漏，但局域网不可达 |
| `tun.disable-icmp-forwarding` | 禁用 ICMP 转发 | bool |  |
| `tun.route-address` | 自定义路由地址集 | list | 格式：如 0.0.0.0/1 |
| `tun.route-address-set` | 路由规则集(包含) | list | 格式：rule-set 名称 |
| `tun.route-exclude-address-set` | 路由规则集(排除) | list |  |
| `tun.endpoint-independent-nat` | 独立于端点的 NAT (EIM) | bool |  |
| `tun.dns-hijack` | DNS 劫持地址 | list | 格式：如 any:53 |
| `tun.udp-timeout` | UDP NAT 超时(秒) | number |  |

**范围过滤（需 auto-route）**（11）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `tun.include-android-user` | 包含的安卓用户 ID | numlist | 格式：如 0、10 |
| `tun.include-package` | 仅代理以下应用 | applist | 留空 = 全部应用；Tproxy 开启时同样生效（白名单，优先于排除名单） |
| `tun.exclude-package` | 排除以下应用 | applist | 被排除应用直连；Tproxy 开启时同样生效（黑名单） |
| `tun.include-uid` | 包含 UID | numlist |  |
| `tun.exclude-uid` | 排除 UID | numlist |  |
| `tun.include-uid-range` | 包含 UID 范围 | list | 格式：start:end，如 10000:19999 |
| `tun.exclude-uid-range` | 排除 UID 范围 | list |  |
| `tun.include-interface` | 包含网卡 | list |  |
| `tun.exclude-interface` | 排除网卡 | list |  |
| `tun.include-mac-address` | 包含 MAC 地址 | list | 格式：AA:BB:CC:DD:EE:FF |
| `tun.exclude-mac-address` | 排除 MAC 地址 | list |  |

### 入站·eBPF（EBPF_SECTIONS，共 35 个字段）

**基础（listeners[].type=ebpf）**（8）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| — | （专门控件） | `ebpf.master` | 由代码实现的定制行，见 P1 渲染层 |
| `name` | 名称 name | text |  |
| — | （专门控件） | `network` | 由代码实现的定制行，见 P1 渲染层 |
| `udp-timeout` | UDP 超时(秒) | number | 提示：内核默认 300，最小 5；可热更新：单独改它不重建入站，已建立的会话与热点客户端不会被打断 |
| `tc-priority` | TC 优先级 tc-priority | number | 提示：默认 1；默认 1：内核支持时经 TCX 挂载，否则 clsact；填其他值一律用 clsact 过滤器 |
| `bypass-rule-set` | 绕行规则集 | rulesetpick | 从已添加的规则集（rule-providers）中选择，命中的 CIDR 在内核里直接绕行。仅 behavior: ip |
| `bypass-tun-direct` | 绕过项直达(配合TUN) | bool | 内核默认开启。被 bypass 的目标仍走路由表、会被 TUN auto-route 声称：开启=命中绕行的流量到达时直 |
| — | （专门控件） | `fakeip-icmp` | 由代码实现的定制行，见 P1 渲染层 |

**local 模式（cgroup 本机应用）**（15）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| — | （专门控件） | `local.enable` | 由代码实现的定制行，见 P1 渲染层 |
| `local.data-plane` | 数据面 data-plane | select | 取值：cgroup / tc；cgroup=接管本机套接字；tc=默认网卡 egress 抓包（内核运行时探测可用性） |
| `local.cgroup-path` | cgroup 路径 | text | 提示：留空自动探测，一般无需填写；仅 data-plane=cgroup 可填，且必须是 cgroup2 挂载内的绝对路径 |
| `local.dns-mode` | DNS 处理 dns-mode | select | 取值：hijack / respect_policy / off；hijack=接管所有 53 端口流量；respect_policy=先按 UID/来源策略；off=不处理 |
| `local.ipv6` | 接管 IPv6 | bool | 内核默认开启 |
| `local.bypass-private-address` | 绕过私有地址 | bool | 内核默认开启。开启=私有网段（10/8、172.16/12、192.168/16、100.64/10、169.254/1 |
| `local.include-uid` | 包含 UID | numlist |  |
| `local.include-uid-range` | 包含 UID 范围 | list | 格式：start:end |
| `local.exclude-uid` | 排除 UID | numlist |  |
| `local.exclude-uid-range` | 排除 UID 范围 | list | 格式：start:end |
| `local.include-android-user` | 包含安卓用户 | numlist | 格式：如 0、10（多开/工作资料） |
| `local.include-package` | 仅代理以下应用 | applist |  |
| `local.exclude-package` | 排除以下应用 | applist |  |
| `local.bypass-port` | 绕过目标端口 | numlist | 格式：如 22、3478；发往这些目标端口的流量一律不接管（DNS 劫持仍按 dns-mode 处理） |
| `local.bypass-port-range` | 绕过端口范围 | list | 格式：start:end，如 27000:27100 |

**shared 模式（热点/共享网络 TC 转发）**（12）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| — | （专门控件） | `shared.enable` | 由代码实现的定制行，见 P1 渲染层 |
| `shared.data-plane` | 数据面 data-plane | select | 取值：packet_rewrite / socket_assign；packet_rewrite 需要以太网帧（MAC 名单也要）；raw-IP 链路（如部分 USB 网络共享）需 soc |
| `shared.dns-mode` | DNS 处理 dns-mode | select | 取值：hijack / respect_policy / off；hijack=接管所有 53 端口流量；respect_policy=先按 UID/来源策略；off=不处理 |
| `shared.interface` | 下游接口 | list | 格式：如 wlan0、ap0、swlan0；shared / hybrid 下必填且不能是 lo；正作为默认上游的接口会被暂时跳过，回到下游角色后自动接管 |
| `shared.ipv6` | 接管 IPv6 | bool | 内核默认开启 |
| `shared.bypass-private-address` | 绕过私有地址 | bool | 内核默认开启。开启=来自热点的私有网段目标不接管直连 |
| `shared.include-source-cidr` | 包含来源 CIDR | list | 格式：如 192.168.43.0/24 |
| `shared.exclude-source-cidr` | 排除来源 CIDR | list |  |
| `shared.include-mac-address` | 包含 MAC | list | 格式：aa:bb:cc:dd:ee:ff |
| `shared.exclude-mac-address` | 排除 MAC | list |  |
| `shared.bypass-port` | 绕过目标端口 | numlist | 格式：如 22、3478；发往这些目标端口的下游流量一律不接管 |
| `shared.bypass-port-range` | 绕过端口范围 | list | 格式：start:end，如 27000:27100 |

### NTP（NTP_SECTIONS，共 6 个字段）

**NTP 时间同步**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `ntp.enable` | 启用 NTP | bool | TLS 握手对时间敏感 |
| `ntp.server` | NTP 服务器 | text | 提示：time.apple.com |
| `ntp.port` | NTP 端口 | number | 提示：123 |
| `ntp.interval` | 同步间隔(分钟) | number |  |
| `ntp.write-to-system` | 同步后写入系统时间 | bool | ⚠️ 需 root |
| `ntp.dialer-proxy` | NTP 出站代理 dialer-proxy | select | 默认 DIRECT；强制 NTP 走指定出站 |

### 实验性配置（EXPERIMENTAL_SECTIONS，共 3 个字段）

****（0）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |

**QUIC / 拨号器**（3）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `experimental.quic-go-disable-gso` | 禁用 QUIC GSO | bool | 打开 = 关闭 GSO 分段卸载加速 |
| `experimental.quic-go-disable-ecn` | 禁用 QUIC ECN | bool | 默认 开；打开 = 关闭 QUIC 的 ECN 显式拥塞通知；部分网络设备会丢弃 ECN 标记包，QUIC 变慢/不通时可开启 |
| `experimental.dialer-ip4p-convert` | IP4P 地址转换 | bool | 启用 IP4P 地址转换（natmap 域名访问） |

