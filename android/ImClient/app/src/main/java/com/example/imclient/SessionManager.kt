package com.example.imclient

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地会话与配置持久化（SharedPreferences）。
 *
 * 保存：
 * - 登录 token / 用户名
 * - IM 服务器地址（host:port），默认 10.0.2.2:3001（Android 模拟器访问宿主机 PC 的地址）
 * - 悬浮窗开关
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("im_session", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var serverHost: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var floatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING, value).apply()

    val httpBase: String
        get() = "http://$serverHost"

    val wsUrl: String
        get() = "ws://$serverHost"

    fun isLoggedIn(): Boolean = !token.isNullOrEmpty() && !username.isNullOrEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_HOST = "10.0.2.2:3001"

        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
        private const val KEY_HOST = "server_host"
        private const val KEY_FLOATING = "floating_enabled"

        // 供 Message.isSelf 快速判断当前用户（无需每次取 Context）
        var currentUserCache: String = ""
    }
}
