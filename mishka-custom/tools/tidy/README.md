# 字段整理对拍（`ConfigTidy.kt` ⇄ mihomo_box 参考实现）

`ConfigTidy.kt` 是 mihomo_box 模块「整理配置字段顺序」按钮（`webroot/ui/js/core.js` 的
`tidyMihomoConfig`）的 Kotlin 版；本目录放的是它的**参考实现 + 对拍脚本**，用来证明两边在
同一批输入上逐字节一致，而不是「看起来差不多」。

| 文件 | 说明 |
| --- | --- |
| `tidy-ref.mjs` | 参考实现原样抽出（`core.js` 2944–3550 行区间，只去掉 `export`、末尾统一导出），不手改 |
| `run-ref.mjs` | 参考实现 CLI：`node run-ref.mjs <config.yaml>` |
| `Main.kt` | Kotlin 版 CLI：`java -jar tidy.jar <config.yaml>` |
| `cases/` | 对拍用例：多行块式 / 单行流式 / 锚点 / 注释 / 声明式 providers / 无结尾换行 / 只有注释 / Tab 缩进 / BOM / CRLF |

跑一遍（需要 node、JDK、kotlinc）：

```bash
tools/tidy/check_tidy_parity.sh --repo <Mishka 仓库> [--kotlinc <kotlinc 路径>]
```

换行符口径：参考实现按 `\n` 拼回，CRLF 输入会在行尾留下裸 `\r`；Kotlin 版保留原文行尾符。
对拍时两边都去掉 `\r` 再比，并单独断言 CRLF 文件整理后不出现裸换行（`14-crlf.yaml`）。
