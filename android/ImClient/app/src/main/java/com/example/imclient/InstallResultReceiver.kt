package com.example.imclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * 接收 PackageInstaller 会话提交后的结果。
 *
 * 之前会话提交完就没人管了：装没装上、为什么失败，界面上完全没有反馈，
 * 用户在小米 / OPPO 上点了安装，系统确认框被 ROM 拦掉后只会以为"App 坏了"。
 *
 * 这里补上三类处理：
 * - 需要用户确认（STATUS_PENDING_USER_ACTION）：把系统给的确认界面拉起来。
 *   多数 ROM 会自己弹，但部分定制系统不会，不处理就永远停在"点完没反应"。
 * - 成功：顶部绿色提示。
 * - 失败：顶部红色提示并带上系统给的原因（签名不一致 / 版本降级 / 空间不足等）。
 * - 用户主动取消（STATUS_FAILURE_ABORTED）：不打扰。
 *
 * 注：PendingIntent 是本应用创建的，广播由本应用身份发出，所以 Manifest 里
 * exported=false 即可收到，不需要对外暴露。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_COMMIT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = pendingActionIntent(intent)
                if (confirm != null) {
                    try {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        StatusToast.show(
                            context,
                            context.getString(R.string.install_failed, message ?: e.message.orEmpty()),
                            StatusToast.ERROR
                        )
                    }
                } else {
                    StatusToast.show(
                        context,
                        context.getString(R.string.install_failed, message.orEmpty()),
                        StatusToast.ERROR
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                StatusToast.show(
                    context,
                    context.getString(R.string.install_success),
                    StatusToast.SUCCESS
                )

            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // 用户自己取消，不提示

            else -> {
                // 会话提交成功 ≠ 安装成功。MIUI 某些版本会直接拒绝会话方式，
                // 这时自动退回传统安装器 Intent 再试一次，而不是干等用户反馈"装不上"。
                if (!InstallState.fallbackTried && tryFallbackInstaller(context)) {
                    InstallState.fallbackTried = true
                    return
                }
                // 状态码必须带上：只说"未知错误"没法定位，MIUI 各版本返回的码不一样。
                // 同时把完整诊断信息复制到剪贴板，方便直接贴给开发排查。
                val reason = message?.takeIf { it.isNotBlank() } ?: statusName(status)
                StatusToast.show(
                    context,
                    context.getString(R.string.install_failed_with_code, reason, status),
                    StatusToast.ERROR
                )
                copyDiagnostic(context, status, message)
            }
        }
    }

    /** 把诊断信息放进剪贴板：Toast 一闪而过，光靠肉眼看不全 */
    private fun copyDiagnostic(context: Context, status: Int, message: String?) {
        try {
            val text = buildString {
                append("IM 安装诊断\n")
                append("status=").append(status).append(" (").append(statusName(status)).append(")\n")
                append("message=").append(message ?: "(空)").append("\n")
                append("device=").append(Build.MANUFACTURER).append(" ")
                append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("installer=").append(context.packageName).append("\n")
                append("canInstall=").append(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.packageManager.canRequestPackageInstalls()
                    } else "n/a"
                )
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("IM 安装诊断", text))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingActionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }
    }

    /** 把状态码翻成人话。注意 STATUS_FAILURE(1) 是"其它"兜底，MIUI 上很常见，
     *  此时真正原因往往在 EXTRA_STATUS_MESSAGE 里（如 INSTALL_FAILED_XXX）。 */
    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "安装失败（通用）"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "被系统或设备策略阻止"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装应用冲突"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与设备不兼容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效或已损坏"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "等待用户确认"
        PackageInstaller.STATUS_SUCCESS -> "成功"
        else -> "未知错误"
    }

    /**
     * 会话方式失败后，用传统安装器 Intent 再试一次。
     * 走 FileProvider content:// 并对每个候选安装器显式授权（Android 11+ 必须）。
     */
    private fun tryFallbackInstaller(context: Context): Boolean {
        val path = InstallState.lastApkPath ?: return false
        val f = File(path)
        if (!f.exists() || f.length() <= 0) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 查询只用于授权：Android 11+ 的包可见性过滤只影响 PackageManager 查询结果，
            // 不影响系统对 Intent 的解析，"查不到"不等于"装不了"。
            // 所以这里不能因为 targets 为空就 return false —— 那会让兜底形同虚设，
            // 与 ChatActivity 里那处同样的问题是一个坑。
            val targets = try {
                context.packageManager.queryIntentActivities(i, 0)
            } catch (_: Exception) {
                emptyList()
            }
            targets.forEach { ri ->
                try {
                    context.grantUriPermission(
                        ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
            }
            context.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val ACTION_INSTALL_COMMIT = "com.example.imclient.action.INSTALL_COMMIT"
    }
}

/** 最近一次安装的 APK 路径 + 是否已回退过（跨 Receiver 与 Activity 共享，只回退一次避免死循环） */
object InstallState {
    @Volatile
    var lastApkPath: String? = null

    @Volatile
    var fallbackTried: Boolean = false
}
