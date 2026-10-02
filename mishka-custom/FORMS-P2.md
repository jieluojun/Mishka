# P2 流量页面：界面清单（自动生成，勿手改）

来源（两份都是从 mihomo_box WebUI 参考实现求值提取的）：

* `tools/forms/fields.json` —— `extract_fields.mjs`：协议专属字段（PER_TYPE）、传输层（NET_FIELDS）、TLS / 多路复用 / 通用字段、新建模板、exclude-type 候选、override-expr 预设（顶层字面量）
* `tools/forms/fields_p2.json` —— `extract_flow.mjs`（jieluojun/mihomo_box@657e799778）：pages-flow.js / pages-config.js 各编辑器函数体内的字段表、模板、默认值与常量；每张表的出处见下方「出处」

## hub：7 个 P2 入口

| 入口 | 配置键 | 形态 | 列表页 | 详情页 |
| --- | --- | --- | --- | --- |
| 出站代理 | `proxies` | 序列 | 名称 · 协议 · 服务器；新建（27 种协议模板）/ 上移下移 / 删除（引用保护） | 名称 · 协议（只读）+ 服务器 / 端口 + 协议参数 + 传输层 + TLS / Reality + 多路复用 + 通用链式 / 拨号 + 表单之外的字段 |
| 代理集合 | `proxy-providers` | 映射 | 名称 · 类型 · 来源；新建（名字 + 类型 + 链接）/ 改名（同步 use）/ 删除（引用保护） | 基础 + 请求 / 过滤 + 健康检查（三态开关）+ 覆写 override + override-expr |
| 代理组 | `proxy-groups` | 序列 | 名称 · 类型 · 成员数；新建（模板「手动选择」）/ 上移下移 / 删除（引用保护） | 名称 · 类型 + 成员 + 通用参数（按类型显隐）+ Smart 专属 |
| 路由规则 | `rules` | 序列 | 一行一条；添加 / 编辑 / 上移下移 / 删除 / 文本模式 | 规则编辑（类型 → 匹配值 → 目标策略 → no-resolve → 预览；可切文本编辑） |
| 规则集合 | `rule-providers` | 映射 | 名称 · 类型 · behavior；新建（名字 + 类型 + 链接）/ 改名（同步 RULE-SET）/ 删除（引用保护） | 基础（file 隐藏远程字段；inline 的 payload 复用规则列表页） |
| 子规则 | `sub-rules` | 映射 | 名称 · 条数；新建 / 改名 / 删除 | 复用路由规则列表页 |
| 流量隧道 | `tunnels` | 序列 | 单行写法 / 映射写法都能读；新增（单行 / 映射）/ 上移下移 / 删除 | 映射项按字段表编辑；单行项整行改 |

## 出站代理：协议清单与小节

| 协议 | 额外小节 | 协议参数字段数 | 模板键 |
| --- | --- | --- | --- |
| `direct` | tail | 2 | name, type, udp |
| `dns` | tail | 0 | name, type |
| `reject` | tail | 0 | name, type |
| `rematch` | tail | 2 | name, type, target-rematch-name |
| `http` | server tls tail | 3 | name, type, server, port |
| `socks5` | server tls tail | 3 | name, type, server, port, udp |
| `ss` | server smux tail | 5 | name, type, server, port, cipher, password, udp |
| `ssr` | server smux tail | 6 | name, type, server, port, cipher, password, protocol, obfs, udp |
| `snell` | server tail | 4 | name, type, server, port, psk, version, udp |
| `vmess` | server net tls tlsbool smux tail | 4 | name, type, server, port, uuid, alterId, cipher, udp |
| `vless` | server net tls tlsbool smux tail | 4 | name, type, server, port, uuid, udp, client-fingerprint |
| `trojan` | server net tls tlsbool smux tail | 1 | name, type, server, port, password, sni, udp |
| `anytls` | server tls tlsbool smux tail | 4 | name, type, server, port, password, sni, skip-cert-verify, udp |
| `mieru` | server tail | 4 | name, type, server, port, username, password, transport |
| `sudoku` | server tail | 5 | name, type, server, port, key, aead |
| `hysteria` | server tls smux tail | 4 | name, type, server, port, auth-str, protocol, up, down, sni |
| `hysteria2` | server tls smux tail | 7 | name, type, server, port, password, sni, skip-cert-verify |
| `tuic` | server tls smux tail | 6 | name, type, server, port, uuid, password, congestion-controller, udp-relay-mode |
| `shadowquic` | server tail | 11 | name, type, server, port, username, password |
| `wireguard` | server tail | 10 | name, type, server, port, ip, private-key, public-key, udp |
| `tailscale` | tail | 9 | name, type, hostname, auth-key, udp, accept-routes |
| `ssh` | server tail | 5 | name, type, server, port, username, password |
| `masque` | server tail | 11 | name, type, server, port, private-key, public-key, ip, mtu, udp |
| `trusttunnel` | server tail | 14 | name, type, server, port, username, password, health-check, udp |
| `zerotier` | tail | 10 | name, type, network, udp |
| `openvpn` | server tail | 17 | name, type, server, port, proto, username, password, ca, udp |
| `cns` | server tail | 4 | name, type, server, port, key, password, flag, udp |

小节含义（参考实现 NO_SERVER / SHOW_NET / SHOW_SMUX / SHOW_TLS / SHOW_HYTLS）：server=服务器/端口；net=传输层（network + 对应 NET_FIELDS，切换时删其它 `*-opts`，http 补 `http-opts.method: GET`）；tls=TLS / Reality 小节；tlsbool=显示 `tls` 开关（开→补 `servername: example.com`，关→删 servername / sni）；smux=多路复用；tail=通用链式 / 拨号。与协议参数重复的路径由渲染层去重。 参考实现流程页没列到、按模板兜底的协议：cns。

### 基础字段

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `server` | 服务器地址 | text |  |
| `port` | 端口 | number |  |

### 协议专属字段（PER_TYPE）

**direct**（2）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `udp` | UDP 支持 | bool |  |
| `ip-version` | IP 版本 | select | 取值：dual / ipv4 / ipv6 / ipv4-prefer / ipv6-prefer |

**rematch**（2）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `target-rematch-name` | 重匹配名称 REMATCH-NAME | text |  |
| `target-sub-rule` | 跳转子规则 sub-rule | text |  |

**http**（3）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码 | text |  |
| `tls` | TLS (https) | bool |  |

**socks5**（3）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码 | text |  |
| `tls` | TLS 加密 | bool |  |

**ss**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `cipher` | 加密方式 cipher | text | 提示：aes-256-gcm / 2022-blake3-aes-128-gcm |
| `password` | 密码 | text |  |
| `plugin` | 插件 plugin | select | 取值：obfs / v2ray-plugin / shadow-tls / restls |
| `plugin-opts` | 插件参数 plugin-opts | maptext | 如 mode: tls / host: bing.com / password: xxx |
| `udp-over-tcp` | UDP over TCP | bool |  |

**ssr**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `cipher` | 加密方式 cipher | text |  |
| `password` | 密码 | text |  |
| `protocol` | 协议 protocol | text | 提示：origin / auth_aes128_md5 |
| `obfs` | 混淆 obfs | text | 提示：plain / http_simple |
| `protocol-param` | 协议参数 | text |  |
| `obfs-param` | 混淆参数 | text |  |

**snell**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `psk` | PSK 密钥 | text |  |
| `version` | 协议版本 | select | 取值：1 / 2 / 3 / 4 / 5 |
| `obfs-opts.mode` | 混淆模式 | select | 取值：http / tls |
| `obfs-opts.host` | 混淆 Host | text | 提示：如 itunes.apple.com |

**vmess**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `uuid` | UUID | text |  |
| `alterId` | alterId | number |  |
| `cipher` | 加密方式 | select | 取值：auto / aes-128-gcm / chacha20-poly1305 / none |
| `xudp` | XUDP | bool |  |

**vless**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `uuid` | UUID | text |  |
| `flow` | flow 流控 | select | 取值：xtls-rprx-vision |
| `packet-encoding` | 包编码 | select | 取值：packetaddr / xudp |
| `encryption` | encryption（ML-KEM 等） | text | 提示：none / mlkem768x25519plus.… |

**trojan**（1）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `password` | 密码 | text |  |

**anytls**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `password` | 密码 | text |  |
| `idle-session-check-interval` | 空闲检查间隔(秒) | number |  |
| `idle-session-timeout` | 空闲会话超时(秒) | number |  |
| `min-idle-session` | 最小空闲会话数 | number |  |

**mieru**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码 | text |  |
| `transport` | 传输 transport | select | 取值：TCP |
| `multiplexing` | 多路复用 | select | 取值：MULTIPLEXING_OFF / MULTIPLEXING_LOW / MULTIPLEXING_MIDDLE / MULTIPLEXING_HIGH |

**sudoku**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `key` | 密钥 key | text |  |
| `aead` | AEAD 加密 | bool |  |
| `table-type` | 映射表类型 | text | 提示：prefer_ascii / prefer_entropy |
| `padding-min` | 最小填充(%) | number |  |
| `padding-max` | 最大填充(%) | number |  |

**hysteria**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `auth-str` | 认证串 auth-str | text |  |
| `protocol` | 协议 | select | 取值：udp / wechat-video / faketcp |
| `up` | 上行带宽 | text |  |
| `down` | 下行带宽 | text |  |

**hysteria2**（7）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `password` | 密码 | text |  |
| `up` | 上行带宽 | text | 提示：30 Mbps / 数字 |
| `down` | 下行带宽 | text | 提示：200 Mbps |
| `obfs` | 混淆 obfs | select | 取值：salamander |
| `obfs-password` | 混淆密码 | text |  |
| `ports` | 端口跳跃 ports | text | 提示：10000-20000；范围或逗号分隔多端口；留空=单端口 |
| `hop-interval` | 跳跃间隔 hop-interval（秒） | number |  |

**tuic**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `uuid` | UUID | text |  |
| `password` | 密码 | text |  |
| `congestion-controller` | 拥塞控制 | select | 取值：cubic / bbr / new_reno |
| `udp-relay-mode` | UDP 中继模式 | select | 取值：native / quic |
| `reduce-rtt` | 0-RTT 握手 | bool |  |
| `heartbeat-interval` | 心跳间隔(ms) | number |  |

**shadowquic**（11）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码 | text |  |
| `sni` | SNI | text |  |
| `alpn` | ALPN | list | 格式：h3 |
| `quic-versions` | QUIC 版本 | text | 提示：v1 |
| `udp-over-stream` | UDP over Stream | bool |  |
| `zero-rtt` | 0-RTT | bool |  |
| `keep-alive-interval` | 保活间隔（ms） | number |  |
| `congestion-controller` | 拥塞控制 | select | 取值：cubic / bbr / new_reno |
| `bbr-profile` | BBR 策略 | select | 取值：standard / conservative / aggressive |
| `max-datagram-frame-size` | 最大 Datagram 帧 | number |  |

**wireguard**（10）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `ip` | 本机 IPv4（CIDR） | text | 提示：172.16.0.2/32 |
| `ipv6` | 本机 IPv6（CIDR） | text |  |
| `private-key` | 本机私钥 | text |  |
| `public-key` | 对端公钥 | text |  |
| `pre-shared-key` | 预共享密钥 PSK | text |  |
| `allowed-ips` | AllowedIPs | list | 格式：0.0.0.0/0, ::/0 |
| `dns` | DNS 服务器 | list |  |
| `mtu` | MTU | number |  |
| `remote-dns-resolve` | 远端 DNS 解析 | bool |  |
| `fwmark` | fwmark 路由标记 | number |  |

**tailscale**（9）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `hostname` | 设备名 hostname | text |  |
| `auth-key` | 登录密钥 auth-key | text | 提示：tskey-auth-xxxx |
| `control-url` | 控制服务地址 | text | 提示：https://controlplane.tailscale.com |
| `state-dir` | 状态目录 state-dir | text |  |
| `ephemeral` | 临时节点 ephemeral | bool |  |
| `accept-routes` | 接受路由 accept-routes | bool |  |
| `exit-node` | 出口节点 exit-node | text | 提示：100.64.0.1 |
| `exit-node-allow-lan-access` | 出口节点允许 LAN 访问 | bool |  |
| `udp` | UDP 支持 | bool |  |

**ssh**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码（与私钥二选一） | text |  |
| `private-key` | 私钥（路径或内容） | text |  |
| `host-key-algorithms` | 主机公钥算法 | list |  |
| `host-key` | 固定主机公钥 | list |  |

**masque**（11）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `private-key` | 私钥 private-key (Base64) | text |  |
| `public-key` | 公钥 public-key (Base64) | text |  |
| `ip` | 本机 IPv4（CIDR） | text | 提示：172.16.0.2/32 |
| `ipv6` | 本机 IPv6（CIDR） | text |  |
| `network` | 工作模式 network | select | 取值： / h3-l4proxy / h2 |
| `sni` | SNI | text |  |
| `mtu` | MTU | number |  |
| `udp` | UDP 支持 | bool |  |
| `congestion-controller` | 拥塞控制 | select | 取值：cubic / bbr / new_reno |
| `bbr-profile` | BBR 策略 | select | 取值：standard / conservative / aggressive |
| `handshake-timeout` | 握手超时（秒） | number |  |

**trusttunnel**（14）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `username` | 用户名 | text |  |
| `password` | 密码 | text |  |
| `health-check` | 健康检查 | bool |  |
| `udp` | UDP 支持 | bool |  |
| `quic` | QUIC 传输 | bool |  |
| `sni` | SNI | text |  |
| `alpn` | ALPN | list | 格式：h2 |
| `client-fingerprint` | TLS 指纹 | select | 取值：chrome / firefox / safari / ios / android / edge / random / randomized |
| `congestion-controller` | 拥塞控制 | select | 取值：cubic / bbr / new_reno |
| `bbr-profile` | BBR 策略 | select | 取值：standard / conservative / aggressive |
| `skip-cert-verify` | 跳过证书校验 | bool |  |
| `max-connections` | 最大连接数 | number |  |
| `min-streams` | 最小复用流 | number |  |
| `max-streams` | 最大复用流 | number |  |

**zerotier**（10）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `network` | 网络 ID (16 位 hex) | text | 提示：0123456789abcdef |
| `state-dir` | 状态目录 | text |  |
| `planet` | 私有 Planet 文件 | text |  |
| `mtu` | MTU | number |  |
| `physical-mtu` | 物理 MTU | number |  |
| `primary-port` | 主端口 | number |  |
| `secondary-port` | 次端口 | number |  |
| `low-bandwidth` | 低带宽模式 | bool |  |
| `encrypted-hello` | 加密握手 | bool |  |
| `udp` | UDP 支持 | bool |  |

**openvpn**（17）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `proto` | 传输协议 proto | select | 取值：udp / tcp；仅支持 udp / tcp |
| `dev` | 虚拟网卡类型 dev | select | 取值：tun；内核校验：非 tun 直接报错 |
| `ca` | CA 根证书（PEM） | textarea | 提示：-----BEGIN CERTIFICATE-----
…
-----END CERTIFICATE-----；必填。用于校验服务端证书，缺失会导致节点不可用 |
| `cert` | 客户端证书（PEM） | textarea | 提示：-----BEGIN CERTIFICATE-----
…
-----END CERTIFICATE-----；证书认证时填；与下方用户名密码二选一 |
| `key` | 客户端私钥（PEM） | textarea | 提示：-----BEGIN PRIVATE KEY-----
…
-----END PRIVATE KEY----- |
| `username` | 用户名 | text |  |
| `password` | 密码 | password |  |
| `tls-crypt` | tls-crypt 静态密钥 | textarea | 提示：-----BEGIN OpenVPN Static key V1-----
…
-----END OpenVPN Static key V1-----；控制通道加密密钥（可与服务端 tls-crypt 配套） |
| `cipher` | 加密算法 cipher | select | 取值：AES-128-GCM / AES-192-GCM / AES-256-GCM / CHACHA20-POLY1305 / AES-128-CBC / AES-192-CBC / AES-256-CBC；内核校验：仅接受列出的套件，留空= AES-128-GCM |
| `auth` | HMAC 校验 auth | select | 取值：SHA256 / SHA1 / SHA384 / SHA512 / MD5；留空= SHA256 |
| `comp-lzo` | LZO 压缩 comp-lzo | select | 取值：yes / adaptive / no；adaptive 等同 yes |
| `mtu` | MTU | number |  |
| `ping` | ping 间隔（秒） | number |  |
| `ping-restart` | ping-restart（秒） | number |  |
| `udp` | UDP 支持 | bool |  |
| `remote-dns-resolve` | 远端 DNS 解析 | bool |  |
| `dns` | 远端 DNS 服务器 | list |  |

**cns**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `key` | 密钥 key | text | 提示：Meng；对应服务端 Proxy_key，省略默认 Meng |
| `password` | 密码 password | text | 对应 Encrypt_password，空则不 XOR |
| `flag` | UDP 标志 flag | text | 提示：httpUDP |
| `headers` | 请求头 headers | headers | 如 Host 伪装 |

### 传输层（NET_FIELDS）

network 取值：`tcp`（tcp（默认））、`ws`（ws · WebSocket）、`h2`（h2 · HTTP/2）、`grpc`（grpc）、`http`（http）、`xhttp`（xhttp · 新版）

**network = ws**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `ws-opts.path` | WS 路径 | text | 提示：/ |
| `ws-opts.headers` | WS 请求头 | headers | 自定义 WebSocket 请求头：左边填请求头名，右边填值 |
| `ws-opts.max-early-data` | max-early-data | number |  |
| `ws-opts.early-data-header-name` | early-data 头名 | text | 提示：Sec-WebSocket-Protocol |
| `ws-opts.v2ray-http-upgrade` | HTTP Upgrade 模式 | bool |  |
| `ws-opts.v2ray-http-upgrade-fast-open` | HTTP Upgrade Fast Open | bool |  |

**network = http**（3）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `http-opts.method` | HTTP 方法 | text | 提示：GET |
| `http-opts.path` | HTTP 路径列表 | list | 格式：如 / |
| `http-opts.headers` | HTTP 请求头 | headers | 自定义 HTTP 请求头：左边填请求头名，右边填值；同一个请求头可添加多行作为候选值（每次请求随机取一个） |

**network = h2**（2）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `h2-opts.host` | H2 Host 列表 | list |  |
| `h2-opts.path` | H2 路径 | text | 提示：/ |

**network = grpc**（1）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `grpc-opts.grpc-service-name` | gRPC ServiceName | text |  |

**network = xhttp**（5）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `xhttp-opts.path` | XHTTP 路径 | text | 提示：/ |
| `xhttp-opts.host` | XHTTP Host | text |  |
| `xhttp-opts.mode` | XHTTP 模式 | select | 取值：auto / packet-up / stream-up / stream-one |
| `xhttp-opts.headers` | XHTTP 请求头 | headers | 自定义 XHTTP 请求头：左边填请求头名，右边填值 |
| `xhttp-opts.no-grpc-header` | 禁用 gRPC 头 | bool |  |

### TLS / Reality（TLS_FIELDS，8）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `tls` | 启用 TLS | bool |  |
| `servername` | SNI（servername） | text | 提示：同义：sni |
| `skip-cert-verify` | 跳过证书校验 | bool |  |
| `alpn` | ALPN | list | 格式：h2, http/1.1 |
| `client-fingerprint` | uTLS 指纹 | select | 取值：chrome / firefox / safari / ios / android / edge / random / randomized |
| `fingerprint` | 证书指纹(HEX) | text |  |
| `reality-opts.public-key` | Reality public-key | text |  |
| `reality-opts.short-id` | Reality short-id | text |  |

### 多路复用 smux（SMUX_FIELDS，6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `smux.enabled` | 启用多路复用 smux | bool |  |
| `smux.protocol` | 复用协议 | select | 取值：smux / yamux / h2mux |
| `smux.max-connections` | 最大连接数 | number |  |
| `smux.min-streams` | 最小流数 | number |  |
| `smux.max-streams` | 最大流数 | number |  |
| `smux.padding` | 填充 padding | bool |  |

### 通用链式 / 拨号（TAIL_FIELDS，7）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `udp` | UDP | bool |  |
| `tfo` | TFO (TCP Fast Open) | bool |  |
| `mptcp` | MPTCP | bool |  |
| `dialer-proxy` | 链式出口 dialer-proxy | select | 本节点经此出口建立连接（代理名/代理组） |
| `interface-name` | 绑定出口网卡 | text |  |
| `routing-mark` | 路由标记 routing-mark | number |  |
| `ip-version` | IP 版本偏好 | select | 取值：dual / ipv4 / ipv6 / ipv4-prefer / ipv6-prefer |

### 新建模板（PROXY_TEMPLATES）

| 协议 | 模板 |
| --- | --- |
| `direct` | `{"name": "新的直连", "type": "direct", "udp": true}` |
| `dns` | `{"name": "DNS 出站", "type": "dns"}` |
| `reject` | `{"name": "新的拒绝出站", "type": "reject"}` |
| `rematch` | `{"name": "新的REMATCH", "type": "rematch", "target-rematch-name": "mark1"}` |
| `http` | `{"name": "新的HTTP代理", "type": "http", "server": "1.2.3.4", "port": 8080}` |
| `socks5` | `{"name": "新的Socks5", "type": "socks5", "server": "1.2.3.4", "port": 1080, "udp": true}` |
| `ss` | `{"name": "新的SS节点", "type": "ss", "server": "1.2.3.4", "port": 8388, "cipher": "aes-256-gcm", "password": "密码", "udp": true}` |
| `ssr` | `{"name": "新的SSR节点", "type": "ssr", "server": "1.2.3.4", "port": 8388, "cipher": "aes-256-cfb", "password": "密码", "protocol": "origin", "obfs": "http_simple", "udp": true}` |
| `snell` | `{"name": "新的Snell节点", "type": "snell", "server": "1.2.3.4", "port": 8388, "psk": "密码", "version": 4, "udp": true}` |
| `vmess` | `{"name": "新的VMess节点", "type": "vmess", "server": "1.2.3.4", "port": 443, "uuid": "uuid", "alterId": 0, "cipher": "auto", "udp": true}` |
| `vless` | `{"name": "新的VLESS节点", "type": "vless", "server": "1.2.3.4", "port": 443, "uuid": "uuid", "udp": true, "client-fingerprint": "chrome"}` |
| `trojan` | `{"name": "新的Trojan节点", "type": "trojan", "server": "1.2.3.4", "port": 443, "password": "密码", "sni": "example.com", "udp": true}` |
| `anytls` | `{"name": "新的AnyTLS节点", "type": "anytls", "server": "1.2.3.4", "port": 443, "password": "密码", "sni": "example.com", "skip-cert-verify": false, "udp": true}` |
| `mieru` | `{"name": "新的Mieru节点", "type": "mieru", "server": "1.2.3.4", "port": 2999, "username": "user", "password": "密码", "transport": "TCP"}` |
| `sudoku` | `{"name": "新的Sudoku节点", "type": "sudoku", "server": "1.2.3.4", "port": 8080, "key": "密钥", "aead": true}` |
| `hysteria` | `{"name": "新的Hy1节点", "type": "hysteria", "server": "1.2.3.4", "port": 443, "auth-str": "密码", "protocol": "udp", "up": 30, "down": 100, "sni": "example.com"}` |
| `hysteria2` | `{"name": "新的Hy2节点", "type": "hysteria2", "server": "1.2.3.4", "port": 443, "password": "密码", "sni": "example.com", "skip-cert-verify": false}` |
| `tuic` | `{"name": "新的Tuic节点", "type": "tuic", "server": "1.2.3.4", "port": 443, "uuid": "uuid", "password": "密码", "congestion-controller": "cubic", "udp-relay-mode": "native"}` |
| `shadowquic` | `{"name": "新的ShadowQUIC", "type": "shadowquic", "server": "www.example.com", "port": 10443, "username": "user", "password": "pass"}` |
| `wireguard` | `{"name": "新的WireGuard", "type": "wireguard", "server": "1.2.3.4", "port": 51820, "ip": "172.16.0.2/32", "private-key": "本机私钥", "public-key": "对端公钥", "udp": true}` |
| `tailscale` | `{"name": "新的Tailscale", "type": "tailscale", "hostname": "mihomo", "auth-key": "tskey-auth-xxxx", "udp": true, "accept-routes": true}` |
| `ssh` | `{"name": "新的SSH节点", "type": "ssh", "server": "1.2.3.4", "port": 22, "username": "root", "password": "密码"}` |
| `masque` | `{"name": "新的MASQUE", "type": "masque", "server": "server.com", "port": 443, "private-key": "", "public-key": "", "ip": "172.16.0.2/32", "mtu": 1280, "udp": true}` |
| `trusttunnel` | `{"name": "新的TrustTunnel", "type": "trusttunnel", "server": "1.2.3.4", "port": 443, "username": "user", "password": "pass", "health-check": true, "udp": true}` |
| `zerotier` | `{"name": "新的ZeroTier", "type": "zerotier", "network": "0123456789abcdef", "udp": true}` |
| `openvpn` | `{"name": "新的OpenVPN", "type": "openvpn", "server": "vpn.example.com", "port": 1194, "proto": "udp", "username": "user", "password": "pass", "ca": "-----BEGIN CERTIFICATE-----\n请替换为 CA 根证书内容\n-----END CERTIFICATE-----", "udp": true}` |
| `cns` | `{"name": "新的CNS节点", "type": "cns", "server": "1.2.3.4", "port": 23333, "key": "Meng", "password": "", "flag": "httpUDP", "udp": true}` |

## 代理组（GROUP_SECTIONS，共 29 个字段）

proxy-groups（序列，按下标编辑；带 only 的字段按组的 type 显隐）

**成员**（2）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `proxies` | 成员(顺序生效) | picklist | 候选：policies（运行时从配置里收集） |
| `use` | 使用订阅(use) | picklist | 候选：providers（运行时从配置里收集） |

**通用参数（按类型自动显示）**（21）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `include-all` | 包含全部 (代理+订阅) | bool | 自动包含全部 proxies 和 providers（按名称排序） |
| `include-all-proxies` | 包含全部代理 | bool | 仅包含全部 proxies |
| `include-all-providers` | 包含全部订阅 | bool | 仅包含全部 providers |
| `filter` | 节点名称过滤(正则) | text | 提示：如 (?i)港|hk|hongkong；仅对 include-all/use 引入的节点生效，多正则用 ` 分隔 |
| `exclude-filter` | 排除节点(正则) | text | 排除匹配的节点 |
| `exclude-type` | 排除节点类型 | picklist | 取值：Shadowsocks / ShadowsocksR / Snell / Socks5 / Http / Vmess / Vless / Trojan；按类型排除，仅对 proxies 引入生效，无视大小写 |
| `url` | 测速 URL | text | 仅 url-test / fallback / load-balance / smart；提示：https://www.gstatic.com/generate_204；url-test/fallback/load-balance 必填 |
| `interval` | 测速间隔(秒) | number | 仅 url-test / fallback / load-balance / smart；提示：300 |
| `timeout` | 测速超时(ms) | number | 仅 url-test / fallback / load-balance / smart；提示：5000 |
| `lazy` | 懒加载 | bool | 仅 url-test / fallback / load-balance / smart；未被选中时不测速（默认 true） |
| `tolerance` | 容差(ms) | number | 仅 url-test / smart；提示：50；仅 url-test/smart：新节点快于当前多少才切换 |
| `max-failed-times` | 最大失败次数 | number | 仅 url-test / fallback / load-balance / smart；提示：5；超过则强制健康检查 |
| `expected-status` | 期望状态码 | text | 仅 url-test / fallback / load-balance / smart；提示：204 或 2xx 或 200/302/400-503；支持 / - 组合，默认 * |
| `strategy` | 负载策略 | select | 仅 load-balance；取值：consistent-hashing / round-robin / sticky-sessions；load-balance 专属，默认 consistent-hashing |
| `default-selected` | 默认选中 | select | 仅 select；候选：group-members（运行时从配置里收集）；组的默认选择项；留空或填了不存在的名字则用第一个成员 |
| `empty-fallback` | 空组回退 | select | 取值：DIRECT / REJECT / REJECT-DROP / PASS / PASS-RULE / COMPATIBLE；组内一个可用节点都没有时走哪条内置策略，默认 COMPATIBLE |
| `disable-udp` | 禁用 UDP | bool | 该组禁用 UDP 转发 |
| `interface-name` | 绑定出口网卡 | text | 提示：如 en0 / eth0；已废弃，建议在节点上配置；优先级 节点>组>全局 |
| `routing-mark` | 路由标记 | number | 已废弃，建议在节点上配置 |
| `hidden` | 隐藏代理组 | bool | 在仪表盘隐藏 |
| `icon` | 图标 URL | text | 提示：https://...；仪表盘显示图标 |

**Smart 专属参数**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `uselightgbm` | 使用 LightGBM 预测权重 | bool | 仅 smart |
| `collectdata` | 采样数据统计 | bool | 仅 smart |
| `sample-rate` | 采样率 (0~1) | number | 仅 smart；提示：默认 1 |
| `prefer-asn` | ASN 粒度权重 | bool | 仅 smart；按目标 ASN 训练/选择 |
| `tolerance` | 切换容差(ms) | number | 仅 smart；延迟差小于该值不切换 |
| `policy-priority` | 节点权重策略 | text | 仅 smart；提示：如 Premium:0.9;SG:1.3；<1 降权 >1 加权，正则/串匹配 |

## 代理集合（PROXY_PROVIDER_SECTIONS，共 35 个字段）

proxy-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）

**基础**（9）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `type` | 类型 | select | 取值：http / file / inline |
| `url` | 订阅链接 | text | 仅 http / inline；http 类型必填 |
| `path` | 保存路径 | text | 提示：默认 ./proxies/ 目录 |
| — | （专门控件） | `provider-file-ops` | 由代码实现的定制行 |
| `interval` | 自动更新间隔(秒) | number | 仅 http / inline |
| `size-limit` | 订阅大小限制(字节) | number | 仅 http / inline；0 为不限制 |
| `proxy` | 下载出口 | select | 仅 http / inline；候选：outbound（运行时从配置里收集）；下载订阅使用的出口 |
| `age-secret-key` | AGE 解密密钥 | text | 仅 http / inline；加密订阅内容自动解密 |
| — | （专门控件） | `provider-payload` | 由代码实现的定制行 |

**请求 / 过滤**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `header` | 请求头 header | headers | 仅 http / inline；如 UA / Authorization，每行一条 |
| `filter` | 筛选节点 filter | text | 提示：(?i)港|hk|hongkong；筛选满足关键词或正则表达式的节点 |
| `exclude-filter` | 排除节点 exclude-filter | text | 排除匹配的节点 |
| `exclude-type` | 排除协议类型 | picklist | 取值：Shadowsocks / ShadowsocksR / Snell / Socks5 / Http / Vmess / Vless / Trojan；按类型排除节点；无视大小写 |

**健康检查**（6）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| — | （专门控件） | `provider-health-enable` | 由代码实现的定制行 |
| `health-check.url` | 测速 URL | text |  |
| `health-check.interval` | 测速间隔(秒) | number |  |
| `health-check.timeout` | 测速超时(ms) | number |  |
| `health-check.lazy` | 懒加载 | bool |  |
| `health-check.expected-status` | 期望状态码 | text | 提示：204 或 2xx |

**覆写 override（对该订阅全部节点生效）**（15）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `override.additional-prefix` | 节点名前缀 | text | 提示：[机场名]  |
| `override.additional-suffix` | 节点名后缀 | text |  |
| `override.tfo` | TFO (TCP Fast Open) | bool |  |
| `override.mptcp` | MPTCP | bool |  |
| `override.udp` | UDP | bool |  |
| `override.udp-over-tcp` | UDP over TCP (UoT) | bool |  |
| `override.up` | 上行带宽 | text | 提示：10 Mbps |
| `override.down` | 下行带宽 | text | 提示：50 Mbps |
| `override.skip-cert-verify` | 跳过证书校验 | bool |  |
| `override.name-cert-verify` | 证书 DNSName 校验目标 | text | 不改 SNI，只改校验对象 |
| `override.dialer-proxy` | 链式出口 dialer-proxy | select | 候选：outbound（运行时从配置里收集）；订阅内全部节点经此出口建立连接 |
| `override.interface-name` | 绑定出口网卡 | text |  |
| `override.routing-mark` | 路由标记 routing-mark | number |  |
| `override.ip-version` | IP 版本偏好 | select | 取值：dual / ipv4 / ipv6 / ipv4-prefer / ipv6-prefer |
| `override.proxy-name` | proxy-name 批量重命名 | maplist | 正则 pattern→target，支持 $1 引用 |

**override-expr（按表达式批量修改节点 · 新版）**（1）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| — | （专门控件） | `override-expr` | 由代码实现的定制行 |

## 规则集合（RULE_PROVIDER_SECTIONS，共 9 个字段）

rule-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）

**基础**（9）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `type` | 类型 | select | 取值：http / file / inline |
| `behavior` | 规则类型 | select | 取值：domain / ipcidr / classical |
| `format` | 格式 | select | 取值：yaml / text / mrs |
| `url` | 下载链接 | text | 仅 http / inline |
| `path` | 本地路径 | text | 提示：默认 ./rules/ 目录 |
| — | （专门控件） | `rule-provider-file-ops` | 由代码实现的定制行 |
| `interval` | 更新间隔(秒) | number | 仅 http / inline |
| `proxy` | 下载出口 | select | 仅 http / inline；候选：outbound（运行时从配置里收集）；下载规则集使用的出口 |
| — | （专门控件） | `rule-provider-payload` | 由代码实现的定制行 |

## 流量隧道（TUNNEL_SECTIONS，共 4 个字段）

tunnels（序列；单行写法 `tcp/udp,地址,目标[,策略]` 整行改，映射写法按字段表）

**隧道**（4）

| 路径 | 标签 | 类型 | 说明 |
| --- | --- | --- | --- |
| `network` | 协议 network | picklist | 取值：tcp / udp；映射写法里是列表（内核 Tunnel.network []string） |
| `address` | 监听地址 address | text | 提示：如 0.0.0.0:8080 |
| `target` | 目标地址 target | text | 提示：如 example.com:80 |
| `proxy` | 出口代理 proxy（留空直连） | select | 候选：policies（运行时从配置里收集） |

## 规则类型（RULE_TYPES，37 种）

拆合照参考实现 parseRule：去掉末尾 `no-resolve`，第一段是类型，最后一段是目标策略，中间整段是匹配值（MATCH 没有匹配值）；AND / OR / NOT / SUB-RULE 的匹配值用多行输入。

| 类型 | 提示语 | 多行 | IP 类（no-resolve） | 目标 |
| --- | --- | --- | --- | --- |
| `DOMAIN` | 完整域名，如 www.google.com |  |  | 策略 |
| `DOMAIN-SUFFIX` | 域名后缀，如 google.com / .google.com |  |  | 策略 |
| `DOMAIN-KEYWORD` | 域名关键词，如 google |  |  | 策略 |
| `DOMAIN-REGEX` | 正则，如 ^.*google.* |  |  | 策略 |
| `DOMAIN-WILDCARD` | 通配，如 *.google.com |  |  | 策略 |
| `GEOSITE` | geosite 类别，如 cn / google / category-ads-all |  |  | 策略 |
| `GEOIP` | 国家码，如 CN / 私有地址 private |  | 是 | 策略 |
| `IP-CIDR` | IPv4 段，如 91.108.4.0/22 |  | 是 | 策略 |
| `IP-CIDR6` | IPv6 段，如 2001:db8::/32 |  | 是 | 策略 |
| `IP-SUFFIX` | IP 后缀，如 8.8.8.8/24 |  | 是 | 策略 |
| `IP-ASN` | ASN 号，如 13335 |  | 是 | 策略 |
| `SRC-GEOIP` | 源 GeoIP，如 CN |  | 是 | 策略 |
| `SRC-IP-ASN` | 源 ASN，如 9808 |  | 是 | 策略 |
| `SRC-IP-CIDR` | 源 IP 段，如 192.168.1.0/24 |  | 是 | 策略 |
| `SRC-IP-SUFFIX` | 源 IP 后缀 |  | 是 | 策略 |
| `DST-PORT` | 目标端口，如 443 / 80-443 |  |  | 策略 |
| `SRC-PORT` | 源端口 |  |  | 策略 |
| `IN-PORT` | 入站端口，如 7890 |  |  | 策略 |
| `IN-TYPE` | 入站类型，如 SOCKS / HTTP |  |  | 策略 |
| `IN-USER` | 入站用户，如 mihomo |  |  | 策略 |
| `IN-NAME` | 入站名称，如 ss |  |  | 策略 |
| `REMATCH-NAME` | Rematch 名称 |  |  | 策略 |
| `PROCESS-NAME` | 进程名，如 chrome.exe |  |  | 策略 |
| `PROCESS-NAME-REGEX` | 进程名正则 |  |  | 策略 |
| `PROCESS-NAME-WILDCARD` | 进程名通配，如 *telegram* |  |  | 策略 |
| `PROCESS-PATH` | 进程路径，如 /usr/bin/curl |  |  | 策略 |
| `PROCESS-PATH-REGEX` | 路径正则 |  |  | 策略 |
| `PROCESS-PATH-WILDCARD` | 路径通配 |  |  | 策略 |
| `UID` | 用户 ID |  |  | 策略 |
| `NETWORK` | TCP / UDP |  |  | 策略 |
| `DSCP` | DSCP 值，如 4 |  |  | 策略 |
| `RULE-SET` | 规则集名称（需在规则集页面创建） |  | 是 | 策略（匹配值为规则集合名） |
| `AND` | 逻辑与，如 ((DOMAIN,example.com),(NETWORK,UDP)) | 是 |  | 策略 |
| `OR` | 逻辑或 | 是 |  | 策略 |
| `NOT` | 逻辑非，如 ((DOMAIN,example.com)) | 是 |  | 策略 |
| `SUB-RULE` | 子规则名称 | 是 |  | 子规则名 |
| `MATCH` | 兜底规则，无需匹配值 |  |  | 策略 |

## 模板 / 默认值 / 常量

* 新建代理组：`{"name": "手动选择", "type": "select", "proxies": ["DIRECT"], "use": [], "url": "https://cp.cloudflare.com/generate_204", "interval": 300, "timeout": 5000, "lazy": true}`
* 新建代理集合：`{"type": "http", "url": "", "interval": 86400, "path": ""}`（http 必填链接；file 默认路径 `./proxies/<名字>.yaml`；inline 带空 payload）
* 新建规则集合：`{"type": "http", "behavior": "domain", "format": "yaml", "url": "", "interval": 86400}`（file 默认路径 `./rules/<名字>.<yaml|txt|mrs>`；inline 带空 payload）
* 新建隧道：`{"network": "tcp", "address": "", "target": ""}`（映射写法里 network 写成列表）
* file 类型不落盘的远程字段：代理集合 url, interval, size-limit, proxy, age-secret-key, header；规则集合 url, interval, proxy
* 健康检查开启时补的默认值：`{"url": "https://cp.cloudflare.com/generate_204", "interval": 300}`
* exclude-type 候选（23）：Shadowsocks、ShadowsocksR、Snell、Socks5、Http、Vmess、Vless、Trojan、Hysteria、Hysteria2、WireGuard、Tuic、Ssh、Mieru、AnyTLS、ShadowQuic、OpenVPN、Tailscale、ZeroTier、Sudoku、Masque、TrustTunnel、GostRelay
* override-expr 预设（6）：前缀改名、后缀改名、开启 UDP、删除跳过证书校验、仅保留 TLS、混淆覆写
* 内置策略：DIRECT（直连，数据直接出站）、REJECT（拒绝，拦截数据出站）、REJECT-DROP（拒绝，静默抛弃请求，不像 REJECT 那样回应错误）、PASS（绕过，跳过当前命中的规则分支继续匹配；在 SUB-RULE 中会跳出子规则回到主规则）、PASS-RULE（绕过，同 PASS，但在 SUB-RULE 中不跳出，继续在子规则内向后匹配）、COMPATIBLE（兼容，策略组筛选不出节点时出现，等效 DIRECT）
* 代理组类型：select、url-test、fallback、load-balance、smart、relay
* 代理集合类型：http、file、inline；规则集合类型：http、file、inline

## 出处（fields_p2.json 的 provenance）

| 表 | 文件:行 |
| --- | --- |
| `OVPN_CIPHERS` | `pages-flow.js:22` |
| `OVPN_AUTHS` | `pages-flow.js:31` |
| `FP_OPTS` | `pages-flow.js:128` |
| `EXCLUDE_TYPE_OPTIONS` | `pages-flow.js:1236` |
| `BUILTIN_POLICIES` | `pages-config.js:11` |
| `NO_SERVER` | `pages-flow.js:345` |
| `SHOW_NET` | `pages-flow.js:348` |
| `SHOW_SMUX` | `pages-flow.js:349` |
| `SHOW_TLS` | `pages-flow.js:350` |
| `SHOW_HYTLS` | `pages-flow.js:351` |
| `NET_TYPES` | `pages-flow.js:352` |
| `NET_OPTS_KEYS` | `pages-flow.js:434` |
| `PROXY_TEMPLATES` | `pages-flow.js:64` |
| `PROXY_BASE_FIELDS` | `pages-flow.js:420` |
| `FILE_HIDDEN_KEYS` | `pages-flow.js:702` |
| `GROUP_TYPES` | `pages-flow.js:1238` |
| `RULE_TYPES` | `pages-flow.js:1732` |
| `HC_DEFAULTS` | `pages-flow.js:921` |
| `HC_SUB` | `pages-flow.js:922` |
| `EXPR_PRESETS` | `pages-flow.js:1128` |
| `PROVIDER_DEFAULT` | `pages-flow.js:831` |
| `SUB_BASIC` | `pages-flow.js:840` |
| `SUB_HTTP_ONLY_IDX` | `pages-flow.js:883` |
| `SUB_filter` | `pages-flow.js:908` |
| `SUB_exclude-filter` | `pages-flow.js:909` |
| `SUB_exclude-type` | `pages-flow.js:910` |
| `SUB_OVERRIDE` | `pages-flow.js:963` |
| `GROUP_TEMPLATE` | `pages-flow.js:1486` |
| `GROUP_SMART` | `pages-flow.js:1541` |
| `GROUP_POLICY_PRIORITY` | `pages-flow.js:1548` |
| `GROUP_COMMON` | `pages-flow.js:1562` |
| `GROUP_HEALTH` | `pages-flow.js:1572` |
| `GROUP_TOLERANCE` | `pages-flow.js:1582` |
| `GROUP_STRATEGY` | `pages-flow.js:1590` |
| `GROUP_DEFAULT_SELECTED` | `pages-flow.js:1601` |
| `GROUP_EMPTY_FALLBACK` | `pages-flow.js:1609` |
| `GROUP_OTHERS` | `pages-flow.js:1613` |
| `GROUP_PROXIES` | `pages-flow.js:1630` |
| `GROUP_USE` | `pages-flow.js:1631` |
| `GROUP_HEALTH_TYPES` | `pages-flow.js:1558` |
| `RULE_HINTS` | `pages-flow.js:1906` |
| `RULE_MULTI_TYPES` | `pages-flow.js:1875` |
| `RULE_PROVIDER_DEFAULT` | `pages-flow.js:2068` |
| `EP_FIELDS` | `pages-flow.js:2083` |
| `EP_HTTP_ONLY_IDX` | `pages-flow.js:2115` |
| `TUNNEL_FIELDS` | `pages-config.js:1591` |
| `TUNNEL_TEMPLATE` | `pages-config.js:1574` |

