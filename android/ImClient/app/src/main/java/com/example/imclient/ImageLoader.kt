package com.example.imclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 轻量图片加载器：OkHttp 下载 + 内存 LRU 缓存 + 后台线程 + 主线程回填。
 * 足够聊天缩略图使用，避免为一个小功能引入 Coil/Glide 依赖。
 */
object ImageLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 120
    }

    /** 异步加载图片到 ImageView（有缓存直接回填） */
    fun load(url: String, target: ImageView) {
        cache[url]?.let {
            target.setImageBitmap(it)
            return
        }
        executor.execute {
            try {
                val bmp = decode(url) ?: return@execute
                cache[url] = bmp
                mainHandler.post { target.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }

    /** 同步下载并解码（限制最大边 800px，避免内存爆炸），失败返回 null */
    fun decode(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                scaleDown(bmp, 800f)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 按最大边长等比缩放；不需要缩时原样返回 */
    private fun scaleDown(bmp: Bitmap?, max: Float): Bitmap? {
        if (bmp == null) return null
        val w = bmp.width
        val h = bmp.height
        if (w <= max && h <= max) return bmp
        val ratio = max / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    /** 同步下载原始字节（文件下载用） */
    fun downloadBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }
}
