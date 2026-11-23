package app.orchard.tv.util

import java.time.LocalDate

/**
 * Chinese lunar (农历) calendar conversion, valid for 1900–2100. Based on the canonical
 * `lunarInfo` bit-encoded table and day-offset algorithm used by many open-source calendars.
 */
object Lunar {
    // 1900..2099, 16-bit per year: low 4 bits = leap month index (0 = none), bit 0x10000 = leap
    // month length (1 = 30 days), bits 0x8000..0x10 = normal month lengths (1 = 30 days).
    private val lunarInfo = longArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0xf252,
        0x0d520,
    )

    private fun lYearDays(y: Int): Int {
        var sum = 348
        var i = 0x8000L
        while (i > 0x8) {
            if (lunarInfo[y - 1900] and i != 0L) sum += 1
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    private fun leapDays(y: Int): Int {
        return if (leapMonth(y) != 0) {
            if (lunarInfo[y - 1900] and 0x10000L != 0L) 30 else 29
        } else 0
    }

    /** The leap month of lunar year [y] (1-12), or 0 if none. */
    fun leapMonth(y: Int): Int = (lunarInfo[y - 1900] and 0xf).toInt()

    private fun monthDays(y: Int, m: Int): Int {
        return if (lunarInfo[y - 1900] and (0x10000L shr m) != 0L) 30 else 29
    }

    /** Lunar date for a Gregorian [date]. */
    fun toLunar(date: LocalDate): LunarDate {
        val base = LocalDate.of(1900, 1, 31)
        var offset = (date.toEpochDay() - base.toEpochDay()).toInt()
        var ly = 1900
        while (ly < 2101) {
            val temp = lYearDays(ly)
            if (offset < temp) break
            offset -= temp
            ly++
        }
        val leap = leapMonth(ly)
        var isLeap = false
        var lm = 1
        while (lm < 13) {
            // The month's length is looked at before it is taken off the offset: the day is the
            // offset that is left over inside the month, so subtracting first would leave a
            // negative remainder and a negative day.
            val temp = if (leap > 0 && lm == leap + 1 && !isLeap) {
                lm -= 1
                isLeap = true
                leapDays(ly)
            } else {
                monthDays(ly, lm)
            }
            if (offset < temp) break
            offset -= temp
            if (isLeap && lm == leap + 1) isLeap = false
            lm++
        }
        val ld = offset + 1
        return LunarDate(ly, lm, ld, isLeap)
    }

    /** Days in the given Gregorian [year]/[month]. */
    fun daysInMonth(year: Int, month: Int): Int = LocalDate.of(year, month, 1).lengthOfMonth()

    /** 0-based weekday of the 1st of [year]/[month] (0 = Sunday). */
    fun firstDayWeekday(year: Int, month: Int): Int = LocalDate.of(year, month, 1).dayOfWeek.value % 7

    private val GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")
    private val MONTH = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")

    /** 干支年 + 生肖, e.g. "丙午年(马年)". */
    fun ganZhiYear(lunarYear: Int): String {
        val stem = GAN[(lunarYear - 4) % 10]
        val branch = ZHI[(lunarYear - 4) % 12]
        return "$stem${branch}年(${ZODIAC[(lunarYear - 4) % 12]}年)"
    }

    /** Lunar month name, e.g. "九月" or "闰九月". */
    fun monthName(month: Int, isLeap: Boolean): String = (if (isLeap) "闰" else "") + MONTH[month - 1]

    /** Lunar day name, e.g. "初一", "十五", "廿三". */
    fun dayName(day: Int): String {
        val ones = arrayOf("日", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        return when {
            day == 10 -> "初十"
            day == 20 -> "二十"
            day == 30 -> "三十"
            day < 10 -> "初" + ones[day]
            day < 20 -> "十" + ones[day - 10]
            day < 30 -> "廿" + ones[day - 20]
            else -> ""
        }
    }

    /** Full lunar label for a Gregorian [date], e.g. "农历丙午年九月十四". */
    fun fullLabel(date: LocalDate): String {
        val l = toLunar(date)
        return "农历${ganZhiYear(l.year)}${monthName(l.month, l.isLeap)}${dayName(l.day)}"
    }

    // ---- 节日 ----
    // 农历节日：键为「农历月 -> 农历日」，闰月不置节日（传统上节日落在正常月）。
    private val LUNAR_FESTIVALS: Map<Int, Map<Int, String>> = mapOf(
        1 to mapOf(1 to "春节", 15 to "元宵"),
        2 to mapOf(2 to "龙抬头"),
        3 to mapOf(3 to "上巳"),
        5 to mapOf(5 to "端午"),
        7 to mapOf(7 to "七夕", 15 to "中元"),
        8 to mapOf(15 to "中秋"),
        9 to mapOf(9 to "重阳"),
        10 to mapOf(1 to "寒衣", 15 to "下元"),
        12 to mapOf(8 to "腊八", 23 to "小年"),
    )

    // 公历节日：键为「公历月 -> 公历日」。
    private val SOLAR_FESTIVALS: Map<Int, Map<Int, String>> = mapOf(
        1 to mapOf(1 to "元旦"),
        2 to mapOf(14 to "情人节"),
        3 to mapOf(8 to "妇女节", 12 to "植树节"),
        4 to mapOf(1 to "愚人节"),
        5 to mapOf(1 to "劳动节", 4 to "青年节"),
        6 to mapOf(1 to "儿童节"),
        7 to mapOf(1 to "建党节"),
        8 to mapOf(1 to "建军节"),
        9 to mapOf(10 to "教师节"),
        10 to mapOf(1 to "国庆"),
        12 to mapOf(25 to "圣诞"),
    )

    /** 一句简短的节日介绍，键为节日名，未收录则返回 null。给「今日详情」面板用。 */
    private val FESTIVAL_INFO: Map<String, String> = mapOf(
        "春节" to "农历新年的第一天，辞旧迎新、阖家团圆，贴春联、放鞭炮、拜年贺岁。",
        "除夕" to "农历一年的最后一天，全家团聚吃年夜饭、守岁迎新，是春节最隆重的前夜。",
        "元宵" to "农历正月十五，又称上元节，赏花灯、猜灯谜、吃元宵，是春节的收尾。",
        "龙抬头" to "农历二月初二，民间理发祈福、祈求风调雨顺，寓意春回大地、万物复苏。",
        "上巳" to "农历三月初三，古人踏青游春、水边祓禊祈福的传统节日。",
        "清明" to "既是节气也是节日，扫墓祭祖、踏青郊游，追思先人、迎接春光。",
        "端午" to "农历五月初五，纪念爱国诗人屈原，赛龙舟、吃粽子、挂艾草菖蒲。",
        "七夕" to "农历七月初七，传说中牛郎织女鹊桥相会的日子，被称为中国的情人节。",
        "中元" to "农历七月十五，又称鬼节，民间祭祀祖先、追思逝者、祈福平安。",
        "中秋" to "农历八月十五，月圆人团圆，赏月、吃月饼、阖家欢聚。",
        "重阳" to "农历九月初九，登高望远、赏菊饮酒，寓意敬老长寿。",
        "寒衣" to "农历十月初一，天气转冷，民间为逝去的亲人送寒衣、祭祀保暖。",
        "下元" to "农历十月十五，道教「水官解厄」之日，民间祭祀祈福、消灾解厄。",
        "腊八" to "农历腊月初八，喝腊八粥、腌腊八蒜，拉开了过年的序幕。",
        "小年" to "农历腊月二十三，祭灶神、扫尘土、备年货，春节的序曲。",
        "元旦" to "公历新年的第一天，万象更新，人们规划新年、互致祝福。",
        "情人节" to "公历 2 月 14 日，情侣互赠礼物、表达爱意的日子。",
        "妇女节" to "公历 3 月 8 日，国际劳动妇女节，致敬女性、庆祝平等与贡献。",
        "植树节" to "公历 3 月 12 日，倡导植树造林、爱护环境、绿化家园。",
        "愚人节" to "公历 4 月 1 日，朋友间互相开玩笑、轻松逗趣的日子。",
        "劳动节" to "公历 5 月 1 日，国际劳动节，致敬劳动者、庆祝劳动成果。",
        "青年节" to "公历 5 月 4 日，源于五四运动，属于青年人的节日。",
        "儿童节" to "公历 6 月 1 日，国际儿童节，属于孩子们的欢乐节日。",
        "建党节" to "公历 7 月 1 日，中国共产党成立纪念日。",
        "建军节" to "公历 8 月 1 日，中国人民解放军建军纪念日。",
        "教师节" to "公历 9 月 10 日，致敬教师、尊师重教的日子。",
        "国庆" to "公历 10 月 1 日，中华人民共和国国庆节，举国同庆。",
        "圣诞" to "公历 12 月 25 日，源自西方的传统节日，如今也是朋友家人相聚互换礼物的日子。",
    )

    /** 返回节日名对应的一句简介，无收录则返回 null。 */
    fun festivalInfo(name: String): String? = FESTIVAL_INFO[name]

    /** 清明：21 世纪用寿星公式估算（1900–2100 误差极小），返回 4 月的日期。 */
    private fun qingmingDay(year: Int): Int {
        val y = year % 100
        return ((y * 0.2422 + 4.81).toInt()) - (y / 4)
    }

    /** 是否农历除夕（明天是农历正月初一）。 */
    private fun isLunarNewYearEve(date: LocalDate): Boolean {
        val t = toLunar(date.plusDays(1))
        return t.month == 1 && t.day == 1 && !t.isLeap
    }

    /** 返回该公历日期对应的节日名，无则返回 null。优先级：除夕 > 农历节日 > 公历节日 > 清明。 */
    fun festivalFor(date: LocalDate): String? {
        if (isLunarNewYearEve(date)) return "除夕"
        val lunar = toLunar(date)
        if (!lunar.isLeap) LUNAR_FESTIVALS[lunar.month]?.get(lunar.day)?.let { return it }
        SOLAR_FESTIVALS[date.monthValue]?.get(date.dayOfMonth)?.let { return it }
        if (date.monthValue == 4 && date.dayOfMonth == qingmingDay(date.year)) return "清明"
        return null
    }

    /**
     * 日历格子里农历一栏应显示的文字：
     * - 有节日则显示节日名；
     * - 否则农历初一显示「月份名」（如 正月/九月/闰二月）；
     * - 其余显示农历日（如 十四/廿三）。
     */
    fun lunarSubLabel(date: LocalDate): String {
        festivalFor(date)?.let { return it }
        val lunar = toLunar(date)
        if (lunar.day == 1) return monthName(lunar.month, lunar.isLeap)
        return dayName(lunar.day)
    }
}

data class LunarDate(val year: Int, val month: Int, val day: Int, val isLeap: Boolean)
