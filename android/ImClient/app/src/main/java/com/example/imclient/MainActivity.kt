package com.example.imclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.imclient.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Enumeration

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionManager

    // 申请 Android 13+ 通知权限（前台服务通知需要）
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不强制，失败也允许继续 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        SessionManager.currentUserCache = session.username ?: ""

        // 服务器地址：自动检测本机 IP 前三段，末段和端口留给用户填
        setupServerFields()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 已登录：直接进入聊天
        if (session.isLoggedIn()) {
            enterChat()
            return
        }

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRegister.setOnClickListener { doRegister() }
    }

    private fun readFields(): Triple<String?, String?, String?> {
        // 拼装 host = 本机IP前三段 + 末段 + ":" + 端口
        val prefix = binding.tvIpPrefix.text.toString().trim()
        val last = binding.etIpLast.text.toString().trim().ifEmpty { null }
        val port = binding.etPort.text.toString().trim().ifEmpty { null }
        val host = if (last != null && port != null) "$prefix$last:$port" else null
        val user = binding.etUsername.text.toString().trim().ifEmpty { null }
        val pass = binding.etPassword.text.toString().ifEmpty { null }
        return Triple(host, user, pass)
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
        binding.tvStatus.visibility = android.view.View.VISIBLE
    }

    private fun doLogin() {
        val (host, user, pass) = readFields()
        if (host == null) return showStatus(getString(R.string.need_server))
        if (user == null || pass == null) return showStatus(getString(R.string.need_user_pass))

        binding.btnLogin.isEnabled = false
        binding.btnRegister.isEnabled = false
        showStatus(getString(R.string.connecting))

        Thread {
            val result = Api.login("http://$host", user, pass)
            runOnUiThread {
                binding.btnLogin.isEnabled = true
                binding.btnRegister.isEnabled = true
                if (result.ok && result.token != null) {
                    session.serverHost = host
                    session.username = result.username ?: user
                    session.token = result.token
                    SessionManager.currentUserCache = session.username ?: ""
                    enterChat()
                } else {
                    showStatus(result.reason ?: "登录失败")
                }
            }
        }.start()
    }

    private fun doRegister() {
        val (host, user, pass) = readFields()
        if (host == null) return showStatus(getString(R.string.need_server))
        if (user == null || pass == null) return showStatus(getString(R.string.need_user_pass))

        binding.btnLogin.isEnabled = false
        binding.btnRegister.isEnabled = false
        showStatus(getString(R.string.connecting))

        Thread {
            val result = Api.register("http://$host", user, pass)
            runOnUiThread {
                binding.btnLogin.isEnabled = true
                binding.btnRegister.isEnabled = true
                if (result.ok && result.token != null) {
                    // 注册成功即视为登录
                    session.serverHost = host
                    session.username = result.username ?: user
                    session.token = result.token
                    SessionManager.currentUserCache = session.username ?: ""
                    enterChat()
                } else {
                    showStatus(result.reason ?: "注册失败")
                }
            }
        }.start()
    }

    private fun enterChat() {
        // 启动前台服务（持有 WebSocket 长连接），再进入聊天界面
        val intent = Intent(this, ChatService::class.java)
        startForegroundService(intent)
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    /**
     * 服务器地址插槽：自动检测本机 IP 并填前缀（前三段）。
     * - 真机：填本机前三段，末段默认 1，端口默认 3001（用户可改）
     * - 模拟器：固定 10.0.2.2（访问宿主机的保留地址），末段 2，端口 3001
     */
    private fun setupServerFields() {
        val ip = detectPhoneIpV4()
        if (isEmulator()) {
            binding.tvIpPrefix.text = "10.0.2."
            binding.etIpLast.setText("2")
            binding.etPort.setText("3001")
            binding.tvStatus.text = getString(R.string.ip_hint, "模拟器（宿主机 10.0.2.2）")
            binding.tvStatus.visibility = android.view.View.VISIBLE
        } else if (ip != null) {
            binding.tvIpPrefix.text = ip.substringBeforeLast('.') + "."
            binding.etIpLast.setText("1")
            binding.etPort.setText("3001")
            binding.tvStatus.text = getString(R.string.ip_hint, ip)
            binding.tvStatus.visibility = android.view.View.VISIBLE
        } else {
            binding.tvIpPrefix.text = "192.168.0."
            binding.etIpLast.setText("1")
            binding.etPort.setText("3001")
        }
    }

    /** 遍历网卡拿到第一个非回环 IPv4（无需任何权限） */
    private fun detectPhoneIpV4(): String? {
        return try {
            val interfaces: Enumeration<*> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement() as NetworkInterface
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            return a.hostAddress
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("Emulator") ||
            Build.PRODUCT.contains("sdk") ||
            Build.MODEL.contains("google_sdk") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
}
