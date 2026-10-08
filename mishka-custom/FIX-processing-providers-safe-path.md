# 修复：导入 / 保存配置报 "path is not subpath of home directory or SAFE_PATHS"

## 现象
- 导入订阅（文件 / URL / 二维码）时校验失败：

      配置校验失败: parse proxy provider <名称> error: path is not subpath of home directory or SAFE_PATHS:
      .../files/mihomo/processing/providers/<hash>
       allowed paths: [.../files/mihomo/geodata]

- 编辑器里修改 config.yaml 后保存失败：

      保存失败：validate config: parse proxy provider <名称> error: path is not subpath of home directory or SAFE_PATHS:
      .../files/mihomo/imported/<uuid>/providers/<文件名>
       allowed paths: [.../files/mihomo/geodata]

## 根因
1. 校验时（fetch.go 的 `patchProvidersPath`）会把 file / http 类 proxy-provider 的 path 在**内存里**改写成
   `<workDir>/providers/<文件名>` 绝对路径，让解析器读到预取的文件。写回磁盘的 config.yaml 仍是原始相对路径。
   workDir 有两种来源：
   - 导入 / 更新订阅：`files/mihomo/processing/`（单例沙箱）；
   - 编辑器保存：`files/mihomo/imported/<uuid>/`（`FileManagerEditorScreen.saveWithValidation` →
     `fetchAndValid(url="")`）。
2. 校验进程里 mihomo 的 home dir 是 `files/mihomo/geodata`。内核 `constant.Path.IsSafePath` 只放行
   home dir 与 SAFE_PATHS 之下的路径，于是上面两个 `providers/` 目录都被拒绝。
3. 上游构建带 `cmfa` tag 时 `IsSafePath` 直接返回 true，所以上游不会出现这个错误。本定制为了让
   PROCESS-NAME 规则生效去掉了 `cmfa`（见 app 补丁里的 `mihomoBuildTags`），这道检查才开始生效。

上一版修复只放行了 `processing/providers/`，没有覆盖编辑器保存用的 `imported/<uuid>/providers/`，所以保存时仍然失败。

## 修法（不改变 cmfa 的取舍，不改运行时行为）
- 内核补丁 `patches/mihomo/0007-constant-safe-path-provider-sandbox.patch`（与上一版相同）：
  在 `constant/path.go` 增加可选钩子 `ExtraSafePathFunc`，只在原有检查失败后调用；默认 nil，上游行为不变。
  附单元测试 `constant/path_extra_test.go`。
- app 侧新文件 `app/src/main/native/mishka_core/safe_paths.go`（已并入 `patches/app/0001-anchor-panel.patch`）：
  安装钩子，只放行以下两类文件（`<home dir 的同级>` 即 `files/mihomo/`）：
  - `processing/providers/<文件>`
  - `imported/<uuid>/providers/<文件>`
  `providers/` 目录本身、`config.yaml`、`proxies/` 等其它路径，以及 `..` 穿越，仍然拒绝。

## 验证
- `tools/verify_mihomo_patches.sh`：0001–0007 在 fc45379 上干净累积应用，逐文件 blob 与树哈希均与基线一致。
- `go test ./constant/`、`go vet -tags "mishka with_gvisor" ./constant/ ./config/ ./tunnel/` 通过。
- 在 mishka_core 中用真实的 `runFetchAndValid` 复现保存路径（workDir = `imported/<uuid>`，url 为空）：
  - 无钩子：报出与截图完全相同的 `validate config: parse proxy provider ... not subpath ...` 错误；
  - 有钩子：providers 文件存在与不存在两种情况下都校验通过；
  - 路径规则表（processing / imported / 目录本身 / 穿越 / runtime / 其它）全部符合预期。

## 未在本环境验证
- 没有 Android SDK / NDK，未能构建 APK，也未在真机上测过导入与编辑保存。请在真机上确认。
- mishka_core 的测试是在上游 09f9a80 的 mishka_core 源码上做的（与 dd21ee4 的 native 代码基本一致但不完全相同）。
- app 补丁基于 dd21ee4，本环境没有该基线 commit。新文件段已确认可以独立应用且内容与源文件一致；
  请用 `tools/verify_app_patch.sh --repo <仓库>` 在仓库上跑一遍。
