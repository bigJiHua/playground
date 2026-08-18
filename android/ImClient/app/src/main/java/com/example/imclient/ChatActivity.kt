package com.example.imclient

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.imclient.databinding.ActivityChatBinding
import java.io.File

class ChatActivity : AppCompatActivity(), WsClient.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: MessageAdapter

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

        binding.btnSend.setOnClickListener { sendText() }
        binding.btnFloat.setOnClickListener { toggleFloating() }
        binding.btnAttach.setOnClickListener { showAttachMenu(it) }
        binding.btnRefresh.setOnClickListener { refreshCurrent() }
        binding.tvHistoryHint.setOnClickListener { exitHistoryMode() }
        binding.btnMenu.setOnClickListener { openDrawer() }
        binding.btnCloseDrawer.setOnClickListener { closeDrawer() }
        binding.btnLogout.setOnClickListener { logout() }
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
        WsClient.addListener(this)
        updateFloatButton()
        // 实时模式下，若界面尚无消息（如刚进入/重建），主动拉当日历史补齐，
        // 避免"丢失聊天记录，需手动刷新"的问题（WS 缓存回放之外再保险一次）。
        if (!isViewingHistory && adapter.itemCount == 0) {
            refreshCurrent()
        }
    }

    override fun onPause() {
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

    /** 刷新当前聊天：清掉本地消息，重新拉取当天历史 */
    private fun refreshCurrent() {
        if (!WsClient.isConnected) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        exitHistoryMode(silent = true)
        binding.tvStatus.text = getString(R.string.history_loading)
        binding.tvStatus.visibility = View.VISIBLE
        Thread {
            val maps = Api.historyToday(session.httpBase)
            val msgs = maps.map { Message.fromMap(it) }
            runOnUiThread {
                adapter.setAll(msgs)
                scrollToBottom()
                binding.tvStatus.visibility = View.GONE
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
                setBackgroundResource(R.color.purple_100)
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.purple_700))
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
     *  forApk=true：APK 安装包必须能被系统安装器直接访问，故优先用公开 Download/ImClient
     *   （MIUI 上私有目录经 FileProvider 的 content:// 安装器常读不到）。无 MANAGE_EXTERNAL_STORAGE
     *   权限时回退私有目录 + FileProvider 安装。
     *  forApk=false：普通文件存应用私有 Download/ImClient（无需任何权限，文件管理器可看）。
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
        val isApk = name.endsWith(".apk", true)

        // APK：走系统 DownloadManager 存到公开 Download/ImClient，安装器可直接读路径，
        //      且无需 MANAGE_EXTERNAL_STORAGE（小米不显示该权限入口也不受影响），体验与改前一致。
        if (isApk) {
            downloadApkViaManager(url, name)
            return
        }

        Toast.makeText(this, R.string.file_downloading, Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = ImageLoader.downloadBytes(url)
            runOnUiThread {
                if (bytes == null) {
                    Toast.makeText(this, getString(R.string.file_download_fail, "网络错误"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    val dir = getDownloadDir(forApk = false)
                    if (dir == null) {
                        Toast.makeText(this, getString(R.string.storage_denied), Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.writeBytes(bytes)

                    // 刷新媒体库，让文件管理器能立即看到
                    MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)

                    // 在聊天记录里插入一条"已下载到本地路径"的系统消息
                    val saved = Message.downloadSaved(name, f.absolutePath)
                    adapter.upsert(saved)
                    scrollToBottom()

                    openFile(f)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.file_download_fail, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 用系统 DownloadManager 把 APK 下载到公开 Download/ImClient（无需特殊权限，安装器可读） */
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
                                val saved = Message.downloadSaved(name, f.absolutePath)
                                adapter.upsert(saved)
                                scrollToBottom()
                                installApk(f)
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

    /** 安装 APK：Android 8+ 需要「安装未知应用」权限，没有则引导去开；
     *  小米/HyperOS 上 canRequestPackageInstalls() 常误判为 true，故启动前先 resolveActivity 兜底。 */
    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.install_perm_guide, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // 个别 ROM 不支持该页面，退回应用详情
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        // 若文件位于公开 Download（MIUI 安装器可直接读文件路径），用 file:// 直接装，无需 FileProvider；
        // 仅当回退到私有目录时才用 FileProvider 的 content://（授权给安装器读取）。
        val publicRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        }
        val uri = if (f.absolutePath.startsWith(publicRoot)) {
            Uri.fromFile(f)
        } else {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        }
        // 优先用 INSTALL_PACKAGE；MIUI 若拦截，则退回 ACTION_VIEW + apk mime（更通用）
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when {
            installIntent.resolveActivity(packageManager) != null -> startActivity(installIntent)
            viewIntent.resolveActivity(packageManager) != null -> startActivity(viewIntent)
            else -> {
                // 仍解析不到安装器：多半是「安装未知应用」被 MIUI 默认关闭
                Toast.makeText(this, R.string.install_perm_guide, Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
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
