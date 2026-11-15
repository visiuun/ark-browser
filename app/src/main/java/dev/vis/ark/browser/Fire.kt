package dev.vis.ark.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * Fire mode ("Forget when closed"): every time Ark starts fresh it erases tabs, history, cookies, site
 * storage, caches and permissions — except for sites the user fireproofed, which stay signed in.
 * After a sign-in Ark offers to fireproof the site, like DuckDuckGo.
 */
object Fire {
    private val main = Handler(Looper.getMainLooper())

    /** True while a burn is running; the UI covers itself until it's done. */
    var burning by mutableStateOf(false)
        private set
    private val afterBurn = mutableListOf<() -> Unit>()

    // ---------------------------------------------------------------- sites

    /** Two-label endings that are public suffixes, so "bbc.co.uk" is a site but "co.uk" isn't. */
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "nic", "mil", "ltd", "plc", "sch", "nom", "gen", "biz", "info", "gv", "ad", "lg", "ed", "in")
    private val HOSTED = setOf(
        "github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev", "web.app",
        "firebaseapp.com", "appspot.com", "azurewebsites.net", "cloudfront.net", "glitch.me", "fly.dev", "onrender.com", "wordpress.com", "tumblr.com",
    )

    /** The registrable site of a host ("mail.google.com" → "google.com"). */
    fun site(host: String): String {
        val h = host.lowercase().trim().trim('.').removePrefix("www.")
        if (h.isEmpty() || ':' in h || h.all { it.isDigit() || it == '.' }) return h
        val p = h.split('.')
        if (p.size <= 2) return h
        val last2 = p.takeLast(2).joinToString(".")
        val three = last2 in HOSTED || (p.last().length == 2 && p[p.size - 2] in SECOND_LEVEL)
        return if (three) p.takeLast(3).joinToString(".") else last2
    }

    fun siteOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
        if (u.scheme != "http" && u.scheme != "https") return ""
        return site(u.host ?: return "")
    }

    fun isFireproof(hostOrSite: String) = site(hostOrSite).let { it.isNotBlank() && it in Prefs.fireproof }

    // ---------------------------------------------------------------- burning

    fun whenReady(block: () -> Unit) { if (burning) afterBurn += block else block() }

    /**
     * Erase everything that isn't fireproofed. Tabs and history go immediately; site data is cleared by
     * GeckoView in the background (usually well under a second) while the UI shows "Starting fresh".
     */
    fun burn(onDone: () -> Unit = {}) {
        afterBurn += onDone
        if (burning) return
        burning = true
        val keep = Prefs.fireproof.toSet()

        // Sites that might hold data, gathered before their records go.
        val candidates = HashSet<String>()
        runCatching { HistoryStore.recent(100_000).forEach { candidates += siteOf(it.url) } }
        Browser.tabs.forEach { candidates += siteOf(it.url) }
        candidates += Prefs.touchedSites()

        Browser.wipeTabs()
        HistoryStore.clear()
        Downloads.clear()
        Prefs.forgetZoom { isFireproof(it) }
        runCatching { Browser.app.cacheDir.listFiles()?.filter { it.name.startsWith("capture_") }?.forEach { it.delete() } }

        val sc = Engine.runtime.storageController
        val finish = {
            Prefs.clearTouched()
            keep.forEach { Prefs.touch(it) }
            burning = false
            val cbs = afterBurn.toList(); afterBurn.clear()
            cbs.forEach { runCatching { it() } }
        }
        if (keep.isEmpty()) {
            // Nothing to keep: one global wipe (cookies, storage, cache, permissions, HSTS…).
            sc.clearData(StorageController.ClearFlags.ALL).accept({ main.post(finish) }, { main.post(finish) })
            return
        }
        // Keep fireproofed sites: clear everything else site by site, then the shared caches.
        sc.allPermissions.accept({ perms ->
            perms?.forEach { candidates += siteOf(it.uri) }
            cookieSites { fromCookies ->
                candidates += fromCookies.map { site(it) }
                val doomed = candidates.filter { it.isNotBlank() && it !in keep }.distinct()
                val all = doomed.map { sc.clearDataFromBaseDomain(it, StorageController.ClearFlags.ALL) } +
                    sc.clearData(StorageController.ClearFlags.ALL_CACHES)
                GeckoResult.allOf(all).accept({ main.post(finish) }, { main.post(finish) })
            }
        }, { main.post(finish) })
    }

    // ---------------------------------------------------------------- cookie inventory (helper extension background page)

    private var bgPort: WebExtension.Port? = null
    private val wantSites = mutableListOf<(Set<String>) -> Unit>()

    val background = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            bgPort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val o = message as? JSONObject ?: return
                    if (o.optString("type") != "cookieSites") return
                    val arr = o.optJSONArray("sites")
                    val set = HashSet<String>()
                    if (arr != null) for (i in 0 until arr.length()) arr.optString(i).trim('.').takeIf { it.isNotBlank() }?.let { set += it }
                    val cbs = wantSites.toList(); wantSites.clear()
                    cbs.forEach { it(set) }
                }

                override fun onDisconnect(port: WebExtension.Port) { if (bgPort === port) bgPort = null }
            })
            if (wantSites.isNotEmpty()) ask(port)
        }
    }

    private fun ask(port: WebExtension.Port) { runCatching { port.postMessage(JSONObject().put("type", "cookieSites")) } }

    /** Every site that has a cookie (including third-party, partitioned ones). Gives up after 4 s. */
    private fun cookieSites(cb: (Set<String>) -> Unit) {
        var done = false
        val once: (Set<String>) -> Unit = { s -> if (!done) { done = true; cb(s) } }
        wantSites += once
        main.postDelayed({ wantSites.remove(once); once(emptySet()) }, 4_000)
        bgPort?.let { ask(it) }
    }

    // ---------------------------------------------------------------- "Fireproof this site?"

    private class PendingLogin(val tabId: Long, val sites: MutableSet<String>, val at: Long)
    private var pending: PendingLogin? = null
    /** Sites already asked about since Ark started, so one sign-in flow doesn't ask twice. */
    private val asked = mutableSetOf<String>()

    private val fallback = Runnable { offer(null) }

    /** A password form was submitted on [host] in [t] (from GeckoView's login capture or our content script). */
    fun loginSeen(t: Tab, host: String) {
        if (!Prefs.forget || !Prefs.fireAsk || t.private) return
        val s = site(host)
        if (s.isBlank()) return
        val now = System.currentTimeMillis()
        val p = pending?.takeIf { it.tabId == t.id && now - it.at < 60_000 } ?: PendingLogin(t.id, mutableSetOf(), now).also { pending = it }
        p.sites += s
        // Ask once the page after the sign-in has loaded; single-page apps may never navigate, so also after a pause.
        main.removeCallbacks(fallback)
        main.postDelayed(fallback, 6_000)
    }

    fun onPageStop(t: Tab) {
        val p = pending ?: return
        if (p.tabId != t.id || System.currentTimeMillis() - p.at < 400) return
        offer(t)
    }

    private fun offer(tab: Tab?) {
        val p = pending ?: return
        pending = null
        main.removeCallbacks(fallback)
        if (!Prefs.forget || !Prefs.fireAsk) return
        val t = tab ?: Browser.tabs.firstOrNull { it.id == p.tabId } ?: return
        // Also offer the site the sign-in landed on (signing in to YouTube happens on google.com).
        val sites = (p.sites + siteOf(t.url)).filter { it.isNotBlank() && it !in Prefs.fireproof && it !in Prefs.fireNever && it !in asked }.distinct()
        if (sites.isEmpty()) return
        asked += sites
        Browser.push(Req.Fireproof(sites))
    }
}
