# mishka-custom（2026-10-08 修复版）—— 仅补丁

针对实机反馈四项问题的修复包。**只需要仓库 + 本目录里的补丁**，不引入额外依赖、不改上游 mihomo 子模块。

## 基线

| 项 | 值 |
| --- | --- |
| app 侧 base commit | `5e6743592b9c465eb015db7b05c588c50cd2b874` |
| 补丁文件 | `patches/app/0001-anchor-panel.patch`（全量补丁，相对 base commit） |
| `patch_sha256` | `1f5cadbcf6be5dc41ca74bd011229230d8c90c7a940bef0eeccbc42e8306c17a` |
| 补丁涉及文件 | 74 个（28 新增 + 46 修改），完整清单与每个文件的 blob 哈希见 `patches/app/BASELINE.txt` |

> 补丁是**全量**补丁：它对 base commit 的干净树上「一次成型」，不是增量补丁。上一版补丁
> （`patch_sha256 = f3435619…` / `6d98c73f…`）请先撤掉，或直接把仓库重置回 base commit 再应用。

## 应用

### 情况 A：仓库是干净的 base commit（推荐）

```bash
cd <Mishka 仓库>
git checkout 5e6743592b9c465eb015db7b05c588c50cd2b874   # 若已在：git reset --hard 5e67435…
git clean -fd                                            # 清掉未跟踪文件（新增源码会落在此）
git apply mishka-custom/patches/app/0001-anchor-panel.patch
```

或直接用包里的脚本：

```bash
bash mishka-custom/scripts/setup.sh          # 按脚本提示指定仓库路径
```

### 情况 B：仓库里已经应用了**上一版**补丁（当前工作区是改过的）

```bash
cd <Mishka 仓库>
git apply -R mishka-custom-old/patches/app/0001-anchor-panel.patch   # 先反向撤掉旧补丁
git clean -fd                                                        # 清掉旧补丁新增的源码目录
# 确认干净：git status --porcelain 应无输出
git apply mishka-custom/patches/app/0001-anchor-panel.patch          # 再应用本包补丁
```

### 撤销

```bash
git apply -R mishka-custom/patches/app/0001-anchor-panel.patch
git clean -fd
```

## 校验

```bash
bash mishka-custom/tools/verify_app_patch.sh --repo <Mishka 仓库>
# 期望：PASS: app 侧补丁双向可逆、结果与基线逐文件一致
#   - 补丁 sha256 == BASELINE.txt 的 patch_sha256
#   - git apply --check → apply → 74 个 blob 逐一比对 → apply -R → 工作区回到干净
#   - 109 条文本断言（运行时配置 / root / 表单 / 拖动手势 / 本轮 4 项修复）
```

编译（落地后至少跑一次）：

```bash
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```

## 本轮修了什么

详见 `CHANGES.md` 顶部「本次改动（2026-10-08）」。一句话版：

1. **拖动排序把配置写坏（`MATCH,国外出口 CT`、`enable: trueCT`）** —— 整篇替换的 END 端点误用**新**文本行数
   计算，删行后旧文档尾巴粘在新文本末尾；改为按旧文本算。顺带：行内标量写回不再重复补 `&锚点`（`&on &on`）。
   站点：`custom/forms/ConfigFormPanel.kt`、`custom/anchor/AnchorPanel.kt`、`custom/forms/YamlEngine.kt`。
2. **分区页开关「切一个、另一个跟着变」** —— 开关状态改为按**生效值**显示（锚点继承 / 别名不再画成关），
   「本来就没设置 / 没有变化」只在生效值层面成立时才短路，写共用键（`&锚点` 被别处 `*别名` 引用）时 toast
   点名「会一起生效」。站点：`custom/forms/ConfigFormPanel.kt`。
3. **规则页 / 序列列表加行尾 ↑ / ↓** —— `SeqListPage` 可选 `onMove`，规则页接 `host.moveItem`；拖动把手保留。
   站点：`custom/forms/FlowFormPages.kt`。
4. **拖到顶又被弹回下面** —— 手指离开容器时直接落列表头 / 尾（不再拿过期量测值做「最近行」兜底），
   命中判定跳过被 lazy 列表回收（`isAttached == false`）的行，自命中早退只在容器内生效。
   站点：`custom/forms/DragSort.kt`。

## 目录

```
mishka-custom/
├── README.md              ← 本文件
├── CHANGES.md             ← 全部改动记录（顶部为本轮修复）
├── patches/app/
│   ├── 0001-anchor-panel.patch   ← app 侧全量补丁
│   └── BASELINE.txt              ← base commit / patch_sha256 / 74 个 blob
├── tools/
│   ├── verify_app_patch.sh            ← 双向可逆 + blob 比对 + 109 条断言
│   ├── check_trailing_lambda.py       ← 尾随 lambda 与形参顺序（CI 那类编译错防回归）
│   ├── check_cross_package_imports.py ← 跨包引用检查
│   └── verify_mihomo_patches.sh       ← mihomo 子模块补丁校验
├── scripts/{setup.sh, lib.sh, prepare-scripta.sh}
├── init/no-debug.init.gradle
└── kernel/                ← 与本次修复无关（内核构建相关文件，原样保留）
```
