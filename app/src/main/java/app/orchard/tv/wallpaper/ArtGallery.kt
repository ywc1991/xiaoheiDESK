package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private const val TIMEOUT = 10_000
    // A half-downloaded cache file (broken connection) decoded to null and left the saver black for
    // 18 s. Fetch in parallel but check each file is a whole JPEG before it is ever used, and write
    // through a .part file so an interrupted download can never masquerade as a valid cache entry.
    private const val PARALLEL = 6

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
            // One slot per painting, in list order. A slot is an optional Artwork: null when both
            // the cache and the download failed, so one dead link cannot stall the others.
            val slots = coroutineScope {
                val gate = Semaphore(PARALLEL)
                (0 until arr.length()).map { i -> async<Artwork?>(Dispatchers.IO) {
                    gate.withPermit {
                        val o = arr.getJSONObject(i)
                        val url = o.optString("u")
                        if (url.isEmpty()) return@withPermit null
                        val name = "museum_%02d.jpg".format(i)
                        val file = File(dir, name)
                        // A cached file is only trusted when it decodes to a real bitmap. A broken
                        // .part from an earlier crash is deleted and fetched again.
                        if (!wholeImage(file)) {
                            file.delete()
                            if (!download(url, file)) return@withPermit null
                        }
                        if (!wholeImage(file)) { file.delete(); return@withPermit null }
                        val cn = o.optString("tc")
                        val en = o.optString("t")
                        Artwork(
                            file,
                            if (cn.isNotEmpty()) cn else en.ifEmpty { name },
                            o.optString("a"),
                        )
                    }
                } }.map { it.await() }
            }
            slots.forEach { if (it != null) out += it }
            DiagLog.i("$TAG online ready: ${out.size}/${arr.length()}")
        } catch (e: Exception) {
            DiagLog.i("$TAG online failed: ${e.message}")
        }
        out
    }

    /** True only when [file] decodes to a bitmap with real pixels; guards against truncated caches. */
    private fun wholeImage(file: File): Boolean {
        if (!file.exists() || file.length() < 2_000L) return false
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            o.outWidth > 0 && o.outHeight > 0
        } catch (e: Exception) {
            false
        }
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

    /** Names are derived from a fixed list index, so a cached file always matches its URL.
     *  Writes to a sibling .part and renames only on success, so a broken connection never leaves a
     *  truncated file at the cache path that the next run would trust. */
    private fun download(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        val part = File(dest.parentFile, dest.name + ".part")
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "xiaoheiDESK/1.1.7")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            conn.inputStream.use { input ->
                FileOutputStream(part, false).use { output -> input.copyTo(output, 16 * 1024) }
            }
            // Only publish a file that fully decodes; otherwise drop it and report failure.
            val ok = part.length() > 2_000L && runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(part.path, o)
                o.outWidth > 0 && o.outHeight > 0
            }.getOrDefault(false)
            if (!ok) { part.delete(); return false }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) { part.delete(); return false }
            true
        } catch (e: Exception) {
            part.delete()
            DiagLog.i("$TAG download ${dest.name}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
