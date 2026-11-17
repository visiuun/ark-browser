package dev.vis.ark.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** Things only the Activity can do. */
interface Host {
    fun requestAndroidPermissions(perms: Array<String>, cb: (Boolean) -> Unit)
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, capture: Boolean, cb: (Array<Uri>?) -> Unit)
    fun openExternal(uri: String): Boolean
    fun setFullscreen(on: Boolean)
    fun lockOrientation(orientation: Int?)
    fun share(text: String?, url: String?)
    fun snack(message: String, action: String? = null, onAction: (() -> Unit)? = null)
}

/** Requests from web content that the UI must answer. */
sealed class Req {
    class Alert(val p: PromptDelegate.AlertPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Button(val p: PromptDelegate.ButtonPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Text(val p: PromptDelegate.TextPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Auth(val p: PromptDelegate.AuthPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Choice(val p: PromptDelegate.ChoicePrompt, val r: GeckoResult<PromptResponse>) : Req()
    class ColorPick(val p: PromptDelegate.ColorPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class DateTime(val p: PromptDelegate.DateTimePrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Popup(val p: PromptDelegate.PopupPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class BeforeUnload(val p: PromptDelegate.BeforeUnloadPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Repost(val p: PromptDelegate.RepostConfirmPrompt, val r: GeckoResult<PromptResponse>) : Req()
    class Permission(val host: String, val perm: PermissionDelegate.ContentPermission, val r: GeckoResult<Int>) : Req()
    class Media(
        val host: String,
        val video: Array<out PermissionDelegate.MediaSource>?,
        val audio: Array<out PermissionDelegate.MediaSource>?,
        val cb: PermissionDelegate.MediaCallback,
    ) : Req()
    class Context(val tab: Tab, val el: ContentDelegate.ContextElement) : Req()
    class Confirm(val title: String, val message: String, val confirm: String, val action: () -> Unit) : Req()
    /** Fire mode: "Fireproof these sites so they stay signed in?" */
    class Fireproof(val sites: List<String>) : Req()
}

object Browser {
    lateinit var app: Context
        private set
    var host: Host? = null
    private val main = Handler(Looper.getMainLooper())

    val tabs = mutableStateListOf<Tab>()
    var currentId by mutableStateOf<Long?>(null)
    val current: Tab? get() = tabs.firstOrNull { it.id == currentId }

    /** Queue of pending web prompts; the UI shows the first one. */
    val prompts = mutableStateListOf<Req>()
    var fullscreen by mutableStateOf(false)
    /** Bumped when a session becomes ready to attach to the GeckoView. */
    var viewEpoch by mutableIntStateOf(0)

    /** The page asked the bottom bar to get out of the way (scrolling down); only honoured with Prefs.autoHideBar. */
    var barHidden by mutableStateOf(false)
    private var scrollTab = -1L
    private var scrollLast = 0
    private var scrollRun = 0

    fun showBar() { barHidden = false; scrollRun = 0 }

    /** Set when a tab's error page asks to edit its address; the UI opens the search sheet prefilled and clears it. */
    var editRequest by mutableStateOf<Long?>(null)

    /** Scrolling down hides the bar, scrolling back up (or reaching the top) brings it back. */
    private fun onScroll(t: Tab, y: Int) {
        if (t.id != currentId) return
        if (scrollTab != t.id) { scrollTab = t.id; scrollLast = y; scrollRun = 0; return }
        val dy = y - scrollLast
        scrollLast = y
        if (dy == 0) return
        if ((dy > 0) != (scrollRun > 0)) scrollRun = 0
        scrollRun += dy
        when {
            y <= 24 -> barHidden = false
            scrollRun > 48 -> barHidden = true
            scrollRun < -48 -> barHidden = false
        }
    }

    private var nextId = System.currentTimeMillis()
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        if (Prefs.restoreTabs && !Prefs.forget) restore()
    }

    fun push(r: Req) { main.post { prompts.add(r) } }
    fun pop(r: Req) { prompts.remove(r) }

    // ---------------------------------------------------------------- tabs

    fun newTab(url: String?, private: Boolean = false, select: Boolean = true, parent: Tab? = null): Tab {
        val t = Tab(nextId++, private, url ?: "", parentId = parent?.id)
        val idx = parent?.let { tabs.indexOf(it) }?.takeIf { it >= 0 }
        if (idx != null) tabs.add(idx + 1, t) else tabs.add(t)
        if (url != null) ensureSession(t).loadUri(url)
        if (select) select(t)
        save()
        return t
    }

    fun ensureSession(t: Tab): GeckoSession {
        t.session?.let { return it }
        val s = createSession(t)
        s.open(Engine.runtime)
        t.session = s
        val state = t.savedState
        if (state != null) {
            runCatching { s.restoreState(GeckoSession.SessionState.fromString(state) ?: error("bad state")) }
                .onFailure { if (t.url.isNotBlank()) s.loadUri(t.url) }
            t.savedState = null
        } else if (t.url.isNotBlank() && t.progress == 0) {
            s.loadUri(t.url)
        }
        return s
    }

    fun select(t: Tab?) {
        val old = current
        if (old != null && old !== t) old.session?.takeIf { it.isOpen }?.let { it.setActive(false); it.setFocused(false) }
        currentId = t?.id
        showBar()
        if (t != null) {
            t.lastUsed = System.currentTimeMillis()
            val s = ensureSession(t)
            if (s.isOpen) s.setActive(true)
            else main.postDelayed({ if (s.isOpen && currentId == t.id) { s.setActive(true); viewEpoch++ } }, 60)
        }
        viewEpoch++
        save()
    }

    fun close(t: Tab, undoable: Boolean = true) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        val wasCurrent = t.id == currentId
        tabs.removeAt(idx)
        val url = t.url; val title = t.title; val priv = t.private
        t.session?.close(); t.session = null
        if (wasCurrent) {
            val parent = tabs.firstOrNull { it.id == t.parentId && it.private == priv }
            val sameKind = tabs.filter { it.private == priv }
            val next = parent ?: sameKind.getOrNull((sameKind.indexOfFirst { tabs.indexOf(it) >= idx }).takeIf { it >= 0 } ?: (sameKind.size - 1))
            select(next)
        }
        save()
        if (undoable && url.isNotBlank()) host?.snack("Closed ${title.ifBlank { url }.take(32)}", "Undo") {
            val r = Tab(nextId++, priv, url, initialTitle = title)
            tabs.add(idx.coerceAtMost(tabs.size), r); select(r)
        }
    }

    fun closeAll(private: Boolean, undoable: Boolean = true) {
        val gone = tabs.filter { it.private == private }
        gone.forEach { it.session?.close(); tabs.remove(it) }
        if (current == null) currentId = null
        viewEpoch++
        save()
        val back = gone.filter { it.url.isNotBlank() }
        if (undoable && back.isNotEmpty()) host?.snack(if (gone.size == 1) "Closed 1 tab" else "Closed ${gone.size} tabs", "Undo") {
            back.forEach { tabs.add(Tab(nextId++, it.private, it.url, initialTitle = it.title).also { n -> n.desktop = it.desktop }) }
            select(tabs.lastOrNull { it.private == private })
        }
    }

    /** Fire mode: drop every tab (normal and private) and the saved tab list, with no undo. */
    fun wipeTabs() {
        tabs.forEach { runCatching { it.session?.close() } }
        tabs.clear()
        prompts.clear()
        currentId = null
        main.removeCallbacks(saver)
        Prefs.putString("tabs", null)
        viewEpoch++
    }

    /** Load what the user typed into the search sheet. */
    fun open(input: String, inNewTab: Boolean, private: Boolean = false) {
        val url = Search.resolve(input)
        val cur = current
        if (inNewTab || cur == null || cur.private != private) newTab(url, private)
        else { ensureSession(cur).loadUri(url); cur.url = url }
    }

    fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.session?.settings?.apply {
            setUserAgentMode(if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            setViewportMode(if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            setUserAgentOverride(Engine.userAgent(on))
        }
        t.session?.reload()
    }

    /** Re-applies the user agent to every open tab (after the Ark-in-user-agent setting changes). */
    fun applyUserAgent() {
        tabs.forEach { t -> t.session?.settings?.setUserAgentOverride(Engine.userAgent(t.desktop)) }
    }

    fun setZoom(t: Tab, z: Float) {
        val v = (Math.round(z * 10f) / 10f).coerceIn(0.3f, 3f)
        t.zoom = v
        Prefs.setZoom(t.host, v)
        t.send(JSONObject().put("type", "zoom").put("value", v.toDouble()))
    }

    // ---------------------------------------------------------------- persistence

    private val saver = Runnable { saveNow() }
    fun save() { main.removeCallbacks(saver); main.postDelayed(saver, 700) }

    fun saveNow() {
        if (!initialized) return
        if (Prefs.forget) { Prefs.putString("tabs", null); return }
        val arr = JSONArray()
        tabs.filter { !it.private && it.url.isNotBlank() }.forEach {
            arr.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("state", it.savedState ?: JSONObject.NULL).put("desktop", it.desktop))
        }
        Prefs.putString("tabs", JSONObject().put("tabs", arr).put("current", currentId ?: -1).toString())
    }

    private fun restore() {
        val raw = Prefs.getString("tabs") ?: return
        runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("tabs")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val t = Tab(j.getLong("id"), false, j.getString("url"), j.optString("state").takeIf { it.isNotBlank() && it != "null" }, initialTitle = j.optString("title"))
                t.desktop = j.optBoolean("desktop")
                tabs += t
                nextId = maxOf(nextId, t.id + 1)
            }
            val cur = o.optLong("current", -1)
            tabs.firstOrNull { it.id == cur }?.let { select(it) }
        }
    }

    // ---------------------------------------------------------------- sessions

    private val internalSchemes = setOf("http", "https", "about", "data", "blob", "file", "resource", "moz-extension", "javascript", "view-source", "chrome")

    private fun hostOf(url: String?) = runCatching { Uri.parse(url).host }.getOrNull() ?: url ?: ""

    private fun createSession(t: Tab): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(t.private)
            .useTrackingProtection(Prefs.etp != 0)
            .suspendMediaWhenInactive(false)
            .userAgentMode(if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentOverride(Engine.userAgent(t.desktop))
            .build()
        val s = GeckoSession(settings)
        t.trackingProtection = Prefs.etp != 0

        s.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url == null) return
                // Our own retry/error pages stand in for the address that failed; keep that address as the tab's URL.
                if (url == t.errorPage) return
                val hostChanged = hostOf(url) != hostOf(t.url)
                t.url = url
                if (!t.private) Prefs.touch(Fire.siteOf(url))
                if (hostChanged) { t.themeColor = null; t.iconUrl = null; t.zoom = Prefs.zoomFor(t.host) }
                save()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { t.canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { t.canForward = canGoForward }

            override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                // "Try again" on an error page: reload with a fresh DNS lookup and connection, not from cache.
                if (request.uri == RETRY_URI) {
                    t.failedUri = null
                    main.post { session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) }
                    return GeckoResult.deny()
                }
                // "Edit address" on an error page: bring back the address bar with the failed address in it.
                if (request.uri == EDIT_URI) {
                    main.post { editRequest = t.id }
                    return GeckoResult.deny()
                }
                val scheme = request.uri.substringBefore(':').lowercase()
                if (scheme in internalSchemes) return GeckoResult.allow()
                val opened = host?.openExternal(request.uri) == true
                if (!opened && scheme == "intent") {
                    runCatching { Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                        .getOrNull()?.let { fb -> main.post { session.loadUri(fb) } }
                } else if (!opened) host?.snack("No app can open this link")
                return GeckoResult.deny()
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val nt = Tab(nextId++, t.private, uri, parentId = t.id)
                val ns = createSession(nt)
                nt.session = ns
                nt.progress = 1
                val idx = tabs.indexOf(t)
                tabs.add(if (idx >= 0) idx + 1 else tabs.size, nt)
                main.post { select(nt) }
                return GeckoResult.fromValue(ns)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                val u = uri ?: ""
                val now = System.currentTimeMillis()
                // A lookup or connection that failed once usually works a moment later (flaky Wi-Fi, a resolver
                // hiccup, a network switch). Retry once by ourselves, bypassing Gecko's cached failure, before
                // showing an error.
                val transient = error.code == WebRequestError.ERROR_UNKNOWN_HOST || error.code == WebRequestError.ERROR_NET_TIMEOUT ||
                    error.code == WebRequestError.ERROR_NET_RESET || error.code == WebRequestError.ERROR_NET_INTERRUPT
                if (transient && u.isNotBlank() && (t.autoRetried != u || now - t.autoRetriedAt > 30_000)) {
                    t.autoRetried = u; t.autoRetriedAt = now
                    main.postDelayed({ if (t.session === session && session.isOpen) session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) }, 700)
                    return GeckoResult.fromValue(retryPage(u).also { t.errorPage = it })
                }
                t.failedUri = u; t.failedAt = now; t.editOffered = false
                if (t.id == currentId) main.post { showBar() }
                return GeckoResult.fromValue(errorPage(u, error).also { t.errorPage = it })
            }
        }

        s.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { onScroll(t, scrollY) }
        }

        s.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // A new load (not the error page that just replaced a failed one) means the error is gone.
                if (t.failedUri != null && url != t.errorPage && (url != t.failedUri || System.currentTimeMillis() - t.failedAt > 1500)) t.failedUri = null
                t.loading = true; t.progress = 8; t.blockedCount = 0; t.blockedHosts.clear(); t.offerTranslate = false
                t.translation = null
                if (t.id == currentId) { showBar(); scrollTab = -1L }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.loading = false; t.progress = 100
                Fire.onPageStop(t)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) { t.progress = progress }
            override fun onSecurityChange(session: GeckoSession, securityInfo: ProgressDelegate.SecurityInformation) { t.security = securityInfo }
            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                t.savedState = sessionState.toString(); save()
            }
        }

        s.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean>? {
                if (t.private) return GeckoResult.fromValue(false)
                if (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL == 0) return GeckoResult.fromValue(false)
                if (flags and (GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR) != 0) return GeckoResult.fromValue(false)
                HistoryStore.visit(url, t.title.takeIf { t.url == url })
                return GeckoResult.fromValue(true)
            }
        }

        s.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                t.title = title ?: ""
                if (!t.private && title != null) HistoryStore.setTitle(t.url, title)
                save()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                t.fullscreen = fullScreen; fullscreen = fullScreen
                host?.setFullscreen(fullScreen)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
                push(Req.Context(t, element))
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val type = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: ""
                if (type.contains("x-xpinstall") || response.uri.substringBefore('?').endsWith(".xpi")) {
                    runCatching { response.body?.close() }
                    push(Req.Confirm("Install extension?", response.uri, "Install") {
                        host?.snack("Installing extension…")
                        Engine.install(response.uri) { e -> host?.snack(if (e == null) "Extension installed" else "Install failed: ${e.message}") }
                    })
                    return
                }
                Downloads.save(app, response, t.private)
            }

            override fun onCloseRequest(session: GeckoSession) { main.post { close(t, undoable = false) } }

            override fun onCrash(session: GeckoSession) = recover(t)
            override fun onKill(session: GeckoSession) = recover(t)

            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                if (t.themeColor == null) manifest.optString("theme_color").takeIf { it.startsWith("#") }?.let {
                    runCatching { t.themeColor = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }
                }
            }
        }

        s.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                t.blockedCount++
                val h = hostOf(event.uri)
                if (h.isNotBlank() && h !in t.blockedHosts) t.blockedHosts.add(h)
            }
        }

        s.permissionDelegate = permissionDelegate(t)
        s.promptDelegate = promptDelegate(t)

        s.setTranslationsSessionDelegate(object : TranslationsController.SessionTranslation.Delegate {
            override fun onOfferTranslate(session: GeckoSession) { t.offerTranslate = true }
            override fun onTranslationStateChange(session: GeckoSession, translationState: TranslationsController.SessionTranslation.TranslationState?) {
                t.translation = translationState
            }
        })

        s.mediaDelegate = object : GeckoSession.MediaDelegate {
            override fun onRecordingStatusChanged(session: GeckoSession, devices: Array<out GeckoSession.MediaDelegate.RecordingDevice>) {
                t.mediaRecording = devices.isNotEmpty()
            }
        }

        Engine.withHelper { ext -> attachHelper(t, s, ext) }
        return s
    }

    private fun attachHelper(t: Tab, s: GeckoSession, ext: WebExtension) {
        s.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                t.port = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        (message as? JSONObject)?.let { t.onPortMessage(it) }
                    }

                    override fun onDisconnect(port: WebExtension.Port) { if (t.port === port) t.port = null }
                })
                val z = Prefs.zoomFor(t.host)
                t.zoom = z
                if (z != 1f) t.send(JSONObject().put("type", "zoom").put("value", z.toDouble()))
            }
        }, "ark")
    }

    private fun recover(t: Tab) {
        main.post {
            val wasCurrent = currentId == t.id
            runCatching { t.session?.close() }
            t.session = null
            t.progress = 0
            if (wasCurrent) { ensureSession(t); viewEpoch++ }
            host?.snack("Page crashed and was reloaded")
        }
    }

    private fun permissionDelegate(t: Tab) = object : PermissionDelegate {
        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) {
            val h = host
            if (permissions.isNullOrEmpty() || h == null) { callback.grant(); return }
            main.post { h.requestAndroidPermissions(arrayOf(*permissions)) { ok -> if (ok) callback.grant() else callback.reject() } }
        }

        override fun onContentPermissionRequest(session: GeckoSession, perm: PermissionDelegate.ContentPermission): GeckoResult<Int>? {
            val allow = GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
            val deny = GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
            when (perm.permission) {
                PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> return allow
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return if (Prefs.autoplay == 2) deny else allow
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return if (Prefs.autoplay == 0) allow else deny
            }
            val r = GeckoResult<Int>()
            push(Req.Permission(hostOf(perm.uri), perm, r))
            return r
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession, uri: String, video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?, callback: PermissionDelegate.MediaCallback,
        ) {
            push(Req.Media(hostOf(uri), video, audio, callback))
        }
    }

    private fun promptDelegate(t: Tab) = object : PromptDelegate {
        private fun <P> ask(make: (GeckoResult<PromptResponse>) -> Req): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            push(make(r))
            return r
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) = ask<Unit> { Req.Alert(prompt, it) }
        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) = ask<Unit> { Req.Button(prompt, it) }
        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) = ask<Unit> { Req.Text(prompt, it) }
        override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt) = ask<Unit> { Req.Auth(prompt, it) }
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) = ask<Unit> { Req.Choice(prompt, it) }
        override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt) = ask<Unit> { Req.ColorPick(prompt, it) }
        override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt) = ask<Unit> { Req.DateTime(prompt, it) }
        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt) = ask<Unit> { Req.Popup(prompt, it) }
        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) = ask<Unit> { Req.BeforeUnload(prompt, it) }
        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt) = ask<Unit> { Req.Repost(prompt, it) }

        // Ark doesn't keep passwords, but a submitted login form is how fire mode knows you just signed in.
        override fun onLoginSave(
            session: GeckoSession, request: PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>,
        ): GeckoResult<PromptResponse>? {
            val origin = runCatching { request.options.firstOrNull()?.value?.origin }.getOrNull()
            main.post { Fire.loginSeen(t, hostOf(origin ?: t.url)) }
            return GeckoResult.fromValue(request.dismiss())
        }

        override fun onSharePrompt(session: GeckoSession, prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
            main.post { host?.share(prompt.text ?: prompt.title, prompt.uri) }
            return GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.SUCCESS))
        }

        override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val h = host ?: return GeckoResult.fromValue(prompt.dismiss())
            val types = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
            main.post {
                h.pickFiles(types, prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE, prompt.capture != PromptDelegate.FilePrompt.Capture.NONE) { uris ->
                    r.complete(
                        when {
                            uris.isNullOrEmpty() -> prompt.dismiss()
                            prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE -> prompt.confirm(app, uris)
                            else -> prompt.confirm(app, uris[0])
                        }
                    )
                }
            }
            return r
        }
    }

    // ---------------------------------------------------------------- error page

    /** Reserved address the error page's "Try again" button points at; onLoadRequest turns it into a fresh reload. */
    private const val RETRY_URI = "https://ark.invalid/retry"
    /** Reserved address the error page's "Edit address" button points at; opens the address bar prefilled. */
    private const val EDIT_URI = "https://ark.invalid/edit"

    private fun hostLabel(uri: String) = runCatching { Uri.parse(uri).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "this site"

    /** Shown for the split second while Ark retries a failed load by itself. */
    private fun retryPage(uri: String): String {
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#fff8f8;color:#6f5a60;font-family:system-ui,sans-serif;font-size:16px}
@media(prefers-color-scheme:dark){body{background:#000;color:#d3c1c6}}</style></head><body>Connecting to ${hostLabel(uri).replace("<", "&lt;")}…</body></html>"""
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    private fun errorPage(uri: String, error: WebRequestError): String {
        val host = hostLabel(uri)
        val dohHint = if (Engine.dohUri() != null) " You're using Secure DNS (${Engine.dohName()}); if this keeps happening, try another provider in Settings." else ""
        val (title, desc) = when {
            error.code == WebRequestError.ERROR_UNKNOWN_HOST -> "Can't find this site" to "Ark couldn't look up $host. Check your connection, or the address may be mistyped.$dohHint"
            error.code == WebRequestError.ERROR_NET_TIMEOUT -> "This site is taking too long" to "$host didn't answer in time. Check your connection and try again."
            error.code == WebRequestError.ERROR_CONNECTION_REFUSED -> "Can't connect" to "$host refused the connection. The site may be down."
            error.code == WebRequestError.ERROR_OFFLINE -> "You're offline" to "Connect to Wi-Fi or mobile data and try again."
            else -> when (error.category) {
            WebRequestError.ERROR_CATEGORY_SECURITY -> "Connection isn't secure" to "The site's security certificate couldn't be verified, so Ark stopped loading it to protect you."
            WebRequestError.ERROR_CATEGORY_NETWORK -> "Can't reach this site" to "Check your connection, or the address may be wrong. The server might also be down."
            WebRequestError.ERROR_CATEGORY_URI -> "Invalid address" to "This web address doesn't look right."
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "Deceptive or harmful site" to "This site was reported as unsafe by Safe Browsing."
            WebRequestError.ERROR_CATEGORY_PROXY -> "Proxy error" to "The proxy server refused the connection."
            else -> "Page couldn't load" to "Something went wrong while loading this page."
            }
        }
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;") }
        val html = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<style>
:root{--bg:#fff8f8;--fg:#22191c;--mut:#6f5a60;--acc:#8e3f63;--chip:#ffd8e6}
@media(prefers-color-scheme:dark){:root{--bg:#000;--fg:#f0dee3;--mut:#d3c1c6;--acc:#ffb0cf;--chip:#3a2530}}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif}
main{padding:32px;max-width:520px}
.blob{width:96px;height:96px;background:var(--chip);border-radius:42% 58% 63% 37%/40% 45% 55% 60%;display:flex;align-items:center;justify-content:center;font-size:44px;margin-bottom:28px}
h1{font-size:34px;line-height:1.1;margin:0 0 14px;font-weight:800;letter-spacing:-.5px}
p{color:var(--mut);font-size:17px;line-height:1.5}
code{display:block;word-break:break-all;color:var(--mut);font-size:13px;margin:18px 0 28px;opacity:.8}
a{display:inline-block;background:var(--acc);color:var(--bg);padding:16px 28px;border-radius:999px;text-decoration:none;font-weight:700;font-size:16px;margin:0 8px 10px 0}
a.alt{background:var(--chip);color:var(--fg)}
</style></head><body><main><div class="blob">⚠︎</div><h1>${esc(title)}</h1><p>${esc(desc)}</p>
<code>${esc(uri)} · error ${error.code}</code><a href="$EDIT_URI" class="alt">Edit address</a><a href="$RETRY_URI">Try again</a></main></body></html>"""
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }
}
