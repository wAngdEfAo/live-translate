package com.shishifanyi.app

/**
 * 「精翻这段」的耗时样本，按方向分组，进程级保存。
 *
 * - 放在对象里而不是 Activity 字段：切主题 `recreate()`、旋转重建都不会归零
 * - 按 `en_zh` / `zh_en` 分开统计：两个方向的模型不同，混在一起的中位数没有意义
 * - 每个样本带输入字符数：5 token 的短句与 40 token 的长句差一个数量级，
 *   不带长度就没法跨机型/跨版本比。要用同一句比，用 [BENCH]。
 * - 只在本进程内有效；「关于 → 复制诊断信息」会把它带上，不必手抄
 */
object Latency {
    private const val MAX = 7

    /** 固定的基准句（~20 词 / 110 字符），跨版本对比统一用它。 */
    const val BENCH =
        "The committee postponed the vote because several members asked for more time to review the proposed budget."

    data class Sample(val ms: Long, val chars: Int)

    private val samples = LinkedHashMap<String, ArrayList<Sample>>()

    fun record(pair: String, ms: Long, chars: Int) {
        val list = samples.getOrPut(pair) { ArrayList() }
        list.add(Sample(ms, chars))
        while (list.size > MAX) list.removeAt(0)
    }

    /**
     * 中位数。样本 ≥4 时**先去掉最大值**再取中位数：
     * 一次调度抖动或一次含加载的样本就足以把 5 个样本的中位数带偏，
     * 去掉最大值是代价最小的稳健化。
     */
    fun median(pair: String): Long {
        val list = samples[pair] ?: return 0L
        if (list.isEmpty()) return 0L
        val ms = list.map { it.ms }.sorted()
        val trimmed = if (ms.size >= 4) ms.dropLast(1) else ms
        return trimmed[trimmed.size / 2]
    }

    fun min(pair: String): Long = samples[pair]?.minOfOrNull { it.ms } ?: 0L

    fun max(pair: String): Long = samples[pair]?.maxOfOrNull { it.ms } ?: 0L

    fun count(pair: String): Int = samples[pair]?.size ?: 0

    fun lastChars(pair: String): Int = samples[pair]?.lastOrNull()?.chars ?: 0

    fun clear(pair: String) {
        samples[pair]?.clear()
    }

    /** 给诊断信息用：每个方向一行，顺序固定（LinkedHashMap 按首次记录的顺序）。 */
    fun summary(): String {
        if (samples.values.all { it.isEmpty() }) return "高精度延迟：尚无样本"
        val sb = StringBuilder("高精度延迟（最多 $MAX 次，中位数已去掉最大值）:")
        for ((pair, list) in samples) {
            if (list.isEmpty()) continue
            val ms = list.map { it.ms }.sorted()
            val trimmed = if (ms.size >= 4) ms.dropLast(1) else ms
            sb.append("\n  ").append(pair)
                .append(": n=").append(list.size)
                .append(" min=").append(ms.first()).append("ms")
                .append(" median=").append(trimmed[trimmed.size / 2]).append("ms")
                .append(" max=").append(ms.last()).append("ms")
                .append(" 输入字符=").append(list.joinToString(",") { it.chars.toString() })
                .append(" 样本ms=").append(list.joinToString(",") { it.ms.toString() })
        }
        return sb.toString()
    }
}