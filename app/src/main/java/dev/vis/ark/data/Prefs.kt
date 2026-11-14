package dev.vis.ark.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class Pin(val url: String, val title: String)

/** App settings, backed by SharedPreferences and exposed as Compose state. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE)
        loadPins()
        loadZoom()
    }

    private class Pref<T>(val key: String, val def: T) : ReadWriteProperty<Any?, T> {
        private val state by lazy {
            @Suppress("UNCHECKED_CAST")
            val v: T = when (def) {
                is Boolean -> sp.getBoolean(key, def) as T
                is Int -> sp.getInt(key, def) as T
                is Float -> sp.getFloat(key, def) as T
                is String -> (sp.getString(key, def) ?: def) as T
                else -> def
            }
            mutableStateOf(v)
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            sp.edit {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }

    // Appearance
    var themeMode by Pref("themeMode", 0)          // 0 system, 1 light, 2 dark
    var oled by Pref("oled", false)
    var dynamicColor by Pref("dynamicColor", true)
    var webTheme by Pref("webTheme", 0)            // 0 follow app, 1 light, 2 dark

    // Search
    var searchEngine by Pref("searchEngine", "duckduckgo")
    var suggestions by Pref("suggestions", true)

    // Home
    var homeMode by Pref("homeMode", 0)            // 0 Ark home, 1 blank (logo), 2 a page
    var homeUrl by Pref("homeUrl", "")
    var homeWeather by Pref("homeWeather", true)
    var homeWiki by Pref("homeWiki", true)
    var homeHN by Pref("homeHN", true)
    var homeRecent by Pref("homeRecent", true)
    var fahrenheit by Pref("fahrenheit", false)
    var clock by Pref("clock", 0)                  // 0 follow system, 1 12-hour, 2 24-hour
    var weatherCity by Pref("weatherCity", "")
    var wikiLang by Pref("wikiLang", "en")

    // Privacy & security
    var etp by Pref("etp", 1)                      // 0 off, 1 standard, 2 strict
    var httpsOnly by Pref("httpsOnly", false)
    var gpc by Pref("gpc", true)
    var doh by Pref("doh", 0)                      // index into Engine.DOH
    var safeBrowsing by Pref("safeBrowsing", true)
    var adBlock by Pref("adBlock", true)
    var adBlockChecked by Pref("adBlockChecked", "0")

    // Sites
    var javascript by Pref("javascript", true)
    var forceZoom by Pref("forceZoom", true)
    var autoplay by Pref("autoplay", 0)            // 0 allow all, 1 block audible, 2 block all
    var desktopDefault by Pref("desktopDefault", false)
    var fontScale by Pref("fontScale", 1f)
    var remoteDebugging by Pref("remoteDebugging", false)
    var uaArk by Pref("uaArk", true)                // append "Ark/<version>" to the user agent
    var aiSummary by Pref("aiSummary", 0)          // 0 ask, 1 use AI (Pollinations), 2 on-device only

    // Tabs
    var restoreTabs by Pref("restoreTabs", true)
    var askedNotifications by Pref("askedNotifications", false)
    var autoUpdate by Pref("autoUpdate", true)
    var updateLastCheck by Pref("updateLastCheck", "0")

    // Raw storage helpers
    fun getString(key: String): String? = sp.getString(key, null)
    fun putString(key: String, value: String?) = sp.edit { putString(key, value) }

    // Pins
    val pins = mutableStateListOf<Pin>()

    private fun loadPins() {
        pins.clear()
        val arr = runCatching { JSONArray(sp.getString("pins", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            pins += Pin(o.getString("url"), o.optString("title"))
        }
        if (!sp.contains("pins")) {
            pins += listOf(
                Pin("https://en.wikipedia.org/", "Wikipedia"),
                Pin("https://www.openstreetmap.org/", "OpenStreetMap"),
                Pin("https://news.ycombinator.com/", "Hacker News"),
                Pin("https://github.com/", "GitHub"),
            )
            savePins()
        }
    }

    private fun savePins() {
        val arr = JSONArray()
        pins.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
        sp.edit { putString("pins", arr.toString()) }
    }

    fun isPinned(url: String) = pins.any { sameUrl(it.url, url) }
    fun togglePin(url: String, title: String): Boolean {
        val existing = pins.firstOrNull { sameUrl(it.url, url) }
        return if (existing != null) {
            pins.remove(existing); savePins(); false
        } else {
            pins.add(Pin(url, title.ifBlank { url })); savePins(); true
        }
    }

    fun removePin(pin: Pin) { pins.remove(pin); savePins() }

    private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    // Per-site zoom
    private val zoom = mutableMapOf<String, Float>()
    private fun loadZoom() {
        val o = runCatching { JSONObject(sp.getString("zoom", "{}")!!) }.getOrDefault(JSONObject())
        o.keys().forEach { zoom[it] = o.getDouble(it).toFloat() }
    }

    fun zoomFor(host: String) = zoom[host] ?: 1f
    fun setZoom(host: String, z: Float) {
        if (z == 1f) zoom.remove(host) else zoom[host] = z
        val o = JSONObject(); zoom.forEach { (k, v) -> o.put(k, v.toDouble()) }
        sp.edit { putString("zoom", o.toString()) }
    }
}
