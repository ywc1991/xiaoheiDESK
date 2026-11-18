package app.orchard.tv.wallpaper

import android.content.Context
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Framed paintings for the screensaver, from two sources: the museum collection baked as a URL
 * list in assets and cached to disk on first run ([online]), or the owner's own images on a USB
 * drive ([usb]). Nothing here needs a special permission to *compile*; the USB path is only
 * readable when the installer has granted MANAGE_EXTERNAL_STORAGE over ADB.
 */
object ArtGallery {
    /** Where the slideshow's pictures come from. */
    enum class Source { Online, Usb }

    data class Artwork(
        val file: File,
        /** Chinese name when known, else the museum's English title, else the file name. */
        val title: String,
        /** Painter / source, shown small under the title. */
        val subtitle: String,
    )

    private const val TAG = "ArtGallery"
    private const val TIMEOUT = 12_000

    /** Where online paintings are cached. Survives screensaver restarts, cleared with app data. */
    private fun cacheDir(context: Context): File = File(context.cacheDir, "artgallery").apply { mkdirs() }

    /**
     * The museum list: a small JSON of {u,t,tc,a} in assets. Each image is downloaded once to the
     * cache with a stable file name; later runs read it straight back (works offline after the
     * first pass). Failures are skipped so one dead link cannot stall the slideshow.
     */
    suspend fun online(context: Context): List<Artwork> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Artwork>()
        try {
            val raw = context.assets.open("art_online.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONObject(raw).getJSONArray("items")
            val dir = cacheDir(context)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("u")
                if (url.isEmpty()) continue
                val name = "museum_%02d.jpg".format(i)
                val file = File(dir, name)
                if (!file.exists() || file.length() < 2_000L) {
                    if (!download(url, file)) continue
                }
                val cn = o.optString("tc")
                val en = o.optString("t")
                out += Artwork(
                    file,
                    if (cn.isNotEmpty()) cn else en.ifEmpty { name },
                    o.optString("a"),
                )
            }
            DiagLog.i("$TAG online ready: ${out.size}")
        } catch (e: Exception) {
            DiagLog.i("$TAG online failed: ${e.message}")
        }
        out
    }

    /** Mirror the owner's images on any mounted USB volume, skipping macOS junk files. */
    suspend fun usb(): List<Artwork> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Artwork>()
        try {
            val roots = File("/storage").listFiles()?.filter {
                it.isDirectory && it.name != "emulated" && it.name != "self"
            } ?: emptyList()
            val imageExt = setOf("jpg", "jpeg", "png", "webp")
            val found = mutableListOf<File>()
            for (root in roots) {
                root.walkTopDown().maxDepth(3).forEach { f ->
                    if (!f.isFile || f.length() < 8_000L) return@forEach
                    if (f.name.startsWith(".") || f.name.startsWith("._")) return@forEach
                    if (f.parentFile?.name?.startsWith(".") == true) return@forEach
                    if (f.extension.lowercase() !in imageExt) return@forEach
                    found += f
                }
            }
            // Stable order so left/right does not reshuffle on every entry.
            found.sortBy { it.name }
            for (f in found) {
                val base = f.nameWithoutExtension
                // Museum file names are "Painter_-_Title_-_Google_Art_Project"; show the human part.
                val title = base.replace("_", " ")
                    .replace(Regex("\\s+"), " ")
                    .removeSuffix(" Google Art Project")
                    .trim()
                    .ifEmpty { f.name }
                out += Artwork(f, title, "U 盘 · USB")
            }
            DiagLog.i("$TAG usb ready: ${out.size}")
        } catch (e: Exception) {
            DiagLog.i("$TAG usb failed: ${e.message}")
        }
        out
    }

    /** Names are derived from a fixed list index, so a cached file always matches its URL. */
    private fun download(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "xiaoheiDESK/1.1.6")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            conn.inputStream.use { input ->
                FileOutputStream(dest, false).use { output -> input.copyTo(output, 16 * 1024) }
            }
            dest.length() > 2_000L
        } catch (e: Exception) {
            dest.delete()
            DiagLog.i("$TAG download ${dest.name}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
