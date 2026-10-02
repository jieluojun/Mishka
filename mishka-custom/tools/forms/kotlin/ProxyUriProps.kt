package top.yukonga.mishka.custom.forms

/** 出站代理「解析节点」的性质测试：分享链接各协议 + YAML 节点 + ensureProxiesRoot 兜底。 */

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

private fun expectThrows(message: String, block: () -> Unit) {
    val threw = try {
        block(); false
    } catch (e: ProxyUriError) {
        true
    }
    expect(threw, "expected ProxyUriError: $message")
}

private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

fun main() {
    // ------------------------------------------------ SS
    run {
        // 明文 userinfo（SIP002 也允许 base64 整段）
        val r = ProxyUri.parseOne("ss://aes-256-gcm:pass@1.2.3.4:8388#我的节点")
        expect(r.first["type"] == "ss", "ss type")
        expect(r.first["server"] == "1.2.3.4", "ss server")
        expect(r.first["port"] == 8388L, "ss port")
        expect(r.first["cipher"] == "aes-256-gcm", "ss cipher")
        expect(r.first["password"] == "pass", "ss password")
        expect(r.first["name"] == "我的节点", "ss name from fragment")
        expect(r.first["udp"] == true, "ss udp default")
    }
    run {
        // base64 整段 userinfo
        val link = "ss://" + b64("chacha20-ietf-poly1305:secret") + "@example.com:8388"
        val r = ProxyUri.parseOne(link)
        expect(r.first["cipher"] == "chacha20-ietf-poly1305", "ss b64 cipher")
        expect(r.first["password"] == "secret", "ss b64 password")
        expect(r.first["server"] == "example.com", "ss b64 server")
        expect((r.first["name"] as String).startsWith("ss-"), "ss fallback name")
    }
    run {
        // obfs 插件
        val r = ProxyUri.parseOne("ss://aes-256-gcm:pass@1.2.3.4:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com")
        expect(r.first["plugin"] == "obfs", "ss obfs plugin name")
        @Suppress("UNCHECKED_CAST")
        val opts = r.first["plugin-opts"] as Map<String, Any?>
        expect(opts["mode"] == "http", "ss obfs mode")
        expect(opts["host"] == "bing.com", "ss obfs host")
    }
    expectThrows("ss unsupported plugin") { ProxyUri.parseOne("ss://aes-256-gcm:pass@1.2.3.4:8388?plugin=kcptun") }
    expectThrows("ss bad cipher charset") { ProxyUri.parseOne("ss://" + b64("bad cipher!:pass") + "@1.2.3.4:8388") }

    // ------------------------------------------------ VMess
    run {
        val json = """{"v":"2","ps":"vm节点","add":"1.2.3.4","port":"443","id":"9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68","aid":"0","scy":"auto","net":"ws","type":"none","host":"bing.com","path":"/ray","tls":"tls","sni":"example.com"}"""
        val r = ProxyUri.parseOne("vmess://" + b64(json))
        expect(r.first["type"] == "vmess", "vmess type")
        expect(r.first["server"] == "1.2.3.4", "vmess server")
        expect(r.first["port"] == 443L, "vmess port")
        expect(r.first["uuid"] == "9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68", "vmess uuid")
        expect(r.first["alterId"] == 0L, "vmess alterId numeric")
        expect(r.first["network"] == "ws", "vmess ws network")
        expect(r.first["tls"] == true, "vmess tls true")
        expect(r.first["servername"] == "example.com", "vmess servername (not sni)")
        @Suppress("UNCHECKED_CAST")
        val ws = r.first["ws-opts"] as Map<String, Any?>
        expect(ws["path"] == "/ray", "vmess ws path")
        @Suppress("UNCHECKED_CAST")
        val headers = ws["headers"] as Map<String, Any?>
        expect(headers["Host"] == "bing.com", "vmess ws host header")
        expect(r.first["name"] == "vm节点", "vmess name from ps")
    }
    expectThrows("vmess bad uuid") { ProxyUri.parseOne("vmess://" + b64("""{"add":"1.2.3.4","port":"443","id":"nope","net":"tcp"}""")) }
    expectThrows("vmess bad cipher") { ProxyUri.parseOne("vmess://" + b64("""{"add":"1.2.3.4","port":"443","id":"9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68","scy":"bogus"}""")) }

    // ------------------------------------------------ VLESS + Reality
    run {
        val link = "vless://9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68@1.2.3.4:443?encryption=none&security=reality&type=grpc&serviceName=GunService&sni=example.com&fp=chrome&pbk=" + "A".repeat(43) + "&sid=ab12#vless节点"
        val r = ProxyUri.parseOne(link)
        expect(r.first["type"] == "vless", "vless type")
        expect(r.first["uuid"] == "9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68", "vless uuid")
        expect(r.first["tls"] == true, "vless reality implies tls")
        expect(r.first["network"] == "grpc", "vless grpc")
        @Suppress("UNCHECKED_CAST")
        val grpc = r.first["grpc-opts"] as Map<String, Any?>
        expect(grpc["grpc-service-name"] == "GunService", "vless grpc service")
        @Suppress("UNCHECKED_CAST")
        val reality = r.first["reality-opts"] as Map<String, Any?>
        expect((reality["public-key"] as String).length == 43, "vless reality pubkey len")
        expect(reality["short-id"] == "ab12", "vless reality short-id")
        expect(r.first["servername"] == "example.com", "vless servername")
    }
    expectThrows("vless bad reality key") {
        ProxyUri.parseOne("vless://9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68@1.2.3.4:443?security=reality&pbk=short&sid=")
    }

    // ------------------------------------------------ Trojan
    run {
        val r = ProxyUri.parseOne("trojan://mypass@1.2.3.4:443?sni=example.com&type=ws&path=%2Fws&host=bing.com#木马")
        expect(r.first["type"] == "trojan", "trojan type")
        expect(r.first["password"] == "mypass", "trojan password")
        expect(r.first["sni"] == "example.com", "trojan sni")
        expect(r.first["network"] == "ws", "trojan ws")
        expect(r.first["name"] == "木马", "trojan name")
    }

    // ------------------------------------------------ Hysteria2
    run {
        val r = ProxyUri.parseOne("hy2://pass@1.2.3.4:443?obfs=salamander&obfs-password=secret&sni=example.com#hy2")
        expect(r.first["type"] == "hysteria2", "hy2 alias -> hysteria2")
        expect(r.first["port"] == 443L, "hy2 default port")
        expect(r.first["obfs"] == "salamander", "hy2 obfs")
        expect(r.first["obfs-password"] == "secret", "hy2 obfs-password")
    }
    expectThrows("hy2 obfs needs password") { ProxyUri.parseOne("hy2://pass@1.2.3.4:443?obfs=salamander") }

    // ------------------------------------------------ TUIC
    run {
        val r = ProxyUri.parseOne("tuic://9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68:pass@1.2.3.4?congestion_control=bbr&udp_relay_mode=native#tuic")
        expect(r.first["type"] == "tuic", "tuic type")
        expect(r.first["uuid"] == "9d0cb9d0-964f-4ef6-897d-6c6b3ccf9e68", "tuic uuid")
        expect(r.first["password"] == "pass", "tuic password")
        expect(r.first["congestion-controller"] == "bbr", "tuic cc")
        expect(r.first["udp-relay-mode"] == "native", "tuic relay mode")
    }

    // ------------------------------------------------ unsupported scheme
    expectThrows("unsupported scheme") { ProxyUri.parseOne("foo://bar") }
    expectThrows("not a uri") { ProxyUri.parseOne("plain text") }

    // ------------------------------------------------ YAML nodes
    run {
        val yaml = "proxies:\n  - { name: a, type: ss, server: 1.1.1.1, port: 1, cipher: aes-256-gcm, password: x }\n  - name: b\n    type: http\n    server: 2.2.2.2\n    port: 8080\n"
        val nodes = ProxyUri.yamlNodes(yaml)
        expect(nodes.size == 2, "yaml two nodes")
        expect(nodes[0]["name"] == "a" && nodes[0]["type"] == "ss", "yaml node0")
        expect(nodes[1]["server"] == "2.2.2.2", "yaml node1 server")
    }
    run {
        // 裸列表 + 流式
        val nodes = ProxyUri.yamlNodes("- { name: a, type: http, server: 1.1.1.1, port: 1 }\n")
        expect(nodes.size == 1 && nodes[0]["name"] == "a", "bare flow list")
    }
    run {
        // 单节点（无 proxies）
        val nodes = ProxyUri.yamlNodes("name: solo\ntype: trojan\nserver: 1.1.1.1\nport: 443\n")
        expect(nodes.size == 1 && nodes[0]["name"] == "solo", "single node map")
    }
    run {
        // 缺 type
        expectThrows("yaml node missing type") { ProxyUri.yamlNodes("- name: x\n") }
    }
    run {
        // name 缺省补 <type>-out
        val nodes = ProxyUri.yamlNodes("- { type: http, server: 1.1.1.1, port: 1 }\n")
        expect(nodes[0]["name"] == "http-out", "yaml default name")
    }

    // ------------------------------------------------ parseAll：去重 / 重名 / URI+YAML 混合
    run {
        val uri = "ss://aes-256-gcm:pass@1.2.3.4:8388#dup"
        val r = ProxyUri.parseAll("$uri\n$uri\n", existingNames = emptyList())
        expect(r.total == 2, "parseAll total counts both")
        expect(r.proxies.size == 1, "parseAll dedup identical uri")
        expect(r.skipped == 1, "parseAll skipped 1")
    }
    run {
        val r = ProxyUri.parseAll("ss://aes-256-gcm:pass@1.2.3.4:8388#dup\n", existingNames = listOf("dup"))
        expect(r.proxies.size == 1, "parseAll existing-name produced 1")
        expect(r.proxies[0]["name"] == "dup (2)", "parseAll uniquify against existing")
    }
    run {
        // 一行多条 URI（空白分隔）
        val r = ProxyUri.parseAll("ss://aes-256-gcm:p1@1.2.3.4:8388#a ss://aes-256-gcm:p2@5.6.7.8:8388#b")
        expect(r.proxies.size == 2, "parseAll two uris one line")
        expect(r.proxies[0]["server"] == "1.2.3.4" && r.proxies[1]["server"] == "5.6.7.8", "parseAll split servers")
    }
    run {
        // 坏行不影响好行
        val r = ProxyUri.parseAll("ss://aes-256-gcm:pass@1.2.3.4:8388#ok\nfoo://bad\n")
        expect(r.proxies.size == 1, "parseAll one good")
        expect(r.errors.size == 1 && r.errors[0].line == 2, "parseAll error on line 2")
    }
    expectThrows("parseAll empty") { ProxyUri.parseAll("   ") }

    // ------------------------------------------------ ensureProxiesRoot
    run {
        val (text, changed) = ProxyUri.ensureProxiesRoot("- name: a\n  type: http\n  server: 1.1.1.1\n  port: 1\n")
        expect(changed, "ensureProxiesRoot wraps bare list")
        expect(text.startsWith("proxies:\n  - name: a"), "ensureProxiesRoot indent: $text")
    }
    run {
        val (text, changed) = ProxyUri.ensureProxiesRoot("name: solo\ntype: http\nserver: 1.1.1.1\nport: 1\n")
        expect(changed, "ensureProxiesRoot wraps single node")
        val reparsed = YamlDoc.parse(text)
        expect(reparsed.get(listOf("proxies", 0, "name")) != null, "ensureProxiesRoot single node -> proxies[0].name")
    }
    run {
        val src = "proxies:\n  - { name: a, type: http, server: 1.1.1.1, port: 1 }\n"
        val (text, changed) = ProxyUri.ensureProxiesRoot(src)
        expect(!changed && text == src, "ensureProxiesRoot leaves proxies: alone")
    }
    run {
        val src = "ss://aes-256-gcm:pass@1.2.3.4:8388\n"
        val (text, changed) = ProxyUri.ensureProxiesRoot(src)
        expect(!changed && text == src, "ensureProxiesRoot leaves uri list alone")
    }
    run {
        // 带公共缩进的粘贴：去掉公共缩进再统一两格
        val (text, changed) = ProxyUri.ensureProxiesRoot("    - name: a\n      type: http\n      server: 1.1.1.1\n      port: 1\n")
        expect(changed, "ensureProxiesRoot indented input changed")
        expect(text.startsWith("proxies:\n  - name: a\n    type: http"), "ensureProxiesRoot dedents common indent: $text")
    }

    // ------------------------------------------------ MiniJson
    run {
        val o = MiniJson.parse("""{"a":1,"b":[true,false,null],"c":"x\"y","d":1.5,"e":-2}""")
        @Suppress("UNCHECKED_CAST")
        val m = o as Map<String, Any?>
        expect(m["a"] == 1L, "json int as long")
        @Suppress("UNCHECKED_CAST")
        val arr = m["b"] as List<Any?>
        expect(arr == listOf(true, false, null), "json array")
        expect(m["c"] == "x\"y", "json escaped quote")
        expect(m["d"] == 1.5, "json float")
        expect(m["e"] == -2L, "json negative int")
    }

    // ------------------------------------------------ looksLikeYamlNodes
    expect(ProxyUri.looksLikeYamlNodes("proxies:\n  - name: a\n"), "looksLikeYaml proxies")
    expect(ProxyUri.looksLikeYamlNodes("- { name: a, type: http }\n"), "looksLikeYaml flow node")
    expect(!ProxyUri.looksLikeYamlNodes("ss://aes-256-gcm:pass@1.2.3.4:8388\n"), "looksLikeYaml uri false")

    // ------------------------------------------------ 端到端：解析 → 批量插入（FormHost.batch 的 InsertItem 同款调用）→ 回读
    run {
        var doc = YamlDoc.parse("proxies:\n  - name: old\n    type: direct\nproxy-groups: []\n")
        // URI 一条
        val r1 = ProxyUri.parseAll("ss://aes-256-gcm:pass@1.2.3.4:8388#导入A\n", listOf("old"))
        expect(r1.proxies.size == 1, "e2e parsed uri node")
        doc = YamlPatch.insertItem(doc, listOf("proxies"), 1, r1.proxies[0])
        // YAML 一条（重名 old → 自动加序号）
        val r2 = ProxyUri.parseAll("- { name: old, type: http, server: 5.6.7.8, port: 80 }\n", listOf("old"))
        expect(r2.proxies.size == 1 && r2.proxies[0]["name"] == "old (2)", "e2e yaml node uniquified")
        doc = YamlPatch.insertItem(doc, listOf("proxies"), 2, r2.proxies[0])
        val text = doc.dump()
        val re = YamlDoc.parse(text)
        expect(FormValues.readRaw(re, listOf("proxies", 1, "name")) == "导入A", "e2e first imported name")
        expect(FormValues.readRaw(re, listOf("proxies", 1, "cipher")) == "aes-256-gcm", "e2e first imported cipher")
        expect(FormValues.readRaw(re, listOf("proxies", 2, "name")) == "old (2)", "e2e second imported name")
        expect(FormValues.readRaw(re, listOf("proxies", 2, "server")) == "5.6.7.8", "e2e second imported server")
        expect(FormValues.readRaw(re, listOf("proxies", 0, "name")) == "old", "e2e existing node untouched")
        // 插入结果仍是合法 YAML（proxy-groups 未被扰动）
        expect(re.get(listOf("proxy-groups")) != null, "e2e siblings intact")
    }

    println("proxy uri / yaml import tests passed")
}
