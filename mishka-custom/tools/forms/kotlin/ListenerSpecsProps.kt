package top.yukonga.mishka.custom.forms

/** 入站监听器类型表的性质测试：类型 / 模板 / 小节组合的结构一致性（对齐参考实现 IN_* 集合）。 */

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

private fun fieldPaths(section: FormSection): List<String> =
    section.fields.mapNotNull { (it as? FormField)?.path }

fun main() {
    // 每个可选类型都有模板；模板 name/type 自洽
    for (opt in LISTENER_TYPES) {
        val tpl = LISTENER_TEMPLATES[opt.value] ?: error("缺模板: ${opt.value}")
        expect(tpl["type"] == opt.value, "模板 type 与选项一致: ${opt.value}")
        expect(tpl["name"] != null, "模板有名字: ${opt.value}")
    }

    // 参考实现 IN_TYPES 全量 22 型
    val expectedTypes = setOf(
        "http", "socks", "mixed", "redirect", "tproxy", "tun", "ebpf", "shadowsocks",
        "vmess", "vless", "trojan", "anytls", "mieru", "sudoku", "tuic", "shadowquic",
        "hysteria2", "hysteria2-realm", "trusttunnel", "tunnel", "snell", "cns",
    )
    expect(LISTENER_TYPES.map { it.value }.toSet() == expectedTypes, "类型集合与参考实现一致")

    // 小节组合：ebpf / 未知类型 → null（页面用 EBPF_SECTIONS / 通用小节兜底）
    expect(listenerSections("ebpf") == null, "ebpf 走独立字段表")
    expect(listenerSections("not-a-type") == null, "未知类型走通用兜底")
    for (opt in LISTENER_TYPES) {
        if (opt.value == "ebpf") continue
        val sections = listenerSections(opt.value) ?: error("缺小节组合: ${opt.value}")
        expect(sections.first().title == "基础", "第一节是基础: ${opt.value}")
        val basic = fieldPaths(sections.first())
        expect("name" in basic, "基础含 name: ${opt.value}")
        expect("proxy" in basic && "rule" in basic && "routing-mark" in basic, "基础含 proxy/rule/routing-mark: ${opt.value}")
        // 接管型没有端口字段
        if (opt.value in LISTENER_NO_PORT) {
            expect("port" !in basic && "listen" !in basic, "接管型无端口字段: ${opt.value}")
        } else {
            expect("port" in basic && "listen" in basic && "udp" in basic, "监听型有端口字段: ${opt.value}")
        }
        // users 对象列表协议：协议参数第一节是专门控件行，且字段表里不再重复 users
        if (opt.value in LISTENER_USERS_COLUMNS) {
            val extra = sections.firstOrNull { it.title == "协议参数" } ?: error("缺协议参数节: ${opt.value}")
            expect(extra.fields.first() is FormCustomRow, "users 走专门控件行: ${opt.value}")
            expect("users" !in fieldPaths(extra), "users 不重复出现在字段表: ${opt.value}")
        }
        // TLS / REALITY / 伪装 / Mux 集合与参考实现一致
        val titles = sections.map { it.title }
        expect(("TLS 证书" in titles) == (opt.value in LISTENER_TLS_TYPES), "TLS 节按需出现: ${opt.value}")
        expect(("REALITY" in titles) == (opt.value in LISTENER_REALITY_TYPES), "REALITY 节按需出现: ${opt.value}")
        expect(("传输层" in titles) == (opt.value in LISTENER_REALITY_TYPES), "传输层节按需出现: ${opt.value}")
        expect(("Multiplex" in titles) == (opt.value in LISTENER_MUX_TYPES), "Multiplex 节按需出现: ${opt.value}")
        // 协议专属字段都在（协议参数节 = users 行 + LISTENER_EXTRA）
        val extraFields = sections.firstOrNull { it.title == "协议参数" }?.let { fieldPaths(it) }.orEmpty()
        for (f in LISTENER_EXTRA[opt.value].orEmpty()) {
            expect(f.path in extraFields, "协议字段入表: ${opt.value} ${f.path}")
        }
    }

    // tun 小节含协议栈；cns 含 key/flag
    expect(fieldPaths(listenerSections("tun")!!.first { it.title == "协议参数" }).containsAll(listOf("stack", "dns-hijack", "mtu")), "tun 协议参数")
    expect(fieldPaths(listenerSections("cns")!!.first { it.title == "协议参数" }).containsAll(listOf("key", "password", "flag")), "cns 协议参数")
    // hysteria2 的 users 是 MAPTEXT（映射形式）
    val hy2Users = LISTENER_EXTRA["hysteria2"]!!.first { it.path == "users" }
    expect(hy2Users.type == FormFieldType.MAPTEXT, "hysteria2 users 为映射")

    // 模板端口/字段与参考实现对齐的抽查
    expect(LISTENER_TEMPLATES["tuic"]!!["port"] == 10005L, "tuic 模板端口")
    expect((LISTENER_TEMPLATES["tuic"]!!["users"] as Map<*, *>).containsKey("00000000-0000-0000-0000-000000000000"), "tuic v5 users 键为 UUID")
    expect(LISTENER_TEMPLATES["vmess"]!!["users"] is List<*>, "vmess users 为对象列表")

    println("listener types / templates / sections tests passed")
}
