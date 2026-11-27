package com.shishifanyi.app

/**
 * ML Kit 在本地支持的 59 种语言，以及这个 App 的两个目标语言。
 *
 * 排序原则：常用语言在前，保证老用户升级后位置不变；其余按字母序补在后面。
 *
 * 阿拉伯语、希伯来语、波斯语、乌尔都语为右起文字，混排时需单独处理，
 * 否则译文块会左右颠倒。目前覆盖层按左起统一排版，右起语言的展示顺序以原文框为准。
 */
object Languages {

    data class Lang(val code: String, val name: String)

    /** 只翻成这两种。 */
    const val TARGET_ZH = "zh"
    const val TARGET_EN = "en"

    val ALL: List<Lang> = listOf(
        // 原有 12 种，保持在前
        Lang("en", "英语"),
        Lang("ja", "日语"),
        Lang("ko", "韩语"),
        Lang("fr", "法语"),
        Lang("de", "德语"),
        Lang("es", "西班牙语"),
        Lang("ru", "俄语"),
        Lang("pt", "葡萄牙语"),
        Lang("it", "意大利语"),
        Lang("th", "泰语"),
        Lang("vi", "越南语"),
        Lang("ar", "阿拉伯语"),
        // 本次补齐
        Lang("af", "南非荷兰语"),
        Lang("be", "白俄罗斯语"),
        Lang("bg", "保加利亚语"),
        Lang("bn", "孟加拉语"),
        Lang("ca", "加泰罗尼亚语"),
        Lang("cs", "捷克语"),
        Lang("cy", "威尔士语"),
        Lang("da", "丹麦语"),
        Lang("el", "希腊语"),
        Lang("eo", "世界语"),
        Lang("et", "爱沙尼亚语"),
        Lang("fa", "波斯语"),
        Lang("fi", "芬兰语"),
        Lang("ga", "爱尔兰语"),
        Lang("gl", "加利西亚语"),
        Lang("gu", "古吉拉特语"),
        Lang("hi", "印地语"),
        Lang("hr", "克罗地亚语"),
        Lang("ht", "海地克里奥尔语"),
        Lang("hu", "匈牙利语"),
        Lang("id", "印尼语"),
        Lang("is", "冰岛语"),
        Lang("iw", "希伯来语"),
        Lang("ka", "格鲁吉亚语"),
        Lang("kn", "卡纳达语"),
        Lang("lt", "立陶宛语"),
        Lang("lv", "拉脱维亚语"),
        Lang("mk", "马其顿语"),
        Lang("mr", "马拉地语"),
        Lang("ms", "马来语"),
        Lang("mt", "马耳他语"),
        Lang("nl", "荷兰语"),
        Lang("no", "挪威语"),
        Lang("pl", "波兰语"),
        Lang("ro", "罗马尼亚语"),
        Lang("sk", "斯洛伐克语"),
        Lang("sl", "斯洛文尼亚语"),
        Lang("sq", "阿尔巴尼亚语"),
        Lang("sv", "瑞典语"),
        Lang("sw", "斯瓦希里语"),
        Lang("ta", "泰米尔语"),
        Lang("te", "泰卢固语"),
        Lang("tl", "菲律宾语"),
        Lang("tr", "土耳其语"),
        Lang("uk", "乌克兰语"),
        Lang("ur", "乌尔都语"),
        Lang("zh", "中文")
    )

    private val BY_CODE: Map<String, Lang> = ALL.associateBy { it.code }


    fun name(code: String): String = BY_CODE[code]?.name ?: code


    /** 选定目标语言后，可以作为源语言的语言（排除目标自身）。 */
    fun sourcesFor(target: String): List<Lang> = ALL.filter { it.code != target }

    /**
     * 翻译需要经过哪些模型。
     *
     * ML Kit 的模型清单是「英语枢纽」结构：只有 en→zh 和一批 X↔en，
     * 所以绝大多数语言对要拆成两跳。返回的列表按顺序执行，
     * 例如 ja→zh 返回 [ja-en, en-zh]。
     */
    fun route(src: String, tgt: String): List<Pair<String, String>> {
        if (src == tgt) return emptyList()
        // 直连：英语 ↔ 中文
        if (src == TARGET_EN || tgt == TARGET_EN) return listOf(src to tgt)
        return listOf(src to TARGET_EN, TARGET_EN to tgt)
    }

    /** 这条路要走几个模型（1 跳 or 2 跳）。 */
    fun isRelayed(src: String, tgt: String): Boolean = route(src, tgt).size > 1
}