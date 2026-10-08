# `find-process-mode: always` 写了不生效 —— 诊断与修复

对象：本次交付的 mishka-custom（app 基线 `d49a1f49a37d0ac01547f723cb598ac0faa4d8e6`（第二轮起跟随上游；写作初稿时为 `5e67435…`），内核基线
`jieluojun/mihomo` @ `fc45379ef1bdbe358c63cc9eb6134e3691cbb113`）。

## 结论（两层，缺一不可）

1. **订阅 YAML 里的 `find-process-mode` 永远不会生效**，跟值写什么无关。
   App 每次启动都把 `override.run.json` 以 `--override-json` 交给内核，内核在解析完订阅 YAML
   **之后**才合并这个 JSON；而 `override.run.json` 里 **一定**带 `find-process-mode`，
   默认值是 `"off"`。你 YAML 里的 `always` 在合并那一步就被覆盖掉了。
2. **即使把模式改成 `always`，这个内核构建里 `PROCESS-*` / `UID` 规则也永远匹配不到。**
   构建标签带 `cmfa` → `features.CMFA == true` → `tunnel.go` 走「CMFA 专用分支」，
   该分支只调 `process.FindPackageName(metadata)`，而全树**唯一**给 `metadata.Uid` 赋值的地方
   就在它跳过的那个分支里 → 解析器拿到的 uid 恒为 0 → 永远返回 `package not found` →
   `metadata.Process` 恒为空。`find-process-mode` 只决定「这次查不查」，查了也查不出东西。

所以：**先按第 1 条让设置真正进到内核（不用改代码），再按第 3 条打内核补丁让查询有结果。**

---

## 证据链（每条都来自本轮实际读到的代码）

### A. override JSON 覆盖订阅 YAML

`patches/mihomo/0001-config-override-json.patch` 给 `config/config.go` 加的 `Parse()`：

```go
func Parse(buf []byte) (*Config, error) {
	rawCfg, err := UnmarshalRawConfig(buf)   // ← 先解析订阅 YAML
	if err != nil { return nil, err }
	applyOverrideJSON(rawCfg)                // ← 再用 override.run.json 覆盖（后写的赢）
	if mishkaPatch != nil { mishkaPatch(rawCfg) }
	return ParseRawConfig(rawCfg)
}
```

`applyOverrideJSON` 用 `json.Unmarshal(data, rawCfg)`：JSON 里出现的字段一律覆盖，
没出现的字段保持 YAML 值。而 `RawConfig` 的字段是
`FindProcessMode process.FindProcessMode \`yaml:"find-process-mode" json:"find-process-mode"\``
（`config/config.go:450`）。

App 侧（`RuntimeOverrideBuilder.kt`，基线 commit 原文）：

```kotlin
// 第 100-104 行的原注释：
// tcp-concurrent：代理侧并发拨号……
// find-process-mode=off：ROOT TUN 分应用已由 sing-tun include/exclude-package 的 uidrange
// 处理，mihomo 运行期遍历 /proc 查进程纯属冗余；VPN 模式 AppProxy 走 VpnService 同理。
// 用户显式设置优先：仅在未设置时注入默认值
tcpConcurrent  = userOverride.tcpConcurrent ?: true,
findProcessMode = userOverride.findProcessMode ?: "off",   // ← 第 105 行，永远非 null
```

- 该字段序列化名：`ConfigurationOverride.kt:34`
  `@SerialName("find-process-mode") val findProcessMode: String? = null`；
  写入时 `encodeDefaults = false / explicitNulls = false`，但这里值**非 null**，所以必然出现在
  JSON 里 → **必然覆盖 YAML**。
- 文件与参数：`RuntimeOverrideBuilder.kt:25` `FILE_NAME = "override.run.json"`、
  `:111` 写到 `ConfigGenerator.getWorkDir(context)`；
  `MihomoRunner.kt:118` `add("--override-json"); add(overrideJsonPath)`。
- 注释里的「用户显式设置」指的是 **App 内的设置项**，不是订阅 YAML —— 这正是误解的来源。
- 顺带一提：mihomo 自己的默认值是 `strict`（`config/config.go:515`
  `FindProcessMode: process.FindProcessStrict`），App 这一行把默认从 `strict` 降到了 `off`。

### B. 设置项在 App 的哪里

`ui/screen/settings/MetaSettingsScreen.kt:375-391`（设置 → **Meta 设置** → 「进程匹配模式」）：

```kotlin
val items  = listOf(notModifiedStr, "Off", "Strict", "Always")
val values = listOf(null, "off", "strict", "always")
```

字符串 `meta_find_process_mode` = 「进程匹配模式」（`values-zh-rCN/strings.xml:474`）。
**留在「不修改」= null → override 里就是 `"off"`。** 必须显式选 Strict / Always。

### C. 生效链路（内核侧）

`config/config.go` → `Config.General.FindProcessMode` → `hub/executor/executor.go:439`
`tunnel.SetFindProcessMode(general.FindProcessMode)` → `tunnel/tunnel.go:457-462`：

```go
switch FindProcessMode() {
case process.FindProcessAlways: helper.FindProcess(); helper.FindProcess = nil  // 每条连接都查
case process.FindProcessOff:    helper.FindProcess = nil                       // 永不查（你现在这里）
}
// strict（默认）：helper 保留，由规则自己触发 —— rules/common/process.go:34、uid.go:48
```

### D. 为什么改成 always 也匹配不到（真正的大坑）

构建标签：`app/build.gradle.kts:220` `val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")`
→ `constant/features/cmfa.go`（`//go:build cmfa`）`const CMFA = true`。

`tunnel/tunnel.go:411-444` 的 `FindProcess`：

```go
if !features.CMFA {                      // ← 第 414 行：cmfa 构建走不到这里
	uid, path, err := findProcessName(...)
	metadata.Uid = uid                   // ← 第 421 行：全树唯一给 Uid 赋值的地方
	...
	if pkg, err := process.FindPackageName(metadata); err == nil { metadata.Process = pkg }
} else {
	// check package names
	pkg, err := process.FindPackageName(metadata)   // ← cmfa 构建只走这条
	if err != nil { log.Debugln("[Process] find process error for %s: %v", ...) } else { metadata.Process = pkg }
}
```

而交付补丁 `0002` 装上的解析器（`listener/sing_tun/server_android.go:70`）是：

```go
uid := metadata.Uid                                  // ← cmfa 分支下恒为 0
if sharedPackage, loaded := packageManager.SharedPackageByID(uid % 100000); loaded { return sharedPackage, nil }
if packageName,   loaded := packageManager.PackageByID(uid % 100000);   loaded { return packageName, nil }
return "", errors.New("package not found")
```

全树 grep 结果（非测试代码）：`metadata.Uid` 的赋值**只有** `tunnel/tunnel.go:421` 一处；
读取处是 `listener/sing_tun/server_android.go:70` 与 `rules/common/uid.go:48`
（`if metadata.Uid != 0 {`）。所以 cmfa 构建下：

- `PROCESS-NAME` / `PROCESS-NAME-REGEX` / `PROCESS-PATH` 恒不匹配（`metadata.Process` 为空）；
- `UID` 规则同样恒不匹配；
- 两条现成的日志证据（`strict` 模式下两类规则都会自己触发查询，
  `rules/common/process.go:34`、`rules/common/uid.go:45-47` 都调了 `helper.FindProcess()`）：
  - `[Process] find process error for ...: package not found` —— debug 级；
  - `[UID] could not get uid from ...` —— **Warn 级，不用开 debug 就能看到**（`uid.go:54`）。
    如果你配置里有 `UID,` 规则，现在日志里应该正在刷这一条，可以直接拿来当现场证据。

`0002` 的注释写着「Mishka always wants the uid -> package-name resolver」，意图对，
但 resolver 拿不到 uid，等于装了个空转的零件。

---

## 修复

### 第 1 步（必做，不改代码）：在 App 里设，而不是在 YAML 里设

设置 → **Meta 设置** → 「进程匹配模式」→ 选 **Strict**（推荐）或 **Always**，然后重启代理服务
（`override.run.json` 是启动时写、内核启动时读）。

- 推荐 `strict` 而不是 `always`：strict 只在真正评估到 process/uid 规则时才查
  （`rules/common/process.go:34`），always 是**每条连接**都做一次 netlink dump + `/proc` 扫描，
  Android 上很费电。既然你现在有 process 规则，strict 就会查。
- YAML 里那行可以留着，但它不是生效来源；生效来源是这个设置项。

**免重启自检**（`hub/route/configs.go:390-391` 支持热改）：

```bash
# 看实际生效值 —— 改设置前这里会是 "off"
curl -s http://127.0.0.1:9090/configs | python3 -m json.tool | grep find-process-mode
# 临时热改验证（重启后失效，仍被 override 覆盖）
curl -sX PATCH http://127.0.0.1:9090/configs -H 'Content-Type: application/json' \
     -d '{"find-process-mode":"strict"}'
```

判据（最直接的）：`GET /connections` 里每条连接的 `metadata.process` / `processPath` / `uid`
是否有值。打完第 3 步的补丁再看这里，就能确认整条链路通了。

### 第 2 步（可选）：想让订阅 YAML 说了算

一行改动，`app/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt:105`：

```diff
-            findProcessMode = userOverride.findProcessMode ?: "off",
+            // 未显式设置时不写这个键：JSON 里缺字段就不会覆盖订阅 YAML（内核默认 strict）
+            findProcessMode = userOverride.findProcessMode,
```

`findProcessMode` 是 `String?`，`explicitNulls = false` → null 时该键**不出现**在
`override.run.json` 里 → 订阅 YAML 的值保留。
注意：这一行在 `patches/app/0001-anchor-panel.patch` 覆盖的文件内，改完要重新导出补丁并更新
`patches/app/BASELINE.txt` 的 `blob_after=…` 与 `patch_sha256`，否则 `tools/verify_app_patch.sh`
会 FAIL。

### 第 3 步（必做，否则规则永远匹配不到）：内核补丁 0006

已生成：`patches/mihomo/0006-process-lookup-under-cmfa-mishka.patch`（本目录同级）。

做法与 `0002` 同一路子（新增 `features.Mishka` 开关，`//go:build mishka` / `!mishka` 两个文件），
让 mishka 构建在 `FindProcess` 里走「正常查进程」那条分支：

```go
-				if !features.CMFA {
+				if !features.CMFA || features.Mishka {
```

这样 `metadata.Uid` / `Process` / `ProcessPath` 由 socket owner 填好
（`component/process/process_linux.go:65-91`：netlink `INET_DIAG` 为主，Android 上自动回退
`/proc/net/{tcp,udp}` 与「按 UID 找进程」），随后 Android 解析器把进程名换成**包名**。
非 mishka 的 cmfa 构建行为一字不变（`features.Mishka` 为 false，走 stub）。

**不要**改成「直接去掉 `cmfa` 标签」：`cmfa` 还管着
`dns/patch_android.go`（Kotlin 侧喂进来的 `UpdateSystemDNS` / `system://` 兜底，
`0003` 补丁依赖它）、`hub/route/patch_android.go`（embed mode）、
`component/loopback/detector.go`（回环检测）与 `constant/path.go:90`（路径安全）。
去掉标签会连带炸掉这几处。

`BASELINE.txt` 需要追加（值已实测）：

```
patch 45f9fdb8b58fba49689cc99d0e90cb0ae3f6e2aa4cc3830b520a09bbcf81a5fd 0006-process-lookup-under-cmfa-mishka.patch
blob 4e9b01b50073bd0c4e3a381ee3d04aae07eff9f3 constant/features/mishka.go
blob 2ffbb37fa0d0f4e2806eb777a3507907600574f1 constant/features/mishka_stub.go
blob 2b32ae9f5d15a6be13380987ee9aa09277cb8d69 constant/features/tags.go
blob 6002159ee8fb6c238179518245c20c5f67794961 tunnel/tunnel.go
patched_tree=d76e8fe8ea8e6eb4af5b93f3eab6b6017b6d995a
```

---

## 规则要怎么写才匹配得上（Android 语义）

- `metadata.Process` 最终是**包名**（解析器按 UID 从 `/data/system/packages.xml` 查），
  例如 `PROCESS-NAME,com.android.chrome`。多进程应用（`com.tencent.mm:push`）也归到主包名。
- `metadata.ProcessPath` 在 Android 上**不是文件路径**，而是 `cmdline[0]` 的 basename
  （`component/process/process_linux.go:274` 的 `splitCmdline`），例如 `com.tencent.mm:push`。
  所以别按 Linux 习惯写 `/data/app/.../base.apk`。
- 生效前提：**root + ROOT TUN 模式**。查 socket owner 要读别的应用的 netlink/`/proc`，
  读 `/data/system/packages.xml` 也要 root。
- **VPN 模式（`tun.file-descriptor > 0`，非 root）**：本段写的时候结论是「查不到，
  只能用 sing-tun 的 `include-package` / `exclude-package`，不能用 `PROCESS-NAME` 规则」。
  **这一条已在 2026-10-08 第二轮被推翻**：内核补丁 `0007` + app 侧 `UidOracleServer` 让内核
  通过本地 unix socket 向「当前生效的 VpnService」要 UID（`ConnectivityManager
  .getConnectionOwnerUid`），VPN 模式下 `PROCESS-NAME` / `UID` 现在也能匹配。
  详见 `CHANGES.md` 第二轮第 2 节。`include-package` / `exclude-package` 仍可用于
  「根本不想让某些应用走代理」，那是分流，不是规则匹配。

## 附带发现（与进程匹配相关，建议一并处理）

**勘误（2026-10-08 第二轮）**：本节原先写「`with_ebpf` 不在构建标签里（只有
`cmfa,mishka,with_gvisor`）」——**这是错的**。交付补丁 `0001-anchor-panel.patch` 已经把
`app/build.gradle.kts:220` 改成了：

```kotlin
val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor", "with_ebpf")
```

（打完补丁后该文件 blob 为 `0ebf6af7fb0238b52e41188f5bb8897f86dc07a4`，
`tools/verify_app_patch.sh` 里有一条断言专门盯这行。）

所以 `listener/sing_ebpf/` 编进 APK 的是**真实现**，不是 `inbound_stub.go`；
配置里的 `ebpf:` / `listeners: [type: ebpf]` 并非一律起不来。

仍然成立的是另一层结论：eBPF 需要 root，`untrusted_app` 域禁 eBPF，所以
**VPN 模式下 eBPF 路线依然不可用**，进程识别在 VPN 模式只能靠本轮新增的
`uid-oracle`（见 `CHANGES.md` 第二轮第 2 节）。补丁 `0005` 的作用也不是「eBPF 不存在」，
而是把 eBPF listener 变成不填充规则元数据的 no-op。

---

## 本轮验证记录（做了什么 / 没做什么）

**做了：**

1. 把交付的 5 个内核补丁按序累积应用到 `jieluojun/mihomo@fc45379e` 的干净 checkout
   （单独 apply `0003` 会失败，它依赖 `0001` 的上下文；累积应用正常），
   逐文件比对 `patches/mihomo/BASELINE.txt`：**11/11 blob 全部一致**。
2. 新补丁 `0006` 在同一棵树（base + 0001~0005）上 `git apply --check` + `git apply`：**通过**。
3. 编译验证（Go 1.25.1，`GOFLAGS=-mod=mod`）：
   - `gofmt -l` 4 个改动文件 → 无输出（格式干净）；
   - `go build -tags "cmfa,mishka,with_gvisor" ./constant/features/ ./tunnel/` → **exit 0**；
   - `go build -tags "cmfa,with_gvisor" ./constant/features/ ./tunnel/`（stub 分支）→ **exit 0**；
   - `go vet -tags "cmfa,mishka,with_gvisor" ./constant/features/` → **exit 0**。
   实际执行到的改动路径：`tunnel.resolveMetadata` 的 `FindProcess` 条件表达式、
   `constant/features` 的 `Mishka` 常量与 `Tags()`。
4. 关键事实全部回读源码确认（不是凭印象）：`metadata.Uid` 全树唯一赋值点、
   `features.CMFA` 的 4 处用法、`with_ebpf` 缺失时的 stub、App 侧 override 的默认值与序列化行为。

**没做（如实说明）：**

- **没有实机验证**：本环境没有 Android 设备 / root，`PROCESS-NAME` 规则在真机上是否命中
  属于代码路径推导（netlink → `/proc` 回退 → 按 UID 查 `packages.xml`），需要你在 ROOT TUN
  模式下用 `GET /connections` 的 `metadata.process` 复核。
- **没有跑 Android 交叉编译 / Gradle**：本环境无 NDK，`GOOS=android` 需要 cgo；
  编译验证是在 `GOOS=linux` 下带同样的构建标签做的（改动文件与平台无关，
  `listener/sing_tun/server_android.go` 本轮未改）。
- **没有跑 App 侧 Gradle 编译**（第 2 步那一行改动只是给了 diff，未落地）。
