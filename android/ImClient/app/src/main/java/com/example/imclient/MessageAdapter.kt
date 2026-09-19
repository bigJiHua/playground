package com.example.imclient

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.imclient.databinding.ItemMessageBinding
import com.example.imclient.databinding.ItemMessageFileBinding
import com.example.imclient.databinding.ItemMessageImageBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天消息适配器。
 * - text：普通文字气泡（自己靠右紫色、他人靠左白色）
 * - image：图片消息（缩略图 + 点击查看大图）
 * - file：文件消息（文件卡片 + 点击下载打开）
 * - system_join：居中灰字
 * - 通过 clientId 把"发送中"的乐观消息更新为"已发送"（upsert）
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    interface OnMessageAction {
        fun onImageClick(url: String)
        fun onFileClick(message: Message)
    }

    private val items = mutableListOf<Message>()
    var selfUser: String = ""
    var baseUrl: String = ""          // http://host，用于拼相对路径的 /uploads/xxx
    var onAction: OnMessageAction? = null

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_IMAGE = 1
        private const val TYPE_FILE = 2

        // 文件 / 图片的下载状态：按钮据此渲染。
        // 之前按钮永远显示"下载"，点下去没有任何变化，用户不知道是没触发还是卡住了。
        const val DL_NONE = 0        // 未下载 → 下载
        const val DL_DOWNLOADING = 1 // 下载中 → 下载中…（禁用，防重复点击重复下载）
        const val DL_DONE = 2        // 已完成 → 打开
        const val DL_FAILED = 3      // 失败 → 重试
    }

    // 消息 id -> 下载状态（仅运行时，不入库）
    private val downloadStates = mutableMapOf<Long, Int>()

    fun getDownloadState(id: Long): Int = downloadStates[id] ?: DL_NONE

    /** 更新下载状态，只刷新对应那一条，避免整列表重绘导致滚动跳动 */
    fun setDownloadState(id: Long, state: Int) {
        downloadStates[id] = state
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /**
     * 整体替换消息列表（对齐 Node 端 renderMessages：直接以服务端返回的 messages 为准）。
     * 查看历史或切回实时时都做干净替换，避免把其它日期的旧消息混入当前列表。
     */
    fun setAll(list: List<Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun upsert(m: Message) {
        val idx = items.indexOfFirst {
            it.clientId != null && m.clientId != null && it.clientId == m.clientId
        }
        if (idx >= 0) {
            items[idx] = m
            notifyItemChanged(idx)
        } else {
            items.add(m)
            notifyItemInserted(items.size - 1)
        }
    }

    val lastPosition: Int
        get() = items.size - 1

    override fun getItemViewType(position: Int): Int = when (items[position].type) {
        "image" -> TYPE_IMAGE
        "file" -> TYPE_FILE
        else -> TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IMAGE -> VH(
                image = ItemMessageImageBinding.inflate(inflater, parent, false)
            )
            TYPE_FILE -> VH(
                file = ItemMessageFileBinding.inflate(inflater, parent, false)
            )
            else -> VH(
                text = ItemMessageBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(
        private val text: ItemMessageBinding? = null,
        private val image: ItemMessageImageBinding? = null,
        private val file: ItemMessageFileBinding? = null
    ) : RecyclerView.ViewHolder(text?.root ?: image?.root ?: file!!.root) {

        fun bind(m: Message) {
            when (m.type) {
                "image" -> bindImage(m)
                "file" -> bindFile(m)
                else -> bindText(m)
            }
        }

        private fun bindText(m: Message) {
            val b = text ?: return
            val tv = b.tvBubble
            val wrapper = b.wrapper
            val lp = wrapper.layoutParams as FrameLayout.LayoutParams

            if (m.isSystem) {
                lp.gravity = Gravity.CENTER
                wrapper.layoutParams = lp
                tv.text = m.text
                tv.setBackgroundResource(0)
                tv.setTextColor(colorOf(R.color.text_system))
                tv.gravity = Gravity.CENTER
                b.tvTime.text = ""
                // 带本地保存路径的系统消息：点击复制路径到剪贴板
                if (!m.fileLocalPath.isNullOrEmpty()) {
                    tv.setTextColor(colorOf(R.color.text_link))
                    tv.setOnClickListener {
                        val ctx = itemView.context
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("path", m.fileLocalPath)
                        )
                        android.widget.Toast.makeText(ctx, R.string.path_copied, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    tv.setOnClickListener(null)
                }
                return
            }

            val isSelf = m.user == selfUser
            lp.gravity = if (isSelf) Gravity.END else Gravity.START
            wrapper.layoutParams = lp

            val prefix = if (!isSelf) "${m.user}\n" else ""
            val sending = if (m.status == Message.STATUS_SENDING) " ⏳" else ""
            tv.text = prefix + (m.text ?: "") + sending
            tv.setBackgroundResource(if (isSelf) R.drawable.bubble_self else R.drawable.bubble_other)
            tv.setTextColor(if (isSelf) colorOf(R.color.text_self) else colorOf(R.color.text_other))
            tv.gravity = Gravity.START
            b.tvTime.text = formatTime(m.createdAt)
        }

        private fun bindImage(m: Message) {
            val b = image ?: return
            val isSelf = m.user == selfUser
            val lp = b.wrapper.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isSelf) Gravity.END else Gravity.START
            b.wrapper.layoutParams = lp
            b.bubbleImage.setBackgroundResource(if (isSelf) R.drawable.bubble_self else R.drawable.bubble_other)
            b.tvCaption.setTextColor(
                if (isSelf) colorOf(R.color.brand_subtle) else colorOf(R.color.text_tertiary)
            )
            b.tvTime.text = formatTime(m.createdAt)

            val url = resolveUrl(m.imageUrl)
            if (url != null) {
                ImageLoader.load(url, b.ivImage)
            }
            b.root.setOnClickListener { url?.let { onAction?.onImageClick(it) } }

            // 图片下载按钮（同样按状态渲染）
            b.btnDownload.setBackgroundResource(if (isSelf) R.drawable.bg_download_btn_self else R.drawable.bg_download_btn)
            applyDownloadState(b.btnDownload, m.id, isSelf)
            b.btnDownload.setOnClickListener { onAction?.onFileClick(m) }
        }

        /** 按下载状态渲染按钮：文案 + 图标 + 可用态 */
        private fun applyDownloadState(btn: TextView, msgId: Long, isSelf: Boolean) {
            val state = getDownloadState(msgId)
            val textRes = when (state) {
                DL_DOWNLOADING -> R.string.downloading
                DL_DONE -> R.string.open_file
                DL_FAILED -> R.string.retry
                else -> R.string.download
            }
            val iconRes = when (state) {
                DL_DONE -> R.drawable.ic_file_attachment
                DL_FAILED -> R.drawable.ic_refresh
                else -> R.drawable.ic_download
            }
            btn.text = getString(textRes)
            // 下载中禁用：背景 selector 会显示灰色，用户看得出"正在忙"，也防重复点击重复下载
            btn.isEnabled = state != DL_DOWNLOADING
            btn.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            val tint = if (isSelf) colorOf(R.color.brand) else colorOf(R.color.on_brand)
            btn.setTextColor(tint)
            btn.compoundDrawables[0]?.setTint(tint)
        }

        private fun bindFile(m: Message) {
            val b = file ?: return
            val isSelf = m.user == selfUser
            val lp = b.wrapper.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isSelf) Gravity.END else Gravity.START
            b.wrapper.layoutParams = lp
            b.bubbleFile.setBackgroundResource(if (isSelf) R.drawable.bubble_self else R.drawable.bubble_other)

            // 图标按气泡底色换色：自己发的（紫底）用白色，别人发的用紫色
            b.ivFileIcon.setColorFilter(
                if (isSelf) colorOf(R.color.on_brand) else colorOf(R.color.brand)
            )
            b.tvFileName.text = m.fileName ?: m.text ?: getString(R.string.file_text)
            b.tvFileName.setTextColor(
                if (isSelf) colorOf(R.color.text_self) else colorOf(R.color.text_other)
            )
            val size = m.fileSize?.let { formatSize(it) } ?: ""
            b.tvFileSize.text = size
            b.tvFileSize.setTextColor(
                if (isSelf) colorOf(R.color.brand_subtle) else colorOf(R.color.text_tertiary)
            )
            b.tvTime.text = formatTime(m.createdAt)

            // 下载按钮：按状态渲染（未下载 / 下载中 / 已完成 / 失败重试）
            b.btnDownload.setBackgroundResource(if (isSelf) R.drawable.bg_download_btn_self else R.drawable.bg_download_btn)
            applyDownloadState(b.btnDownload, m.id, isSelf)
            b.btnDownload.setOnClickListener { onAction?.onFileClick(m) }
            // 整条点击也触发下载（更顺手）
            b.root.setOnClickListener { onAction?.onFileClick(m) }
        }

        private fun resolveUrl(u: String?): String? {
            if (u.isNullOrEmpty()) return null
            return if (u.startsWith("http")) u else baseUrl + u
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }

        private fun getString(id: Int): String = itemView.context.getString(id)

        /** 取色板里的颜色，避免代码里再写 #RRGGBB 字面量 */
        private fun colorOf(id: Int): Int = ContextCompat.getColor(itemView.context, id)

        /**
         * 由消息的 created_at（ISO，如 2026-08-15T14:30:00）取 HH:mm。
         * 与 Node 端 formatTime 同日行为一致；跨日日期由顶栏 history_hint 提示条承载。
         */
        private fun formatTime(createdAt: String?): String {
            if (createdAt.isNullOrEmpty()) return ""
            return try {
                val dt = LocalDateTime.parse(createdAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
            } catch (_: Exception) {
                ""
            }
        }
    }
}
