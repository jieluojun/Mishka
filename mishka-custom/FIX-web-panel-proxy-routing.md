# 修复：内嵌 Web 面板外网请求绕过代理

补丁：`patches/app/0007-panel-webview-mihomo-proxy.patch`（在 0001–0006 之上应用；
`sha256` 见 `patches/app/BASELINE.txt`）。`scripts/setup.sh` 已加入 0007 自动应用步骤。

## 现象

Mishka 内嵌的 Web 面板里，国外 IP 检测显示成国内直连 IP，YouTube 延迟探测失败；同一面板改用系统浏览器打开后，出口 IP 与 YouTube 检测正常。

## 根因

Web 面板的页面和 JavaScript 检测请求由 `android.webkit.WebView` 发起，网络请求属于 **Mishka 自己的 UID**。VPN 模式下，`MishkaTunService` 为避免代理核心访问本机代理时形成流量环，会把 Mishka 自身排除在 VpnService 的 TUN 路由之外（`addDisallowedApplication(packageName)`）。因此 WebView 的 `ipip.net` / `ip.sb` / YouTube 等请求可能直接走 Wi‑Fi / 蜂窝网络。

系统浏览器则属于另一个 UID，会按 Android VPN 的应用路由规则进入 mihomo；这就是截图中同一面板两种出口表现不一致的原因。它不是面板站点缓存或检测脚本本身的问题。

## 修复方式

- 接入 AndroidX WebKit `ProxyController`（`androidx.webkit:webkit:1.16.0`），只给 WebView 配置进程内代理，不把 Mishka 整个 UID 拉回 TUN，也不影响 app 其它网络请求。
- 代理运行时，通过正在使用的 external-controller `/configs` 读取实际端口，优先选 `mixed-port`，其次 HTTP `port`，最后 SOCKS `socks-port`。代理目标为 `127.0.0.1:<port>`。
- 等待 `setProxyOverride` 的完成回调后才创建 / 加载面板，避免首屏检测请求抢在代理设置之前发出。
- 将 `localhost`、`127.*`、`[::1]` 及当前 external-controller 主机加入 bypass，保证面板仍能直连本机控制器，不会把控制器 API / WebSocket 请求转发到 mihomo 自己造成循环。
- 核心启停或代理端口变化时更新 override；有效路由发生改变后 reload 当前面板，让 IP / 延迟检测重新请求。启停过程保留已生效路由；controller 查询失败时不覆盖上次配置，首次查询失败则仍允许页面打开。
- 若当前 WebView provider 不支持 `PROXY_OVERRIDE`，代码安全回退，不会因缺少该能力而崩溃。

## 验证 / 手工回归

已完成：

- `0007` 补丁在 0006 后的状态通过正向应用、反向应用检查；
- `scripts/setup.sh` 与 `tools/verify_app_patch.sh` 通过 `bash -n` 语法检查；
- 加入 `verify_app_patch.sh --series` 对 0006 / 0007 的静态断言和补丁 SHA 校验。

未完成：

- 未完成 Android Gradle 编译：离线环境缺少 Foojay toolchain resolver 插件；在线构建在依赖解析阶段超时。未在真机安装验证。

建议在设备上回归：

1. 启动代理并进入 Mishka 的「面板 / Web 界面」，选择 MetaCubeXD 或 Zashboard。
2. 对比内嵌面板与外部浏览器：同一个 `ip.sb` / IP 检测应沿 mihomo 当前规则出站；若选中的规则出口在国外，应看到相同的国外出口 IP。
3. 重新执行 YouTube 延迟检测，应能收到延迟结果（具体数值仍受节点连通性及面板测试 URL 影响）。
4. 再切换到本地面板，确认 `http://<external-controller-host>:<port>/ui`（默认 `http://127.0.0.1:9090/ui`）与控制器通信正常。
5. 重启代理或切换配置后重新测试，确认端口变化会更新 WebView 代理。

如果设备的 Android System WebView 不支持 AndroidX 的 proxy override，面板会按旧行为直连；这时可在 `tools.verify_app_patch.sh` 静态断言通过的前提下，检查 WebView provider 版本 / 功能支持情况。
