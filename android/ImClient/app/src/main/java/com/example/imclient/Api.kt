package com.example.imclient

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 封装 IM 服务端的 HTTP 接口（登录 / 注册 / 文件上传）。
 * 实时消息走 WsClient（WebSocket），这里只做鉴权和上传。
 */
object Api {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class AuthResult(
        val ok: Boolean,
        val token: String? = null,
        val username: String? = null,
        val reason: String? = null
    )

    data class UploadResult(
        val ok: Boolean,
        val url: String = "",
        val name: String = "",
        val size: Long = 0,
        val reason: String? = null
    )

    private fun post(base: String, path: String, username: String, password: String): AuthResult {
        return try {
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$base$path")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
                val ok = obj.optBoolean("ok", false)
                if (ok) {
                    AuthResult(
                        ok = true,
                        token = obj.optString("token", null),
                        username = obj.optString("username", username)
                    )
                } else {
                    AuthResult(ok = false, reason = obj.optString("reason", "请求失败"))
                }
            }
        } catch (e: Exception) {
            AuthResult(ok = false, reason = e.message ?: "网络错误")
        }
    }

    /** 登录：POST /api/login */
    fun login(base: String, username: String, password: String): AuthResult =
        post(base, "/api/login", username, password)

    /** 注册：POST /api/register（成功后服务端直接返回 token，可视为自动登录） */
    fun register(base: String, username: String, password: String): AuthResult =
        post(base, "/api/register", username, password)

    /**
     * 拉取所有有聊天记录的日期列表（用于"聊天记录选择"）。
     * GET /api/history/dates → { dates: ["2026-08-17", ...] }
     */
    fun historyDates(base: String): List<String> {
        return try {
            val request = Request.Builder()
                .url("$base/api/history/dates")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
                val arr = obj.optJSONArray("dates") ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拉取指定日期的完整消息（用于"聊天记录选择"）。
     * GET /api/history/messages?date=YYYY-MM-DD
     */
    fun historyByDate(base: String, date: String): List<Map<String, Any?>> {
        return try {
            val request = Request.Builder()
                .url("$base/api/history/messages?date=$date")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
                parseMessageArray(obj.optJSONArray("messages"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拉取当天消息（用于手动"刷新"当前聊天）。
     * 服务端在 date 为空时返回空数组，因此这里显式带当天日期。
     */
    fun historyToday(base: String): List<Map<String, Any?>> {
        return try {
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val request = Request.Builder()
                .url("$base/api/history/messages?date=$today")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
                parseMessageArray(obj.optJSONArray("messages"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 把服务端 messages 数组解析成与 WsClient 一致的消息 Map 列表 */
    private fun parseMessageArray(arr: JSONArray?): List<Map<String, Any?>> {
        if (arr == null) return emptyList()
        val list = mutableListOf<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                mapOf(
                    "id" to o.optLong("id"),
                    "user" to (o.optString("user").takeIf { it.isNotEmpty() } ?: "系统"),
                    "text" to o.optString("text", ""),
                    "type" to (o.optString("type").takeIf { it.isNotEmpty() } ?: "text"),
                    "createdAt" to (o.optString("created_at").takeIf { it.isNotEmpty() }),
                    "imageUrl" to (o.optString("image_url").takeIf { it.isNotEmpty() }),
                    "fileName" to (o.optString("file_name").takeIf { it.isNotEmpty() }),
                    "fileSize" to if (o.has("file_size")) o.optLong("file_size") else null,
                    "replyTo" to if (o.has("reply_to")) o.optLong("reply_to") else null,
                    "replyToUser" to (o.optString("reply_to_user").takeIf { it.isNotEmpty() }),
                    "replyToText" to (o.optString("reply_to_text").takeIf { it.isNotEmpty() })
                )
            )
        }
        return list
    }

    /**
     * 上传文件：POST /upload（multipart，字段名 file）。
     * 成功返回 { url, name, size }，url 是相对路径 /uploads/xxx，可直接作为 image_url 发消息。
     */
    fun uploadFile(base: String, file: File, fileName: String): UploadResult {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$base/upload")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
                val url = obj.optString("url", "")
                if (url.isNotEmpty()) {
                    UploadResult(
                        ok = true,
                        url = url,
                        name = obj.optString("name", fileName),
                        size = obj.optLong("size", file.length())
                    )
                } else {
                    UploadResult(ok = false, reason = obj.optString("error", "上传失败"))
                }
            }
        } catch (e: Exception) {
            UploadResult(ok = false, reason = e.message ?: "网络错误")
        }
    }
}
