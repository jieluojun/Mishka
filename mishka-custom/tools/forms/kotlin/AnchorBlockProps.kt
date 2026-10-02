package top.yukonga.mishka.custom.anchor

/**
 * 锚点定义块可视化的性质测试：重点是「行内 flow 映射 / 序列」的键值分离——
 * `键: &名 { a: 1, b: 2 }` 必须被认成映射（逐项键值编辑），写回仍是同一行 flow。
 */

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

fun main() {
    // ---- 单行 flow 映射：键值拆行、嵌套 flow 整体保留、未改值逐字往返 ----
    val line = "代理合集: &providers { interval: 3600, proxy: 订阅更新, health-check: { enable: true, url: \"https://x/y#z\" } }"
    val block = AnchorBlock.parse(listOf(line), 0, 0, "providers")!!
    expect(block.kind == DefKind.Map, "flow header must be a Map, got ${block.kind}")
    expect(block.flowInline, "single-line flow must be flagged flowInline")
    expect(block.entries.map { it.keyRaw } == listOf("interval", "proxy", "health-check"), "keys split, got ${block.entries.map { it.keyRaw }}")
    expect(block.entries[0].value == "3600", "plain value kept")
    expect(block.entries[2].value == "{ enable: true, url: \"https://x/y#z\" }", "nested flow kept verbatim, got ${block.entries[2].value}")

    val unchanged = block.entries.map { DefRow(it, it.keyRaw, it.value) }
    expect(AnchorBlock.render(block, unchanged) == line, "unchanged render must reproduce the original line, got: ${AnchorBlock.render(block, unchanged)}")

    val edited = block.entries.map { DefRow(it, it.keyRaw, if (it.keyRaw == "interval") "7200" else it.value) }
    val rendered = AnchorBlock.render(block, edited)
    expect(rendered == "代理合集: &providers { interval: 7200, proxy: 订阅更新, health-check: { enable: true, url: \"https://x/y#z\" } }", "edited value rendered inline, got: $rendered")

    val appended = unchanged + DefRow(null, "timeout", "2000")
    expect(AnchorBlock.render(block, appended).endsWith("timeout: 2000 }"), "append keeps single-line flow, got: ${AnchorBlock.render(block, appended)}")

    // ---- 带引号的键 ----
    val quoted = AnchorBlock.parse(listOf("k: &q { \"a.b\": 1, 'c d': 2 }"), 0, 0, "q")!!
    expect(quoted.kind == DefKind.Map && quoted.entries.map { it.keyRaw } == listOf("\"a.b\"", "'c d'"), "quoted keys split, got ${quoted.entries.map { it.keyRaw }}")
    expect(AnchorBlock.render(quoted, quoted.entries.map { DefRow(it, it.keyRaw, it.value) }) == "k: &q { \"a.b\": 1, 'c d': 2 }", "quoted-key roundtrip")

    // ---- 单行 flow 序列 ----
    val seq = AnchorBlock.parse(listOf("k: &s [a, \"b,c\", d]"), 0, 0, "s")!!
    expect(seq.kind == DefKind.Seq && seq.flowInline, "flow seq kind")
    expect(seq.entries.map { it.value } == listOf("a", "\"b,c\"", "d"), "seq items keep quoting, got ${seq.entries.map { it.value }}")
    expect(AnchorBlock.render(seq, seq.entries.map { DefRow(it, "", it.value) }) == "k: &s [a, \"b,c\", d]", "seq roundtrip")

    // ---- 拆不动的回落标量：括号不平 / 非 flow 值 ----
    expect(AnchorBlock.parse(listOf("k: &u { a: 1"), 0, 0, "u")!!.kind == DefKind.Scalar, "unbalanced flow falls back to scalar")
    expect(AnchorBlock.parse(listOf("k: &v DIRECT"), 0, 0, "v")!!.kind == DefKind.Scalar, "plain scalar unchanged")
    expect(AnchorBlock.parse(listOf("k: &w { a: 1 } # note"), 0, 0, "w")!!.kind == DefKind.Map, "trailing comment stripped before flow split")

    // ---- 块式映射 / 序列与嵌套边界不受影响 ----
    val blockMap = AnchorBlock.parse(listOf("base: &base", "  type: http", "  interval: 3600"), 0, 2, "base")!!
    expect(blockMap.kind == DefKind.Map && !blockMap.flowInline, "block map stays block map")
    expect(AnchorBlock.render(blockMap, blockMap.entries.map { DefRow(it, it.keyRaw, if (it.keyRaw == "interval") "7200" else it.value) }) ==
        "base: &base\n  type: http\n  interval: 7200", "block render path intact")
    val nested = AnchorBlock.parse(listOf("x: &x", "  a: 1", "  hc:", "    enable: true"), 0, 3, "x")!!
    expect(nested.entries.size == 2 && nested.entries[1].nested, "nested entry flagged")
    expect(AnchorBlock.render(nested, nested.entries.map { DefRow(it, it.keyRaw, it.value) }) == "x: &x\n  a: 1\n  hc:\n    enable: true", "nested rows verbatim on unchanged values")

    // ---- 分类即继承资格：flow 映射认成 Map（isMergeableAnchor 的 Map 分支放行 <<:）----
    val mergeable = AnchorBlock.parse(listOf("base: &base { a: 1 }"), 0, 0, "base")!!
    expect(mergeable.kind == DefKind.Map, "flow map anchor classified as Map (mergeable)")
    expect(AnchorBlock.parse(listOf("s: &s DIRECT"), 0, 0, "s")!!.kind == DefKind.Scalar, "plain scalar not a map")

    println("anchor block flow key/value tests passed")
}
