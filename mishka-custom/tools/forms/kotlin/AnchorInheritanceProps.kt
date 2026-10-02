package top.yukonga.mishka.custom.forms

/** 锚点继承 / 二级锚点手术的性质测试：现状读取 + 行级手术 + 展开视图读参（可视化「读配置文件参数」）。 */

private const val SAMPLE = """dl: &dl DIRECT
base: &base
  type: http
  behavior: domain
  interval: 86400
hc: &hc
  enable: true
  interval: 600
rule-providers:
  ads:
    <<: *base
    url: https://example.com/ads.yaml
  local-set:
    type: file
    behavior: classical
    fmt: &fmt yaml
    health-check:
      <<: *hc
    path: ./rules/x.yaml
proxy-groups:
  - <<: *base
    name: g1
    type: select
"""

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

private fun reparsed(text: String): YamlDoc = YamlDoc.parse(text)

fun main() {
    val doc = reparsed(SAMPLE)
    val ads: YPath = listOf("rule-providers", "ads")
    val local: YPath = listOf("rule-providers", "local-set")
    val group: YPath = listOf("proxy-groups", 0)

    // ---- 展开视图读参：源码没有本地行、纯继承来的参数也要读得到 ----
    expect(FormValues.effectiveText(doc, ads + "behavior") == "domain", "inherited behavior must be readable")
    expect(FormValues.effectiveText(doc, ads + "type") == "http", "inherited type must be readable")
    expect(FormValues.readRaw(doc, ads + "behavior") == null, "local read must stay local-only")
    expect(FormValues.effectiveText(doc, local + "behavior") == "classical", "local value wins over inheritance")
    expect(
        FormValues.effectiveText(doc, local + "health-check" + "enable") == "true",
        "nested inherited value must be readable",
    )
    val field = FormField(path = "behavior", label = "规则类型", type = FormFieldType.SELECT,
        options = listOf(FormOption("domain", "domain 域名")))
    expect(FormValues.describeEffective(doc, field, ads + "behavior") == "domain 域名（继承）", "inherited summary maps labels")

    // ---- 锚点定义清单 ----
    val defs = AnchorInheritance.anchorDefs(doc)
    expect(defs.map { it.name } == listOf("dl", "base", "hc", "fmt"), "anchor defs order")
    expect(defs.first { it.name == "base" }.isMap, "base is a map anchor")
    expect(!defs.first { it.name == "dl" }.isMap, "dl is scalar anchor")

    // ---- 一级现状 ----
    val l1 = AnchorInheritance.entryMerges(doc, ads)
    expect(l1.merges == listOf("base") && l1.mergeLines == listOf(11) && !l1.multi, "ads level-1 state")
    val g1 = AnchorInheritance.entryMerges(doc, group)
    expect(g1.merges == listOf("base") && g1.mergeLines == listOf(21), "dash-line merge detected")

    // ---- 一级手术：换绑 / 清除 / 新挂 ----
    val rebound = reparsed(AnchorInheritance.setEntryMerge(doc, ads, "hc") ?: error("rebind rejected"))
    expect(AnchorInheritance.entryMerges(rebound, ads).merges == listOf("hc"), "rebind takes effect")
    expect(rebound.lines[10] == "    <<: *hc", "rebind rewrites only the merge line")
    expect(rebound.lines[1] == "base: &base" && rebound.lines[11] == "    url: https://example.com/ads.yaml", "other lines untouched")

    val cleared = reparsed(AnchorInheritance.setEntryMerge(doc, ads, null) ?: error("clear rejected"))
    expect(AnchorInheritance.entryMerges(cleared, ads).merges.isEmpty(), "clear removes merge")
    expect(cleared.lines.size == doc.lines.size - 1 && cleared.lines[10] == "    url: https://example.com/ads.yaml", "clear deletes the line only")

    val attached = reparsed(AnchorInheritance.setEntryMerge(doc, local, "base") ?: error("attach rejected"))
    expect(attached.lines[13] == "    <<: *base", "attach inserts merge after entry head")
    expect(AnchorInheritance.entryMerges(attached, local).merges == listOf("base"), "attached merge readable")
    // 本地 type 仍是覆写：合并只补缺失键
    expect(FormValues.effectiveText(attached, local + "type") == "file", "local key wins over merged default")

    val dashRebound = reparsed(AnchorInheritance.setEntryMerge(doc, group, "hc") ?: error("dash rebind rejected"))
    expect(dashRebound.lines[20] == "  - <<: *hc", "dash-line merge rebind keeps dash")
    val dashCleared = reparsed(AnchorInheritance.setEntryMerge(doc, group, null) ?: error("dash clear rejected"))
    expect(dashCleared.lines[20] == "  -", "dash-line merge clear leaves bare dash")
    expect(dashCleared.lines[21] == "    name: g1", "dash clear keeps children")

    // ---- 二级现状 ----
    val states = AnchorInheritance.fieldStates(doc, local)
    val hcState = states.first { it.key == "health-check" }
    expect(hcState.merges == listOf("hc") && hcState.isMap && !hcState.multi, "field merge state")
    val fmtState = states.first { it.key == "fmt" }
    expect(fmtState.defAnchor == "fmt", "field def anchor state")

    // ---- 二级手术 ----
    val fmRebind = reparsed(AnchorInheritance.setFieldMerge(doc, local, "health-check", "base") ?: error("field rebind rejected"))
    expect(fmRebind.lines[17] == "      <<: *base", "field merge rebind")
    val fmClear = reparsed(AnchorInheritance.setFieldMerge(doc, local, "health-check", null) ?: error("field clear rejected"))
    expect(fmClear.lines.size == doc.lines.size - 1, "field merge clear deletes line")
    expect(AnchorInheritance.setFieldMerge(doc, local, "path", "base") == null, "scalar field must refuse merge")
    val fmNew = reparsed(AnchorInheritance.setFieldMerge(doc, local, "override", "base") ?: error("field attach rejected"))
    expect(fmNew.lines[13] == "    override:" && fmNew.lines[14] == "      <<: *base", "absent field gets key + merge lines")

    val faSet = reparsed(AnchorInheritance.setFieldAlias(doc, local, "proxy", "dl", null) ?: error("alias rejected"))
    expect(faSet.lines[13] == "    proxy: *dl", "field alias insert")
    val faState = AnchorInheritance.fieldStates(faSet, local).first { it.key == "proxy" }
    expect(faState.aliasRef == "dl", "alias state readable")
    val faClear = reparsed(AnchorInheritance.setFieldAlias(faSet, local, "proxy", null, "DIRECT") ?: error("alias clear rejected"))
    expect(faClear.lines[13] == "    proxy: DIRECT", "alias clear restores scalar")
    expect(AnchorInheritance.setFieldAlias(faSet, local, "proxy", null, null) == null, "alias clear without restore refused")
    // 别名替换掉块式字段：子行一起删
    val faOver = reparsed(AnchorInheritance.setFieldAlias(doc, local, "health-check", "hc", null) ?: error("alias over map rejected"))
    expect(faOver.lines[16] == "    health-check: *hc", "alias replaces block field")
    expect(faOver.lines.size == doc.lines.size - 1, "block child lines removed with alias")

    // ---- 存量字段级 & 摘除 ----
    val defRemoved = reparsed(AnchorInheritance.removeFieldDef(doc, local, "fmt") ?: error("def removal rejected"))
    expect(defRemoved.lines[15] == "    fmt: yaml", "field def stripped keeps value")

    // ---- 多来源锁定 ----
    val multiDoc = reparsed(SAMPLE.replace("    <<: *base\n    url:", "    <<: [*base, *hc]\n    url:"))
    val multi = AnchorInheritance.entryMerges(multiDoc, ads)
    expect(multi.multi && multi.merges.toSet() == setOf("base", "hc"), "flow multi merge locked")
    expect(AnchorInheritance.setEntryMerge(multiDoc, ads, "hc") == null, "multi merge refuses surgery")

    testFlowAnchors()
    testFlowMapListField()
    testAnchorRefCounts()

    println("anchor inheritance and second-level anchor tests passed")
}

private const val FLOW_SAMPLE = """providers-def: &providers { interval: 3600, proxy: DIRECT, health-check: { enable: true } }
host-def: &host { additional-suffix: " [x]" }
proxy-providers:
  sub-a:
    <<: *providers
    url: https://example.com/a.yaml
    override: { additional-prefix: "P/", <<: *host }
  sub-b:
    <<: *providers
    url: https://example.com/b.yaml
    override: { additional-prefix: "Q/" }
"""

/** 真实配置常见形态：锚点定义是 flow 映射、二级合并写在 flow 里——候选与读取都不能漏。 */
private fun testFlowAnchors() {
    val doc = reparsed(FLOW_SAMPLE)
    val subA: YPath = listOf("proxy-providers", "sub-a")
    val subB: YPath = listOf("proxy-providers", "sub-b")

    val defs = AnchorInheritance.anchorDefs(doc)
    expect(defs.first { it.name == "providers" }.isMap, "flow map anchor must count as mergeable map")
    expect(defs.first { it.name == "host" }.isMap, "flow map anchor must count as mergeable map")

    val states = AnchorInheritance.fieldStates(doc, subA)
    val ov = states.first { it.key == "override" }
    expect(ov.merges == listOf("host") && ov.isMap, "flow-field merge must be read")

    // flow 内换绑 / 摘除 / 追加，其余 flow 内容逐字不动
    val rebound = reparsed(AnchorInheritance.setFieldMerge(doc, subA, "override", "providers") ?: error("flow rebind rejected"))
    val ovLine = rebound.lines[6]
    expect(ovLine.contains("<<: *providers") && ovLine.contains("additional-prefix: \"P/\"") && !ovLine.contains("*host"), "flow rebind keeps other keys")
    val removed = reparsed(AnchorInheritance.setFieldMerge(doc, subA, "override", null) ?: error("flow remove rejected"))
    expect(removed.lines[6] == "    override: { additional-prefix: \"P/\" }", "flow remove strips merge and one comma")
    val appended = reparsed(AnchorInheritance.setFieldMerge(doc, subB, "override", "host") ?: error("flow append rejected"))
    expect(appended.lines[10] == "    override: { additional-prefix: \"Q/\", <<: *host }", "flow append inserts before closing brace")
    expect(AnchorInheritance.fieldStates(appended, subB).first { it.key == "override" }.merges == listOf("host"), "appended flow merge readable")

    // 一级读取在 flow 定义下依旧成立（候选过滤用 isMap，这里验证展开读参）
    expect(FormValues.effectiveText(doc, subA + "interval") == "3600", "inherited value via flow anchor readable")
}

/**
 * flow 映射里写列表字段（override-expr 快捷表达式的路径）：引擎拒绝把装不下一行的集合塞进 flow，
 * 手术必须先展开成块式（`<<:` 与其它键逐字保留）再写；写完的形态要能被引擎常规路径继续改。
 */
private fun testFlowMapListField() {
    val doc = reparsed(FLOW_SAMPLE)
    val subA: YPath = listOf("proxy-providers", "sub-a")
    val exprs = listOf(".servername = \"填免流混淆\"", ".sni = .servername", "it's fine")

    val out = AnchorInheritance.setFlowMapListField(doc, subA + "override", "override-expr", exprs)
        ?: error("flow list write rejected")
    val re = reparsed(out)
    // `<<: *host` 与既有键原样保留，override 由 flow 变块式，列表按块式序列写入
    expect(re.get(subA + "override")?.flow == false, "override must expand to block form")
    expect(AnchorInheritance.fieldStates(re, subA).first { it.key == "override" }.merges == listOf("host"), "flow merge must survive expansion")
    expect(re.get(subA + "override" + "additional-prefix") != null, "existing flow keys must survive")
    val (items, inherited) = FormValues.exprItems(re, subA)
    expect(items == exprs && !inherited, "written list must roundtrip verbatim, value is local now")
    expect(out.contains("- 'it''s fine'"), "single-quote escaping must double inner quotes")
    expect(re.get(subA + "url") != null, "sibling keys untouched")

    // 写完是块式：第二次编辑走引擎常规 setValue，删除走 removeKey
    val re2 = YamlPatch.setValue(re, subA + "override" + "override-expr", listOf(".only = 1"))
    expect(re2 !== re && FormValues.exprItems(re2, subA).first == listOf(".only = 1"), "block rewrite via engine")
    val re3 = YamlPatch.removeKey(re2, subA + "override" + "override-expr")
    expect(re3.get(subA + "override" + "override-expr") == null, "block removal via engine")
    expect(re3.get(subA + "override" + "additional-prefix") != null, "removal keeps map intact")

    // 空列表 = 删除该字段（其余键保留）
    val emptied = reparsed(AnchorInheritance.setFlowMapListField(doc, subA + "override", "override-expr", emptyList()) ?: error("empty write rejected"))
    expect(emptied.get(subA + "override" + "override-expr") == null, "empty list removes field")
    expect(AnchorInheritance.fieldStates(emptied, subA).first { it.key == "override" }.merges == listOf("host"), "empty write keeps merge")

    // 行尾注释挪到 `键:` 行尾而不是丢掉
    val withComment = reparsed("proxy-providers:\n  - name: c\n    override: { type: file } # keep me\n")
    val co = AnchorInheritance.setFlowMapListField(withComment, listOf("proxy-providers", 0, "override"), "override-expr", listOf(".a = 1"))
        ?: error("comment write rejected")
    expect(co.contains("override: # keep me"), "trailing comment preserved on key line")

    // 跨多行 flow / 块式映射：不手术，交回引擎 / 编辑器
    val multiline = reparsed("proxy-providers:\n  - name: m\n    override: { a: 1,\n      b: 2 }\n")
    expect(AnchorInheritance.setFlowMapListField(multiline, listOf("proxy-providers", 0, "override"), "e", listOf("1")) == null, "multiline flow refused")
    val block = reparsed("proxy-providers:\n  - name: b\n    override:\n      type: file\n")
    expect(AnchorInheritance.setFlowMapListField(block, listOf("proxy-providers", 0, "override"), "e", listOf("1")) == null, "block map refused (engine handles it)")
}

/** 锚点定义一览的引用计数：`<<: *x`、`键: *x` 都计入。 */
private fun testAnchorRefCounts() {
    val doc = reparsed(FLOW_SAMPLE)
    val counts = AnchorInheritance.anchorRefCounts(doc)
    expect(counts["providers"] == 2, "providers referenced by sub-a and sub-b, got ${counts["providers"]}")
    expect(counts["host"] == 1, "host referenced inside sub-a flow override, got ${counts["host"]}")
    expect(counts["nope"] == null, "unknown anchors absent from counts")

    val doc2 = reparsed(SAMPLE)
    val counts2 = AnchorInheritance.anchorRefCounts(doc2)
    expect(counts2["base"] == 2, "block merges counted, got ${counts2["base"]}")
    expect(counts2["hc"] == 1, "nested block merge counted, got ${counts2["hc"]}")
    expect(counts2["dl"] == 0 || counts2["dl"] == null, "defined but unreferenced anchor has no count, got ${counts2["dl"]}")
}
