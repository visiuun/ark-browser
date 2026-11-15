package dev.vis.ark.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import dev.vis.ark.data.Prefs
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Owns the single GeckoRuntime (Firefox engine) for the process. */
object Engine {
    private const val TAG = "ArkEngine"
    lateinit var runtime: GeckoRuntime
        private set
    val isReady get() = ::runtime.isInitialized

    var helper: WebExtension? = null
        private set
    private val waiting = mutableListOf<(WebExtension) -> Unit>()

    /** Installed user extensions (uBlock Origin etc). */
    val extensions = mutableStateListOf<WebExtension>()

    data class Doh(val name: String, val uri: String?)
    val DOH = listOf(
        Doh("Off (system DNS)", null),
        Doh("Cloudflare", "https://mozilla.cloudflare-dns.com/dns-query"),
        Doh("Quad9", "https://dns.quad9.net/dns-query"),
        Doh("Mullvad (ad-block)", "https://base.dns.mullvad.net/dns-query"),
        Doh("AdGuard (ad-block)", "https://dns.adguard-dns.com/dns-query"),
        Doh("NextDNS", "https://firefox.dns.nextdns.io/"),
    )
    /** Prefs.doh value meaning "use the resolver in Prefs.dohCustom". */
    val DOH_CUSTOM get() = DOH.size

    /** Whether a typed resolver address looks usable: DNS over HTTPS needs an https:// URL with a host. */
    fun validDoh(url: String): Boolean = runCatching {
        val u = java.net.URI(url.trim())
        u.scheme.equals("https", true) && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    /** The resolver in use (null = system DNS) and its display name. */
    fun dohUri(): String? =
        if (Prefs.doh == DOH_CUSTOM) Prefs.dohCustom.trim().takeIf { validDoh(it) }
        else DOH.getOrElse(Prefs.doh) { DOH[0] }.uri

    fun dohName(): String =
        if (Prefs.doh == DOH_CUSTOM) "Custom · " + (runCatching { java.net.URI(Prefs.dohCustom.trim()).host }.getOrNull() ?: Prefs.dohCustom)
        else DOH.getOrElse(Prefs.doh) { DOH[0] }.name

    fun init(ctx: Context) {
        if (isReady) return
        val doh = dohUri()
        val builder = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(Prefs.javascript)
            .contentBlocking(contentBlocking())
            .preferredColorScheme(colorScheme())
            .forceUserScalableEnabled(Prefs.forceZoom)
            .remoteDebuggingEnabled(Prefs.remoteDebugging)
            .fontSizeFactor(Prefs.fontScale)
            .consoleOutput(false)
            .aboutConfigEnabled(true)
            .webManifest(true)
            .loginAutofillEnabled(true)
            .translationsOfferPopup(true)
            .globalPrivacyControlEnabled(Prefs.gpc)
            .doubleTapZoomingEnabled(true)
            .inputAutoZoomEnabled(true)
            .extensionsWebAPIEnabled(true)
            .extensionsProcessEnabled(true)
            .allowInsecureConnections(if (Prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
        if (doh != null) builder.trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST).trustedRecursiveResolverUri(doh)
        runtime = GeckoRuntime.create(ctx.applicationContext, builder.build())
        // Extensions (uBlock Origin compiling its filter lists on first run) must never be killed by the
        // slow-script watchdog; on slower phones that would leave the ad blocker half-started.
        for (pref in listOf("dom.max_ext_content_script_run_time", "dom.max_chrome_script_run_time")) runCatching {
            org.mozilla.geckoview.GeckoPreferenceController.setGeckoPref(pref, 0, org.mozilla.geckoview.GeckoPreferenceController.PREF_BRANCH_USER)
        }

        // DNS: on Android GeckoView also asks the system resolver for each site's HTTPS record and waits up to
        // 20 s for it. Many routers, carriers and Private DNS servers silently drop those queries, which made
        // every new site (search results included) hang, and sometimes fail as "unknown host". Ark doesn't
        // need them, so skip them; if anything turns them back on, give up after 2 s instead of 20.
        // With Secure DNS on, a lookup the DoH server can't answer falls back to the system resolver.
        val user = org.mozilla.geckoview.GeckoPreferenceController.PREF_BRANCH_USER
        runCatching { org.mozilla.geckoview.GeckoPreferenceController.setGeckoPref("network.dns.native_https_query", false, user) }
        runCatching { org.mozilla.geckoview.GeckoPreferenceController.setGeckoPref("network.dns.native_https_timeout_android", 2000, user) }
        runCatching { org.mozilla.geckoview.GeckoPreferenceController.setGeckoPref("network.trr.strict_native_fallback", false, user) }
        if (dev.vis.ark.BuildConfig.DEBUG) org.mozilla.geckoview.GeckoPreferenceController.getGeckoPrefs(
            listOf("network.dns.native_https_query", "network.dns.native_https_timeout_android", "network.trr.strict_native_fallback"),
        ).accept { l -> l?.forEach { Log.d(TAG, "pref $it") } }
        val ctrl = runtime.webExtensionController
        ctrl.promptDelegate = object : WebExtensionController.PromptDelegate {
            // Installs are always user initiated from our UI, so accept the permission prompt.
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<out String>, origins: Array<out String>, dataCollectionPermissions: Array<out String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))

            override fun onUpdatePrompt(
                extension: WebExtension, newPermissions: Array<out String>, newOrigins: Array<out String>, newDataCollectionPermissions: Array<out String>,
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> = GeckoResult.allow()

            override fun onOptionalPrompt(
                extension: WebExtension, permissions: Array<out String>, origins: Array<out String>, dataCollectionPermissions: Array<out String>,
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> = GeckoResult.allow()
        }
        ctrl.ensureBuiltIn("resource://android/assets/ark/", "ark-helper@vis.dev").accept({ ext ->
            if (ext != null) {
                helper = ext
                ext.setMessageDelegate(Fire.background, "arkbg")
                waiting.forEach { it(ext) }
                waiting.clear()
            }
        }, { e -> Log.e(TAG, "helper extension failed", e) })
        refreshExtensions()
    }

    // ---------------------------------------------------------------- ad blocker (bundled uBlock Origin)

    /**
     * The user agent websites see: Firefox's own (mobile, or desktop for "Desktop site"),
     * with "Ark/<version>" appended — the same way Edge adds "Edg/".
     */
    fun userAgent(desktop: Boolean): String {
        val v = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION.substringBefore('.') + ".0"
        val base = if (desktop) "Mozilla/5.0 (X11; Linux x86_64; rv:$v) Gecko/20100101 Firefox/$v"
            else GeckoSession.getDefaultUserAgent()
        return if (Prefs.uaArk) "$base Ark/${dev.vis.ark.BuildConfig.VERSION_NAME}" else base
    }

    const val UBO_ID = "uBlock0@raymondhill.net"
    private const val UBO_AMO = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    val adBlocker: WebExtension? get() = extensions.firstOrNull { it.id == UBO_ID }
    var adBlockInstalling = androidx.compose.runtime.mutableStateOf(false)

    /** Installs the bundled, Mozilla-signed uBlock Origin on first run (offline) and keeps it updated. */
    fun ensureAdBlocker(ctx: Context) {
        if (!Prefs.adBlock || adBlockInstalling.value) return
        val ctrl = runtime.webExtensionController
        ctrl.list().accept { list ->
            val ext = list?.firstOrNull { it.id == UBO_ID }
            if (ext == null) { installBundledUbo(ctx); return@accept }
            if (!ext.metaData.enabled) ctrl.enable(ext, WebExtensionController.EnableSource.USER).accept({ refreshExtensions() }, { })
            if (!ext.metaData.allowedInPrivateBrowsing) ctrl.setAllowedInPrivateBrowsing(ext, true)
            val now = System.currentTimeMillis()
            if (now - (Prefs.adBlockChecked.toLongOrNull() ?: 0L) > 24 * 3600_000L) {
                ctrl.update(ext).accept({
                    Prefs.adBlockChecked = now.toString()
                    // An update reloads the add-on; read its state again once it has settled.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshExtensions() }, 5_000)
                }, { })
            }
        }
    }

    private fun installBundledUbo(ctx: Context) {
        adBlockInstalling.value = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        kotlin.concurrent.thread {
            val f = java.io.File(ctx.filesDir, "ublock_origin.xpi")
            runCatching { ctx.assets.open("ublock_origin.xpi").use { i -> f.outputStream().use { i.copyTo(it) } } }
            main.post {
                val ctrl = runtime.webExtensionController
                val done = { ext: WebExtension? ->
                    adBlockInstalling.value = false
                    if (ext != null) ctrl.setAllowedInPrivateBrowsing(ext, true)
                    Prefs.adBlockChecked = System.currentTimeMillis().toString()
                    refreshExtensions()
                }
                ctrl.install("file://" + f.absolutePath, WebExtensionController.INSTALLATION_METHOD_FROM_FILE).accept({ done(it) }, { e ->
                    Log.w(TAG, "bundled uBO install failed, trying AMO", e)
                    ctrl.install(UBO_AMO).accept({ done(it) }, { e2 -> adBlockInstalling.value = false; Log.e(TAG, "uBO install failed", e2) })
                })
            }
        }
    }

    fun setAdBlock(ctx: Context, on: Boolean) {
        Prefs.adBlock = on
        val ext = adBlocker
        val ctrl = runtime.webExtensionController
        when {
            ext == null && on -> ensureAdBlocker(ctx)
            ext != null -> (if (on) ctrl.enable(ext, WebExtensionController.EnableSource.USER) else ctrl.disable(ext, WebExtensionController.EnableSource.USER))
                .accept { refreshExtensions() }
        }
    }

    fun withHelper(cb: (WebExtension) -> Unit) {
        val h = helper
        if (h != null) cb(h) else waiting += cb
    }

    fun refreshExtensions() {
        runtime.webExtensionController.list().accept { list ->
            extensions.clear()
            list?.filter { !it.isBuiltIn }?.let { extensions.addAll(it) }
        }
    }

    fun install(url: String, onDone: (Throwable?) -> Unit) {
        runtime.webExtensionController.install(url).accept({ refreshExtensions(); onDone(null) }, { e -> onDone(e ?: Exception("Install failed")) })
    }

    private fun colorScheme() = when (Prefs.webTheme) {
        1 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        2 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        else -> when (Prefs.themeMode) {
            1 -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            2 -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        }
    }

    private fun contentBlocking(): ContentBlocking.Settings {
        val b = ContentBlocking.Settings.Builder()
        applyCb(
            set = { at, lvl, cookie, social, strip, sb ->
                b.antiTracking(at).enhancedTrackingProtectionLevel(lvl).cookieBehavior(cookie)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .strictSocialTrackingProtection(social).queryParameterStrippingEnabled(strip)
                    .queryParameterStrippingPrivateBrowsingEnabled(true).safeBrowsing(sb)
            }
        )
        return b.build()
    }

    private inline fun applyCb(set: (Int, Int, Int, Boolean, Boolean, Int) -> Unit) {
        val sb = if (Prefs.safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        when (Prefs.etp) {
            0 -> set(ContentBlocking.AntiTracking.NONE, ContentBlocking.EtpLevel.NONE, ContentBlocking.CookieBehavior.ACCEPT_ALL, false, false, sb)
            2 -> set(ContentBlocking.AntiTracking.STRICT, ContentBlocking.EtpLevel.STRICT, ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS, true, true, sb)
            else -> set(ContentBlocking.AntiTracking.DEFAULT, ContentBlocking.EtpLevel.DEFAULT, ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS, false, false, sb)
        }
    }

    /** Push changed preferences into the live runtime. */
    fun applySettings() {
        if (!isReady) return
        val s = runtime.settings
        s.setJavaScriptEnabled(Prefs.javascript)
        s.setPreferredColorScheme(colorScheme())
        s.setForceUserScalableEnabled(Prefs.forceZoom)
        s.setRemoteDebuggingEnabled(Prefs.remoteDebugging)
        s.setFontSizeFactor(Prefs.fontScale)
        s.setGlobalPrivacyControl(Prefs.gpc)
        s.setAllowInsecureConnections(if (Prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
        val doh = dohUri()
        if (doh != null) {
            s.setTrustedRecursiveResolverUri(doh)
            s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
        } else s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        val cb = s.contentBlocking
        applyCb(set = { at, lvl, cookie, social, strip, sb ->
            cb.setAntiTracking(at); cb.setEnhancedTrackingProtectionLevel(lvl); cb.setCookieBehavior(cookie)
            cb.setStrictSocialTrackingProtection(social); cb.setQueryParameterStrippingEnabled(strip); cb.setSafeBrowsing(sb)
        })
    }
}
