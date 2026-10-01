# 内核侧：把 mihomo 换成 jieluojun/mihomo(Alpha)

这个目录只放两样东西，都是为了**不改仓库里任何已有文件**：

| 文件 | 作用 |
| --- | --- |
| `go.work` | Go 工作区文件。作用是把 `<仓库>/mihomo` 这份内核和 `app/src/main/native/mishka_core` 放进同一个工作区，于是内核换了分支之后**缺的依赖哈希由 `go.work.sum` 补**，仓库自带的 `go.mod` / `go.sum` 一个字节都不用动。 |
| `go.work.sum` | 上面那份工作区的校验和（130 行）。生成方式见下。 |

`go.work` 落在仓库根目录（`scripts/setup.sh` 会按实际内核路径生成，默认内核放在 `<仓库>/mihomo`）。

## 为什么非要有 go.work

`mishka_core/go.mod` 里写着 `require github.com/metacubex/mihomo v0.0.0` + `replace => ../../../../../mihomo`，
也就是说内核是被当作**本地模块**编译的。本地 replace 没问题，问题是**内核自己的依赖**也进了构建列表：
Mishka 内核（YuKongA/mihomo 的 `Mishka` 分支，基点 `dc66a8a1`）与 jieluojun/mihomo 的 `Alpha`
分叉了 700 多个提交，Alpha 多出来的依赖（mipstack、sing 系、ebpf 系…）在 `mishka_core/go.sum`
里没有哈希记录。实测（本仓 `VERIFY.md` 有完整命令）：

```
$ GOWORK=off GOPROXY=off go build -tags cmfa,mishka,with_gvisor ./     # 关掉工作区、断网
go: updates to go.mod needed, disabled by -mod=readonly; to update it:
        go mod tidy        # ← 要么改仓库里的 go.mod/go.sum，要么换方案
```

工作区模式（`go.work` + `go.work.sum`）是唯一不改仓库文件的解法：编译器只认 `go.work.sum`，`go.mod` / `go.sum` 保持原样。

## go.work.sum 是怎么生成的

在真实布局（`<仓库>/app/src/main/native/mishka_core` + `<仓库>/mihomo`）下，用与本项目 CI 一致的
Go 版本（`go-version-file: app/src/main/native/mishka_core/go.mod` → go 1.25.x）执行：

```bash
cd <仓库>/app/src/main/native/mishka_core
GOOS=android GOARCH=arm64 go mod download all     # 补齐 workspace 的 go.work.sum
GOOS=linux   GOARCH=amd64 go mod download all     # 桌面侧也补一遍，保证两边都能构建
```

之后这两条命令都可以重跑（幂等）。如果以后上游又加了新依赖、构建时报
`missing go.work.sum entry`，跑一次就能补上：

```bash
bash mishka-custom/scripts/setup.sh --repo <仓库> --refresh-sum
```

`scripts/build-release.sh` 在构建失败时会识别这类报错并提示上面这条命令。

## 内核补丁（4 个）

补丁文件在 `../patches/mihomo/`，基线 commit 与逐文件 blob 哈希记在 `../patches/mihomo/BASELINE.txt`。
它们全部来自 YuKongA/mihomo 的 `Mishka` 分支（Mishka 应用依赖的行为），rebase 到 Alpha 之上：

| 补丁 | 内容 | 来源 |
| --- | --- | --- |
| `0001-config-override-json` | `config.OverrideJSONPath` + `Parse()` 里合并该 JSON。`mishka_core/runtime.go` 的 `--override-json` 依赖它。 | `ceafd04` |
| `0002-sing-tun-mishka-build-tag` | `mishka` build tag：Android 上启用 `server_android.go`（uid→包名解析 + 按包名建规则），并让 fd（VpnService）模式跳过重复建路由。 | `2b7de8f` |
| `0003-config-mishka-tun-dns-patch` | `mishkaPatch` 钩子 + `config/patch_mishka.go`：DNS 关闭时注入 fake-ip 默认值；VPN 模式追加 `system://` 兜底；VPN 模式把 `tun` 段按白名单重建，丢掉 Linux-only 字段。 | `addd66b` + `4c724df` |
| `0004-sing-tun-forwarder-bind-interface` | fd 模式下恢复 `forwarderBindInterface = true`（上游 `e38aa82a` 删掉后，Android VPN 下延迟测试通、真实流量不通）。 | `523fc3e` |

Mishka 分支一共 6 个提交，其中：

* `ab405ba`（`stack: mips`）—— **Alpha 已经有了**（`constant/tun.go` 里有 `TunMips` 常量和 mips 映射），不需要移植；
* `4c724df` 里的 `--prefetch` CLI + `main.go` 改动 —— **Android 侧用不到**：`libmihomo.so` 的入口是
  `mishka_core/runtime.go` 的 `mihomoEntry()`，不走内核的 `main()`；订阅/provider 预下载由
  `mishka_core/fetch.go` 在进程内完成。故未移植，内核的 `main` 包在 App 构建里根本不会被编译。
