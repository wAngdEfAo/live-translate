package com.shishifanyi.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 让 ML Kit 认可"手工导入"的语言包。
 *
 * ML Kit 判断模型可用时并不重新扫目录，而是读两个地方：
 *   1) 私有目录/com.google.mlkit.translate.models/dict.<lang>_<ver>/
 *   2) SharedPreferences 里的 current_model_hash_<模型名>，须等于官方 hash
 * 导入只写文件不写登记，就会判定"未下载"。
 * 这里负责补登记、清登记、以及查出模型名与官方 hash。
 */
object ModelRegistry {

    private const val TAG = "ModelRegistry"

    /** ML Kit 内部偏好文件名。 */
    private const val PREF = "com.google.mlkit.translate.download_manager"

    private fun modelRoot(ctx: Context): File =
        File(ctx.noBackupFilesDir, "com.google.mlkit.translate.models")

    /** 已存在的模型目录名，如 dict.en_zh_25。 */
    fun localModelDirs(ctx: Context): List<String> {
        val root = modelRoot(ctx)
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("dict.") }
            ?.map { it.name }
            ?: emptyList()
    }

    /** 从模型目录名解析语言对，如 dict.en_zh_25 -> en / zh。 */
    fun langsOf(dirName: String): Pair<String, String>? {
        val body = dirName.removePrefix("dict.")
        val parts = body.split('_')
        if (parts.size < 2) return null
        return Pair(parts[0], parts[1])
    }

    /**
     * 在导入的模型里挑一个代表文件算 hash，与官方索引比对。
     * 官方清单以整个下载包的 sha256 为准，所以对单个文件无法核对；
     * 这里只做"结构完整"检查，hash 由调用方另行判定。
     */
    /**
     * 判断导入目录是否是结构完整的模型目录。
     * 以前硬编码「translation_model.pb + 任意 .bipe」，扩展名一换就整包导入静默失效
     * （只留一行日志），所以这里放宽成按已知载荷扩展名匹配。
     */
    fun looksComplete(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, "translation_model.pb").exists()) return true
        var hit: String? = null
        dir.walkTopDown().forEach { f ->
            if (hit != null || !f.isFile) return@forEach
            val n = f.name.lowercase()
            if (f.length() > 1024L && PAYLOAD_EXT.any { n.endsWith(it) }) hit = f.name
        }
        if (hit == null) Log.d(TAG, "目录结构不完整，跳过登记: " + dir.name)
        return hit != null
    }

    /** ML Kit 各版本用过的模型载荷扩展名；新格式不认识时按日志加一项即可。 */
    private val PAYLOAD_EXT =
        listOf(".bipe", ".pb", ".tflite", ".binarypb", ".model", ".bin", ".dat")

    /**
     * 为某个模型目录补写 ML Kit 的登记。
     * @param hash 官方索引里的 HASH；为 null 时跳过登记
     * @return 是否写入成功
     */
    fun register(ctx: Context, dirName: String, hash: String?): Boolean {
        if (hash.isNullOrBlank()) return false
        return try {
            val main = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val editor = main.edit()
            editor.putString("current_model_hash_" + dirName, hash)
            editor.remove("bad_hash_" + dirName)
            editor.remove("downloading_model_hash_" + dirName)
            editor.remove("downloading_model_id_" + dirName)
            editor.remove("downloading_model_type_" + dirName)
            val now = System.currentTimeMillis()
            if (main.getLong("model_first_use_time_" + dirName, 0L) == 0L) {
                editor.putLong("model_first_use_time_" + dirName, now)
            }
            val okWrite = editor.commit()
            Log.d(TAG, "登记 " + dirName + " hash=" + hash.take(12) + "… 写入=" + okWrite)
            okWrite
        } catch (t: Throwable) {
            Log.w(TAG, "写登记失败: " + t.message)
            false
        }
    }

    /** 清除某个模型的登记。 */

    /**
     * 从 app 内打包的官方索引里查 hash。
     * 索引随 mlkit translate 库一起落在 res/raw，这里运行时找不到资源，
     * 因此改用随 App 内置的副本（见 assets 说明）。
     */
    /**
     * 按导入目录的实际体积挑选 PKG_HIGH / PKG_LOW 变体，再取其 HASH。
     * 两档同一语言对的 HASH 与体积都不同，固定取 HIGH 会把 LOW 包登记成损坏。
     * @param localBytes 导入目录的总字节数；传 0 时退回 HIGH。
     */
    fun officialHash(ctx: Context, langPair: String, localBytes: Long = 0L): String? {
        return try {
            ctx.assets.open("translate_models_metadata.json").use { ins ->
                val json = JSONObject(ins.bufferedReader().readText())
                // 收集两档候选：DL_SZ 是**压缩包**体积，本地是**解压后**目录体积，
                // 天然不相等（解压通常更大）。因此先按比例容差筛选，再取最接近的一档。
                data class Cand(val hash: String, val dl: Long, val pkg: String)
                val cands = ArrayList<Cand>()
                for (pkg in PKG_ORDER) {
                    val group = json.optJSONObject(pkg) ?: continue
                    val entry = group.optJSONObject(langPair) ?: continue
                    val h = entry.optString("HASH", "")
                    if (h.isEmpty()) continue
                    cands.add(Cand(h, entry.optLong("DL_SZ", 0L), pkg))
                }
                if (cands.isEmpty()) return null
                if (localBytes <= 0L) {
                    Log.d(TAG, "officialHash($langPair) 无体积信息，取 " + cands.first().pkg)
                    return cands.first().hash
                }

                Log.d(
                    TAG,
                    "officialHash($langPair) 本地解压 " + localBytes + "B，候选：" +
                        cands.joinToString("；") { it.pkg + "=" + it.dl + "B" }
                )

                // 解压体积一般落在压缩体积的 0.5x ~ 2.5x 区间内
                val inRange = cands.filter {
                    localBytes >= (it.dl * 0.5).toLong() && localBytes <= (it.dl * 2.5).toLong()
                }
                val pool = if (inRange.isNotEmpty()) inRange else cands
                val best = pool.minByOrNull { kotlin.math.abs(it.dl - localBytes) } ?: return null
                Log.d(
                    TAG,
                    "officialHash($langPair) 选定 " + best.pkg + "（DL_SZ=" + best.dl + "B，差 " +
                        kotlin.math.abs(best.dl - localBytes) + "B）"
                )
                best.hash
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读官方索引失败: " + t.message)
            null
        }
    }

    /** 变体优先级：高配包优先，没有再退到低配包。顺序写死，不看 JSON 遍历顺序。 */
    private val PKG_ORDER = listOf("PKG_HIGH", "PKG_LOW")

    /** 目录总字节数，用于匹配官方 DL_SZ。 */
    fun dirBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirBytes(it) }
        return sum
    }
}
