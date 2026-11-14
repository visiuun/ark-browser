package dev.vis.ark.browser

import android.util.Patterns
import dev.vis.ark.data.Prefs
import java.net.URLEncoder

object Search {
    data class EngineDef(val id: String, val name: String, val template: String)

    val engines = listOf(
        EngineDef("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        EngineDef("google", "Google", "https://www.google.com/search?q=%s"),
        EngineDef("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        EngineDef("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
        EngineDef("bing", "Bing", "https://www.bing.com/search?q=%s"),
        EngineDef("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
        EngineDef("kagi", "Kagi", "https://kagi.com/search?q=%s"),
        EngineDef("qwant", "Qwant", "https://www.qwant.com/?q=%s"),
        EngineDef("wikipedia", "Wikipedia", "https://en.wikipedia.org/wiki/Special:Search?search=%s"),
    )

    val engine get() = engines.firstOrNull { it.id == Prefs.searchEngine } ?: engines[0]

    fun searchUrl(q: String) = engine.template.replace("%s", URLEncoder.encode(q, "UTF-8"))

    private val passthrough = listOf("http:", "https:", "about:", "file:", "data:", "view-source:", "moz-extension:", "resource:", "javascript:", "blob:")

    fun isUrlLike(input: String): Boolean {
        val t = input.trim()
        if (t.contains(' ')) return false
        if (passthrough.any { t.startsWith(it, ignoreCase = true) }) return true
        if (t.startsWith("localhost")) return true
        return Patterns.WEB_URL.matcher(t).matches() && t.contains('.')
    }

    /** Turns whatever the user typed into a loadable URL. */
    fun resolve(input: String): String {
        val t = input.trim()
        if (passthrough.any { t.startsWith(it, ignoreCase = true) } && !t.contains(' ')) return t
        if (isUrlLike(t)) return if (t.startsWith("localhost") || Regex("""^\d+\.\d+\.\d+\.\d+""").containsMatchIn(t)) "http://$t" else "https://$t"
        return searchUrl(t)
    }

    /** If url is a search results page of the current engine, returns the query. */
    fun queryOf(url: String): String? = runCatching {
        val u = android.net.Uri.parse(url)
        val prefix = engine.template.substringBefore("%s").substringBefore("?")
        if (!url.startsWith(prefix)) return null
        u.getQueryParameter("q") ?: u.getQueryParameter("search")
    }.getOrNull()
}
