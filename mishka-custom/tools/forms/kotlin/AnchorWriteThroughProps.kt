package top.yukonga.mishka.custom.forms

/**
 * 继承感知写入（写穿锚点 / 物化兜底）的性质测试：修复「修改已继承锚点的配置会全量覆盖到继承锚点的地方」。
 * 语料取 mihomo_box 默认配置的真实形态——顶层 flow 锚点（`代理合集: &providers { … }`）、
 * 条目 `<<: *providers` 继承、条目内 flow 映射二级合并（`override: { …, <<: *host }`）、
 * rule-providers 同构形态。对齐参考实现 mihomo_box core.js `applySet/applyDel` 的锚点语义：
 *  - proxy-providers / rule-providers 的「键本身继承」→ 写穿锚点定义（改一处、所有使用者同步）；
 *  - 其它顶层块的「键本身继承」→ 本地覆写行（显式键优先于合并键）；
 *  - 任何块的「路径穿过继承映射」→ 写穿锚点（本地只建半块会把继承映射整块顶掉）；
 *  - 写穿被引擎拒绝 → 物化完整继承块到本地再写（兄弟键一个不丢）。
 */

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

private fun docOf(text: String): YamlDoc = YamlDoc.parse(text)

private const val USER_SHAPE = """代理合集: &providers { interval: 3600, proxy: 订阅更新, health-check: { enable: true, url: https://www.gstatic.com/generate_204, interval: 86400, timeout: 2000 } }
混淆覆写: &host { override-expr: [ '.servername = "填免流混淆"', '.sni = .servername' ] }
域名集: &domain { type: http, interval: 86400, proxy: 订阅更新, behavior: domain, format: mrs }
proxy-providers:
  百度直连:
    type: http
    <<: *providers
    url: "https://example.com/BaiduDirect"
    override: { additional-prefix: "百度直连/" }
  CNS订阅:
    type: http
    <<: *providers
    url: "https://example.com/cns"
    override: { additional-prefix: "CNS订阅/", <<: *host }
rule-providers:
  排除域名:
    <<: *domain
    url: "https://example.com/fakeip-filter.mrs"
proxy-groups:
  - <<: *providers
    name: g1
    type: select
"""

private fun lines(text: String) = text.split("\n")

/** 除允许变化的行外，其余行必须逐字保留（锚点行、`<<:` 行、注释、空行）。 */
private fun expectOnlyChanged(before: String, after: String, allowedChanged: Set<String>, note: String) {
    val b = lines(before)
    val a = lines(after)
    expect(b.size == a.size, "$note: 行数变了 ${b.size} -> ${a.size}")
    for ((i, pair) in b.zip(a).withIndex()) {
        if (pair.first != pair.second) {
            expect(pair.first in allowedChanged || pair.second in allowedChanged,
                "$note: 第 ${i + 1} 行被意外改动\n  旧: ${pair.first}\n  新: ${pair.second}")
        }
    }
}

private fun testProviderScalarWriteThrough() {
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "百度直连", "interval")
    val w = AnchorInheritance.applySetAware(doc, path, 1800)
    expect(w.anchor == "providers", "合集改继承键应写穿 &providers，实际 ${w.anchor}")
    expect(w.path == listOf<Any>("代理合集", "interval"), "落点应是锚点定义侧，实际 ${w.path}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("interval: 1800"), "锚点行应更新为 interval: 1800")
    expect(!out.contains("    interval:"), "条目里不得出现本地 interval 行")
    expect(out.contains("<<: *providers"), "`<<: *providers` 引用必须原样保留")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "CNS订阅", "interval")) == "1800",
        "同锚点的其它使用者必须同步生效")
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "百度直连", "interval")) == "1800", "本条目生效值更新")
    expectOnlyChanged(USER_SHAPE, out, setOf(lines(USER_SHAPE)[0]), "写穿标量")
}

private fun testProviderNestedFlowWriteThrough() {
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "百度直连", "health-check", "url")
    expect(doc.get(path) == null, "health-check.url 本地必须不存在（纯继承）")
    val w = AnchorInheritance.applySetAware(doc, path, "https://cp.cloudflare.com/generate_204")
    expect(w.anchor == "providers", "穿过继承映射必须写穿 &providers，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("url: https://cp.cloudflare.com/generate_204"), "锚点内嵌套 flow 的 url 段应被替换")
    expect(lines(out)[0].contains("timeout: 2000"), "同级继承键 timeout 必须保住")
    expect(lines(out)[0].contains("enable: true"), "同级继承键 enable 必须保住")
    expect(!out.contains("health-check:\n"), "条目里不得物化出本地 health-check 块")
    expect(out.contains("<<: *providers"), "合并引用保留")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "CNS订阅", "health-check", "url")) ==
        "https://cp.cloudflare.com/generate_204", "其它使用者同步")
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "CNS订阅", "health-check", "timeout")) == "2000",
        "其它使用者未编辑键不受影响")
    expectOnlyChanged(USER_SHAPE, out, setOf(lines(USER_SHAPE)[0]), "写穿嵌套 flow")
}

private fun testHcEnableOffWriteThroughKeepsSiblings() {
    // 复刻健康检查三态选择「关」：以前会在条目里建 enable:false 半块，把继承的 url/interval/timeout 整块顶掉
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "百度直连", "health-check", "enable")
    val w = AnchorInheritance.applySetAware(doc, path, false)
    expect(w.anchor == "providers", "enable 写穿锚点，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("enable: false"), "锚点内 enable 应改为 false")
    expect(lines(out)[0].contains("url: https://www.gstatic.com/generate_204"), "继承 url 不得被默认值顶掉")
    expect(lines(out)[0].contains("interval: 86400"), "继承 interval 不得被默认值顶掉")
    expect(!out.contains("cp.cloudflare.com"), "不得写入官方默认 url")
    expect(out.contains("<<: *providers"), "合并引用保留")
}

private fun testLocalLineWins() {
    val src = USER_SHAPE.replace(
        "    <<: *providers\n    url: \"https://example.com/BaiduDirect\"",
        "    <<: *providers\n    interval: 7200\n    url: \"https://example.com/BaiduDirect\"",
    )
    val doc = docOf(src)
    val path: YPath = listOf("proxy-providers", "百度直连", "interval")
    val w = AnchorInheritance.applySetAware(doc, path, 900)
    expect(w.anchor == null, "本地已有行必须本地优先，不写穿")
    val out = w.doc.dump()
    expect(out.contains("    interval: 900"), "本地行就地更新")
    expect(lines(out)[0].contains("interval: 3600"), "锚点不动")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "CNS订阅", "interval")) == "3600",
        "其它使用者不受本地覆写影响")
}

private fun testNonProviderKeyStaysLocal() {
    // 参考实现语义：非合集块的「键本身继承」写本地覆写行（proxy-groups 的 interval 来自 <<: *providers）
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-groups", 0, "interval")
    val w = AnchorInheritance.applySetAware(doc, path, 900)
    expect(w.anchor == null, "非合集块键本身继承 → 本地覆写，实际写穿 ${w.anchor}")
    val out = w.doc.dump()
    expect(out.contains("    interval: 900"), "组条目里应插入本地覆写行")
    expect(lines(out)[0].contains("interval: 3600"), "锚点不动")
    expect(out.contains("<<: *providers"), "合并引用保留")
}

private fun testNonProviderThroughMapWritesThrough() {
    // 参考实现语义：任何块里「路径穿过继承映射」都写穿——本地半块会顶掉整块继承映射
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-groups", 0, "health-check", "timeout")
    val w = AnchorInheritance.applySetAware(doc, path, 3000)
    expect(w.anchor == "providers", "穿过继承映射应写穿（不限合集块），实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("timeout: 3000"), "锚点侧更新")
    expect(!out.contains("    health-check:"), "组条目里不得出现半块本地 health-check")
}

private fun testNestedFlowSecondLevelMerge() {
    // 用户配置形态：override 是条目本地 flow 映射，内含 `<<: *host` 二级合并
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "CNS订阅", "override", "override-expr")
    val (items, inherited) = FormValues.exprItems(doc, listOf("proxy-providers", "CNS订阅"))
    expect(inherited && items.size == 2, "表达式应识别为继承（2 条），实际 $items / $inherited")
    val w = AnchorInheritance.applySetAware(doc, path, listOf(".sni = .x"))
    expect(w.anchor == "host", "二级合并应写穿 &host，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[1].contains("override-expr: [ .sni = .x ]") || lines(out)[1].contains("override-expr: [.sni = .x]"),
        "host 锚点行应更新为新表达式列表，实际 ${lines(out)[1]}")
    expect(out.contains("override: { additional-prefix: \"CNS订阅/\", <<: *host }"),
        "条目的 override flow 行必须原样保留（含二级合并引用）")
    val after = docOf(out)
    val (items2, inherited2) = FormValues.exprItems(after, listOf("proxy-providers", "CNS订阅"))
    expect(inherited2 && items2 == listOf(".sni = .x"), "编辑后仍是继承态且值更新，实际 $items2 / $inherited2")
    expect(FormValues.effectiveValue(after, listOf("proxy-providers", "百度直连", "override", "override-expr")) == null,
        "没挂 *host 的条目不受影响")
}

private fun testRuleProviderWriteThrough() {
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("rule-providers", "排除域名", "interval")
    val w = AnchorInheritance.applySetAware(doc, path, 3600)
    expect(w.anchor == "domain", "规则集写穿 &domain，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[2].contains("interval: 3600"), "域名集 锚点行更新")
    expect(out.contains("    <<: *domain"), "合并引用保留")
    expect(!out.contains("    interval:"), "条目里不得出现本地 interval")
}

private fun testNewKeyStaysLocal() {
    // 锚点里没有的全新键：写本地，不污染共享锚点
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "百度直连", "filter")
    val w = AnchorInheritance.applySetAware(doc, path, "(?i)hk")
    expect(w.anchor == null, "全新键不得写穿，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(out.contains("    filter: (?i)hk"), "本地插入新键")
    expect(!lines(out)[0].contains("filter"), "锚点行不得被污染")
}

private fun testMaterializeFallback() {
    // 写穿被引擎拒绝（多行值装不进锚点的 flow 行）→ 物化完整继承块到本地再写，兄弟键一个不丢
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "百度直连", "health-check", "url")
    val w = AnchorInheritance.applySetAware(doc, path, "line1\nline2")
    expect(w.anchor == null, "物化兜底不算写穿")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("url: https://www.gstatic.com/generate_204"), "锚点原样不动")
    expect(out.contains("    health-check:"), "条目里物化出本地 health-check 块")
    expect(out.contains("      enable: true"), "物化块带上继承的 enable")
    expect(out.contains("      interval: 86400"), "物化块带上继承的 interval")
    expect(out.contains("      timeout: 2000"), "物化块带上继承的 timeout")
    expect(out.contains("      url: |"), "新值按块标量写入")
    expect(out.contains("line1"), "多行值第一段")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "百度直连", "health-check", "timeout")) == "2000",
        "物化后生效值与原继承值一致")
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "CNS订阅", "health-check", "url")) ==
        "https://www.gstatic.com/generate_204", "其它使用者仍走锚点")
}

private fun testAnchorChain() {
    // 锚点链：条目 <<: *mid，mid 自己 <<: *leafbase，键只在 leafbase 里 → 一路追到 leafbase
    val src = """leafbase: &leafbase { timeout: 2000 }
mid: &mid
  <<: *leafbase
  interval: 600
proxy-providers:
  sub:
    <<: *mid
    url: https://example.com/x
"""
    val doc = docOf(src)
    val path: YPath = listOf("proxy-providers", "sub", "timeout")
    val w = AnchorInheritance.applySetAware(doc, path, 3000)
    expect(w.anchor == "leafbase", "锚点链应追到定义处 &leafbase，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("timeout: 3000"), "leafbase 行更新")
    expect(out.contains("<<: *mid") && out.contains("<<: *leafbase"), "两级合并引用都保留")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxy-providers", "sub", "timeout")) == "3000", "生效值更新")
    expect(FormValues.effectiveText(after, listOf("mid", "timeout")) == "3000", "中间锚点视图同步")
}

private fun testAnchorChainThroughMap() {
    // 锚点链 + 穿过继承映射：health-check 只在 leafbase 里，改它的子键要落到 leafbase 的 flow 行
    val src = """leafbase: &leafbase { health-check: { enable: true, url: https://a.example/204 } }
mid: &mid
  <<: *leafbase
  interval: 600
proxy-providers:
  sub:
    <<: *mid
    url: https://example.com/x
"""
    val doc = docOf(src)
    val path: YPath = listOf("proxy-providers", "sub", "health-check", "url")
    val w = AnchorInheritance.applySetAware(doc, path, "https://b.example/204")
    expect(w.anchor == "leafbase", "穿过链上继承映射应落到 &leafbase，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[0].contains("url: https://b.example/204"), "leafbase 的嵌套 flow 更新")
    expect(!out.contains("    health-check:"), "条目里不得物化半块")
}

private fun testSeqItemAnchorThroughMap() {
    // 锚点定义在序列项上（`- &nodebase { … }`）：def 路径 = [proxies, 0]
    val src = """proxies:
  - &nodebase { type: ss, ws-opts: { path: /old, headers: { Host: a.example } } }
  - name: n2
    <<: *nodebase
    server: 1.2.3.4
"""
    val doc = docOf(src)
    val path: YPath = listOf("proxies", 1, "ws-opts", "path")
    val w = AnchorInheritance.applySetAware(doc, path, "/new")
    expect(w.anchor == "nodebase", "序列项锚点写穿，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(lines(out)[1].contains("path: /new"), "锚点项的 flow 行更新")
    expect(!out.contains("    ws-opts:"), "n2 不得物化半块 ws-opts")
    expect(out.contains("<<: *nodebase"), "合并引用保留")
    val after = docOf(out)
    expect(FormValues.effectiveText(after, listOf("proxies", 1, "ws-opts", "headers", "Host")) == "a.example",
        "未编辑的继承子键保住")
}

private fun testWriteThroughTargetGating() {
    val doc = docOf(USER_SHAPE)
    // 本地已有行 → 不写穿
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-providers", "百度直连", "url")) == null,
        "本地行必须本地优先")
    // 非合集块 + 键本身继承 → 不写穿（本地覆写行）
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-groups", 0, "interval")) == null,
        "非合集块键本身继承走本地覆写")
    // 合集块 + 键本身继承 → 写穿
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-providers", "百度直连", "interval"))?.anchor == "providers",
        "合集键本身继承应写穿")
    // 任何块 + 穿过继承映射 → 写穿
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-groups", 0, "health-check", "url"))?.anchor == "providers",
        "穿过继承映射应写穿")
    // 全新键 → 不写穿
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-providers", "百度直连", "filter")) == null,
        "锚点没有的键不得写穿")
    // 没有合并的普通路径 → 不写穿
    expect(AnchorInheritance.writeThroughTarget(doc, listOf("proxy-providers", "百度直连", "override", "additional-prefix")) == null,
        "本地 flow 里的显式键不写穿")
    // 物化定位：只有「本地缺失 + 继承视图是映射」的层级才返回
    val mat = AnchorInheritance.materializeInheritedPrefix(doc, listOf("proxy-providers", "百度直连", "health-check", "url"))
    expect(mat != null && mat.first == listOf<Any>("proxy-providers", "百度直连", "health-check"),
        "物化定位应停在 health-check 层，实际 ${mat?.first}")
    expect(mat?.second?.keys == setOf("enable", "url", "interval", "timeout"),
        "物化内容应是完整继承映射，实际 ${mat?.second?.keys}")
    expect(AnchorInheritance.materializeInheritedPrefix(doc, listOf("proxy-providers", "百度直连", "url")) == null,
        "本地存在的父级不物化")
    expect(AnchorInheritance.materializeInheritedPrefix(doc, listOf("proxy-providers", "百度直连", "filter")) == null,
        "全新键路径不物化")
}

private fun testNoAnchorRegression() {
    // 无锚点配置：applySetAware 与原始 setValue 行为一致
    val src = "dns:\n  enable: true\nproxies:\n  - name: a\n    type: ss\n    server: 1.2.3.4\n"
    val doc = docOf(src)
    val w = AnchorInheritance.applySetAware(doc, listOf("dns", "enable"), false)
    expect(w.anchor == null, "无锚点不得写穿")
    expect(w.doc.dump() == YamlPatch.setValue(doc, listOf("dns", "enable"), false).dump(), "与普通写入完全一致")
    val w2 = AnchorInheritance.applySetAware(doc, listOf("proxies", 0, "ws-opts", "path"), "/x")
    expect(w2.doc.dump() == YamlPatch.setValue(doc, listOf("proxies", 0, "ws-opts", "path"), "/x").dump(),
        "新建中间映射行为不变")
    expect(w2.doc.dump().contains("    ws-opts:\n      path: /x"), "普通新建照旧")
}

private fun testEffectiveHelpers() {
    val doc = docOf(USER_SHAPE)
    val baidu: YPath = listOf("proxy-providers", "百度直连")
    expect(FormValues.hasEffective(doc, baidu + "interval"), "继承键 hasEffective 必须为真")
    expect(!FormValues.hasValue(doc, baidu + "interval"), "继承键 hasValue（本地）必须为假")
    expect(FormValues.effectiveBool(doc, baidu + "health-check" + "enable") == true, "继承布尔按展开视图读")
    expect(FormValues.effectiveBool(doc, baidu + "type") == null, "非布尔给 null")
    expect(!FormValues.hasEffective(doc, baidu + "filter"), "不存在的键 hasEffective 为假")
}

private fun testWriteThroughFlowMapExpandsForLongList() {
    // 用户报告形态（r5）：锚点是单行 flow 映射，写穿值是「一行装不下」的字符串列表。
    // 旧行为：flowSet 对装不下一行的集合返回原文 → 写穿/物化/直写三路全拒 →
    // 「override-expr 未改动：这个位置不能这样写」。现在应展开 flow 映射成块式再写穿。
    val doc = docOf(USER_SHAPE)
    val path: YPath = listOf("proxy-providers", "CNS订阅", "override", "override-expr")
    val long = listOf(
        ".servername = \"填免流混淆\"",
        ".sni = .servername",
        "(select(.network == \"ws\") | .[\"ws-opts\"].headers.Host) = .servername",
        "(select(.network == \"http\") | .[\"http-opts\"].headers.Host) = [.servername]",
    )
    val w = AnchorInheritance.applySetAware(doc, path, long)
    expect(w.anchor == "host", "应写穿 &host，实际 ${w.anchor}")
    val out = w.doc.dump()
    expect(out != doc.dump(), "不得原样返回（旧 bug：拒写后退到「未改动」提示）")
    expect(!lines(out)[1].contains("override-expr: ["), "flow 行应已展开成块式，实际 ${lines(out)[1]}")
    for (e in long) expect(out.contains(e.take(12)), "表达式应落盘: $e")
    val after = docOf(out)
    val (items, inherited) = FormValues.exprItems(after, listOf("proxy-providers", "CNS订阅"))
    expect(items == long && inherited, "回读应等于写入列表且仍标继承，实际 $items / $inherited")
    val (items2, _) = FormValues.exprItems(after, listOf("proxy-providers", "百度直连"))
    expect(items2.isEmpty(), "未继承 host 的条目不应获得表达式")
    expect(after.get(listOf("proxy-providers", "CNS订阅", "override", "additional-prefix")) != null,
        "本地兄弟键 additional-prefix 应保留")
}

fun main() {
    testProviderScalarWriteThrough()
    testProviderNestedFlowWriteThrough()
    testHcEnableOffWriteThroughKeepsSiblings()
    testLocalLineWins()
    testNonProviderKeyStaysLocal()
    testNonProviderThroughMapWritesThrough()
    testNestedFlowSecondLevelMerge()
    testRuleProviderWriteThrough()
    testNewKeyStaysLocal()
    testMaterializeFallback()
    testAnchorChain()
    testAnchorChainThroughMap()
    testSeqItemAnchorThroughMap()
    testWriteThroughTargetGating()
    testWriteThroughFlowMapExpandsForLongList()
    testNoAnchorRegression()
    testEffectiveHelpers()
    println("AnchorWriteThroughProps: all test groups passed")
}
