package com.example.imclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 前台服务：同时负责两件事
 *  1. 持有 IM 的 WebSocket 长连接（即使 App 退到后台/关闭也不断）
 *  2. 通过 WindowManager 显示「全局悬浮输入框」，用户无需打开 App 即可快速发消息
 *
 * 悬浮窗需要 SYSTEM_ALERT_WINDOW 权限（在 ChatActivity 中引导开启）。
 */
class ChatService : Service(), WsClient.Listener {

    companion object {
        var instance: ChatService? = null

        const val ACTION_STOP = "com.example.imclient.action.STOP"
        const val ACTION_SHOW_FLOAT = "com.example.imclient.action.SHOW_FLOAT"
        const val ACTION_HIDE_FLOAT = "com.example.imclient.action.HIDE_FLOAT"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "im_service_channel"
    }

    private lateinit var session: SessionManager
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatInput: EditText? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionManager(this)
        WsClient.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_FLOAT -> showFloating()
            ACTION_HIDE_FLOAT -> hideFloating()
            else -> {
                // 默认：建立长连接（仅首次）
                val user = session.username
                val token = session.token
                if (!user.isNullOrEmpty() && !token.isNullOrEmpty()) {
                    WsClient.start(session.wsUrl, user, token)
                }
                if (session.floatingEnabled) showFloating()
            }
        }
        return START_STICKY
    }

    // ---------------- 悬浮窗 ----------------

    fun isFloatingShown(): Boolean = floatingView != null

    fun showFloating() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.float_permission_missing, Toast.LENGTH_LONG).show()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            // 关键：Service 没有 Activity 主题，MaterialButton 等组件必须用带主题的上下文 inflate，
            // 否则抛 "Binary XML file line #xx: Error inflating class MaterialButton"
            val themed = ContextThemeWrapper(this, R.style.Theme_ImClient)
            floatingView = LayoutInflater.from(themed).inflate(R.layout.floating_window, null)

            floatInput = floatingView!!.findViewById(R.id.et_float_input)
            val sendBtn = floatingView!!.findViewById<View>(R.id.btn_float_send)
            val closeBtn = floatingView!!.findViewById<View>(R.id.btn_float_close)
            val header = floatingView!!.findViewById<View>(R.id.float_header)

            sendBtn.setOnClickListener { sendFromFloat() }
            closeBtn.setOnClickListener {
                hideFloating()
                session.floatingEnabled = false
            }
            // 输入法回车/换行 = 发送
            floatInput?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendFromFloat()
                    true
                } else {
                    false
                }
            }
            setupDrag(header, closeBtn)

            val dm = Resources.getSystem().displayMetrics
            // 固定宽度（280dp）+ 高度自适应，避免无限拉宽
            floatingParams = WindowManager.LayoutParams(
                dp(280),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 默认不抢输入焦点（NOT_FOCUSABLE）：不弹键盘、不影响下方应用（如微信）正常输入；
                // WATCH_OUTSIDE_TOUCH：点窗口外任意处时收到 ACTION_OUTSIDE，强制释放焦点
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 初始位置：屏幕右侧中部（避开底部键盘/微信输入区，防止遮挡导致"抢焦点"错觉）
                x = (dm.widthPixels - dp(300)).coerceAtLeast(0)
                y = (dm.heightPixels * 35 / 100).coerceIn(0, (dm.heightPixels - dp(400)).coerceAtLeast(0))
            }

            windowManager!!.addView(floatingView, floatingParams)
            session.floatingEnabled = true

            // 点输入框 → 临时切换为可聚焦并弹键盘；焦点丢失（点到其他应用）→ 自动释放焦点
            floatInput?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    makeInputFocusable()
                }
                false
            }
            floatInput?.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) makeInputNonFocusable()
            }
            // 点悬浮窗窗口外的任意位置：立即释放输入焦点并收起键盘（弥补触摸穿透时焦点不自动转移的盲区）
            floatingView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    makeInputNonFocusable()
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            // addView 异常（如权限/ROM 限制）时清理现场并提示
            floatingView = null
            Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hideFloating() {
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Exception) {
            }
            floatingView = null
        }
        session.floatingEnabled = false
    }

    private fun sendFromFloat() {
        val text = floatInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!WsClient.isConnected) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        WsClient.sendText(text)
        floatInput?.setText("")
        Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
    }

    /** 点输入框：去掉 NOT_FOCUSABLE，让本窗口获得焦点并弹出键盘 */
    private fun makeInputFocusable() {
        val p = floatingParams ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try {
                windowManager?.updateViewLayout(floatingView, p)
            } catch (_: Exception) {
            }
        }
        floatInput?.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(floatInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 焦点丢失（点到其他应用/空白处）：恢复 NOT_FOCUSABLE，把输入焦点还给下方应用并收起键盘 */
    private fun makeInputNonFocusable() {
        val p = floatingParams ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try {
                windowManager?.updateViewLayout(floatingView, p)
            } catch (_: Exception) {
            }
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(floatInput?.windowToken, 0)
    }

    /**
     * 拖动悬浮窗：整个标题栏可拖（点击到关闭按钮时放行，让它正常响应）。
     * 拖动过程把窗口限制在屏幕范围内，防止拖出可视区。
     */
    private fun setupDrag(handle: View, closeBtn: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 按在关闭按钮上：不接管拖动，交给按钮的点击
                    if (isTouchOnView(closeBtn, event)) return@setOnTouchListener false
                    initialX = floatingParams!!.x
                    initialY = floatingParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val w = floatingParams!!.width
                    val h = floatingParams!!.height
                    val dm = Resources.getSystem().displayMetrics
                    // 限制在屏幕内
                    floatingParams!!.x = (initialX + dx.toInt()).coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                    floatingParams!!.y = (initialY + dy.toInt()).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
                    windowManager!!.updateViewLayout(floatingView, floatingParams)
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun isTouchOnView(v: View, e: MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = e.rawX
        val y = e.rawY
        return x >= loc[0] && x <= loc[0] + v.width &&
            y >= loc[1] && y <= loc[1] + v.height
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, ChatActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()

    // ---------------- WsClient.Listener（服务本身不展示消息） ----------------

    override fun onStatusChanged(connected: Boolean) {}
    override fun onMessage(message: Message) {}
    override fun onHistory(messages: List<Message>) {}
    override fun onOnlineUsers(users: List<String>) {}
    override fun onKicked(reason: String) {
        // 被踢：停止连接（WsClient 内部已关闭），保留服务以便用户重新登录
    }

    override fun onDestroy() {
        WsClient.removeListener(this)
        WsClient.stop()
        hideFloating()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
