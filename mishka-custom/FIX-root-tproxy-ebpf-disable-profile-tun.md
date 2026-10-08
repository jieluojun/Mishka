# 使用 TPROXY（和 eBPF）时关闭配置文件里的 tun

补丁：`patches/app/0008-root-tproxy-ebpf-disable-profile-tun.patch`（在 0001–0007 之上应用；
应用方式：`git apply <本包路径>/patches/app/0008-root-tproxy-ebpf-disable-profile-tun.patch`，
或通过 `scripts/setup.sh` 在 0007 之后继续叠加）。

## 现象

ROOT 模式切到 **TPROXY**（以及 **eBPF**）子模式后，内核确实没有初始化 sing-tun，但活动配置
（明文 `config.yaml` / 订阅配置，以及导出的配置）里的 `tun.enable` 仍是 `true`：

- 面板 / 文件管理器里看 `config.yaml` 是 `tun: {enable: true, ...}`，与运行时实际状态相反；
- 把配置导出给别的工具（或贴出来求助）时，对方会以为 TUN 是开的，排查方向被带偏；
- 只在 `override.run.json` 里关掉的 tun，对「用户看得见的配置文件」没有影响。

## 根因

`service/ActiveProfileRuntimeConfig.kt` 的 `prepare()` 只认 `forceTunEnabled`：

- 活动配置缺 `tun` 段时，一律按 `tunDefaults`（`enable: true`、mtu 9000、device `mihomo`…）
  补进去，**不看子模式**；
- age 密文配置无法回写，改而在 per-profile overlay 里写 `tunDefaults`，`enable` 同样是 `true`；
- 明文配置里已存在 `tun` 段时，只有 `forceTunEnabled`（TUN 子模式）才会写 `enable: true`，
  TPROXY / eBPF 分支什么也不写，于是用户自己的 `enable: true` 原样留着。

真正「关掉」TUN 的只有运行时 override：`RuntimeOverrideBuilder.buildTunOverride()` 在
`TunMode.RootTproxy` / `RootEbpf` 分支里 `copy(enable = false)`。也就是说今天关闭只存在于
`override.run.json`，配置文件文本自始至终是 `true`。

## 修复

1. `prepare()` 新增 `forceTunDisabled: Boolean = false`，并用一个 `tunEnabledInSource` 三态
   把两个开关合成一个「源配置里 tun 应该是什么值」：

   | 子模式 | forceTunEnabled | forceTunDisabled | 写入源配置的 `tun.enable` |
   | --- | --- | --- | --- |
   | TUN（ROOT/VPN） | true | false | `true`（与改动前一致） |
   | TPROXY | false | true | **`false`** |
   | eBPF | false | true | **`false`** |
   | VPN 路径（`MishkaTunService`） | false | false | 沿用原逻辑（`true`） |

   - 缺 `tun` 段时：按上表的值整段插入默认项（`tunDefaults(enable = …)`）；
   - 已有 `tun` 段时：把 `tun.enable` 直接写成上表的值；
   - 两个开关互斥，TUN 子模式优先。
2. age 密文配置：ciphertext 仍然一字不改，改为在 per-profile overlay 的 `tunDefaults` 里记
   `enable = !forceTunDisabled`，与明文路径保持一致。
3. `service/MishkaRootService.kt` 的调用点补一行：

   ```kotlin
   forceTunDisabled = submode == Submode.Tproxy || submode == Submode.Ebpf,
   ```

   与既有的 `forceTunEnabled = submode == Submode.Tun` 并列。
4. `MishkaTunService`（VPN）的调用点**完全不动**：默认参数就是「既不强制开也不强制关」。

## 影响面

- 运行时行为不变：TPROXY / eBPF 的 `buildTunOverride` 本来就强制 `enable = false`，
  写入源配置的 `false` 只是让配置文件与运行时对上，不会额外开关 sing-tun；
- `useProfileTunDefaults`（`tunDefaultsInserted || profileParameters?.hasTun == false`）喂给
  `defaultProfileTunOverride()`（`enable = true`），随后仍会被 RootTproxy / RootEbpf 分支
  `copy(enable = false)` 覆盖，值不变；
- TUN 子模式与 VPN 模式逐字不变；
- 从 TPROXY 切回 TUN 时，`forceTunEnabled` 会把 `tun.enable` 重新写成 `true`。

## 验证

- `tools/verify_app_patch.sh --series` 的 0008 段断言：`forceTunDisabled` 参数与三态分支、
  默认段按子模式取值、overlay 的 `enable = !forceTunDisabled`、ROOT 服务里两个子模式都传
  `forceTunDisabled`，外加一条否定断言——`MishkaTunService.kt` 里**不出现** `forceTunDisabled`
  （VPN 路径必须保持原样）；
- 补丁在 0001–0007 之上 `git apply --check` 通过，且可 `git apply -R` 逆序还原。

**未验证**：沙箱没有 Android SDK 与真机（也没有 root 环境），未实机跑过 TPROXY / eBPF；
以上只是静态检查 + 补丁可应用性检查。
