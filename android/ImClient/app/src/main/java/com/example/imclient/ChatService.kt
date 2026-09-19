package com.example.imclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.RingtoneManager
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
import androidx.core.content.ContextCompat

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
        // 新消息通知固定用一个 id：多条消息合并成一条，而不是把通知栏刷满
        private const val NOTIF_MSG_ID = 2002
        private const val CHANNEL_ID = "im_service_channel"
        // 消息通知走独立渠道，且必须是 IMPORTANCE_HIGH ——
        // 服务渠道是 IMPORTANCE_LOW（常驻通知不该打扰用户），但消息必须弹横幅 + 响铃。
        private const val CHANNEL_MSG_ID = "im_message_channel"

        // 通知里最多保留多少条消息（再多也读不完，还拖慢构建）
        private const val MAX_NOTIF_LINES = 10

        /**
         * 回到聊天页时清掉累积的消息通知，避免"已经看过了通知还在"。
         * 放在 companion 而不是做成实例方法：服务被系统回收后 instance 为 null，
         * 那时通知还留在通知栏里，实例方法就够不着了。
         */
        fun clearMessageNotifications(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_MSG_ID)
            } catch (_: Exception) {
            }
            instance?.pendingMessages?.clear()
        }
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

    /** 消息渠道：必须用 IMPORTANCE_HIGH 才会弹横幅（heads-up）并响铃。
     *  之前"对方发消息没有系统弹窗"，是因为只有服务渠道且它是 IMPORTANCE_LOW——
     *  低重要性按设计就只在通知栏静默显示，不会弹也不会响。 */
    private fun createMessageChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_MSG_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_MSG_ID,
            getString(R.string.msg_channel_name),
            NotificationManager.IMPORTANCE_HIGH      // 高：横幅 + 响铃 + 震动
        ).apply {
            description = getString(R.string.msg_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 250, 250)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(channel)
    }

    /** Android 13+ 必须运行时授予 POST_NOTIFICATIONS，否则通知一条都发不出来 */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** 未读消息的累积（供通知合并展示，最多保留最近 N 条） */
    private val pendingMessages = mutableListOf<Message>()

    /**
     * 新消息通知：点击回到聊天页，带系统通知音与震动。
     *
     * 之前每条消息各发一条通知、用消息 id 当通知 id，消息一多就把通知栏刷满，
     * 而且每条都重新响铃。现在改成：固定 id + MessagingStyle 合并成一条会话，
     * setOnlyAlertOnce(true) 让后续更新不再重复响铃（只第一次提醒）。
     */
    private fun notifyNewMessage(m: Message) {
        if (!hasNotificationPermission()) return
        createMessageChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        pendingMessages.add(m)
        while (pendingMessages.size > MAX_NOTIF_LINES) pendingMessages.removeAt(0)

        val intent = Intent(this, ChatActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val style = NotificationCompat.MessagingStyle(
            androidx.core.app.Person.Builder().setName(session.username ?: getString(R.string.app_name)).build()
        ).setConversationTitle(getString(R.string.app_name))

        pendingMessages.forEach { msg ->
            style.addMessage(
                bodyOf(msg),
                System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName(msg.user).build()
            )
        }

        val last = pendingMessages.last()
        val title = if (pendingMessages.size > 1) {
            getString(R.string.notif_multi_title, pendingMessages.size)
        } else last.user

        val b = NotificationCompat.Builder(this, CHANNEL_MSG_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(bodyOf(last))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // 只有第一条响铃+震动，后续合并更新静默刷新，避免"消息轰炸"
            .setOnlyAlertOnce(pendingMessages.size > 1)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // 8.0 以下没有渠道概念，声音和震动只能挂在单条通知上
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            b.setVibrate(longArrayOf(0, 250, 250, 250))
        }

        nm.notify(NOTIF_MSG_ID, b.build())
    }

    private fun bodyOf(m: Message): String = when (m.type) {
        "image" -> getString(R.string.notif_body_image, m.fileName ?: "")
        "file" -> getString(R.string.notif_body_file, m.fileName ?: "")
        else -> m.text ?: ""
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
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()

    // ---------------- WsClient.Listener（服务本身不展示消息） ----------------

    override fun onStatusChanged(connected: Boolean) {}
    override fun onMessage(message: Message) {
        // 自己的消息和系统提示不打扰
        if (message.isSelf || message.isSystem) return
        // 前台：不再弹系统通知（横幅会盖住正在看的聊天页，还会堆通知栏），
        //       改成应用内提示音，与通知权限解耦；
        // 后台：系统通知是唯一能触达用户的方式。
        if (ChatActivity.isForeground) {
            MessageSound.play(this)
        } else {
            notifyNewMessage(message)
        }
    }
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
