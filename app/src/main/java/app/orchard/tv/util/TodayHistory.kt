package app.orchard.tv.util

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * "历史上的今天": a small, offline dataset bundled in assets/history_today.json, distilled from
 * the Wikipedia-sourced TodayInHistory collection (MIT). One entry per calendar day, up to three
 * notable events each, weighted towards modern times. No network, no key — the calendar's detail
 * panel opens instantly even on a TV that has been offline for days.
 *
 * Parsed once on first use and cached. [eventsFor] returns an empty list for any date it cannot
 * find, so the caller simply hides the section.
 */
object TodayHistory {
    data class Event(val year: Int, val text: String)

    @Volatile
    private var cache: Map<String, List<Event>>? = null

    private fun load(context: Context): Map<String, List<Event>> {
        cache?.let { return it }
        val root = try {
            context.assets.open("history_today.json").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            return emptyMap()
        }
        val json = runCatching { JSONObject(root) }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, List<Event>>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = json.optJSONArray(key) ?: continue
            val events = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                val year = e.optInt(0, Int.MIN_VALUE)
                val text = e.optString(1)
                if (year == Int.MIN_VALUE || text.isBlank()) null else Event(year, text)
            }
            if (events.isNotEmpty()) map[key] = events
        }
        cache = map
        return map
    }

    /** Events on the Gregorian [date], regardless of year; empty when none are bundled. */
    fun eventsFor(context: Context, date: LocalDate): List<Event> =
        load(context)["%02d-%02d".format(date.monthValue, date.dayOfMonth)].orEmpty()

    /** The year as a display string: positive as-is, negative as 公元前 N. */
    fun yearLabel(year: Int): String = if (year > 0) "$year 年" else "公元前 ${-year} 年"
}
