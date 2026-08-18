package com.example.imclient

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * IM 长连接单例：负责 WebSocket 的建立、鉴权、收发消息、断线重连。
 *
 * 与服务端协议对应（见 server.js）：
 *  - 连接成功后发送 {type:"register", user, token}
 *  - 收到 {type:"history"} 当日历史
 *  - 收到 {type:"online_users"} 在线列表
 *  - 收到 {type:"kicked"} 被踢下线
 *  - 普通消息对象含 id / _clientId，用于把乐观消息更新为"已发送"
 *
 * 所有回调统一切回主线程派发，方便 Activity / Service 直接更新 UI。
 */
object WsClient {

    interface Listener {
        fun onStatusChanged(connected: Boolean) {}
        fun onMessage(message: Message) {}
        fun onHistory(messages: List<Message>) {}
        fun onOnlineUsers(users: List<String>) {}
        fun onKicked(reason: String) {}
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    private val listeners = Collections.newSetFromMap(WeakHashMap<Listener, Boolean>())
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    private var baseWsUrl: String = ""
    private var currentUser: String = ""
    private var currentToken: String = ""

    @Volatile
    private var shouldReconnect = false

    // 缓存最近一次数据，供后加入的监听器（如后打开的 Activity）回放
    // 关键：维护"完整消息序列"缓存，否则 Activity 在 WS 已连好的情况下重新注册时，
    // 只会拿到旧的 history 快照，期间到达的实时消息永久丢失（表现为"聊天记录丢失，需手动刷新"）。
    @Volatile
    private var cachedMessages: List<Message> = emptyList()
    private var cachedOnline: List<String> = emptyList()
    private var cachedConnected: Boolean = false

    // clientId -> 乐观消息（发送中），收到回显后移除
    private val pending = mutableMapOf<String, Message>()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(l: Listener) {
        listeners.add(l)
        // 回放已缓存的数据，避免后加入的界面错过历史/实时消息/在线列表
        mainHandler.post {
            if (cachedMessages.isNotEmpty()) l.onHistory(ArrayList(cachedMessages))
            if (cachedOnline.isNotEmpty()) l.onOnlineUsers(cachedOnline)
            l.onStatusChanged(cachedConnected)
        }
    }

    fun removeListener(l: Listener) = listeners.remove(l)

    /** 启动连接（已启动则忽略） */
    fun start(url: String, user: String, token: String) {
        if (webSocket != null) return
        this.baseWsUrl = url
        this.currentUser = user
        this.currentToken = token
        SessionManager.currentUserCache = user
        shouldReconnect = true
        connect()
    }

    /** 主动停止（登出时调用） */
    fun stop() {
        shouldReconnect = false
        try {
            webSocket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        webSocket = null
        client = null
        isConnected = false
        // 清空缓存，避免下次登录串号/残留旧消息
        cachedMessages = emptyList()
        cachedOnline = emptyList()
        cachedConnected = false
    }

    private fun connect() {
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS) // 协议层心跳，自动回 pong
            .build()

        val request = Request.Builder().url(baseWsUrl).build()
        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                cachedConnected = true
                dispatch { l -> l.onStatusChanged(true) }
                // 应用层鉴权
                val reg = JSONObject().apply {
                    put("type", "register")
                    put("user", currentUser)
                    put("token", currentToken)
                }.toString()
                ws.send(reg)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                cachedConnected = false
                dispatch { l -> l.onStatusChanged(false) }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                cachedConnected = false
                dispatch { l -> l.onStatusChanged(false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || isConnected) return
        mainHandler.postDelayed({
            if (shouldReconnect && !isConnected) connect()
        }, 3000)
    }

    private fun handle(raw: String) {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("type")) {
                "registered" -> {
                    // ok=false 时服务端会主动关闭连接
                }
                "history" -> {
                    val list = parseArray(obj.optJSONArray("messages"))
                    cachedMessages = list
                    dispatch { l -> l.onHistory(ArrayList(list)) }
                }
                "online_users" -> {
                    val arr = obj.optJSONArray("users") ?: JSONArray()
                    val users = (0 until arr.length()).map { arr.optString(it) }
                    cachedOnline = users
                    dispatch { l -> l.onOnlineUsers(users) }
                }
                "kicked" -> {
                    shouldReconnect = false
                    try {
                        webSocket?.close(1000, "kicked")
                    } catch (_: Exception) {
                    }
                    // 关键：置空 socket，否则重新登录时 start() 会因 webSocket != null 直接 return，永远连不上
                    webSocket = null
                    client = null
                    isConnected = false
                    cachedConnected = false
                    val reason = obj.optString("reason", "账号已在其他设备登录")
                    dispatch { l -> l.onKicked(reason) }
                }
                else -> {
                    // 普通消息（含 id）
                    parseMessage(obj)?.let { msg ->
                        // 命中乐观消息：更新为已发送
                        var finalMsg = msg
                        if (!msg.clientId.isNullOrEmpty() && pending.containsKey(msg.clientId)) {
                            pending.remove(msg.clientId)
                            finalMsg = msg.copy(status = Message.STATUS_SENT)
                        }
                        dispatch { l -> l.onMessage(finalMsg) }
                        appendToCache(finalMsg)
                    }
                }
            }
        } catch (_: Exception) {
            // 解析失败忽略
        }
    }

    /** 发送文字消息。返回用于匹配的 clientId。 */
    fun sendText(text: String, clientId: String = UUID.randomUUID().toString()): String =
        send("text", text = text, clientId = clientId)

    /** 发送图片消息（先上传拿到 image_url 再调用） */
    fun sendImage(imageUrl: String, clientId: String = UUID.randomUUID().toString()): String =
        send("image", imageUrl = imageUrl, clientId = clientId)

    /** 发送文件消息（先上传拿到 image_url 再调用） */
    fun sendFile(
        imageUrl: String,
        fileName: String,
        fileSize: Long,
        clientId: String = UUID.randomUUID().toString()
    ): String = send("file", imageUrl = imageUrl, fileName = fileName, fileSize = fileSize, clientId = clientId)

    /** 通用发送：构造乐观消息立即上屏，同时走 WebSocket 发给服务端 */
    private fun send(
        type: String,
        text: String? = null,
        imageUrl: String? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        clientId: String = UUID.randomUUID().toString()
    ): String {
        val optimistic = Message(
            id = -System.nanoTime(),
            user = currentUser,
            text = text,
            type = type,
            imageUrl = imageUrl,
            fileName = fileName,
            fileSize = fileSize,
            replyTo = null,
            createdAt = nowLocal(),
            replyToUser = null,
            replyToText = null,
            clientId = clientId,
            status = Message.STATUS_SENDING
        )
        pending[clientId] = optimistic
        val json = JSONObject().apply {
            put("type", type)
            text?.let { put("text", it) }
            imageUrl?.let { put("image_url", it) }
            fileName?.let { put("file_name", it) }
            fileSize?.let { put("file_size", it) }
            put("_clientId", clientId)
        }.toString()
        webSocket?.send(json)
        // 立即把乐观消息推给 UI
        dispatch { l -> l.onMessage(optimistic) }
        appendToCache(optimistic)
        return clientId
    }

    /** 把一条消息追加进完整序列缓存（去重：同 id 或同 clientId 已存在则替换），供后加入的监听器回放 */
    private fun appendToCache(msg: Message) {
        synchronized(cachedMessages) {
            val existIdx = cachedMessages.indexOfFirst { it.id == msg.id ||
                (!msg.clientId.isNullOrEmpty() && it.clientId == msg.clientId) }
            cachedMessages = if (existIdx >= 0) {
                cachedMessages.toMutableList().also { it[existIdx] = msg }
            } else {
                cachedMessages + msg
            }
        }
    }

    private fun parseArray(arr: JSONArray?): List<Message> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Message>()
        for (i in 0 until arr.length()) {
            parseMessage(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseMessage(obj: JSONObject?): Message? {
        if (obj == null || !obj.has("id")) return null
        return Message(
            id = obj.optLong("id"),
            user = obj.optString("user", ""),
            text = obj.optStringOrNull("text"),
            type = obj.optString("type", "text"),
            imageUrl = obj.optStringOrNull("image_url"),
            fileName = obj.optStringOrNull("file_name"),
            fileSize = if (obj.has("file_size")) obj.optLong("file_size") else null,
            replyTo = if (obj.has("reply_to")) obj.optLong("reply_to") else null,
            createdAt = obj.optStringOrNull("created_at"),
            replyToUser = obj.optStringOrNull("reply_to_user"),
            replyToText = obj.optStringOrNull("reply_to_text"),
            clientId = obj.optStringOrNull("_clientId"),
            status = Message.STATUS_SENT
        )
    }

    private fun nowLocal(): String {
        val n = java.util.Date()
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return f.format(n)
    }

    private fun dispatch(block: (Listener) -> Unit) {
        mainHandler.post {
            listeners.toList().forEach { block(it) }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key)) return null
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }
}
