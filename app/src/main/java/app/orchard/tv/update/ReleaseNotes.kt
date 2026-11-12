package app.orchard.tv.update

import org.json.JSONObject

/**
 * What a release changed, as the update page shows it. The repo's update/notes.json, and the
 * copy of every release up to this build kept in assets/release-notes.json:
 * {"releases": [{"v": "1.0.1", "zh": [...], "en": [...]}, ...]}, newest first.
 */
data class ReleaseNotes(val version: String, val changes: List<String>)

internal object ReleaseNotesParser {
    private val NEWEST_FIRST = Comparator<ReleaseNotes> { a, b ->
        if (Updates.newer(a.version, b.version)) -1 else if (Updates.newer(b.version, a.version)) 1 else 0
    }

    /**
     * The releases after [current] up to [latest], newest first, in Chinese when [zh] (else English;
     * either falls back to the other where a release has only one). An owner several versions
     * behind sees everything the update brings, not only the latest release's notes.
     */
    fun parse(json: String, current: String, latest: String, zh: Boolean): List<ReleaseNotes> {
        val releases = JSONObject(json).optJSONArray("releases") ?: return emptyList()
        return (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i) ?: return@mapNotNull null
            val v = r.optString("v").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!Updates.newer(v, current) || Updates.newer(v, latest)) return@mapNotNull null
            changes(r, zh)?.let { ReleaseNotes(v, it) }
        }.sortedWith(NEWEST_FIRST)
    }

    /** Every release in the file, newest first: the features of this build shown offline. */
    fun parseAll(json: String, zh: Boolean): List<ReleaseNotes> {
        val releases = JSONObject(json).optJSONArray("releases") ?: return emptyList()
        return (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i) ?: return@mapNotNull null
            val v = r.optString("v").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            changes(r, zh)?.let { ReleaseNotes(v, it) }
        }.sortedWith(NEWEST_FIRST)
    }

    private fun changes(r: JSONObject, zh: Boolean): List<String>? {
        val lines = lines(r, if (zh) "zh" else "en").ifEmpty { lines(r, if (zh) "en" else "zh") }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun lines(r: JSONObject, key: String): List<String> {
        val a = r.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
