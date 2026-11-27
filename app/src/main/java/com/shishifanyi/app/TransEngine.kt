package com.shishifanyi.app

/**
 * 翻译引擎的公共入口。
 *
 * 实时覆盖默认走 ML Kit（快、离线、59 种语言）；「高精度」是可选的第二引擎，
 * 用 OPUS-MT 的 int8 ONNX 模型，只做英⇄中，慢但句子更通顺。
 * 两个引擎都实现这个接口，AppCore 只认接口，按设置挑一个。
 */
interface TransEngine {

    /** 引擎标识，用于日志与状态展示。 */
    val id: String

    /** 命中缓存直接返回，避免重复推理。 */
    fun peek(src: String, tgt: String, text: String): String?

    /** 翻译一段文字；回调只能在主线程之外也不能保证线程，调用方自己 post。 */
    fun translate(src: String, tgt: String, text: String, cb: (err: String?, out: String) -> Unit)
}

/**
 * ML Kit 引擎：现有实时路径，行为与加接口之前完全一致。
 * 这里只是把原来的静态调用包一层，不改变任何逻辑。
 */
object MlKitEngine : TransEngine {
    override val id: String = "mlkit"

    override fun peek(src: String, tgt: String, text: String): String? =
        Translator.peek(src, tgt, text)

    override fun translate(
        src: String,
        tgt: String,
        text: String,
        cb: (err: String?, out: String) -> Unit
    ) {
        Translator.translateWith(src, tgt, text, cb)
    }
}

/**
 * 引擎选择：设置 + 模型是否装好 + 文本长度共同决定。
 *
 * 实时覆盖每秒可能要翻几十块，ONNX 单句几十到几百毫秒，绝不能无脑切过去；
 * 所以默认「仅长句」用高精度，短 UI 文案仍走 ML Kit。
 */
object Engines {
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_ONNX = "onnx"

    /**
     * 整屏一次的判定快照：偏好与"装了哪些模型"在渲染一屏期间不会变，
     * 逐块去读（每块 3 次文件 stat + 3 次偏好）在一屏 30 块时就是上百次系统调用。
     */
    class Reach(
        val onnx: Boolean,
        val installed: Set<String>,
        val longOnly: Boolean,
        val minChars: Int,
        val maxChars: Int
    )

    fun reach(ctx: android.content.Context): Reach {
        val on = Prefs.engine == ENGINE_ONNX
        val pairs = if (on) EnginePack.installed(ctx).map { it.first }.toSet() else emptySet()
        return Reach(on, pairs, Prefs.onnxLongOnly, Prefs.onnxMinChars, Prefs.onnxMaxChars)
    }

    /** 兼容旧调用：没有快照时就地算一次（界面上的自测等低频路径）。 */
    fun pick(src: String, tgt: String, text: String): TransEngine {
        val ctx = AppCore.context() ?: return MlKitEngine
        return pick(reach(ctx), src, tgt, text)
    }

    fun pick(reach: Reach, src: String, tgt: String, text: String): TransEngine {
        if (!reach.onnx) return MlKitEngine
        val key = EnginePack.pairKey(src, tgt) ?: return MlKitEngine
        if (key !in reach.installed) return MlKitEngine
        // 长段落：无 KV cache 的解码是 O(n²)，几千字符必然超预算，直接交给 ML Kit
        if (text.length > reach.maxChars) return MlKitEngine
        if (reach.longOnly && text.length < reach.minChars) return MlKitEngine
        return OnnxEngine
    }
}