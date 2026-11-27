package com.shishifanyi.app

/**
 * 「精翻这段」的耗时样本，按方向分组，进程级保存。
 *
 * 线程约定：所有方法都 `@Synchronized`。当前调用点都在 UI 线程，加锁是为了将来
 * 从后台线程调 [summary]（比如诊断信息改到子线程生成）时不会踩到并发修改。
 *
 * 记录规则：
 * - 只记**解码**耗时（排队与首次加载模型不计入），见 `OnnxEngine.translateFresh(onStart)`
 * - 按 `en_zh` / `zh_en` 分开统计：两个方向的模型不同，混在一起没有意义
 * - 每个样本带输入字符数：5 token 与 40 token 差一个数量级，不带长度无法跨版本比
 * - 最多 7 个样本，样本 ≥4 时去掉最大值再取中位数
 */
object Latency {
    private const val MAX = 7

    /** 英文基准句（~20 词 / 110 字符）。目标=中文时用它。 */
    const val BENCH_EN =
        "The committee postponed the vote because several members asked for more time to review the proposed budget."

    /** 中文基准句（与英文句语义对应，~40 字）。目标=英文时用它。 */
    const val BENCH_ZH =
        "委员会推迟了表决，因为几位成员要求有更多时间审阅这份预算提案，会议因此顺延到下周同一时间继续。"

    /** 按当前目标语言选基准句：翻成英文喂中文句，翻成中文喂英文句。 */
    fun benchFor(tgt: String): String =
        if (tgt == Languages.TARGET_EN) BENCH_ZH else BENCH_EN

    data class Sample(val ms: Long, val chars: Int)

    private val samples = LinkedHashMap<String, ArrayList<Sample>>()

    @Synchronized
    fun record(pair: String, ms: Long, chars: Int) {
        val list = samples.getOrPut(pair) { ArrayList() }
        list.add(Sample(ms, chars))
        while (list.size > MAX) list.removeAt(0)
    }

    @Synchronized
    fun median(pair: String): Long {
        val list = samples[pair] ?: return 0L
        if (list.isEmpty()) return 0L
        val ms = list.map { it.ms }.sorted()
        val trimmed = if (ms.size >= 4) ms.dropLast(1) else ms
        return trimmed[trimmed.size / 2]
    }

    @Synchronized
    fun min(pair: String): Long = samples[pair]?.minOfOrNull { it.ms } ?: 0L

    @Synchronized
    fun max(pair: String): Long = samples[pair]?.maxOfOrNull { it.ms } ?: 0L

    @Synchronized
    fun count(pair: String): Int = samples[pair]?.size ?: 0

    /** 清空某个方向的样本（长按「精翻这段」用）。 */
    @Synchronized
    fun clear(pair: String) {
        samples[pair]?.clear()
    }

    /** 给诊断信息用：每个方向一行，顺序固定（按首次记录的顺序）。 */
    @Synchronized
    fun summary(): String {
        if (samples.values.all { it.isEmpty() }) return "高精度延迟：尚无样本"
        val sb = StringBuilder("高精度延迟（解码耗时，最多 $MAX 次，中位数已去掉最大值）:")
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