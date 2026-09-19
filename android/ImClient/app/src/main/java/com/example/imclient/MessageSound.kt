package com.example.imclient

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

/**
 * 应用内新消息提示音（App 在前台时用）。
 *
 * 为什么单独做：之前只有「系统通知」一条路径，结果有两个死角——
 *  1. App 在前台时系统通知不会响铃（也不会弹），界面上就完全静悄悄；
 *  2. Android 13+ 用户没授予 POST_NOTIFICATIONS 时，一条通知都发不出，声音自然也没有。
 * 所以前台改用应用内播放，与通知权限解耦。
 *
 * 实现上对标桌面端 SoundEngine 的做法：**单例 + 节流**。
 * 反复 new Ringtone 会叠音成噪音；消息轰炸时（比如一次性回放十几条历史）
 * 不节流会把喇叭糊住，所以 400ms 内只响一次。
 */
object MessageSound {

    private const val MIN_INTERVAL_MS = 400L

    @Volatile
    private var ringtone: Ringtone? = null

    @Volatile
    private var lastPlayAt = 0L

    fun play(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPlayAt < MIN_INTERVAL_MS) return
        lastPlayAt = now

        try {
            val r = ringtone ?: RingtoneManager.getRingtone(
                context.applicationContext,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.also { ringtone = it }
            if (r == null) return
            // 重播前先停：Ringtone 不支持并发，连续 play 会被上一次占用而没声音
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (r.isPlaying) r.stop()
            }
            r.play()
        } catch (e: Exception) {
            // 取不到系统通知音（个别 ROM 定制后为空）时静默降级，不打断消息接收
        }
    }
}
