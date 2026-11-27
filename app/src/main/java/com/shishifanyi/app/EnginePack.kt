package com.shishifanyi.app

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 高精度引擎的模型包管理。
 *
 * 模型不打进 APK（单方向 encoder 53MB + decoder 93MB），而是作为一个 zip 侧的
 * 「引擎包」下载或导入，落在 `filesDir/engine_onnx/packs/<pair>/`。
 * 目录约定：
 *
 * ```
 * engine_onnx/manifest.json          { "format":1, "packs":[{ "pair":"en_zh", ... }] }
 * engine_onnx/packs/en_zh/encoder.onnx
 * engine_onnx/packs/en_zh/decoder.onnx
 * engine_onnx/packs/en_zh/tokenizer.json
 * ```
 */
object EnginePack {
    private const val TAG = "EnginePack"
    private const val DIR = "engine_onnx"
    private const val PACKS = "packs"

    /** 单条目 / 总量上限：模型最大的文件约 93MB，留足余量但不给爆盘机会。 */
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    private val REQUIRED = listOf("encoder.onnx", "decoder.onnx", "tokenizer.json")

    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    private fun packsDir(ctx: Context): File = File(root(ctx), PACKS)

    fun pairKey(src: String, tgt: String): String? = when {
        src == "en" && tgt == "zh" -> "en_zh"
        src == "zh" && tgt == "en" -> "zh_en"
        else -> null
    }

    fun pairDir(ctx: Context, key: String): File = File(packsDir(ctx), key)

    /** 该方向的模型是否齐全（只看文件是否到位，不校验内容）。 */
    fun isInstalled(ctx: Context, key: String): Boolean {
        val d = pairDir(ctx, key)
        if (!d.isDirectory) return false
        for (n in REQUIRED) {
            val f = File(d, n)
            if (!f.isFile || f.length() <= 0) return false
        }
        return true
    }

    /** 已装好的方向与占用。 */
    fun installed(ctx: Context): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>(2)
        for (k in listOf("en_zh", "zh_en")) {
            if (isInstalled(ctx, k)) out.add(k to dirBytes(pairDir(ctx, k)))
        }
        return out
    }

    fun totalBytes(ctx: Context): Long = dirBytes(root(ctx))

    private fun dirBytes(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var sum = 0L
        f.listFiles()?.forEach { sum += dirBytes(it) }
        return sum
    }

    /** 删除某个方向（腾出空间）。 */
    fun remove(ctx: Context, key: String): Boolean {
        return try {
            val d = pairDir(ctx, key)
            val ok = !d.exists() || d.deleteRecursively()
            if (ok) OnnxEngine.forget(key)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "删除失败: " + t.message)
            false
        }
    }

    /**
     * 从 zip 导入模型包。条目名以 `packs/` 开头，或直接是 `en_zh/encoder.onnx`
     * 这类也接受（兼容手工打包）。
     */
    fun importZip(ctx: Context, open: () -> java.io.InputStream?): Pair<Boolean, String> {
        val rootDir = root(ctx)
        var count = 0
        var written = 0L
        var truncated = false
        try {
            val raw = open() ?: return false to "读不到文件"
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (!e.isDirectory && name.isNotEmpty() && !name.contains("..")) {
                        val target = File(rootDir, name)
                        // 只允许落在 rootDir 内
                        if (!target.canonicalPath.startsWith(rootDir.canonicalPath)) {
                            zis.closeEntry()
                            e = zis.nextEntry
                            continue
                        }
                        target.parentFile?.mkdirs()
                        var n = 0L
                        var over = false
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val r = zis.read(buf)
                                if (r <= 0) break
                                n += r
                                if (n > MAX_ENTRY_BYTES || written + n > MAX_TOTAL_BYTES) {
                                    over = true
                                    break
                                }
                                out.write(buf, 0, r)
                            }
                        }
                        if (over) {
                            try {
                                target.delete()
                            } catch (ex: Throwable) {
                                Log.w(TAG, "清理超限文件失败: " + ex.message)
                            }
                            truncated = true
                            break
                        }
                        written += n
                        count++
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "导入失败: " + t.message)
            return false to ("导入失败：" + (t.message ?: "未知错误"))
        }
        OnnxEngine.forgetAll()
        if (truncated) return false to "导入中断：体积超出上限（已写入 $count 个文件）"
        val ok = installed(ctx).isNotEmpty()
        return ok to if (ok) {
            "导入完成，共 $count 个文件（" + mb(dirBytes(rootDir)) + "）"
        } else {
            "写入了 $count 个文件，但没有找到完整的模型目录"
        }
    }

    /** 导出成 zip（换机免重新下载）。 */
    fun exportZip(open: (String) -> java.io.OutputStream?, ctx: Context): Boolean {
        return try {
            val out = open("live-translate-engine-opus-mt-int8.zip") ?: return false
            ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                val base = root(ctx)
                base.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)
                    zos.putNextEntry(ZipEntry(rel.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "导出失败: " + t.message)
            false
        }
    }

    fun mb(bytes: Long): String = when {
        bytes >= 1048576L -> String.format(java.util.Locale.US, "%.0fMB", bytes / 1048576.0)
        bytes >= 1024L -> (bytes / 1024L).toString() + "KB"
        else -> bytes.toString() + "B"
    }
}