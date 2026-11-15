package dev.vis.ark.browser

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.vis.ark.data.Prefs
import dev.vis.ark.net.Net
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension

class Tab(
    val id: Long,
    val private: Boolean,
    initialUrl: String,
    var savedState: String? = null,
    val parentId: Long? = null,
    initialTitle: String = "",
) {
    var session: GeckoSession? = null

    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var security by mutableStateOf<GeckoSession.ProgressDelegate.SecurityInformation?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var themeColor by mutableStateOf<Color?>(null)
    var iconUrl by mutableStateOf<String?>(null)
    var desktop by mutableStateOf(Prefs.desktopDefault)
    var zoom by mutableFloatStateOf(1f)
    var javascript by mutableStateOf(true)
    var trackingProtection by mutableStateOf(true)
    var blockedCount by mutableIntStateOf(0)
    val blockedHosts = mutableStateListOf<String>()
    var translation by mutableStateOf<TranslationsController.SessionTranslation.TranslationState?>(null)
    var translating by mutableStateOf(false)
    var offerTranslate by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var mediaRecording by mutableStateOf(false)
    var lastUsed = System.currentTimeMillis()

    /** Port to our helper content script in the current page. */
    var port: WebExtension.Port? = null
    private var pending: ((JSONObject) -> Unit)? = null

    val host: String get() = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: ""
    val displayTitle: String get() = title.ifBlank { host.ifBlank { url.ifBlank { "New tab" } } }
    val favicon: String? get() = iconUrl ?: host.takeIf { it.isNotBlank() }?.let { Net.faviconFor(it) }
    val isSecure get() = security?.isSecure == true

    fun send(msg: JSONObject) { runCatching { port?.postMessage(msg) } }

    /** Ask the content script for the page's visible text (fallback for page extraction). */
    fun requestText(cb: (JSONObject) -> Unit): Boolean {
        val p = port ?: return false
        pending = cb
        runCatching { p.postMessage(JSONObject().put("type", "extract")) }.onFailure { pending = null; return false }
        return true
    }

    fun onPortMessage(o: JSONObject) {
        when (o.optString("type")) {
            "meta" -> {
                themeColor = parseRgb(o.optString("theme"))
                o.optString("icon").takeIf { it.startsWith("http") }?.let { iconUrl = it }
            }
            "text" -> { pending?.invoke(o); pending = null }
            "login" -> Fire.loginSeen(this, runCatching { Uri.parse(o.optString("url")).host }.getOrNull() ?: host)
        }
    }

    private fun parseRgb(s: String): Color? {
        val m = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)""").find(s) ?: return null
        val (r, g, b) = m.destructured
        val a = m.groupValues[4].toFloatOrNull() ?: 1f
        if (a < 0.5f) return null
        return Color(r.toInt(), g.toInt(), b.toInt())
    }
}
