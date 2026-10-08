# 导入订阅时「配置校验失败: path is not subpath of home directory or SAFE_PATHS」

## 1. 报错在说什么

错误来自内核 `constant/path.go`：

```go
func (p *path) IsSafePath(path string) bool {
	if p.allowUnsafePath || features.CMFA { return true }   // ← 两条快捷通道
	path = p.Resolve(path)
	for _, safePath := range p.SafePaths() { ... }           // SafePaths() = [homeDir] + SAFE_PATHS
	return false
}
```

调用点在 `adapter/provider/parser.go`（rule-provider 在 `rules/provider/parse.go` 同理）：
provider 带 `path` 时先 `C.Path.Resolve(path)`，不在安全路径内就 `ErrNotSafePath`。

截图里的信息把现场说死了：

- 被拒绝的路径：`/data/user/0/top.yukonga.mishka/files/mihomo/processing/providers/c6e0d28d…`
- 允许的路径只有一个：`[/data/user/0/top.yukonga.mishka/files/mihomo/geodata]`

**只有一个元素 ⇒ `SAFE_PATHS` 环境变量是空的，列表里那一个就是 homeDir。**

## 2. 根因（三件事撞在一起）

1. `MishkaApplication.onCreate()` 里：
   `MishkaCoreBridge.init(homeDir = ProfileFileOps.getGeodataDir(this).absolutePath, …)`
   → JNI（app 进程内）这份内核的 homeDir = `files/mihomo/geodata`，**只为让内核找得到 GeoIP/GeoSite/ASN**。
2. 导入校验走的是进程内 JNI 路径（`mishkaFetchAndValid`）。`fetch.go` 的 `patchProvidersPath()`
   把每个 provider 的 `path` 改写成 **`processing/providers/<hash>` 绝对路径**（沙箱目录，不在 geodata 下）。
3. 你的 app 侧补丁把 `cmfa` 构建标签去掉了：

   ```
   -val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
   +val mihomoBuildTags = listOf("mishka", "with_gvisor", "with_ebpf")
   ```

   上游 Mishka 带 `cmfa` 时 `features.CMFA == true`，`IsSafePath` 第一行就 `return true`，
   第 1、2 条的矛盾**被整条短路掉了**，所以上游不会报这个错。去掉 `cmfa` 之后检查真的开始生效 ——
   这就是「拉完上游、重新打完补丁、重新构建」之后才冒出来的原因（内核那边 `IsSafePath`
   在你钉的基线 `fc45379` 里就已经是现在这样了，不是内核今天改的）。

补充：**只有导入校验会炸，跑起来不会**。运行期是 fork/exec 子进程，`-d` 给的是
`imported/{uuid}`（ROOT 是 `runtime/{uuid}`），config.yaml 里的 provider 路径是相对路径，
`Resolve` 后正好落在 homeDir 里，天然安全。

快速自证（任选）：

```bash
grep -n "mihomoBuildTags" app/build.gradle.kts          # 确认没有 cmfa
grep -rn "features.CMFA" mihomo/constant/path.go        # 确认快捷通道存在
# 还可以在 geodata 目录下手造一个 providers/ 软链，导入立刻就过 —— 可证路径判定就是症结
```

## 3. 修复方案

### 方案 A（推荐，和你现有补丁流水线一致）：给内核加一个运行时注册安全路径的入口

`SAFE_PATHS` 只在 `constant` 包 init（也就是 `.so` dlopen 的那一瞬）读一次，
Android 侧没有可靠时机去 `Os.setenv`，所以补一个 setter 最干净。

本目录下已经备好两个补丁（都已在基线源码上 `git apply` 验证过能干净应用）：

| 文件 | 作用 |
| --- | --- |
| `patches/mihomo/0007-constant-add-safe-paths.patch` | `constant` 包新增 `AddSafePaths(paths ...string)` |
| `patches/app/0002-jni-register-workdir-safe-path.patch` | `mishkaCoreInit` 里把 homeDir 的上一级（`files/mihomo`）登记为 safe path |

落地步骤：

```bash
cp fix/patches/mihomo/0007-constant-add-safe-paths.patch   mishka-custom/patches/mihomo/
cp fix/patches/app/0002-jni-register-workdir-safe-path.patch mishka-custom/patches/app/
git -C mihomo apply mishka-custom/patches/mihomo/0007-constant-add-safe-paths.patch
git apply mishka-custom/patches/app/0002-jni-register-workdir-safe-path.patch
./gradlew :app:assembleDebug
```

注意两处登记：

- `patches/mihomo/BASELINE.txt`：加一行 `patch <sha256> 0007-constant-add-safe-paths.patch`，
  并刷新 `blob … constant/path.go`（新文件会进入校验），否则 `tools/verify_mihomo_patches.sh` 会红。
  当前 sha256：`a166ea8f709bb916c7db98f54e3562aabbcf45ff3ddea0721a6135ece0770d61`
- `patches/app/BASELINE.txt`：把 `app/src/main/native/mishka_core/main.go` 的 `blob_after` 补上
  （或者把这段 diff 直接并进 `0001-anchor-panel.patch` 再重算 `patch_sha256`，看你更想要哪种粒度）。

登记进去之后，`files/mihomo` 整棵树（`processing/`、`pending/`、`imported/`、`runtime/`）都算安全路径，
导入校验、provider 预取、rule-provider 全部一次过；geodata 作为 homeDir 的语义完全不动。

### 方案 B（零内核补丁，只改 Kotlin 两三行）

把 JNI 这份内核的 homeDir 从 `geodata` 抬到工作目录 `files/mihomo`，再把 geo 文件链到工作目录根下
（`ensureGeodataLinks` 已经是现成的，ROOT/VPN 启动时就是这么干的）：

```kotlin
extractGeoFiles()
val workDir = ProfileFileOps.getGeodataDir(this).parentFile!!   // files/mihomo
ProfileFileOps.ensureGeodataLinks(this, workDir)                // geoip.metadb 等软链到工作目录
MishkaCoreBridge.init(
    homeDir = workDir.absolutePath,
    userAgent = "ClashMetaForAndroid/${BuildConfig.VERSION_NAME}",
)
```

优点：不碰内核，跟上游 rebase 成本最低。
代价：homeDir 语义变了，`Path.Cache()`、`GetPathByHash("proxies", …)` 等会落到 `files/mihomo/` 根下；
`ensureGeodataLinks` 在软链失败时会退化成**复制**，geo 数据更新后那份拷贝不会自动跟新（软链没这问题）。

### 方案 C（只想马上能用，不推荐长期）

把 `cmfa` 加回 `mihomoBuildTags`。改一行就好了，但这正是你当初刻意去掉的东西：
`tunnel.go` 会走 `features.CMFA` 分支，只查包名、不做 socket-owner 查询，`metadata.Uid` 恒为 0，
按 UID 的规则和 `patches/mihomo/0006-process-name-android-uid-fallback.patch` 的意图一起失效。

## 4. 顺带检查

- `rules/provider/parse.go` 同一套判定：订阅里如果有带 `path:` 的 rule-provider，
  只要还在 `processing/` 下，方案 A/B 一并覆盖，方案 C 靠短路。
- `patches/mihomo/0003` 注入的 `external-ui: "ui"` 是**相对路径**，`Resolve` 到 homeDir 下，
  三种方案都安全，不用动。
