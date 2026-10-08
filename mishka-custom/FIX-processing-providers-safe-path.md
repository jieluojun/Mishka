# 修复：导入配置报 "path is not subpath of home directory or SAFE_PATHS"

## 现象
创建配置（文件 / URL / 二维码）时，校验失败，提示：

    parse proxy provider <名称> error: path is not subpath of home directory or SAFE_PATHS:
    /data/user/0/top.yukonga.mishka/files/mihomo/processing/providers/<hash>
     allowed paths: [/data/user/0/top.yukonga.mishka/files/mihomo/geodata]

## 根因
1. 校验时（fetch.go / transform_bridge.go）会把 file / http 类 proxy-provider 的 path 在**内存里**改写成
   `files/mihomo/processing/providers/<文件名>` 绝对路径，让解析器读到刚预取的文件。
   写回磁盘的 config.yaml 仍是原始相对路径，运行时不受影响。
2. 校验进程里 mihomo 的 home dir 是 `files/mihomo/geodata`。
   内核 `constant.Path.IsSafePath` 只放行 home dir 与 SAFE_PATHS 之下的路径，于是 `processing/` 被拒绝。
3. 上游构建带 `cmfa` tag 时 `IsSafePath` 直接返回 true，所以上游不会出现这个错误。
   本定制为了让 PROCESS-NAME 规则生效去掉了 `cmfa`（见 app 补丁里的 `mihomoBuildTags`），
   于是这道检查开始生效。

## 修法（不改变 cmfa 的取舍，不改运行时行为）
- 内核补丁 `patches/mihomo/0007-constant-safe-path-provider-sandbox.patch`：
  在 `constant/path.go` 增加可选钩子 `ExtraSafePathFunc`，只在原有检查失败后才调用；默认 nil，上游行为不变。
  附单元测试 `constant/path_extra_test.go`。
- app 侧新文件 `app/src/main/native/mishka_core/safe_paths.go`（已并入 `patches/app/0001-anchor-panel.patch`）：
  安装钩子，只放行 `<home dir 的同级>/processing/providers/` 里的文件，`processing/providers` 目录本身与其它路径仍拒绝。

## 验证
- `tools/verify_mihomo_patches.sh`：0001–0007 在 fc45379 上干净累积应用，逐文件 blob 与树哈希均与基线一致。
- 在 mihomo 源码上 `go test ./constant/`、`go vet -tags "mishka with_gvisor" ./constant/ ./config/ ./tunnel/` 通过。
- 复现测试（mishka_core）：按校验流程构造 file provider，无钩子时报出与截图完全相同的错误；
  装上钩子后校验通过；processing 之外的路径（含 `..` 穿越、`providers2/` 前缀）仍被拒绝。

## 未在本环境验证
- 没有 Android SDK / NDK，未能构建 APK，也未在真机（ROOT / VPN 两种模式）上导入订阅。请在真机上确认。
- app 补丁基于 dd21ee4，本环境没有该基线 commit，所以只验证了新文件段可独立应用，以及 0001 整体结构完好；
  请用 `tools/verify_app_patch.sh --repo <仓库>` 在仓库上跑一遍。
