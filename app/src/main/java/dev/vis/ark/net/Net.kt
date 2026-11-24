package dev.vis.ark.net

import android.graphics.BitmapFactory
import android.text.Html
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * All data comes from free, keyless, open APIs:
 *  - DuckDuckGo autocomplete (suggestions)
 *  - Open-Meteo (weather + geocoding, CC BY 4.0)
 *  - OpenStreetMap Nominatim (reverse geocoding, ODbL)
 *  - Wikipedia REST feed (CC BY-SA)
 *  - Hacker News Firebase API
 */
object Net {
    private const val UA = "ArkBrowser/1.0 (Android; open-source personal browser)"

    // Home screen data is kept for a while, so coming back to the home screen shows it at once instead of
    // spinners and a dozen requests every time.
    private class Memo(val at: Long, val value: Any?)
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Memo>()
    private fun now() = android.os.SystemClock.elapsedRealtime()

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String, ttlMs: Long): T? = memo[key]?.takeIf { now() - it.at < ttlMs }?.value as T?

    private suspend fun <T : Any> memoized(key: String, ttlMs: Long, load: suspend () -> T): T {
        peek<T>(key, ttlMs)?.let { return it }
        return load().also { memo[key] = Memo(now(), it) }
    }

    const val WIKI_TTL = 30 * 60_000L
    const val HN_TTL = 5 * 60_000L
    const val WEATHER_TTL = 10 * 60_000L

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Suggestions ----------
    suspend fun suggestions(q: String): List<String> = runCatching {
        val arr = JSONArray(get("https://duckduckgo.com/ac/?type=list&q=${enc(q)}"))
        val list = arr.getJSONArray(1)
        (0 until list.length()).map { list.getString(it) }.take(6)
    }.getOrDefault(emptyList())

    // ---------- Weather ----------
    data class Hour(val time: String, val temp: Double, val code: Int)
    data class Day(val date: LocalDate, val max: Double, val min: Double, val code: Int, val rainChance: Int)
    data class Weather(
        val place: String, val temp: Double, val feels: Double, val code: Int, val isDay: Boolean,
        val humidity: Int, val wind: Double, val precip: Double, val uv: Double,
        val sunrise: String, val sunset: String, val hours: List<Hour>, val days: List<Day>, val unit: String,
    )

    suspend fun geocode(city: String): Triple<Double, Double, String>? = peek("geo:$city", 24 * 3600_000L) ?: geocodeNow(city)?.also { memo["geo:$city"] = Memo(now(), it) }

    private suspend fun geocodeNow(city: String): Triple<Double, Double, String>? = runCatching {
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${enc(city)}"))
        val r = o.getJSONArray("results").getJSONObject(0)
        Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name"))
    }.getOrNull()

    suspend fun placeName(lat: Double, lon: Double): String? {
        val key = "place:%.2f,%.2f".format(java.util.Locale.ROOT, lat, lon)
        return peek(key, 24 * 3600_000L) ?: placeNameNow(lat, lon)?.also { memo[key] = Memo(now(), it) }
    }

    private suspend fun placeNameNow(lat: Double, lon: Double): String? = runCatching {
        val o = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=$lat&lon=$lon"))
        val a = o.getJSONObject("address")
        listOf("city", "town", "village", "municipality", "county", "state").firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
    }.getOrNull()

    suspend fun weather(lat: Double, lon: Double, place: String, fahrenheit: Boolean): Weather =
        memoized("wx:%.2f,%.2f,$place,$fahrenheit".format(java.util.Locale.ROOT, lat, lon), WEATHER_TTL) { weatherNow(lat, lon, place, fahrenheit) }

    private suspend fun weatherNow(lat: Double, lon: Double, place: String, fahrenheit: Boolean): Weather {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        val o = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon$unit&timezone=auto&forecast_days=6" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,precipitation" +
                    "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max"
            )
        )
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val ht = hourly.getJSONArray("time"); val htemp = hourly.getJSONArray("temperature_2m"); val hcode = hourly.getJSONArray("weather_code")
        val now = cur.getString("time").take(13)
        var start = (0 until ht.length()).firstOrNull { ht.getString(it).take(13) >= now } ?: 0
        val hours = (start until minOf(start + 12, ht.length())).map { Hour(ht.getString(it).substring(11, 16), htemp.getDouble(it), hcode.getInt(it)) }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map {
            Day(
                LocalDate.parse(dt.getString(it)), d.getJSONArray("temperature_2m_max").getDouble(it),
                d.getJSONArray("temperature_2m_min").getDouble(it), d.getJSONArray("weather_code").getInt(it),
                d.getJSONArray("precipitation_probability_max").optInt(it, 0)
            )
        }
        return Weather(
            place = place,
            temp = cur.getDouble("temperature_2m"), feels = cur.getDouble("apparent_temperature"),
            code = cur.getInt("weather_code"), isDay = cur.optInt("is_day", 1) == 1,
            humidity = cur.optInt("relative_humidity_2m"), wind = cur.optDouble("wind_speed_10m"),
            precip = cur.optDouble("precipitation", 0.0), uv = d.getJSONArray("uv_index_max").optDouble(0, 0.0),
            sunrise = d.getJSONArray("sunrise").getString(0).takeLast(5), sunset = d.getJSONArray("sunset").getString(0).takeLast(5),
            hours = hours, days = days, unit = if (fahrenheit) "°F" else "°C",
        )
    }

    fun weatherText(code: Int): String = when (code) {
        0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
        61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
        71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
        80, 81 -> "Rain showers"; 82 -> "Violent showers"; 85, 86 -> "Snow showers"
        95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with hail"; else -> "—"
    }

    // ---------- Wikipedia ----------
    data class WikiArticle(val title: String, val extract: String, val image: String?, val url: String)
    data class WikiFeed(val featured: WikiArticle?, val news: List<Pair<String, String>>, val onThisDay: List<Pair<Int, Pair<String, String>>>, val mostRead: List<WikiArticle>)

    private fun article(o: JSONObject): WikiArticle = WikiArticle(
        title = o.optJSONObject("titles")?.optString("normalized") ?: o.optString("title"),
        extract = o.optString("extract"),
        image = o.optJSONObject("thumbnail")?.optString("source"),
        url = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "",
    )

    suspend fun wikipedia(lang: String): WikiFeed = memoized("wiki:$lang:${LocalDate.now()}", WIKI_TTL) { wikipediaNow(lang) }

    private suspend fun wikipediaNow(lang: String): WikiFeed {
        val d = LocalDate.now()
        val path = "%04d/%02d/%02d".format(d.year, d.monthValue, d.dayOfMonth)
        val o = runCatching { JSONObject(get("https://$lang.wikipedia.org/api/rest_v1/feed/featured/$path")) }
            .getOrElse { JSONObject(get("https://en.wikipedia.org/api/rest_v1/feed/featured/$path")) }
        val featured = o.optJSONObject("tfa")?.let(::article)
        val news = o.optJSONArray("news")?.let { arr ->
            (0 until minOf(arr.length(), 4)).map {
                val n = arr.getJSONObject(it)
                val text = Html.fromHtml(n.optString("story"), Html.FROM_HTML_MODE_COMPACT).toString().trim()
                val link = n.optJSONArray("links")?.optJSONObject(0)?.let(::article)?.url ?: ""
                text to link
            }
        } ?: emptyList()
        val otd = o.optJSONArray("onthisday")?.let { arr ->
            (0 until minOf(arr.length(), 4)).map {
                val e = arr.getJSONObject(it)
                val link = e.optJSONArray("pages")?.optJSONObject(0)?.let(::article)?.url ?: ""
                e.optInt("year") to (e.optString("text") to link)
            }
        } ?: emptyList()
        val mostRead = o.optJSONObject("mostread")?.optJSONArray("articles")?.let { arr ->
            (0 until minOf(arr.length(), 5)).map { article(arr.getJSONObject(it)) }
        } ?: emptyList()
        return WikiFeed(featured, news, otd, mostRead)
    }

    // ---------- Hacker News ----------
    data class Story(val title: String, val url: String, val score: Int, val comments: Int, val by: String, val id: Long)

    suspend fun hackerNews(n: Int = 8): List<Story> = peek("hn:$n", HN_TTL)
        ?: hackerNewsNow(n).also { if (it.isNotEmpty()) memo["hn:$n"] = Memo(now(), it) }

    private suspend fun hackerNewsNow(n: Int): List<Story> = coroutineScope {
        val ids = JSONArray(get("https://hacker-news.firebaseio.com/v0/topstories.json"))
        (0 until minOf(n, ids.length())).map { i ->
            async {
                runCatching {
                    val id = ids.getLong(i)
                    val o = JSONObject(get("https://hacker-news.firebaseio.com/v0/item/$id.json"))
                    Story(
                        o.optString("title"), o.optString("url").ifBlank { "https://news.ycombinator.com/item?id=$id" },
                        o.optInt("score"), o.optInt("descendants"), o.optString("by"), id
                    )
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    // ---------- Images (favicons, thumbnails) ----------
    private val images = LruCache<String, ImageBitmap>(160)
    private val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() // written from several IO threads

    fun cachedImage(url: String): ImageBitmap? = images.get(url)

    suspend fun image(url: String): ImageBitmap? {
        images.get(url)?.let { return it }
        if (url in failed) return null
        if (url.startsWith("fav:")) {
            val host = url.removePrefix("fav:")
            val root = host.split('.').takeLast(2).joinToString(".")
            val candidates = listOf("https://$host/apple-touch-icon.png", "https://$host/favicon.ico", "https://www.$root/apple-touch-icon.png", "https://$root/favicon.ico").distinct()
            for (c in candidates) {
                val img = image(c)
                if (img != null && img.width >= 16) { images.put(url, img); return img }
            }
            failed += url
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", UA)
                c.instanceFollowRedirects = true
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()?.also { images.put(url, it) } ?: run { failed += url; null }
        }
    }

    /** Favicons come straight from the site itself (no third-party icon service). */
    fun faviconFor(host: String) = "fav:$host"
}
