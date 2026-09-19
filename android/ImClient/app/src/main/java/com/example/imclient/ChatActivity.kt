package com.example.imclient

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.imclient.databinding.ActivityChatBinding
import java.io.File
import java.io.FileInputStream

class ChatActivity : AppCompatActivity(), WsClient.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: MessageAdapter

    // 待安装的 APK：用户去设置开权限后返回时自动继续，免得再点一次
    private var pendingInstallApk: File? = null

    // 安装界面"是否真的被拉起"的检测：
    // startActivity 返回成功只代表系统接了单，部分 ROM 会静默丢弃这次跳转，
    // 用户看到的就是"点了毫无反应"，而代码这边以为一切正常。
    // 记下发起时刻，若超时后仍未离开本页（onPause 未触发），就判定为没拉起来。
    private var installLaunchAt = 0L
    private var installLaunchApk: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installLaunchWatch = Runnable { checkInstallLaunched() }

    // 已成功下载的文件名 -> 绝对路径。APK 由 DownloadManager 落到公开目录，
    // 普通文件在私有目录，靠目录规则反推容易找错（会导致每次点都重新下载），
    // 所以下载成功时直接记下真实路径。
    private val downloadedPaths = mutableMapOf<String, String>()

    // APK 绝对路径 -> DownloadManager 的内容 URI。会话方式是异步的，
    // 用户可能过一会儿才再点一次安装，那时外部拿不到原始 URI，只能靠这里补查。
    private val downloadedUris = mutableMapOf<String, Uri>()

    companion object {
        /**
         * 聊天页是否在前台。ChatService 据此决定提醒方式：
         * 前台播应用内提示音（系统通知不会响，还会盖住正在看的内容），后台才发系统通知。
         * 用 @Volatile：读写跨主线程（WsClient 回调线程）与 UI 线程。
         */
        @Volatile
        var isForeground = false

        /**
         * 判定"安装界面没被拉起"的等待时长。
         * 取 2 秒：够覆盖安装器冷启动（MIUI 上偶有 0.5~1s 的解析停顿），
         * 又不至于让用户盯着空白等太久。
         */
        const val INSTALL_LAUNCH_TIMEOUT_MS = 2000L
    }

    // 申请「显示在其他应用上层」权限后回调
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            ChatService.instance?.showFloating()
        } else {
            Toast.makeText(this, R.string.float_permission_denied, Toast.LENGTH_LONG).show()
        }
        updateFloatButton()
    }

    // Android 13+ 通知权限：没授权的话，收到新消息一条系统通知都弹不出来
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notif_perm_guide, Toast.LENGTH_LONG).show()
        }
    }

    // 文件选择（图片 / 任意文件共用，mime 在 launch 时指定）
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAndSend(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            // 未登录（如进程被回收）回登录页
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        SessionManager.currentUserCache = session.username ?: ""

        binding.tvTitle.text = getString(R.string.title_chat)
        adapter = MessageAdapter().apply {
            selfUser = session.username ?: ""
            baseUrl = session.httpBase
            onAction = object : MessageAdapter.OnMessageAction {
                override fun onImageClick(url: String) = showImagePreview(url)
                override fun onFileClick(message: Message) = downloadAndOpenFile(message)
            }
        }

        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        // 确保后台服务（长连接）在运行
        if (ChatService.instance == null) {
            startForegroundService(Intent(this, ChatService::class.java))
        }

        // Android 13+ 必须运行时申请通知权限，否则新消息通知完全发不出来
        requestNotificationPermissionIfNeeded()

        binding.btnSend.setOnClickListener { sendText() }
        binding.btnFloat.setOnClickListener { toggleFloating() }
        binding.btnAttach.setOnClickListener { showAttachMenu(it) }
        binding.btnRefresh.setOnClickListener { refreshCurrent(notify = true) }
        binding.tvHistoryHint.setOnClickListener { exitHistoryMode() }
        binding.btnMenu.setOnClickListener { openDrawer() }
        binding.btnCloseDrawer.setOnClickListener { closeDrawer() }
        binding.btnLogout.setOnClickListener { logout() }
    }

    /** Android 13+ 通知权限：没有它，新消息的系统通知一条都弹不出来 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 按需申请「所有文件管理」权限（仅用户点击下载时才调用）：
     * - 已授权 -> 返回 true，可直接写入公开 Download/ImClient；
     * - 未授权 -> 跳到系统设置页引导用户开启，返回 false（不强制，不阻塞 app 进入）。
     * 小米等机型上该特殊权限页常表现为「应用信息」且不显示「所有文件访问权限」开关，
     * 因此下载默认走应用私有目录，无需此权限也能保存文件。
     */
    private fun ensureStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                // 个别机型无该设置页，直接跳应用设置
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            Toast.makeText(this, R.string.storage_grant_hint, Toast.LENGTH_LONG).show()
            return false
        } else {
            // Android 10 及以下：WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) return true
            try {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            Toast.makeText(this, R.string.storage_grant_hint, Toast.LENGTH_LONG).show()
            return false
        }
    }

    /** 附件菜单：发送图片 / 发送文件 */
    private fun showAttachMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.attach_image))
        menu.menu.add(0, 2, 0, getString(R.string.attach_file))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> filePicker.launch("image/*")
                2 -> filePicker.launch("*/*")
            }
            true
        }
        menu.show()
    }

    /** 顶栏三横按钮：打开左侧历史侧边栏（对齐 Node 端抽屉） */
    private fun openDrawer() {
        // 进入侧边栏即加载日期列表，填充抽屉
        loadDrawerDates()
        binding.drawer.openDrawer(binding.drawerSidebar)
    }

    private fun closeDrawer() {
        binding.drawer.closeDrawer(binding.drawerSidebar)
    }

    /** 选到文件：复制到缓存 → 上传 /upload → 发 image/file 消息 */
    private fun uploadAndSend(uri: Uri) {
        if (!WsClient.isConnected) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val name = queryDisplayName(uri) ?: "upload"
        val isImage = name.contains(".png", true) || name.contains(".jpg", true) ||
            name.contains(".jpeg", true) || name.contains(".gif", true) ||
            name.contains(".webp", true) || name.contains(".bmp", true)

        Toast.makeText(this, getString(R.string.uploading), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                if (bytes == null) {
                    runOnUiThread { toastUploadFail("读取文件失败") }
                    return@Thread
                }
                val tmp = File(cacheDir, "upload_tmp_" + System.currentTimeMillis())
                tmp.writeBytes(bytes)
                val result = Api.uploadFile(session.httpBase, tmp, name)
                tmp.delete()
                runOnUiThread {
                    if (result.ok) {
                        if (isImage) {
                            WsClient.sendImage(result.url)
                        } else {
                            WsClient.sendFile(result.url, result.name, result.size)
                        }
                        scrollToBottom()
                    } else {
                        toastUploadFail(result.reason ?: "上传失败")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toastUploadFail(e.message ?: "上传失败") }
            }
        }.start()
    }

    private fun toastUploadFail(msg: String) {
        Toast.makeText(this, getString(R.string.upload_fail, msg), Toast.LENGTH_SHORT).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        WsClient.addListener(this)
        updateFloatButton()
        // 已经进来看了，通知栏里那些消息提醒就没意义了
        ChatService.clearMessageNotifications(this)
        // 实时模式下，若界面尚无消息（如刚进入/重建），主动拉当日历史补齐，
        // 避免"丢失聊天记录，需手动刷新"的问题（WS 缓存回放之外再保险一次）。
        if (!isViewingHistory && adapter.itemCount == 0) {
            refreshCurrent()
        }
        // 从「安装未知应用」设置页返回：权限已开就自动继续刚才那次安装。
        // 设置页没法用 startActivityForResult 可靠拿结果，回到前台再判断最稳。
        val pending = pendingInstallApk
        if (pending != null && hasInstallPermission()) {
            pendingInstallApk = null
            installApk(pending)
        }
    }

    override fun onPause() {
        isForeground = false
        // 本页被遮挡 = 安装界面确实起来了，撤掉"没拉起"的判定。
        // 放在这里而不是 onResume，是为了覆盖"用户秒退回"的情况：
        // 只要离开过一次就说明跳转生效了，回来再快也不该误报。
        installLaunchAt = 0L
        WsClient.removeListener(this)
        super.onPause()
    }

    private fun sendText() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!WsClient.isConnected) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        WsClient.sendText(text)
        binding.etInput.text?.clear()
        scrollToBottom()
    }

    private fun toggleFloating() {
        val svc = ChatService.instance
        if (svc == null) {
            // 服务还没起来（如进程被杀后），先启动再提示重试
            startForegroundService(Intent(this, ChatService::class.java))
            Toast.makeText(this, R.string.float_starting, Toast.LENGTH_SHORT).show()
            return
        }
        if (svc.isFloatingShown()) {
            svc.hideFloating()
        } else {
            if (Settings.canDrawOverlays(this)) {
                svc.showFloating()
            } else {
                Toast.makeText(this, R.string.float_permission_guide, Toast.LENGTH_LONG).show()
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        updateFloatButton()
    }

    private fun updateFloatButton() {
        val shown = ChatService.instance?.isFloatingShown() ?: false
        binding.btnFloat.setText(if (shown) R.string.float_on else R.string.float_off)
    }

    // 是否处于"查看历史记录"模式（此时实时消息不插入列表）
    private var isViewingHistory: Boolean = false
    // 当前正在查看的日期（抽屉列表高亮用；null 表示实时公共聊天室）
    private var currentDate: String? = null

    /** 顶部居中状态提示：图标 + 语义色。实现已提到 StatusToast，供 Receiver / Service 复用 */
    private fun showTopToast(text: String, kind: String = StatusToast.INFO) =
        StatusToast.show(this, text, kind)

    /**
     * 刷新当前聊天：清掉本地消息，重新拉取当天历史。
     * @param notify 成功后是否弹顶部「刷新成功」。只有手动点刷新按钮才为 true；
     *               进入页面时的自动刷新、抽屉里切换房间都保持 false，避免无谓打扰。
     */
    private fun refreshCurrent(notify: Boolean = false) {
        if (!WsClient.isConnected) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        exitHistoryMode(silent = true)
        // 手动刷新时文案更明确（"正在刷新中…"），自动补历史仍用"加载中…"，
        // 让用户知道这一步是刚才那次点击触发的，而不是界面自己在抽风。
        binding.tvStatus.text = getString(
            if (notify) R.string.refresh_loading else R.string.history_loading
        )
        binding.tvStatus.visibility = View.VISIBLE
        Thread {
            val msgs = try {
                Api.historyToday(session.httpBase).map { Message.fromMap(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                binding.tvStatus.visibility = View.GONE
                if (msgs == null) {
                    showTopToast(getString(R.string.refresh_failed), StatusToast.ERROR)
                    return@runOnUiThread
                }
                adapter.setAll(msgs)
                scrollToBottom()
                if (notify) showTopToast(getString(R.string.refresh_success), StatusToast.SUCCESS)
            }
        }.start()
    }

    /** 拉取历史日期并填充左侧抽屉列表（最新日期在最上） */
    private fun loadDrawerDates() {
        // 先放一个"加载中"占位，保证抽屉打开就有可见内容（对齐 Node 体验）
        val list = binding.dateList
        list.removeAllViews()
        addDrawerItem(getString(R.string.loading), isCurrent = false) {}
        Thread {
            val dates = try {
                Api.historyDates(session.httpBase)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                list.removeAllViews()
                if (dates.isNullOrEmpty()) {
                    addDrawerItem(getString(R.string.history_no_dates), isCurrent = false) {}
                    return@runOnUiThread
                }
                val sorted = dates.sortedDescending()
                // 顶部固定项：回到实时公共聊天室（Node 的"公共聊天室"语义）
                addDrawerItem(getString(R.string.room_public), isCurrent = !isViewingHistory) {
                    closeDrawer()
                    exitHistoryMode(silent = true)
                    refreshCurrent()
                }
                sorted.forEach { date ->
                    addDrawerItem(date, isCurrent = date == currentDate) {
                        closeDrawer()
                        loadHistoryByDate(date)
                    }
                }
            }
        }.start()
    }

    /** 往抽屉里加一个日期条目（含当前选中高亮） */
    private fun addDrawerItem(text: String, isCurrent: Boolean, onClick: () -> Unit) {
        val item = TextView(this).apply {
            setText(text)
            textSize = 15f
            setPadding(16, 18, 16, 18)
            if (isCurrent) {
                setBackgroundResource(R.color.brand_subtle)
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.brand_dark))
            } else {
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.black))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        binding.dateList.addView(item)
    }

    /** 加载指定日期的历史消息并进入历史模式 */
    private fun loadHistoryByDate(date: String) {
        binding.tvStatus.text = getString(R.string.history_loading)
        binding.tvStatus.visibility = View.VISIBLE
        Thread {
            val maps = Api.historyByDate(session.httpBase, date)
            val msgs = maps.map { Message.fromMap(it) }
            runOnUiThread {
                isViewingHistory = true
                currentDate = date
                adapter.setAll(msgs)
                scrollToBottom()
                binding.tvStatus.visibility = View.GONE
                binding.tvHistoryHint.text = getString(R.string.history_hint, date)
                binding.tvHistoryHint.visibility = View.VISIBLE
            }
        }.start()
    }

    /** 退出历史模式，回到实时聊天（并在可能时回放最新的实时历史） */
    private fun exitHistoryMode(silent: Boolean = false) {
        if (!isViewingHistory) return
        isViewingHistory = false
        currentDate = null
        binding.tvHistoryHint.visibility = View.GONE
        // 直接请求一次当天实时历史，恢复到实时视图
        Thread {
            val maps = Api.historyToday(session.httpBase)
            val msgs = maps.map { Message.fromMap(it) }
            runOnUiThread {
                adapter.setAll(msgs)
                scrollToBottom()
                if (!silent) Toast.makeText(this, R.string.history_realtime, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun scrollToBottom() {
        binding.recyclerMessages.post {
            if (adapter.lastPosition >= 0) {
                binding.recyclerMessages.scrollToPosition(adapter.lastPosition)
            }
        }
    }

    /** 图片消息：弹窗查看大图 */
    private fun showImagePreview(url: String) {
        val dialog = Dialog(this)
        val img = ImageView(this).apply { adjustViewBounds = true }
        dialog.setContentView(
            img,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        ImageLoader.load(url, img)
    }

    /** 取下载目录。
     *
     *  APK **一律放应用私有目录**（getExternalFilesDir/Download/ImClient）：
     *  之前用 DownloadManager 存到公开 Download/ImClient，MIUI 上系统安装器对该路径
     *  有白名单限制，给了「安装未知应用」权限照样报「安装包位置错误」。
     *  改成私有目录后反而更稳——因为 PackageInstaller 会话是把 APK 的**字节**直接写给
     *  系统安装服务的，安装器根本不需要访问我们的文件路径；
     *  兜底的 Intent 方式走 FileProvider content:// + 显式 grantUriPermission 也能读到。
     *  顺带还免掉了存储权限依赖。
     */
    private fun getDownloadDir(forApk: Boolean): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (forApk && Environment.isExternalStorageManager()) {
                // APK 且已授权：直接写公开 Download，安装器能直接读文件路径
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ImClient")
            } else {
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "ImClient") }
            }
        } else {
            // Android 10 及以下：兼容模式直接写公开 Download
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ImClient")
        }
    }

    /** 文件消息：下载到本机后用系统应用打开，并在聊天记录展示保存路径 */
    private fun downloadAndOpenFile(m: Message) {
        val u = m.imageUrl ?: return
        val url = if (u.startsWith("http")) u else session.httpBase + u
        val name = (m.fileName ?: "download").replace(Regex("[\\\\/:*?\"<>|]"), "_")

        // 已下载过：直接打开本地文件，不再重复下载
        if (adapter.getDownloadState(m.id) == MessageAdapter.DL_DONE) {
            val cached = findDownloaded(name)
            if (cached != null) {
                openFile(cached)
                return
            }
            // 本地文件被清理掉了，退回重新下载
            adapter.setDownloadState(m.id, MessageAdapter.DL_NONE)
        }

        val isApk = name.endsWith(".apk", true)

        // APK：走系统 DownloadManager 写到公开 Download/ImClient。
        // 坚持用公开目录的原因：用户在文件管理器里能直接看到安装包，
        // 万一键内安装被 ROM 拦了，还能手动点一下装上（这条兜底实测可用）。
        // DownloadManager 写公开目录不需要任何存储权限，这点比自己写文件更省事。
        if (isApk) {
            downloadApkViaManager(url, name)
            return
        }

        // 进入下载中：按钮变灰显示"下载中…"，杜绝连点触发多次下载
        adapter.setDownloadState(m.id, MessageAdapter.DL_DOWNLOADING)
        Thread {
            val bytes = ImageLoader.downloadBytes(url)
            runOnUiThread {
                if (bytes == null) {
                    adapter.setDownloadState(m.id, MessageAdapter.DL_FAILED)
                    showTopToast(getString(R.string.file_download_fail, "网络错误"), StatusToast.ERROR)
                    return@runOnUiThread
                }
                try {
                    val dir = getDownloadDir(forApk = false)
                    if (dir == null) {
                        adapter.setDownloadState(m.id, MessageAdapter.DL_FAILED)
                        showTopToast(getString(R.string.storage_denied), StatusToast.ERROR)
                        return@runOnUiThread
                    }
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.writeBytes(bytes)
                    downloadedPaths[name] = f.absolutePath
                    adapter.setDownloadState(m.id, MessageAdapter.DL_DONE)

                    // 刷新媒体库，让文件管理器能立即看到
                    MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)

                    // 在聊天记录里插入一条"已下载到本地路径"的系统消息
                    val saved = Message.downloadSaved(name, f.absolutePath)
                    adapter.upsert(saved)
                    scrollToBottom()

                    openFile(f)
                } catch (e: Exception) {
                    adapter.setDownloadState(m.id, MessageAdapter.DL_FAILED)
                    showTopToast(getString(R.string.file_download_fail, e.message), StatusToast.ERROR)
                }
            }
        }.start()
    }

    /**
     * 找已下载的文件。
     * 先看下载成功时记下的真实路径；没有再按目录兜底（APK 在公开 Download/ImClient，
     * 普通文件在私有 ImClient，两边都要找）。
     */
    private fun findDownloaded(name: String): File? {
        downloadedPaths[name]?.let { p ->
            val f = File(p)
            if (f.exists() && f.length() > 0) return f
        }
        val candidates = mutableListOf<File>()
        @Suppress("DEPRECATION")
        candidates += File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ImClient/$name"
        )
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            candidates += File(File(it, "ImClient"), name)
        }
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** 用系统 DownloadManager 把 APK 下载到公开 Download/ImClient（无需特殊权限，用户可见） */
    private fun downloadApkViaManager(url: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ImClient/$name")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            Toast.makeText(this, R.string.file_downloading, Toast.LENGTH_SHORT).show()

            // 轮询下载完成，完成后直接拉起安装器
            Thread {
                var finished = false
                while (!finished) {
                    val q = DownloadManager.Query().setFilterById(id)
                    val c = dm.query(q)
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                            finished = true
                            runOnUiThread {
                                val path = uri.path ?: return@runOnUiThread
                                val f = File(path)
                                downloadedPaths[name] = f.absolutePath
                                downloadedUris[f.absolutePath] = uri
                                val saved = Message.downloadSaved(name, f.absolutePath)
                                adapter.upsert(saved)
                                scrollToBottom()
                                // 带上 DownloadManager 的内容 URI：它就是系统下载通知点进去
                                // 用的那个，安装器一定认，比我们自己造的 URI 更稳
                                installApk(f, uri)
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            finished = true
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.file_download_fail, "下载失败"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    c.close()
                    if (!finished) Thread.sleep(800)
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.file_download_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(f: File) {
        // APK 安装包走系统安装器，其余文件走 ACTION_VIEW
        if (f.extension.equals("apk", true)) {
            installApk(f)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 安装 APK。
     *
     * 路径优先级（**顺序很重要，别再调换**）：
     *   1) 系统安装器 Intent：ACTION_VIEW + content:// —— 与在文件管理器里点 APK 是同一条路；
     *   2) PackageInstaller 会话：仅作兜底。
     * 实测依据：同一个 APK，文件管理器里点能装，App 内用会话方式会被 MIUI 拒
     * （报"位置错误 / 未知错误"）。所以必须跟系统保持一致，先走安装器 Intent。
     *
     * URI 优先级：DownloadManager 给的 content://（下载通知点进去用的，安装器必然认）
     * > 自己 FileProvider 的 content://。
     *
     * 历史坑：曾用 Uri.fromFile 传 file://，targetSdk>=24 必抛 FileUriExposedException，
     * 表现为点了毫无反应；也别只认 ACTION_INSTALL_PACKAGE（Android 10 起对三方应用受限）。
     *
     * @param systemUri DownloadManager 给的内容 URI；为空时内部会按路径再查一次缓存
     */
    private fun installApk(f: File, systemUri: Uri? = null) {
        // 记下路径并重置回退标志：会话方式失败时 Receiver 要用它再试一次
        InstallState.lastApkPath = f.absolutePath
        InstallState.fallbackTried = false
        // 从"已下载，再点一次"进来时外部拿不到 URI，这里按路径补查缓存
        val uri = systemUri ?: downloadedUris[f.absolutePath]
        // 第一步先确认安装包本身是好的。系统安装器对坏包只给"位置错误""解析包出错"
        // 这类含糊提示，根本分不清是下载不完整、文件不是 APK、还是版本/签名冲突。
        val verdict = verifyApk(f)
        // 结论是"文件我们读不到"时，只有在没有系统 URI 可走的情况下才算致命：
        // 有 URI 时安装走 content://，不要求我们能直接用 File API 读到这个文件
        // （Android 11+ 用路径读公共 Download 目录可能被拒，但 URI 方式照样能装），
        // 此时提前拦截反而会误报，交给系统安装器给最终裁决更准。
        // 而"降级""签名不一致"这类确定性结论，有没有 URI 都必须拦下来。
        if (verdict.problem != null && (!verdict.fileLevelOnly || uri == null)) {
            showInstallProblem(verdict.problem, f)
            return
        }
        if (!hasInstallPermission()) {
            requestInstallPermission(f)
            return
        }
        // 优先级：安装器 Intent **先于** PackageInstaller 会话。
        // 实测依据：同一个 APK，在文件管理器里点能正常拉起系统安装并装成功，
        // 但在 App 内点就失败（MIUI 上报"位置错误 / 未知错误"）。
        // 文件管理器走的就是 ACTION_VIEW 安装器 Intent，而会话方式在这台设备上
        // 会被直接拒绝——所以先走与系统完全一致的那条路，会话只作兜底。
        val e1 = installViaInstallerIntent(f, uri)
        if (e1 == null) {
            watchInstallLaunch(f)
            return
        }
        val e2 = installViaPackageInstaller(f)
        if (e2 == null) return
        if (!hasInstallPermission()) {
            // 权限确实没开：引导去设置
            requestInstallPermission(f)
        } else {
            // 权限已开却还是装不了 —— 不能再去误导用户开权限，直接给出真实原因
            showInstallProblem("安装器方式：$e1\n会话方式：$e2", f)
        }
    }

    /**
     * 发起跳转后挂一个延时复查：startActivity 成功 ≠ 安装界面真的出来了。
     * 部分 ROM 会静默丢弃这次跳转（包可见性、安装监控、后台启动限制都可能），
     * 这时用户看到的就是"点了没反应"，而代码这边已经当成成功返回了。
     */
    private fun watchInstallLaunch(f: File) {
        mainHandler.removeCallbacks(installLaunchWatch)
        installLaunchApk = f
        installLaunchAt = System.currentTimeMillis()
        mainHandler.postDelayed(installLaunchWatch, INSTALL_LAUNCH_TIMEOUT_MS)
    }

    private fun checkInstallLaunched() {
        // installLaunchAt 为 0 表示期间触发过 onPause —— 确实跳走了，正常
        if (installLaunchAt == 0L || isFinishing) return
        installLaunchAt = 0L
        val f = installLaunchApk ?: return
        showInstallProblem(getString(R.string.install_not_launched), f)
    }

    /**
     * 校验结论。
     * @param problem null = 可以装；否则是给用户看的原因
     * @param fileLevelOnly true = 只是"这个文件我们读不到/解析不了"。走 content:// 时
     *        并不要求我们能直接用 File API 读到它（Android 11+ 用路径读公共 Download 可能被拒），
     *        所以这类问题在有系统 URI 时不能拦截，交给安装器裁决；
     *        而"降级""签名不一致"是确定性结论，无论如何都要拦下来。
     */
    private class Verdict(val problem: String?, val fileLevelOnly: Boolean) {
        companion object {
            fun ok() = Verdict(null, false)
            fun fileLevel(p: String) = Verdict(p, true)
            fun fatal(p: String) = Verdict(p, false)
        }
    }

    /**
     * 安装前完整性校验。挡在这里能省掉大量"系统说位置错误/未知错误，但其实文件是坏的"这类误判。
     */
    private fun verifyApk(f: File): Verdict {
        if (!f.exists() || !f.isFile) return Verdict.fileLevel(getString(R.string.install_file_missing))
        val size = f.length()
        if (size <= 0) return Verdict.fileLevel(getString(R.string.install_file_missing))
        // APK 本质是 zip，文件头必须是 "PK"。下载到 HTML 错误页时会在这里立刻暴露。
        try {
            FileInputStream(f).use { input ->
                val magic = ByteArray(2)
                val n = input.read(magic)
                if (n != 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                    return Verdict.fileLevel(
                        getString(R.string.install_bad_file, "$size 字节 / 头=${dumpHead(magic, n)}")
                    )
                }
            }
        } catch (e: Exception) {
            return Verdict.fileLevel(getString(R.string.install_bad_file, e.message ?: "读取失败"))
        }
        val info = readArchiveInfo(f) ?: return Verdict.fileLevel(getString(R.string.install_parse_failed))
        val pkg = info.packageName ?: return Verdict.ok()
        val installed = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            null
        }
        // 降级安装系统一律拒绝，但报错极不直观，这里提前说清楚
        if (installed != null && versionCodeOf(info) < versionCodeOf(installed)) {
            return Verdict.fatal("无法降级安装：待装 ${versionCodeOf(info)}，已装 ${versionCodeOf(installed)}")
        }
        // 签名不一致（已装的是 release 包、现在装的是 debug 包，或反之）也是覆盖安装的硬拒条件。
        // 系统对这种情况给的正是"未知错误 / 应用未安装"这类无信息量提示，一直没被识别出来，
        // 所以在这里提前明确告知，别再让用户以为是权限问题。
        if (installed != null && signatureMismatch(info, installed)) {
            return Verdict.fatal(getString(R.string.install_signature_mismatch))
        }
        return Verdict.ok()
    }

    /** 待装包与已装包的签名证书是否完全不相交。取不到签名时保守返回 false（不拦） */
    private fun signatureMismatch(pending: PackageInfo, installed: PackageInfo): Boolean {
        val a = sigFingerprints(pending)
        val b = sigFingerprints(installed)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.none { it in b }
    }

    /** 取包里所有签名证书的 SHA-256，尽量兼容新旧两套 API */
    @Suppress("DEPRECATION")
    private fun sigFingerprints(p: PackageInfo): Set<String> {
        val out = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                p.signingInfo?.let { si ->
                    si.apkContentsSigners?.forEach { out += sha256(it.toByteArray()) }
                    si.signingCertificateHistory?.forEach { out += sha256(it.toByteArray()) }
                }
            } catch (_: Exception) { }
        }
        if (out.isEmpty()) {
            try {
                p.signatures?.forEach { out += sha256(it.toByteArray()) }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    private fun dumpHead(magic: ByteArray, n: Int): String {
        if (n <= 0) return "(空)"
        return magic.take(n).joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else String.format("%02X", c)
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchiveInfo(f: File): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                f.absolutePath, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        }
    } catch (_: Exception) {
        null
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** 安装失败：用对话框而不是 Toast——信息较长且需要能复制给开发排查 */
    private fun showInstallProblem(reason: String, f: File) {
        val detail = buildString {
            append(reason).append("\n\n")
            append("文件：").append(f.absolutePath).append("\n")
            append("大小：").append(if (f.exists()) f.length() else -1).append(" 字节\n")
            append("权限：").append(if (hasInstallPermission()) "已开启" else "未开启").append("\n")
            // 候选数为 0 说明是 Android 11+ 包可见性过滤挡住了查询（只影响查询、不影响真实跳转）
            append("安装器候选：").append(installerCandidateCount()).append(" 个\n")
            append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.install_problem_title)
            .setMessage(detail)
            .setPositiveButton(R.string.copy_diag) { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("IM 安装诊断", detail))
                StatusToast.show(this, getString(R.string.install_diag_copied), StatusToast.INFO)
            }
            // 自动安装被 ROM 拦掉时的确定可用路径：手动点同一个 APK 是能装的
            // （实测文件管理器里点安装包可以正常拉起系统安装）
            .setNeutralButton(R.string.open_downloads) { _, _ -> openDownloadsList() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 打开系统下载列表，让用户能手动点到安装包 */
    private fun openDownloadsList() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            StatusToast.show(this, getString(R.string.open_downloads_hint), StatusToast.INFO)
            return
        } catch (_: Exception) {
        }
        // 部分 ROM 没有下载列表页，退回文件选择器
        try {
            startActivity(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/vnd.android.package-archive"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            )
        } catch (e: Exception) {
            StatusToast.show(this, e.message ?: "", StatusToast.ERROR)
        }
    }

    /** 诊断用：系统里能处理 APK 的 Activity 有几个。0 = 包可见性把查询挡了；-1 = 查询本身抛异常 */
    private fun installerCandidateCount(): Int = try {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).apply { type = "application/vnd.android.package-archive" },
            PackageManager.MATCH_DEFAULT_ONLY
        ).size
    } catch (_: Exception) {
        -1
    }

    private fun hasInstallPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
    }

    /** 先解释再跳设置——直接跳容易被当成"莫名跳到设置页"而关掉，用户根本不知道要开什么 */
    private fun requestInstallPermission(f: File) {
        pendingInstallApk = f
        AlertDialog.Builder(this)
            .setTitle(R.string.install_perm_title)
            .setMessage(R.string.install_perm_guide)
            .setPositiveButton(R.string.go_settings) { _, _ -> openInstallPermissionSettings() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingInstallApk = null }
            .setCancelable(false)
            .show()
    }

    /** 逐级降级打开授权页：各 ROM 入口差异极大，不能只认一种 Intent */
    private fun openInstallPermissionSettings() {
        val pkgUri = Uri.parse("package:$packageName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri))
                return
            } catch (e: Exception) { /* 该 ROM 没有这个页面，继续降级 */ }
            // 部分 MIUI / ColorOS 不认带 package 数据的 URI，再试一次不带数据的
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                return
            } catch (e: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            return
        } catch (e: Exception) { }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) { }
    }

    /** 首选路径：系统 PackageInstaller 会话。返回 null 表示已提交成功，否则返回失败原因。 */
    private fun installViaPackageInstaller(f: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return "系统版本过低"
        return try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                FileInputStream(f).use { input ->
                    session.openWrite("imclient_${System.currentTimeMillis()}", 0, f.length())
                        .use { out -> input.copyTo(out) }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
                // 显式指定组件：结果由 InstallResultReceiver 接，装成功/失败都有反馈
                val pi = PendingIntent.getBroadcast(
                    this, sessionId,
                    Intent(this, InstallResultReceiver::class.java)
                        .setAction(InstallResultReceiver.ACTION_INSTALL_COMMIT),
                    flags
                )
                session.commit(pi.intentSender)
            }
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 走系统安装器 Intent（与在文件管理器里点 APK 是同一条路）。null=已拉起安装器。
     * URI 优先级：DownloadManager 给的 content:// > 自己 FileProvider 的 content://。
     */
    private fun installViaInstallerIntent(f: File, systemUri: Uri? = null): String? {
        val mime = "application/vnd.android.package-archive"
        val errors = mutableListOf<String>()

        // 系统下载服务的 URI：下载完成通知点进去用的就是它，MIUI 安装器必然认
        if (systemUri != null) {
            val e = startInstallIntent(Intent(Intent.ACTION_VIEW), systemUri, mime)
            if (e == null) return null
            errors += "下载服务URI[$e]"
        }

        val fpUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        } catch (e: Exception) {
            if (errors.isEmpty()) return "FileProvider 取不到 URI（路径未授权）：${e.message}"
            null
        }
        if (fpUri != null) {
            // ACTION_VIEW 兼容性最好；ACTION_INSTALL_PACKAGE 自 Android 10 起对三方应用受限，仅作兜底
            val e1 = startInstallIntent(Intent(Intent.ACTION_VIEW), fpUri, mime)
            if (e1 == null) return null
            val e2 = startInstallIntent(Intent(Intent.ACTION_INSTALL_PACKAGE), fpUri, mime)
            if (e2 == null) return null
            errors += "VIEW[$e1] / INSTALL_PACKAGE[$e2]"
        }
        return errors.joinToString("，")
    }

    /** null=已拉起；否则返回原因。把"查不到安装器"和"启动被拒"分开说，之前都混成一句"没反应" */
    private fun startInstallIntent(base: Intent, uri: Uri, mime: String): String? {
        return try {
            val i = base.apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Android 11+ 光加 FLAG 不够，需逐个显式授权，否则目标安装器读到的 content:// 是空的
            val targets = try {
                packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) {
                emptyList()
            }
            targets.forEach { ri ->
                try {
                    grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) { }
            }
            // 关键：**不要因为查询为空就提前放弃**。
            // Android 11+ 的包可见性过滤只作用于 PackageManager 的查询结果，
            // 并不影响系统对 Intent 的真正解析——也就是说"查不到"≠"装不了"。
            // 之前这里 `if (targets.isEmpty()) return` 会在某些 ROM 上直接放弃安装器路径，
            // 表现就是点了安装毫无反应，于是误判成"没权限"，正好对上"App 内装不了、
            // 文件管理器里点却能装"的现象。现在一律真的发起一次，由系统给最终结果。
            startActivity(i)
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun logout() {
        WsClient.stop()
        startService(Intent(this, ChatService::class.java).apply {
            action = ChatService.ACTION_STOP
        })
        session.clear()
        SessionManager.currentUserCache = ""
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- WsClient.Listener ----------

    override fun onStatusChanged(connected: Boolean) {
        // 顶栏红/绿灯连接状态
        binding.dotConn.setBackgroundResource(if (connected) R.drawable.dot_green else R.drawable.dot_red)
        binding.tvConn.text = if (connected) getString(R.string.conn_online) else getString(R.string.conn_offline)
        if (connected) {
            binding.tvStatus.visibility = android.view.View.GONE
        }
    }

    override fun onMessage(message: Message) {
        // 查看历史记录时不插入实时消息，避免污染历史视图
        if (isViewingHistory) return
        adapter.upsert(message)
        scrollToBottom()
    }

    override fun onHistory(messages: List<Message>) {
        // 查看历史日期（非当日）时不覆盖历史视图；只有实时模式才接受当日历史/缓存回放
        if (isViewingHistory) return
        adapter.setAll(messages)
        scrollToBottom()
    }

    override fun onOnlineUsers(users: List<String>) {
        // APP 不再展示在线列表，仅更新连接灯（已连接）
        binding.dotConn.setBackgroundResource(R.drawable.dot_green)
        binding.tvConn.text = getString(R.string.conn_online)
    }

    override fun onKicked(reason: String) {
        Toast.makeText(this, getString(R.string.kicked), Toast.LENGTH_LONG).show()
        logout()
    }
}
