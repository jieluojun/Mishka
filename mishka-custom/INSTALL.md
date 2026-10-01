# 安装、使用与回滚

## 安装 APK

```bash
adb install -r app/build/outputs/apk/release/<产物>.apk
# 手机上手动安装也行：把 APK 拷进去点安装即可（需要允许安装未知来源）
```

* **签名不同不能覆盖安装**：本定制的包与官方 Mishka 发布包签名不同（除非你把自己的 keystore 配到了官方构建里），
  从官方版切过来需要先卸载；同理，用 CI 的「debug 风格兜底密钥」构建的包，切换到你自己的正式 keystore 后
  也需要卸载重装。
* **包名与官方一致**（`applicationId` 未改），所以无法与官方版共存；如需共存，改 `buildSrc` 里的
  `PACKAGE_NAME`（这属于改仓库文件，本定制没做）。
* 升级：装更高版本的 APK 即可（同签名）。配置/订阅数据保留在应用数据里。

## 首次启动检查

1. **VPN / ROOT 模式**：内核换成了 jieluojun/Alpha，`tun` 段的行为差异由补丁对齐（VPN 模式下会按白名单
   重建 `tun` 段、DNS 关闭时注入 fake-ip 默认值、fd 模式绑定 forwarder）。首次启动请确认能正常连通；
   `Proxy` 页看延迟、`Connections` 页看真实流量（延迟通但流量不通就是补丁 `0004` 在治的那个问题）。
2. **TUN 栈**：Alpha 的默认栈是 `mips`，`mishka_core` 在配置与 override 都没指定栈时也会主动设成
   `Const.TunMips`。如果你的订阅里有 `tun.stack`，以订阅为准。
3. **DNS**：订阅里没写 DNS 时，面板外的默认值来自补丁 `0003`（fake-ip + 国内外 nameserver）。
4. **锚点面板**：文件管理器 → 打开任意 `.yaml` / `.yml` → 顶栏的「链环」图标。改动即时可见，
   真正写盘仍走顶栏的「确定」（内核校验不过会回滚并提示）。
5. **配置表单**：同一个顶栏里的「调节」图标（在链环图标右边）→ 13 格配置首页。改动同样即时可见、
   同样靠顶栏「确定」写盘。

## 日常使用配置表单

* 首页 13 格 = 参考实现的配置页入口。六格字段页：**全局配置 / DNS / 域名嗅探 / 入站 / NTP / 实验性配置**；
  七格流程页（P2）：**出站代理 / 代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道**，都是「列表 → 详情」：
  列表页新增 / 上移下移 / 删除（映射类是新建 / 改名 / 删除），详情页按协议 / 类型显示字段；路由规则有逐条对话框和整列表文本模式。
  删出站代理 / 代理集合 / 代理组 / 规则集合前会先查当前草稿里的引用（代理组成员、规则目标、`RULE-SET`、DNS、隧道…），
  有引用就列出位置、不让删；改名会同步引用它的地方（节点 → 代理组成员；代理组 → 其它组成员与规则目标；集合 → `use`；
  规则集 → `RULE-SET,名字`），提示里会写清同步了几处。
  任何一格里表单改不了的结构（别名项、纯值、装不下的单行集合、引用落在锚点 / 别名里的改名）都会明确提示，照旧可以在 YAML 编辑器里直接改。
* 每格显示当前状态：代理数 / 规则数 / 订阅数，DNS、嗅探、NTP 显示「已启用 / 未启用」。
* 字段行按类型给控件：**开关**（直接拨）、**下拉**（点开列选项，可用的选项带「当前」标记；`allowEmpty`
  的字段多一项「（未设置）」= 删掉这个键回内核默认）、**文本 / 数字**（弹窗输入）、**多行文本**（按 `|`
  块写入）、**列表**（逐项增 / 删 / 上移，确定时整键重写）。
* 改动会立刻写进编辑器草稿并跳到那一行 —— **此时还没落盘**，顶栏「确定」才写文件。
  写之前想反悔：编辑器自己的撤销（左下角）能回退整个改动。
* 行摘要里如果写着「该路径下面是列表/纯值，不能在这里改」，说明这个键的结构不适合表单改（比如
  `mergeTun` 那种带下标的东西），在编辑器里直接改原文即可。
* 落盘是**手术式**的：只重写被改的那一行 / 那个键，其余字节（注释、缩进、CRLF、锚点语法）原样保留；
  改回原值会逐字节还原。

## 日常使用锚点面板

* 卡片上的行号可点，跳到编辑器对应位置（面板会自动关闭）。
* 「编辑」定义块：映射/序列逐行增删改，值支持单行标量与 `[..]` / `{..}`；写回前有预览。
  形态拆不动（嵌套多行值、缩进 flow 根）时会提示切「文本」模式改原文。
* 「编辑引用行」：换绑 / `<<:` 的「不继承」（等于删掉这一行）/ 删行。候选列表只列
  **定义在这一行之前**的锚点，且 `<<:` 只列映射锚点 —— 这是 YAML 的硬要求，不是保守。
* 「新建」：顶层条目名 + 锚点名 + 参数行，插到文件头（便于后面 `<<:` 引用）。
* 「改名」：锚点名级联到所有 `&` / `*` 出现处；条目名（顶层键）只改定义行。
* 「删除」：还有引用时按钮是灰的；mihomo 真正读取的配置段只摘 `&名`，自建的容器整块删。

## 构建报错速查（CI / 本地）

| 报错 | 真实原因 | 修复 |
| --- | --- | --- |
| `bash: mishka-custom/scripts/setup.sh: No such file or directory` | `mishka-custom/` 被提交成了子模块指针（网页上是个空目录） | 本地 `rm -rf mishka-custom/.git && git rm --cached -r mishka-custom && cp -r <交付包>/mishka-custom . && git add mishka-custom && git commit -m 'fix' && git push` |
| `Checkout scripta submodule` 失败：`pathspec 'scripta' did not match any file(s) known to git` | fork 里丢了 scripta 子模块指针（`includeBuild("scripta")` 因此找不到源码） | `bash mishka-custom/scripts/repair-fork.sh --repo .`（会按上游 pin 恢复指针），或让工作流自带的兜底逻辑 clone 一份 |
| `./gradlew: Permission denied` | gradlew 丢了可执行位（100644） | `chmod +x gradlew && git update-index --chmod=+x gradlew && git commit -m 'fix: gradlew +x' && git push` |
| `mishka-custom/ 里缺下面这些文件…`（工作流自检报的） | 上传不完整（常见：只传了 scripts/，没传 patches//kernel/） | 把交付包里的 `mishka-custom/` **整个**重新覆盖上传 |
| `missing go.work.sum entry` / `updates to go.mod needed` | 依赖哈希不全 | `bash mishka-custom/scripts/setup.sh --repo . --refresh-sum`（新包已内置，无需 tools/） |

## 拉上游更新（推荐流程）

```bash
cd <你的 Mishka 仓库>

# 1) 还原补丁，回到干净工作区
bash mishka-custom/scripts/revert-patches.sh --repo .

# 2) 拉上游（内核不用管：mihomo/ 已设 ignore，且随时可重装）
git pull

# 3) 装回来（幂等；上游若改过编辑器入口，脚本会尝试 3way 并提示确认）
bash mishka-custom/scripts/setup.sh --repo .

# 4) 重新构建
bash mishka-custom/scripts/build-release.sh --repo .
```

更新内核（jieluojun/Alpha 有新提交时）：

```bash
# 只想把内核挪到 Alpha 的新提交：先还原再重装
bash mishka-custom/scripts/revert-patches.sh --repo . --all
bash mishka-custom/scripts/setup.sh --repo .          # 会重新 checkout 到补丁基线 commit
```

> 注意：补丁是**绑定基线 commit** 的（`patches/mihomo/BASELINE.txt` 里的 `base_commit`）。
> 如果 Alpha 前进后补丁打不上，`setup.sh` 会在预检阶段直接报错，不会留下半成品。
> 想跟到新 commit：先把内核挪过去，再用 `tools/verify_mihomo_patches.sh --kernel-dir <内核>`
> 看补丁是否还能干净应用；不行就按 `patches/mihomo/*.patch` 的内容手工 rebase 一次，
> 然后 `tools/export_app_patch.sh` 之外的补丁用 `git diff` 重新导出（并更新 BASELINE.txt）。

## 彻底移除定制

```bash
cd <你的 Mishka 仓库>
bash mishka-custom/scripts/revert-patches.sh --repo . --all   # 补丁 + 内核 + go.work 全部还原
rm -rf mishka-custom                                          # 删掉定制目录本身
git status                                                    # 应为空
```

`.git/info/exclude` 里多出来的几行登记（`/mishka-custom/`、`/go.work`、`/go.work.sum`、
`/app/src/main/kotlin/top/yukonga/mishka/custom/`）可以手动删掉，它们不影响任何构建。
