package top.yukonga.mishka.service

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import top.yukonga.mishka.platform.ProxyServiceController

/**
 * Tile 等无 Activity 上下文的入口在 VPN 模式启动代理时，需要弹系统 VPN 授权对话框；
 * 该对话框要求调用方是 Activity，本透明 Activity 充当跳板。UI 路径仍走 MainActivity 自身处理。
 */
class VpnPermissionActivity : Activity() {

    private val subscriptionId: String?
        get() = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID)?.ifEmpty { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = subscriptionId ?: run {
            finish()
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startProxyAndFinish(id)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = subscriptionId
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK && id != null) {
            ProxyServiceController(this).start(id)
        }
        finish()
    }

    private fun startProxyAndFinish(subscriptionId: String) {
        ProxyServiceController(this).start(subscriptionId)
        finish()
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        private const val VPN_REQUEST_CODE = 1002
    }
}
