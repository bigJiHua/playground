package com.example.imclient

/**
 * 与 IM 服务端 messages 表 / WebSocket 推送对应的消息模型。
 *
 * 关键字段说明（来自 server.js）：
 * - id: 服务端自增主键；本地乐观消息使用负数临时 id
 * - type: text / image / file / system_join
 * - _clientId: 发送时由客户端生成，服务端原样回传，用于把"发送中"更新为"已发送"
 */
data class Message(
    val id: Long,
    val user: String,
    val text: String?,
    val type: String,
    val imageUrl: String?,
    val fileName: String?,
    val fileSize: Long?,
    val replyTo: Long?,
    val createdAt: String?,
    val replyToUser: String?,
    val replyToText: String?,
    val clientId: String?,
    val fileLocalPath: String? = null,   // 本地下载保存路径（用于"下载完成"系统提示）
    var status: Int = STATUS_SENT
) {
    val isSelf: Boolean
        get() = user == SessionManager.currentUserCache

    val isSystem: Boolean
        get() = type == "system_join" || type == "system"

    companion object {
        const val STATUS_SENDING = 0   // 发送中（乐观消息）
        const val STATUS_SENT = 1      // 已发送（服务端已确认）
        const val STATUS_FAILED = 2    // 发送失败

        /**
         * 把 Api.historyByDate / historyToday 返回的 Map（字段名已与服务端一致）
         * 转成 Message，供 adapter.setAll 展示历史聊天记录。
         */
        /**
         * 构造一条"文件已下载到本机"的系统提示消息，
         * 在聊天记录中展示安卓系统里的保存路径。
         */
        fun downloadSaved(fileName: String, localPath: String): Message {
            return Message(
                id = -(System.currentTimeMillis()),
                user = "系统",
                text = "已下载文件「$fileName」\n保存到：$localPath",
                type = "system",
                imageUrl = null,
                fileName = fileName,
                fileSize = null,
                replyTo = null,
                createdAt = null,
                replyToUser = null,
                replyToText = null,
                clientId = null,
                fileLocalPath = localPath,
                status = STATUS_SENT
            )
        }

        fun fromMap(m: Map<String, Any?>): Message {
            val id = (m["id"] as? Number)?.toLong() ?: 0L
            val user = (m["user"] as? String) ?: ""
            val text = (m["text"] as? String)
            val type = (m["type"] as? String) ?: "text"
            val createdAt = (m["createdAt"] as? String)
            val imageUrl = (m["imageUrl"] as? String)
            val fileName = (m["fileName"] as? String)
            val fileSize = (m["fileSize"] as? Number)?.toLong()
            val replyTo = (m["replyTo"] as? Number)?.toLong()
            val replyToUser = (m["replyToUser"] as? String)
            val replyToText = (m["replyToText"] as? String)
            return Message(
                id = id,
                user = user,
                text = text,
                type = type,
                imageUrl = imageUrl,
                fileName = fileName,
                fileSize = fileSize,
                replyTo = replyTo,
                createdAt = createdAt,
                replyToUser = replyToUser,
                replyToText = replyToText,
                clientId = null,
                status = STATUS_SENT
            )
        }
    }
}
