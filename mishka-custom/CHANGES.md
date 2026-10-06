# 本次改动（2026-10-06）

基线：`upstream_commit=5e6743592b9c465eb015db7b05c588c50cd2b874`（未变）
补丁：`mishka-custom/patches/app/0001-anchor-panel.patch`（已重新导出）

## 1. 隐藏不可用：排除 mihomo 内置策略

`代理组` 页的「隐藏不可用」按 `delay == -1`（healthcheck 超时）过滤节点。mihomo 的内置
出站 `DIRECT` / `REJECT` / `REJECT-DROP` / `PASS` / `COMPATIBLE` / `GLOBAL` **从不参与拨测**，
`history` 里的延迟恒为 0，于是被一并判定成「超时」——一开开关它们就整组消失，偏偏这几个是
任何配置都得留着的兜底出口。

改动：`ProxyViewModel.kt` 新增 `isBuiltInOutbound(name, type)`，`ProxyScreen.kt` 的过滤条件
改为「内置出站 **或** delay != -1」，内置出站一律豁免，只滤真正测不通的节点。

**判定只按名字走**，类型仅作兜底：`/proxies` 的 `type` 在内置出站上经常拿不到（`GLOBAL` 的
类型是 `Selector`，与用户自建的代理组无从区分；provider 视角下也可能为空）。之前那版如果挂在
类型上，就会表现为「改了没生效」——名字是唯一在每条数据路径上都存在的信号。

## 2. 锚点面板：编辑 / 删行 之间补间距

miuix 的 `BasicComponent` 把 `endActions` 直接塞进一个**没有 arrangement 的 Row**，两个实心
按钮各带 12dp 内边距，贴在一起时看着像一整块胶囊、也容易误触。

改动：`AnchorPanel.kt` 在「编辑」与「删行」之间插入 `Spacer(Modifier.width(RefActionGap))`，
取 8dp，与卡片底部「定位 / 改名 / 删除」那一行的 `spacedBy(8.dp)` 对齐。锚点卡片与悬空引用
卡片两处同步。

## 验证

- `mishka-custom/tools/verify_app_patch.sh --repo <仓库>` → **PASS**：补丁双向可逆，
  应用结果与 `BASELINE.txt` 的 64 个 blob 逐文件一致。
- `BASELINE.txt` 的 `patch_sha256` 已随新补丁更新为
  `537b26fdb8d6771913b69fb509944e75029a0b8b3e98f7ec794a0c6faf0beee3`。
- 沙箱内存只有 2GB，`:app:compileDebugKotlin` 跑到配置阶段就被 OOM 掉了，**本次没有跑通
  编译验证**。落地后请先跑一次：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```
