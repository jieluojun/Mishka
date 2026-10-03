package top.yukonga.mishka.custom.forms

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

private fun applyBatch(text: String, ops: List<BatchOp>): YamlDoc? {
    var current = YamlDoc.parse(text)
    for (op in ops) {
        val next = when (op) {
            is BatchOp.Set -> {
                if (!current.canSet(op.path)) return null
                // 与 FormHost.batch 同一套继承感知落地（写穿锚点 / 物化继承块 / 普通写）
                AnchorInheritance.applySetAware(current, op.path, op.value).doc
            }
            is BatchOp.Remove -> {
                if (current.get(op.path) == null) continue
                YamlPatch.removeKey(current, op.path)
            }
            is BatchOp.SetItem -> YamlPatch.setItem(current, op.seqPath, op.index, op.value)
            is BatchOp.InsertItem -> YamlPatch.insertItem(current, op.seqPath, op.index, op.value)
            is BatchOp.Rename -> {
                val parent = current.get(op.path.dropLast(1))
                if (parent != null && current.findEntry(parent, op.newKey) != null) return null
                YamlPatch.renameKey(current, op.path, op.newKey)
            }
        }
        if (next === current) return null
        current = next
    }
    return current
}

private fun roleState(listenerYaml: String): Pair<Boolean, Boolean> {
    val doc = YamlDoc.parse("listeners:\n  - name: test\n    type: ebpf\n$listenerYaml")
    return ebpfRoleState(doc, listOf("listeners", 0))
}

private fun testEbpfRoleStateAndWrites() {
    expect(roleState("    mode: local\n") == (true to false), "mode=local must enable only local")
    expect(roleState("    mode: shared\n") == (false to true), "mode=shared must enable only shared")
    expect(roleState("    mode: hybrid\n") == (true to true), "mode=hybrid must enable both roles")
    expect(roleState("") == (true to false), "missing mode must use the documented local default")
    expect(
        roleState("    mode: hybrid\n    local:\n      enable: null\n    shared:\n      enable: null\n") == (true to true),
        "null role flags should not override the legacy mode",
    )
    expect(
        roleState("    mode: hybrid\n    local:\n      enable: false\n    shared:\n      enable: true\n") == (false to true),
        "explicit role flags should take precedence over mode",
    )
    expect(
        roleState("    local:\n      enabled: true\n    shared:\n      enabled: false\n") == (true to false),
        "legacy enabled spelling should still be read",
    )

    val source = """listeners:
  - name: keep-me
    type: ebpf
    mode: hybrid
    local:
      enable: true
      enabled: true
      data-plane: tc
      include-package: [com.example.app]
    shared:
      enable: false
      enabled: true
      interface: [br0]
      data-plane: packet_rewrite
  - name: other-listener
    type: http
    port: 8080
unrelated:
  keep: true
"""
    val base = listOf<Any>("listeners", 0)
    val before = YamlDoc.parse(source)
    val updated = applyBatch(source, ebpfRoleUpdateOps(base, localOn = false, sharedOn = true))
        ?: error("eBPF role update batch was rejected")
    expect(updated.get(base + "mode") == null, "role update should remove legacy mode")
    expect(updated.get(base + listOf("local", "enabled")) == null, "role update should remove local.enabled")
    expect(updated.get(base + listOf("shared", "enabled")) == null, "role update should remove shared.enabled")
    expect(FormValues.readBool(updated, base + listOf("local", "enable")) == false, "local role flag should be written false")
    expect(FormValues.readBool(updated, base + listOf("shared", "enable")) == true, "shared role flag should be written true")
    expect(FormValues.readRaw(updated, base + listOf("local", "data-plane")) == "tc", "unrelated local settings must survive")
    expect(FormValues.readList(updated, base + listOf("local", "include-package")) == listOf("com.example.app"), "local package list must survive")
    expect(FormValues.readList(updated, base + listOf("shared", "interface")) == listOf("br0"), "shared interface must survive")
    expect(FormValues.readRaw(updated, listOf("listeners", 1, "name")) == "other-listener", "neighbor listener must survive")
    expect(FormValues.readBool(updated, listOf("unrelated", "keep")) == true, "unrelated top-level data must survive")
    expect(before.dump().contains("mode: hybrid"), "test fixture should remain independent from the edited document")
}

private fun testFakeIpIcmpPrerequisites() {
    val localTc = YamlDoc.parse("""listeners:
  - type: ebpf
    local:
      enable: true
      data-plane: tc
    shared:
      enable: false
      interface: []
""")
    expect(ebpfHasFakeIpIcmpHook(localTc, listOf("listeners", 0), localOn = true, sharedOn = false), "active local tc should supply the FakeIP ICMP hook")
    expect(!ebpfHasFakeIpIcmpHook(localTc, listOf("listeners", 0), localOn = false, sharedOn = false), "disabled local must not count as an active TC hook")

    val sharedNoInterface = YamlDoc.parse("""listeners:
  - type: ebpf
    local:
      enable: false
    shared:
      enable: true
      interface: []
""")
    expect(!ebpfHasFakeIpIcmpHook(sharedNoInterface, listOf("listeners", 0), localOn = false, sharedOn = true), "shared without a downstream interface must not count as an active hook")
    val sharedReady = YamlDoc.parse("""listeners:
  - type: ebpf
    shared:
      enable: true
      interface: [br0]
""")
    expect(ebpfHasFakeIpIcmpHook(sharedReady, listOf("listeners", 0), localOn = false, sharedOn = true), "active shared with an interface should supply the FakeIP ICMP hook")
}

private fun testFormValueReaders() {
    val text = """base-list: &servers [1.1.1.1, 8.8.8.8]
hosts:
  alpha.example: 192.0.2.10, 192.0.2.11
  beta.example:
    - 198.51.100.20
    - 198.51.100.21
dns:
  nameserver-policy:
    "rule-set:ads": [rcode://success, 1.1.1.1]
    "*.lan": rcode://success
    nested: {server: 1.1.1.1}
    alias: *servers
  use-hosts: true
headers:
  Host: [one.example, two.example]
  User-Agent: Mishka
"""
    val doc = YamlDoc.parse(text)
    val hosts = FormValues.readMapListRows(doc, listOf("hosts"), splitScalar = true)
    expect(hosts.size == 2, "map-list reader should read both hosts keys")
    expect(hosts[0].key == "alpha.example" && hosts[0].values == listOf("192.0.2.10", "192.0.2.11"), "scalar host list should split into values")
    expect(hosts[0].supported, "simple scalar host entries should be editable")
    expect(hosts[1].isSequence && hosts[1].values == listOf("198.51.100.20", "198.51.100.21"), "sequence form should retain its shape")

    val policy = FormValues.readMapListRows(doc, listOf("dns", "nameserver-policy"))
    expect(policy[0].key == "rule-set:ads" && policy[0].isSequence, "quoted nameserver-policy keys and sequences should be read")
    expect(policy[1].supported, "scalar nameserver-policy values should be editable")
    expect(!policy[2].supported && !policy[3].supported, "nested and alias values should be marked unsupported")

    val headerRows = FormValues.readHeaderRows(doc, listOf("headers"))
    expect(headerRows == listOf("Host" to "one.example", "Host" to "two.example", "User-Agent" to "Mishka"), "header arrays should expand into rows")

    val boolField = FormField(
        path = "use-hosts",
        label = "Use hosts",
        type = FormFieldType.SELECT,
        options = listOf(FormOption("True", "启用"), FormOption("False", "关闭")),
        boolKind = "bool",
    )
    expect(FormValues.describe(doc, boolField, listOf("dns", "use-hosts")) == "启用", "boolean select summary should match YAML true case-insensitively")

    val mapField = FormField(path = "hosts", label = "Hosts", type = FormFieldType.MAPLIST)
    expect(FormValues.describe(doc, mapField, listOf("hosts")).contains("alpha.example→192.0.2.10"), "map-list summary should echo existing keys and values")
}

private fun testMapListRenameAndSwap() {
    val source = """hosts:
  alpha.example: 192.0.2.10 # keep alpha value
  beta.example: 198.51.100.20 # keep beta value
  stable.example: 203.0.113.30
other: keep
"""
    val path = listOf<Any>("hosts")
    val oldRows = FormValues.readMapListRows(YamlDoc.parse(source), path, splitScalar = true)
    val alpha = oldRows.single { it.key == "alpha.example" }
    val beta = oldRows.single { it.key == "beta.example" }
    val stable = oldRows.single { it.key == "stable.example" }

    val swapEdits = listOf(
        FormMapListEdit(alpha, "beta.example", alpha.values, alpha.isSequence),
        FormMapListEdit(beta, "alpha.example", beta.values, beta.isSequence),
        FormMapListEdit(stable, stable.key, stable.values, stable.isSequence),
    )
    val swapOps = buildMapListEditOps(path, oldRows, swapEdits) ?: error("valid key swap was rejected")
    val swapped = applyBatch(source, swapOps) ?: error("key swap batch was rejected")
    val swappedRows = FormValues.readMapListRows(swapped, path, splitScalar = true).associateBy { it.key }
    expect(swappedRows["alpha.example"]?.values == beta.values, "A↔B swap must move B's original value under A")
    expect(swappedRows["beta.example"]?.values == alpha.values, "A↔B swap must move A's original value under B")
    expect(swappedRows["stable.example"]?.values == stable.values, "unmodified map-list entries must survive a swap")
    expect(swapped.dump().contains("# keep alpha value") && swapped.dump().contains("# keep beta value"), "comments must survive a key swap")
    expect(FormValues.readRaw(swapped, listOf("other")) == "keep", "unrelated keys must survive a key swap")

    val renameEdits = listOf(
        FormMapListEdit(alpha, "gamma.example", alpha.values, alpha.isSequence),
        FormMapListEdit(beta, beta.key, beta.values, beta.isSequence),
        FormMapListEdit(stable, stable.key, stable.values, stable.isSequence),
    )
    val renamed = applyBatch(source, buildMapListEditOps(path, oldRows, renameEdits) ?: error("single rename was rejected"))
        ?: error("single rename batch was rejected")
    val renamedKeys = FormValues.readMapListRows(renamed, path, splitScalar = true).map { it.key }.toSet()
    expect("gamma.example" in renamedKeys && "alpha.example" !in renamedKeys, "single rename must not delete its source before staging")

    val replaceEdits = listOf(
        FormMapListEdit(alpha, "beta.example", alpha.values, alpha.isSequence),
        FormMapListEdit(stable, stable.key, stable.values, stable.isSequence),
    )
    val replaced = applyBatch(source, buildMapListEditOps(path, oldRows, replaceEdits) ?: error("rename onto deleted key was rejected"))
        ?: error("rename onto deleted key batch was rejected")
    val replacedRows = FormValues.readMapListRows(replaced, path, splitScalar = true).associateBy { it.key }
    expect(replacedRows["beta.example"]?.values == alpha.values && "alpha.example" !in replacedRows, "renaming onto a deleted key must remove the old target first")

    expect(buildMapListEditOps(path, oldRows, swapEdits + swapEdits.first()) == null, "duplicate target keys should be rejected")
    val unsupported = FormMapListRow("complex", emptyList(), isSequence = false, supported = false)
    expect(buildMapListEditOps(path, listOf(unsupported), listOf(FormMapListEdit(unsupported, "complex", emptyList(), false))) == null, "unsupported nested values must not be rewritten")
}

private fun testExprItemsInheritance() {
    val text = """host-expr: &host { override-expr: [ '.sni = .servername', '.a = 1' ] }
one-expr: &one '.only = 1'
proxy-providers:
  local-block:
    override:
      override-expr:
        - '.b = 2'
  local-flow:
    override: { additional-prefix: "P/", override-expr: [ '.c = 3' ] }
  local-scalar:
    override:
      override-expr: '.d = 4'
  via-merge:
    override: { additional-prefix: "Q/", <<: *host }
  via-alias:
    override: *host
  field-alias:
    override:
      override-expr: *one
  none:
    type: http
"""
    val doc = YamlDoc.parse(text)
    fun items(name: String) = FormValues.exprItems(doc, listOf("proxy-providers", name))
    expect(items("local-block") == (listOf(".b = 2") to false), "local block list read as local")
    expect(items("local-flow") == (listOf(".c = 3") to false), "local flow list read as local")
    expect(items("local-scalar") == (listOf(".d = 4") to false), "local scalar read as one-item list")
    expect(items("via-merge") == (listOf(".sni = .servername", ".a = 1") to true), "flow merge <<: *host inherited expressions resolved")
    expect(items("via-alias") == (listOf(".sni = .servername", ".a = 1") to true), "override: *alias inherited expressions resolved")
    expect(items("field-alias") == (listOf(".only = 1") to true), "override-expr: *alias resolved via expanded view, not literal *one")
    expect(items("none") == (emptyList<String>() to true), "absent override-expr is empty")
}

/**
 * 序列项 / 映射条目头上带 `&锚点` 的解析与写入（用户报告的「改锚点参数 → 全量覆盖且锚点没变」回归）：
 * `- &tpl { … }` 必须按 flow 映射原地改，`- &tpl`（块式）必须解析成映射并可编辑，锚点前缀逐字保留。
 */
private fun testSeqItemAnchors() {
    // C：锚点 + 单行 flow 映射的序列项
    run {
        val src = """
proxy-groups:
  - &tpl { name: 模板, type: select, proxies: [A, B] }
  - { <<: *tpl, name: 分组1 }
"""
        val doc = YamlDoc.parse(src)
        expect(doc.get(listOf("proxy-groups", 0, "type"))?.let { FormValues.scalarText(doc, it) } == "select",
            "anchored flow seq item parsed as flow map")
        expect(doc.canSet(listOf("proxy-groups", 0, "type")), "anchored flow seq item editable")
        val out = YamlPatch.setValue(doc, listOf("proxy-groups", 0, "type"), "url-test").lines.joinToString("\n")
        expect(out.contains("- &tpl { name: 模板, type: url-test, proxies: [A, B] }"),
            "anchored flow seq item edited in place, anchor & flow kept: $out")
        expect(!out.contains("    type: url-test"), "no block line appended below flow seq item")
        expect(out.contains("- { <<: *tpl, name: 分组1 }"), "sibling item untouched")
        // 新增键：追加进 flow 而不是追加块行
        val added = YamlPatch.setValue(doc, listOf("proxy-groups", 0, "interval"), 300).lines.joinToString("\n")
        expect(added.contains("interval: 300 }") || added.contains("interval: 300}"),
            "new key appended inside flow braces: $added")
        // 删键：走 flowRemove，行仍然合法
        val removed = YamlPatch.removeKey(doc, listOf("proxy-groups", 0, "type")).lines.joinToString("\n")
        expect(removed.contains("- &tpl { name: 模板, proxies: [A, B] }"), "flow key removed in place: $removed")
        // 继承读取不受影响
        val after = YamlDoc.parse(out)
        expect(FormValues.effectiveText(after, listOf("proxy-groups", 1, "type")) == "url-test",
            "<<: *tpl inheritance reads edited anchor value")
    }
    // D：锚点独占 dash 行、内容在缩进块里的序列项
    run {
        val src = """
proxy-groups:
  - &tpl
    name: 模板
    type: select
  - <<: *tpl
    name: 分组1
"""
        val doc = YamlDoc.parse(src)
        expect(doc.get(listOf("proxy-groups", 0))?.kind == YamlNode.Kind.MAP,
            "anchored block seq item parsed as map")
        expect(FormValues.readRaw(doc, listOf("proxy-groups", 0, "type")) == "select",
            "anchored block seq item fields readable")
        expect(doc.canSet(listOf("proxy-groups", 0, "type")), "anchored block seq item editable")
        val out = YamlPatch.setValue(doc, listOf("proxy-groups", 0, "type"), "url-test").lines.joinToString("\n")
        expect(out.contains("- &tpl\n    name: 模板\n    type: url-test"), "block edit keeps dash-line anchor: $out")
        expect(out.contains("<<: *tpl"), "merge sibling untouched")
        expect(FormValues.effectiveText(YamlDoc.parse(out), listOf("proxy-groups", 1, "type")) == "url-test",
            "<<: *tpl reads edited block anchor value")
    }
    // A：锚点在映射条目头上的 flow 值（原地改，行前缀保留）
    run {
        val src = """
proxy-providers:
  模板: &providers { interval: 3600, proxy: 订阅更新 }
  订阅A: { <<: *providers, url: "https://a" }
"""
        val doc = YamlDoc.parse(src)
        val out = YamlPatch.setValue(doc, listOf("proxy-providers", "模板", "interval"), 1800).lines.joinToString("\n")
        expect(out.contains("模板: &providers { interval: 1800, proxy: 订阅更新 }"),
            "map-entry flow anchor edited in place: $out")
        expect(out.contains("订阅A: { <<: *providers, url: \"https://a\" }"), "sibling entry untouched")
    }
    // 带标签 / 别名的序列项不被误判
    run {
        val doc = YamlDoc.parse("list:\n  - !!str x\n  - *ref\n  - &a scalar\n")
        expect(doc.get(listOf("list"))?.items?.size == 3, "tag/alias/scalar seq items still parse")
    }
    // dash 行锚点 + 内联映射首键：`- &tpl name: x`（后续键与首键同列）
    run {
        val doc = YamlDoc.parse("g:\n  - &tpl name: 模板\n         type: select\n  - <<: *tpl\n    name: B\n")
        expect(doc.get(listOf("g", 0))?.kind == YamlNode.Kind.MAP, "dash-anchor inline map parsed")
        expect(FormValues.readRaw(doc, listOf("g", 0, "name")) == "模板", "dash-anchor inline first key readable")
        expect(FormValues.readRaw(doc, listOf("g", 0, "type")) == "select", "dash-anchor continuation key readable")
        val out = YamlPatch.setValue(doc, listOf("g", 0, "type"), "url-test").lines.joinToString("\n")
        expect(out.contains("- &tpl name: 模板"), "dash line with anchor kept: $out")
        expect(out.contains("type: url-test"), "inline anchored entry edited")
    }
}

fun main() {
    testEbpfRoleStateAndWrites()
    testFakeIpIcmpPrerequisites()
    testFormValueReaders()
    testExprItemsInheritance()
    testSeqItemAnchors()
    testMapListRenameAndSwap()
    println("eBPF role, FormValues reader, expr inheritance, seq-item anchors, and MAPLIST rename/swap tests passed")
}
