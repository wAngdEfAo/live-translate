package com.shishifanyi.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 高精度模型包的下载器。
 *
 * 模型 294MB，不可能塞进 APK，也不该逼用户自己找浏览器下载再手工导入。
 * 这里用系统 DownloadManager：断点续传、通知栏进度、仅 Wi-Fi、失败重试都由系统负责，
 * 落盘位置是 **应用私有的外部目录**（`Android/data/<pkg>/files/Download/`），
 * 不需要任何存储权限，App 自己就能读回来。
 *
 * 下载完成后交给 [EnginePack.importZip] 走同一套「解到临时目录 → 校验 → 整体替换」流程，
 * 所以下载和手工导入的落地路径完全一致，校验规则也一样。
 */
object EngineDownloader {
    private const val TAG = "EngineDownloader"
    private const val DESCRIPTOR = "engine_pack.json"
    private const val FILE_NAME = "live-translate-engine-opus-mt-int8.zip"

    /** 没有下载任务。 */
    const val NO_TASK = -1L

    /** 不需要下载：本地已经有完整文件，直接走导入。 */
    const val REUSE_ID = -2L

    /** 下载状态，给界面显示用。 */
    data class Status(
        val running: Boolean,
        val done: Boolean,
        val failed: String?,
        val downloaded: Long,
        val total: Long,
        val note: String? = null
    ) {
        fun text(): String = when {
            failed != null -> "下载失败：$failed"
            done -> "下载完成，正在导入…"
            note != null -> note + "（" + EnginePack.mb(downloaded) + "/" + EnginePack.mb(total) + "）"
            running -> {
                val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                "下载中 $pct%（" + EnginePack.mb(downloaded) + "/" + EnginePack.mb(total) + "）"
            }
            else -> ""
        }
    }

    /** 内置的包描述（地址 + 大小 + 校验值），随 APK 一起发。 */
    private var cached: JSONObject? = null

    fun descriptor(ctx: Context): JSONObject? {
        cached?.let { return it }
        return try {
            ctx.assets.open(DESCRIPTOR).use { ins ->
                val o = JSONObject(ins.bufferedReader().readText())
                cached = o
                o
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读不到下载描述: " + t.message)
            null
        }
    }

    /**
     * 当前是不是 Wi-Fi / 有线。
     *
     * 刻意不复用「是否计费网络」：部分运营商 APN 会被系统标成 NOT_METERED，
     * 那样 `setAllowedOverMetered(false)` 就不拦了 —— 用户明明开着「仅 Wi-Fi」，
     * 下载照样跑在流量上。直接看 TRANSPORT_WIFI 才是与用户理解一致的那个判断。
     */
    fun isOnWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (t: Throwable) {
            false
        }
    }

    fun primaryUrl(ctx: Context): String? {
        val d = descriptor(ctx) ?: return null
        val arr = d.optJSONArray("urls") ?: return null
        return if (arr.length() > 0) arr.optString(0, "") else null
    }

    fun expectedBytes(ctx: Context): Long = descriptor(ctx)?.optLong("bytes", -1L) ?: -1L

    fun expectedSha256(ctx: Context): String = descriptor(ctx)?.optString("sha256", "") ?: ""

    fun targetFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        return File(dir, FILE_NAME)
    }

    /**
     * 开始下载。会先取消上一次没跑完的任务。
     * @return 下载任务 id，失败返回 -1
     */
    fun start(ctx: Context): Long {
        val url = primaryUrl(ctx)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "没有可用的下载地址")
            return -1
        }
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return -1
        cancel(ctx)
        val dest = targetFile(ctx)
        try {
            // 已经下好且大小对得上：不删，留给 importIfReady 直接导入。
            // 以前无条件 dest.delete()，用户下载完成但导入失败（空间不足等）后重试
            // 会把那份已校验的文件删掉，从 0 重下 294MB。
            val want = expectedBytes(ctx)
            val reusable = dest.isFile && dest.length() > 0 &&
                (want <= 0 || dest.length() == want)
            if (reusable) {
                Log.d(TAG, "已有完整下载文件，跳过重复下载")
                Prefs.engineDlId = REUSE_ID
                return REUSE_ID
            }
            if (dest.exists()) dest.delete()
            // 新任务清零进度基线：否则 startDlPolling 会把 lastDlBytes 恢复成上一轮的
            // 完成值，grew 全程为 false，仅 Wi-Fi 守卫整段失效
            Prefs.engineDlBytes = 0L
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("实时翻译 · 高精度模型包")
                .setDescription("英→中 / 中→英，约 294MB")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(!Prefs.engineWifiOnly)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    ctx, Environment.DIRECTORY_DOWNLOADS, FILE_NAME
                )
            val id = dm.enqueue(req)
            Prefs.engineDlId = id
            Log.d(TAG, "开始下载 id=$id url=$url wifiOnly=${Prefs.engineWifiOnly}")
            return id
        } catch (t: Throwable) {
            Log.w(TAG, "发起下载失败: " + t.message)
            return -1
        }
    }

    fun cancel(ctx: Context) {
        val id = Prefs.engineDlId
        if (id < 0) {
            // REUSE_ID 只清标记，不动磁盘上那份已经下好的文件
            if (id == REUSE_ID) Prefs.engineDlId = NO_TASK
            return
        }
        try {
            ctx.getSystemService(DownloadManager::class.java)?.remove(id)
        } catch (t: Throwable) {
            Log.w(TAG, "取消下载失败: " + t.message)
        }
        Prefs.engineDlId = NO_TASK
    }

    /** 查询当前任务状态；没有任务就返回 null（并把失效的 id 清掉，避免每次 onResume 重查）。 */
    fun status(ctx: Context): Status? {
        val id = Prefs.engineDlId
        if (id == NO_TASK) return null
        if (id == REUSE_ID) {
            // 本地已有完整文件：直接报「完成」，交给 importIfReady 去校验并导入
            val f = targetFile(ctx)
            val n = if (f.isFile) f.length() else 0L
            return Status(false, true, null, n, n)
        }
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return null
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    // 任务被系统清掉了（或用户清理了下载）：清 id，别让界面一直空转重查
                    Log.d(TAG, "下载任务已不存在，清掉 id=$id")
                    Prefs.engineDlId = NO_TASK
                    return null
                }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL ->
                        Status(false, true, null, got, if (total > 0) total else got)
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Status(false, false, reasonText(reason), got, total)
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val note = when (reason) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                                "等待网络（已限制仅 Wi-Fi，切到 Wi-Fi 会继续）"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "等待重试"
                            DownloadManager.PAUSED_UNKNOWN -> "已暂停"
                            else -> "已暂停（$reason）"
                        }
                        Status(true, false, null, got, total, note)
                    }
                    else -> Status(true, false, null, got, total)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询下载状态失败: " + t.message)
            null
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_FILE_ERROR -> "写入失败"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "传输中断"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向过多"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
        DownloadManager.ERROR_UNKNOWN -> "未知错误"
        else -> "错误码 $reason"
    }

    /**
     * 下载完成后调用：校验整体 sha256 → 交给 [EnginePack.importZip] → 删除下载文件。
     * 返回 null 表示还没下完。
     */
    fun importIfReady(ctx: Context): Pair<Boolean, String>? {
        val st = status(ctx) ?: return null
        if (!st.done) return null
        val f = targetFile(ctx)
        if (!f.isFile || f.length() <= 0) {
            Prefs.engineDlId = NO_TASK
            return false to "下载的文件不见了，请重新下载"
        }
        val wantBytes = expectedBytes(ctx)
        if (wantBytes > 0 && f.length() != wantBytes) {
            f.delete()
            Prefs.engineDlId = NO_TASK
            return false to ("文件大小不符（" + f.length() + " / " + wantBytes + "），请重新下载")
        }
        val wantHash = expectedSha256(ctx)
        if (wantHash.isNotEmpty()) {
            val got = sha256(f)
            if (!got.equals(wantHash, ignoreCase = true)) {
                f.delete()
                Prefs.engineDlId = NO_TASK
                return false to "校验失败（sha256 不匹配），文件已删除，请重新下载"
            }
        }
        val result = EnginePack.importZip(ctx) { f.inputStream() }
        if (result.first) {
            // 导入成功就把 294MB 的 zip 删掉，别白占一份
            try {
                f.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "删除下载文件失败: " + t.message)
            }
        }
        // 成功或失败都要清 id：不清的话每次回到前台都会重跑一遍 sha256（2–4 秒）再试一次导入，
        // 没有次数上限、也没有任何进度提示
        Prefs.engineDlId = NO_TASK
        return result
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}