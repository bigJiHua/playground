package com.example.imclient

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 顶部居中的状态提示（图标 + 语义色）。
 *
 * 原来只有 ChatActivity 里有私有实现，BroadcastReceiver / Service 之类的
 * 非 Activity 组件想提示就只能退化成纯文字 Toast，视觉语言不统一。
 * 这里提成顶层对象，任何地方都能 StatusToast.show(ctx, "...", StatusToast.ERROR)。
 *
 * 语义色：绿=成功 红=错误 黄=警告 灰=信息（与桌面端顶部提示同一套）。
 */
object StatusToast {

    const val SUCCESS = "success"
    const val ERROR = "error"
    const val WARN = "warn"
    const val INFO = "info"

    fun show(context: Context, text: String, kind: String = INFO) {
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.toast_status, null)
            view.findViewById<ImageView>(R.id.toast_icon).setImageResource(iconOf(kind))
            view.findViewById<TextView>(R.id.toast_text).text = text
            val tint = ContextCompat.getColor(context, colorOf(kind))
            // mutate() 防止着色影响共用同一 drawable 的其它实例
            val bg = view.background?.mutate()
            bg?.setTint(tint)
            view.background = bg

            Toast(context).apply {
                setGravity(
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    0,
                    (72 * context.resources.displayMetrics.density).toInt()
                )
                duration = Toast.LENGTH_SHORT
                this.view = view
            }.show()
        } catch (e: Exception) {
            // 自定义 Toast 在个别 ROM 上可能失败，退回系统 Toast，保证信息不丢
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun iconOf(kind: String): Int = when (kind) {
        SUCCESS -> R.drawable.ic_status_success
        ERROR -> R.drawable.ic_status_error
        WARN -> R.drawable.ic_status_warn
        else -> R.drawable.ic_status_info
    }

    private fun colorOf(kind: String): Int = when (kind) {
        SUCCESS -> R.color.status_success
        ERROR -> R.color.status_error
        WARN -> R.color.status_warn
        else -> R.color.status_info
    }
}
