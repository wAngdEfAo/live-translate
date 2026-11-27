package com.shishifanyi.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
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
 * 模型不打进 APK（单方向 encoder 53MB + decoder 93MB），而是作为一个 zip 形式的
 * 「引擎包」导入，落在 `filesDir/engine_onnx/`：
 *
 * ```
 * engine_onnx/manifest.json          { "format":1, "packs":[{ "pair":"en_zh", "files":{...} }] }
 * engine_onnx/packs/en_zh/encoder.onnx
 * engine_onnx/packs/en_zh/decoder.onnx
 * engine_onnx/packs/en_zh/tokenizer.json
 * ```
 *
 * 导入流程刻意做成「先解到临时目录 → 校验清单里的 size + sha256 → 再整体替换」：
 * 直接往目标目录写，一旦中途失败（空间不足 / 取消 / zip 截断）就会留下
 * 「旧 encoder + 新 decoder」这种混合状态，而 App 还会认为已安装。
 */
object EnginePack {
    private const val TAG = "EnginePack"
    private const val DIR = "engine_onnx"
    private const val PACKS = "packs"
    private const val MANIFEST = "manifest.json"

    /** 单条目 / 总量上限：模型最大的文件约 93MB，留足余量但不给爆盘机会。 */
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    /** 导入时给自己留的余量，低于这个值就当作空间不足。 */
    private const val SPACE_MARGIN = 32L * 1024 * 1024

    private val REQUIRED = listOf("encoder.onnx", "decoder.onnx", "tokenizer.json")

    @Volatile
    private var manifestCache: Map<String, Map<String, Long>>? = null

    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    private fun packsDir(ctx: Context): File = File(root(ctx), PACKS)

    fun pairKey(src: String, tgt: String): String? = when {
        src == "en" && tgt == "zh" -> "en_zh"
        src == "zh" && tgt == "en" -> "zh_en"
        else -> null
    }

    fun pairDir(ctx: Context, key: String): File = File(packsDir(ctx), key)

    /**
     * 该方向的模型是否齐全。
     * 有清单时连大小一起核对 —— 半截 decoder 不能被当成「已安装」，
     * 否则 ORT 建会话报错，屏幕就会整片留白。
     */
    fun isInstalled(ctx: Context, key: String): Boolean {
        val d = pairDir(ctx, key)
        if (!d.isDirectory) return false
        for (n in REQUIRED) {
            val f = File(d, n)
            if (!f.isFile || f.length() <= 0) return false
        }
        val sizes = manifestSizes(ctx)[key] ?: return true
        for (n in REQUIRED) {
            val want = sizes[n] ?: return false
            if (File(d, n).length() != want) {
                Log.w(TAG, "$key/$n 大小不符（期望 $want，实际 " + File(d, n).length() + "）")
                return false
            }
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

    /** 清单里记的每方向文件大小（文件名 → 字节数）；没有清单就返回空表。 */
    private fun manifestSizes(ctx: Context): Map<String, Map<String, Long>> {
        manifestCache?.let { return it }
        val out = HashMap<String, Map<String, Long>>()
        try {
            val f = File(root(ctx), MANIFEST)
            if (f.isFile) {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                val packs = json.optJSONArray("packs")
                if (packs != null) {
                    for (i in 0 until packs.length()) {
                        val p = packs.optJSONObject(i) ?: continue
                        val pair = p.optString("pair", "")
                        if (pair.isEmpty()) continue
                        val files = p.optJSONObject("files") ?: continue
                        val m = HashMap<String, Long>()
                        val it = files.keys()
                        while (it.hasNext()) {
                            val name = it.next()
                            m[name] = files.optJSONObject(name)?.optLong("size", -1L) ?: -1L
                        }
                        out[pair] = m
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读清单失败: " + t.message)
        }
        manifestCache = out
        return out
    }

    private fun clearManifestCache() {
        manifestCache = null
    }

    /** 删除某个方向（腾出空间）。 */
    fun remove(ctx: Context, key: String): Boolean {
        return try {
            val d = pairDir(ctx, key)
            val ok = !d.exists() || d.deleteRecursively()
            if (ok) {
                OnnxEngine.forget(key)
                clearManifestCache()
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "删除失败: " + t.message)
            false
        }
    }

    /**
     * 把 zip 里的条目名规整成 `packs/<key>/...` 或 `manifest.json`。
     * 兼容三种打包方式：`packs/en_zh/...`、`en_zh/...`、外面多套一层文件夹。
     * 认不出的条目返回 null（跳过），避免往磁盘上乱写。
     */
    private fun normalizeEntry(raw: String): String? {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.contains("..")) return null
        if (name == MANIFEST) return MANIFEST
        val segs = name.split('/')
        val pi = segs.indexOf(PACKS)
        if (pi >= 0 && pi + 2 < segs.size && segs[pi + 1] in setOf("en_zh", "zh_en")) {
            return segs.drop(pi).joinToString("/")
        }
        val ki = segs.indexOfFirst { it == "en_zh" || it == "zh_en" }
        if (ki >= 0 && ki + 1 < segs.size) {
            return (listOf(PACKS) + segs.drop(ki)).joinToString("/")
        }
        return null
    }

    /**
     * 从 zip 导入模型包：解到临时目录 → 校验 → 整体替换。
     * @return (成功, 给用户看的说明)
     */
    fun importZip(ctx: Context, open: () -> java.io.InputStream?): Pair<Boolean, String> {
        val rootDir = root(ctx)
        if (!rootDir.exists() && !rootDir.mkdirs()) return false to "无法创建模型目录"
        manifestSizes(ctx)
        val avail = try {
            rootDir.usableSpace
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
        val staging = File(rootDir, ".staging-" + System.currentTimeMillis())
        if (!staging.mkdirs()) return false to "无法创建临时目录"

        var count = 0
        var written = 0L
        var skipped = 0
        try {
            val raw = open() ?: return false to "读不到文件"
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    val rel = if (!e.isDirectory) normalizeEntry(e.name) else null
                    if (rel == null) {
                        if (!e.isDirectory) skipped++
                        zis.closeEntry()
                        e = zis.nextEntry
                        continue
                    }
                    val target = File(staging, rel)
                    target.parentFile?.mkdirs()
                    var n = 0L
                    var over = false
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val r = zis.read(buf)
                            if (r <= 0) break
                            n += r
                            if (n > MAX_ENTRY_BYTES || written + n > MAX_TOTAL_BYTES ||
                                written + n + SPACE_MARGIN > avail
                            ) {
                                over = true
                                break
                            }
                            out.write(buf, 0, r)
                        }
                    }
                    if (over) {
                        val why = if (written + n + SPACE_MARGIN > avail) "剩余空间不足" else "超出体积上限"
                        Log.w(TAG, "导入中止：$why（已写 $written B）")
                        staging.deleteRecursively()
                        return false to ("导入失败：$why（需要约 " +
                            mb(written + n + SPACE_MARGIN) + " 可用空间）")
                    }
                    written += n
                    count++
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }

            if (count == 0) {
                staging.deleteRecursively()
                return false to "这个 zip 里没有模型文件（跳过 $skipped 个条目）"
            }

            // 校验：清单存在就逐文件核 size + sha256
            val stagedManifest = File(staging, MANIFEST)
            if (stagedManifest.isFile) {
                val problems = verifyAgainstManifest(staging, stagedManifest)
                if (problems.isNotEmpty()) {
                    staging.deleteRecursively()
                    return false to ("模型包校验失败：" + problems.joinToString("；"))
                }
            } else {
                Log.w(TAG, "模型包没有 manifest.json，跳过完整性校验")
            }

            // 整体替换：每个方向先删旧的，再把临时目录挪过去
            val promoted = ArrayList<String>(2)
            val stagedPacks = File(staging, PACKS)
            for (k in listOf("en_zh", "zh_en")) {
                val src = File(stagedPacks, k)
                if (!src.isDirectory) continue
                var complete = true
                for (n in REQUIRED) if (!File(src, n).isFile) complete = false
                if (!complete) continue
                val dest = pairDir(ctx, k)
                if (dest.exists() && !dest.deleteRecursively()) {
                    Log.w(TAG, "旧目录删不掉：$k")
                    continue
                }
                dest.parentFile?.mkdirs()
                if (!src.renameTo(dest)) {
                    // 跨设备/异常情况：退回复制
                    src.copyRecursively(dest, overwrite = true)
                    src.deleteRecursively()
                }
                promoted.add(k)
            }
            if (promoted.isEmpty()) {
                staging.deleteRecursively()
                return false to "写入了 $count 个文件，但没有一个方向是完整的（已回滚）"
            }
            // 清单一起搬过去，下次 isInstalled 就能核对大小
            if (stagedManifest.isFile) {
                stagedManifest.copyTo(File(rootDir, MANIFEST), overwrite = true)
            }
            clearManifestCache()
            OnnxEngine.forgetAll()
            val total = installed(ctx)
            val names = total.joinToString("、") { pairName(it.first) + " " + mb(it.second) }
            return true to "导入完成：$names" + if (skipped > 0) "（跳过 $skipped 个无关条目）" else ""
        } catch (t: Throwable) {
            Log.w(TAG, "导入失败: " + t.message)
            staging.deleteRecursively()
            return false to ("导入失败：" + (t.message ?: "未知错误"))
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** 按清单核对 size 与 sha256，返回问题列表（空表示通过）。 */
    private fun verifyAgainstManifest(staging: File, manifestFile: File): List<String> {
        val problems = ArrayList<String>(2)
        try {
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val packs = json.optJSONArray("packs") ?: return problems
            for (i in 0 until packs.length()) {
                val p = packs.optJSONObject(i) ?: continue
                val pair = p.optString("pair", "")
                val dir = File(staging, "$PACKS/$pair")
                if (pair.isEmpty() || !dir.isDirectory) continue
                val files = p.optJSONObject("files") ?: continue
                val it = files.keys()
                while (it.hasNext()) {
                    val name = it.next()
                    val meta = files.optJSONObject(name) ?: continue
                    val f = File(dir, name)
                    if (!f.isFile) {
                        problems.add("$pair/$name 缺失")
                        continue
                    }
                    val wantSize = meta.optLong("size", -1L)
                    if (wantSize >= 0 && f.length() != wantSize) {
                        problems.add("$pair/$name 大小不符")
                        continue
                    }
                    val wantHash = meta.optString("sha256", "")
                    if (wantHash.isNotEmpty()) {
                        val got = sha256(f)
                        if (!got.equals(wantHash, ignoreCase = true)) problems.add("$pair/$name 哈希不符")
                    }
                }
            }
        } catch (t: Throwable) {
            problems.add("清单解析失败：" + (t.message ?: "?"))
        }
        return problems
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
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

    fun pairName(key: String): String = when (key) {
        "en_zh" -> "英→中"
        "zh_en" -> "中→英"
        else -> key
    }

    /** 导出成 zip（换机免重新下载）。 */
    fun exportZip(open: (String) -> java.io.OutputStream?, ctx: Context): Boolean {
        return try {
            val out = open("live-translate-engine-opus-mt-int8.zip") ?: return false
            val base = root(ctx)
            ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                base.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)
                    if (rel.startsWith(".")) return@forEach   // 跳过临时目录
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