package com.shishifanyi.app

/**
 * 「精翻这段」的耗时样本，按方向分组，进程级保存。
 *
 * - 放在对象里而不是 Activity 字段：切主题 `recreate()`、旋转重建都不会归零
 * - 按 `en_zh` / `zh_en` 分开统计：两个方向的模型不同，混在一起的中位数没有意义
 * - 只在本进程内有效（不算持久证据）；「关于 → 复制诊断信息」会把它带上，
 *   这样把结果发出去时不必手抄
 */
object Latency {
    private const val MAX = 5
    private val samples = HashMap<String, ArrayList<Long>>()

    fun record(pair: String, ms: Long) {
        val list = samples.getOrPut(pair) { ArrayList() }
        list.add(ms)
        while (list.size > MAX) list.removeAt(0)
    }

    fun median(pair: String): Long {
        val list = samples[pair] ?: return 0L
        if (list.isEmpty()) return 0L
        val s = list.sorted()
        return s[s.size / 2]
    }

    fun count(pair: String): Int = samples[pair]?.size ?: 0

    fun clear(pair: String) {
        samples[pair]?.clear()
    }

    /** 给诊断信息用：每个方向一行。 */
    fun summary(): String {
        if (samples.isEmpty()) return "高精度延迟：尚无样本"
        val sb = StringBuilder("高精度延迟（近 ${MAX} 次中位数）:")
        for ((pair, list) in samples) {
            if (list.isEmpty()) continue
            val s = list.sorted()
            sb.append("\n  ").append(pair).append(": 中位数 ").append(s[s.size / 2]).append("ms")
                .append(" / 样本 ").append(list.joinToString(",")).append("ms")
        }
        return sb.toString()
    }
}