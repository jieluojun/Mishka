# CI 构建报错：两个真因 + 已修好的交付文件

日志：`Build release APK` @ `jieluojun/Mishka`，HEAD `d49a1f49a37d0ac01547f723cb598ac0faa4d8e6`，
挂在 **step 3/6 内核**：

```
  ok   内核已切到基线 commit（fc45379e）
  预检补丁（临时索引，不动工作区）…
FAIL: 补丁应用结果与基线不一致
  错误 内核补丁无法干净应用到 fc45379ef1bdbe358c63cc9eb6134e3691cbb113，请检查内核版本
##[error]Process completed with exit code 1.
```

**不是上游内核变了。** 我把 `jieluojun/mihomo` 干净 clone 到基线 `fc45379e`，用你 fork 里那 5 个
原始补丁跑交付自带的 `tools/verify_mihomo_patches.sh` → **PASS，11/11 blob 全对**。所以内核基线没漂。

---

## 真因 1（当前这条报错）：加了 0006 补丁，但 `patches/mihomo/BASELINE.txt` 没跟着更新

你 fork 的 `d49a1f4` 里：

- `mishka-custom/patches/mihomo/` 已经有 `0006-process-lookup-under-cmfa-mishka.patch`
  （sha256 `45f9fdb8…`，与我给的字节一致）；
- 但 `patches/mihomo/BASELINE.txt` 还是老的：`blob` 条目 11 条、`patched_tree=872e84ff…`。

`verify_mihomo_patches.sh` 的判定是「所有补丁累积应用后的**树哈希**必须等于 `patched_tree`」。
0006 新增 2 个文件、改了 2 个文件，树必然变：

```
期望 tree: 872e84ff05b7ed62b3ccd145362076b01479a6a8   ← 老 BASELINE
实际 tree: d76e8fe8ea8e6eb4af5b93f3eab6b6017b6d995a   ← 加了 0006 之后
FAIL: 补丁应用结果与基线不一致
```

这正是我在上一轮文档里「BASELINE.txt 需要追加（值已实测）」那段要做的事 —— 只复制补丁文件、
不更新 BASELINE 就会这样。注意 11 条 blob 检查**全部通过**（0006 改的文件不在老清单里，所以没有
`FAIL blob` 行），只有最后的树哈希对不上，报错文案才这么含糊。

**修复**：用本目录的 `patches/mihomo/BASELINE.txt` 覆盖 `mishka-custom/patches/mihomo/BASELINE.txt`。
改动只有 6 行：`patched_tree` 换成 `d76e8fe8…`，新增 4 条 blob（`constant/features/mishka.go`、
`mishka_stub.go`、`constant/features/tags.go`、`tunnel/tunnel.go`）+ 0006 的 patch sha256 行。
（`patched_commit` 那行是注释性质，脚本不读；`base_commit` 不动。）

## 真因 2（改完 BASELINE 后**下一个**会撞上的）：app 补丁在新上游打不上了

`d49a1f4` 相对补丁基线 `5e67435` 只漂了一个文件，但正好撞上：

```
app/src/main/kotlin/top/yukonga/mishka/data/api/MihomoApiClient.kt | 16 +++++++-------
```

上游 commit `09f9a80 fix: Encode slash in external-controller paths (#16)` 给这个文件加了
`private fun pathSegment(raw) = raw.encodeURLPath(encodeSlash = true)` 并把 7 处路径参数包了起来；
而交付补丁自己也加了同一目的的 `private fun pathSeg(raw) = URLEncoder.encode(raw,"UTF-8").replace("+","%20")`
并改写了 `getProxyDelay` / `getProviderProxyDelay` 那几行 —— **同一个 bug 两边各修了一遍**，hunk 冲突。

`setup.sh` step 5 的行为（这里有个坑）：

```bash
if   git apply --check "$patch";              then …        # 失败（MihomoApiClient.kt:90）
elif git apply --check --3way "$patch";       then          # ← 通过！--check 不做 3way 冲突检测
     git apply --3way "$patch"                              # ← 这里才真失败（exit 1，set -e 直接 die）
```

我实测：`git apply --check` → exit 1；`git apply --check --3way` → **exit 0**；
`git apply --3way` → exit 1，留下 `U MihomoApiClient.kt` 和冲突标记。也就是说 CI 会走到
「已应用（3way 合并…）」那条分支然后当场死掉，还会留个半应用的树。

**修复**：我已把 app 补丁重新导出到新基线，冲突按「保留上游的 `pathSegment`，删掉交付侧重复的
`pathSeg` + `URLEncoder` import，交付新增代码里的 `pathSeg(...)` 全改成 `pathSegment(...)`」解决。
理由：上游用的是 ktor 的 `encodeURLPath(encodeSlash = true)`（正确的路径段百分号编码），交付侧那个
`URLEncoder` 是表单编码 + 手工把 `+` 换回 `%20`，语义等价但更绕；两边留一个就行，留上游维护的那个。

交付文件：

- `patches/app/0001-anchor-panel.patch` —— 重新导出，67 个文件不变，**只有 MihomoApiClient.kt 一个
  文件的 diff 变了**（逐文件比对确认）；
- `patches/app/BASELINE.txt` —— `upstream_commit=d49a1f4…`、新 `patch_sha256`、
  `MihomoApiClient.kt` 的 `blob_after` 更新（其余 66 条一字未改）。

---

## 更正上一轮的一个错误结论

上一轮我说「`with_ebpf` 不在构建标签里，`ebpf:` 段和补丁 0005 在成品里是死代码」——**这条是错的**。
我当时读的是 `app/build.gradle.kts:220` 的**上游原文**（`listOf("cmfa","mishka","with_gvisor")`），
没看 app 补丁对它的改动。实际上补丁第 9→10 行就是：

```diff
-val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor")
+val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor", "with_ebpf")
```

实测：在基线上应用 app 补丁后该行确实是四个标签，且 `git hash-object app/build.gradle.kts` =
`0ebf6af7fb0238b52e41188f5bb8897f86dc07a4`，与 `patches/app/BASELINE.txt` 的 `blob_after` 一致；
`tools/verify_app_patch.sh` 里还有一条断言专门钉这个标签。所以 **`with_ebpf` 是开着的**：
`listener/sing_ebpf` 的真实现（不是 stub）编进了 APK，补丁 0005 有效，App 的 ROOT eBPF 模式可用，
eBPF 进程追踪（`common/ebpf/process_tracker.go`）也在。

**但 `PROCESS-*` 规则的诊断不变**（这部分我重新核过）：在 6 个补丁都打上的内核树上，
`metadata.Process` / `metadata.Uid` 的赋值点全树只有 `tunnel/tunnel.go`（FindProcess）与
`listener/inner/{tcp,udp}.go`（自身流量）；`listener/sing_ebpf` 只用 `TrackProcess` 做自己的
bypass 策略，**不写规则用的 metadata**。所以 cmfa 分支下 `metadata.Uid` 恒为 0 的结论、
以及补丁 0006 的必要性，都照旧成立。

---

## 落地步骤

```bash
# 1) 覆盖内核基线（修当前这条 CI 报错）
cp patches/mihomo/BASELINE.txt  mishka-custom/patches/mihomo/BASELINE.txt

# 2) 覆盖 app 补丁 + 基线（修下一个会撞上的冲突）
cp patches/app/0001-anchor-panel.patch mishka-custom/patches/app/0001-anchor-panel.patch
cp patches/app/BASELINE.txt            mishka-custom/patches/app/BASELINE.txt

# 3) 本地自检（两条都应 PASS）
bash mishka-custom/tools/verify_mihomo_patches.sh --kernel-dir mihomo
bash mishka-custom/tools/verify_app_patch.sh --repo .

git add mishka-custom/patches && git commit -m "fix: rebase app patch onto d49a1f4; refresh mihomo baseline for 0006" && git push
```

注意 `patches/app/BASELINE.txt` 的 `upstream_commit` 现在钉的是 `d49a1f4`：以后再 pull 上游，
只要 `MihomoApiClient.kt` 之外的文件没动，app 补丁还能干净打上；再漂就得重新导出一次。

---

## 本轮验证记录

**跑了的：**

1. 复刻当前 CI 报错：`jieluojun/mihomo` 干净 clone → `fc45379e` → 原始 5 补丁 + 老 BASELINE
   → `verify_mihomo_patches.sh` → **PASS**（证明内核基线没漂）。
2. 复刻失败：加 0006、BASELINE 不动 → 同一脚本 → `期望 872e84ff… / 实际 d76e8fe8…` +
   `FAIL: 补丁应用结果与基线不一致`，与 CI 日志逐字一致；11 条 blob 检查全通过（无 `FAIL blob`）。
3. 修正后：0006 + 新 BASELINE → **PASS**，15/15 blob 一致，`实际 tree == 期望 tree == d76e8fe8…`。
4. app 补丁冲突定位：`git apply --check` exit 1（`MihomoApiClient.kt:90`）、
   `--check --3way` exit 0、`--3way` exit 1 并留 `U` + 冲突标记（2 个冲突块）。
5. 重新导出后：`tools/verify_app_patch.sh --repo <d49a1f4 干净树>` → **161 条 ok / 0 FAIL**，
   「PASS: app 侧补丁双向可逆、结果与基线逐文件一致」，跑完工作区回到干净。
6. 逐文件比对：新补丁 vs 老补丁，67 个文件清单一致，diff 内容有差异的只有 `MihomoApiClient.kt`。
7. 新 BASELINE vs 老 BASELINE：只差 4 行（upstream_commit / patch_sha256 / MihomoApiClient 的 blob_after）。
8. 解决后的 `MihomoApiClient.kt` 静态自洽：`pathSegment` 定义 1 处、`encodeURLPath` 已 import、
   `JsonElement`/`intOrNull` 已 import、`delayBodyOrThrow` / `getGroupDelay` /
   `DelayTestFailedException` / `extractErrorMessage` / `ensureSuccess`（`private suspend fun`，第 273 行）
   都在位；`pathSeg(` / `URLEncoder` / 冲突标记均 0 处；大括号与圆括号配平。
9. `with_ebpf` 结论的更正：在基线上应用 app 补丁后读 `app/build.gradle.kts:220` 并核对 blob 哈希。

**没跑的：**

- **没跑 Gradle / Kotlin 编译**：本环境无 Android SDK + NDK、内存 2GB。解决后的
  `MihomoApiClient.kt` 只做了上面第 8 条那种符号级静态检查，`pathSeg → pathSegment` 的改名
  与上游 #16 的语义合并**没有经过编译器验证**，push 前建议本地跑一次
  `./gradlew :app:compileReleaseKotlin -x buildMihomo_arm64_v8a`。
- 没跑真机（`PROCESS-NAME` 规则命中与否仍需 ROOT TUN 下用 `GET /connections` 的 `metadata.process` 复核）。
