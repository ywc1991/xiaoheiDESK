package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/**
 * [high] and [low] are today's, shown under the large clock; null in a reading saved by an older
 * version. [code] is Open-Meteo's raw WMO weather code, kept so the forecast panel can name the
 * exact condition (e.g. 61 light rain vs 65 heavy rain) and pick how many raindrops to draw.
 */
data class WeatherNow(
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
    val high: Int? = null,
    val low: Int? = null,
    val code: Int = 0,
)

/** One hour of today, from the current hour on. */
data class HourPoint(val hour: Int, val celsius: Int, val kind: WeatherKind, val code: Int = 0)

/** One day of the week ahead. [date] is ISO, "2026-10-22". */
data class DayPoint(val date: String, val max: Int, val min: Int, val kind: WeatherKind, val code: Int = 0)

/** Today hour by hour, and the coming week. Shown when the weather is selected on Home. */
data class WeatherForecast(val hours: List<HourPoint>, val days: List<DayPoint>)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * A home network can drop one host for a while without dropping the rest: on 11-14 the TV timed
 * out during Open-Meteo's TLS handshake while NTP and smart-home push also failed. So the
 * forecast is read from Open-Meteo first and, if that fails, from Norway's MET Locationforecast
 * (api.met.no, also keyless and reachable from China). MET's timeseries is converted to the
 * same JSON shape Open-Meteo returns, so the parsing below and the weather UI stay unchanged.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. Long enough to survive a night offline. */
    private const val STALE_MS = 12 * 60 * 60 * 1000L
    /** MET asks clients to cache; never hit it twice inside this window. */
    private const val METNO_CACHE_MS = 10 * 60 * 1000L
    private const val METNO_URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    private const val METNO_UA = "xiaoheiDESK/1.0.5 (https://github.com/ywc1991/xiaoheiDESK)"
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    /** Today's hours and the coming week, once the weather has been selected on Home. */
    var forecast by mutableStateOf<WeatherForecast?>(null); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null
    private var forecastJob: Job? = null
    /** Last MET response, already converted; shared by the clock and the forecast panel. */
    @Volatile
    private var metNoCache: Pair<Long, String>? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
                code = prefs.getInt("code", 0),
            )
        }
    }

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("code", reading.code)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                }
                // The panel's forecast is not persisted. If it was lost to an earlier outage,
                // fetch it again now that the network is answering.
                if (forecast == null) loadForecast()
            }
        }
    }

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        val (lat, lon) = coordinates()
        val primary = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        val json = JSONObject(forecastJson(primary, lat, lon))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            code = current.getInt("weather_code"),
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
        )
    }

    /**
     * The place to ask about: the one named on the phone page, or a guess from the network's public
     * address (ipinfo.io, no key, its "loc" being "lat,lon"), re-guessed once a day. Off the main
     * thread.
     */
    private fun coordinates(): Pair<Double, Double> {
        var lat = prefs.getFloat("lat", Float.NaN).toDouble()
        var lon = prefs.getFloat("lon", Float.NaN).toDouble()
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat.toFloat(), lon.toFloat())?.let { lat = it.lat; lon = it.lon }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            val guess = locate()
            if (guess != null) {
                lat = guess.lat.toDouble()
                lon = guess.lon.toDouble()
                val city = guess.city
                // The services name the city in English ("Foshan"); in China, say it as the owner would.
                // A name the table spells otherwise, or a district's, stayed in pinyin among Chinese
                // text (Hisense 43E3G, issue #1195): then the main listed place at that point names it.
                val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                    ?.let {
                        (Cities.near(app, it, lat, lon, km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat, lon, km = 20.0))?.name
                    }
                prefs.edit {
                    putFloat("lat", lat.toFloat()); putFloat("lon", lon.toFloat())
                    putString("place", zh ?: city)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        return lat to lon
    }

    /**
     * Today hour by hour from now on, plus the coming week. Fetched when the weather is selected on
     * Home; the old reading stays up until the new one arrives. Off the main thread.
     */
    fun loadForecast() {
        forecastJob?.cancel()
        forecastJob = scope.launch {
            val f = runCatching { withContext(Dispatchers.IO) { fetchForecast() } }
                .onFailure { Log.w(TAG, "could not fetch the forecast", it); failed = true }
                .getOrNull() ?: return@launch
            forecast = f
        }
    }

    private fun fetchForecast(): WeatherForecast {
        val (lat, lon) = coordinates()
        val primary = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=weather_code&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=7"
        val json = JSONObject(forecastJson(primary, lat, lon))
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        // Anchor to the current hour in the place's own timezone. Open-Meteo answers in local time
        // (timezone=auto), which may differ from the device clock, so read "now" from the same
        // source as the hourly times. Floor to the hour so the first cell is the current hour.
        val currentTime = json.optJSONObject("current")?.optString("time")?.takeIf { it.length >= 13 }
        val threshold = currentTime?.substring(0, 13)?.plus(":00")
        // The next 12 hours from now on, crossing midnight into tomorrow when the evening runs out.
        val hours = mutableListOf<HourPoint>()
        for (i in 0 until times.length()) {
            val t = times.getString(i)
            if (threshold != null && t < threshold) continue
            hours.add(HourPoint(t.substring(11, 13).toInt(), temps.getDouble(i).roundToInt(), kindOf(codes.getInt(i)), codes.getInt(i)))
            if (hours.size >= 12) break
        }
        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val dMax = daily.getJSONArray("temperature_2m_max")
        val dMin = daily.getJSONArray("temperature_2m_min")
        val dCodes = daily.getJSONArray("weather_code")
        val days = (0 until dTimes.length()).map { i ->
            DayPoint(
                date = dTimes.getString(i),
                max = dMax.getDouble(i).roundToInt(),
                min = dMin.getDouble(i).roundToInt(),
                kind = kindOf(dCodes.getInt(i)),
                code = dCodes.getInt(i),
            )
        }
        return WeatherForecast(hours, days)
    }

    /**
     * The forecast as the Open-Meteo JSON both callers already parse. A short timeout on the
     * primary host keeps a brief outage from stalling the clock; MET then answers instead.
     */
    private fun forecastJson(primary: String, lat: Double, lon: Double): String {
        val own = runCatching { get(primary, connectMs = 5_000, readMs = 8_000) }
            .onFailure { Log.i(TAG, "Open-Meteo unavailable (${it.javaClass.simpleName})") }
            .getOrNull()
        // A captive portal or an error page is 200 with HTML; do not hand that to the parser.
        if (own != null && own.trimStart().startsWith("{") && own.contains("\"current\"")) return own
        Log.i(TAG, "using met.no fallback")
        return metNoAsOpenMeteo(lat, lon)
    }

    /** MET's Locationforecast, reshaped once and reused by both readers for ten minutes. */
    private fun metNoAsOpenMeteo(lat: Double, lon: Double): String {
        metNoCache?.let { (at, json) ->
            if (System.currentTimeMillis() - at < METNO_CACHE_MS) return json
        }
        val json = convertMetNo(readMetNo("$METNO_URL?lat=$lat&lon=$lon"))
        metNoCache = System.currentTimeMillis() to json
        return json
    }

    /** MET requires a recognisable User-Agent and answers 403 without one. */
    private fun readMetNo(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", METNO_UA)
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * MET reports a UTC timeseries (hourly at first, then six-hourly) with a symbol code per
     * period. Turn it into the current/hourly/daily object Open-Meteo sends, in the device's
     * local time, so the existing parsing and the hourly threshold keep working unchanged.
     */
    private fun convertMetNo(raw: String): String {
        val series = JSONObject(raw).getJSONObject("properties").getJSONArray("timeseries")
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        data class P(val at: LocalDateTime, val temp: Double, val code: Int)
        val points = ArrayList<P>(series.length())
        for (i in 0 until series.length()) {
            val e = series.getJSONObject(i)
            val at = Instant.parse(e.getString("time")).atZone(zone).toLocalDateTime()
            val data = e.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details")
                .optDouble("air_temperature", Double.NaN)
            if (temp.isNaN()) continue
            val symbol = data.optJSONObject("next_1_hours")?.optJSONObject("summary")
                ?.optString("symbol_code")
                ?: data.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
                ?: "cloudy"
            points.add(P(at, temp, metNoCode(symbol)))
        }
        if (points.isEmpty()) throw IllegalArgumentException("empty MET timeseries")

        val hourNow = LocalDateTime.now(zone).withMinute(0).withSecond(0).withNano(0)
        val fromNow = points.filter { !it.at.isBefore(hourNow) }
            .ifEmpty { listOf(points.first()) }.take(24)

        val hTime = JSONArray(); val hTemp = JSONArray(); val hCode = JSONArray()
        for (p in fromNow) {
            hTime.put(p.at.format(fmt)); hTemp.put(p.temp); hCode.put(p.code)
        }

        val byDay = LinkedHashMap<String, MutableList<P>>()
        for (p in points) byDay.getOrPut(p.at.toLocalDate().toString()) { mutableListOf() }.add(p)
        val dTime = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray(); val dCode = JSONArray()
        for ((date, list) in byDay.entries.take(7)) {
            dTime.put(date)
            dMax.put(list.maxOf { it.temp }); dMin.put(list.minOf { it.temp })
            dCode.put(list.first().code)
        }

        val first = fromNow.first()
        val current = JSONObject()
            .put("time", first.at.format(fmt))
            .put("temperature_2m", first.temp)
            .put("weather_code", first.code)
            .put("is_day", if (hourNow.hour in 6..17) 1 else 0)
        return JSONObject()
            .put("current", current)
            .put("hourly", JSONObject().put("time", hTime)
                .put("temperature_2m", hTemp).put("weather_code", hCode))
            .put("daily", JSONObject().put("time", dTime).put("weather_code", dCode)
                .put("temperature_2m_max", dMax).put("temperature_2m_min", dMin))
            .toString()
    }

    /** MET's symbol codes are names; the WMO codes below are what the UI expects. */
    private fun metNoCode(symbol: String): Int = when {
        symbol.startsWith("clearsky") -> 0
        symbol.startsWith("fair") -> 1
        symbol.startsWith("partlycloudy") -> 2
        symbol.startsWith("cloudy") -> 3
        symbol.startsWith("fog") -> 45
        symbol.contains("thunder") -> 95
        symbol.contains("sleet") -> 56
        symbol.contains("heavyrain") -> 65
        symbol.contains("rain") -> 61
        symbol.contains("heavysnow") -> 75
        symbol.contains("snow") -> 71
        else -> 3
    }

    /**
     * Where the connection is: ipwho.is first, ipinfo.io after it. ipinfo.io puts China Mobile's
     * pooled addresses in the wrong province (a Foshan, Guangdong line came out as Jiaxing,
     * Zhejiang), where ipwho.is names the city; both are keyless and answer over HTTPS.
     */
    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    private fun locate(): Guess? {
        // Over IPv6 the databases place China Mobile's prefixes in another city of the province
        // (a Foshan line came out as Jiangmen), so the IPv4 address is asked for and looked up.
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(
                    ip.getDouble("latitude").toFloat(),
                    ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("country_code").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }
        runCatching {
            // ipinfo.io: no key, and its "loc" is "lat,lon".
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(
                loc[0].toFloat(),
                loc[1].toFloat(),
                ip.optString("city").ifBlank { null },
                ip.optString("country") == "CN",
            )
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }
        return null
    }

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    // Towns before villages of the same name.
                    val best = all.firstOrNull { it.optString("feature_code").startsWith("PPLA") || it.optString("feature_code") == "PPLC" } ?: all.firstOrNull()
                    best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String, connectMs: Int = 8_000, readMs: Int = 10_000): String = try {
        read(url, connectMs, readMs)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"), connectMs, readMs)
    }

    private fun read(url: String, connectMs: Int = 8_000, readMs: Int = 10_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
