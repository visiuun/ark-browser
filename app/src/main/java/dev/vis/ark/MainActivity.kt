package dev.vis.ark

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Downloads
import dev.vis.ark.browser.Engine
import dev.vis.ark.browser.Fire
import dev.vis.ark.browser.Host
import dev.vis.ark.browser.Notifier
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import dev.vis.ark.ui.ArkRoot
import dev.vis.ark.ui.ArkTheme
import dev.vis.ark.ui.Snack
import java.io.File

class MainActivity : ComponentActivity(), Host {
    companion object {
        const val ACTION_WEB_NOTIFICATION = "dev.vis.ark.WEB_NOTIFICATION"
        var snack by mutableStateOf<Snack?>(null)
        /** Text to pre-fill into a freshly opened search sheet (from share / process text). */
        var pendingSearch by mutableStateOf<String?>(null)
        var inPip by mutableStateOf(false)
        /** Set when the "update available" notification is tapped: Root opens Settings. */
        var pendingUpdate by mutableStateOf(false)
        /** App lock: true while Ark is covered and waiting for a fingerprint, face or screen-lock check. */
        var locked by mutableStateOf(false)
    }

    /** A system sheet we opened ourselves (file picker, camera, voice) is up: coming back from it doesn't re-lock. */
    private var ownSheetOpen = false
    private var authInFlight = false
    private var authCb: ((Boolean) -> Unit)? = null
    private val credLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = authCb; authCb = null; authInFlight = false
        cb?.invoke(res.resultCode == RESULT_OK)
    }

    private var permCb: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cb = permCb; permCb = null
        cb?.invoke(res.isNotEmpty() && res.values.all { it })
    }

    private var fileCb: ((Array<Uri>?) -> Unit)? = null
    private var cameraUri: Uri? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        ownSheetOpen = false
        val cb = fileCb; fileCb = null
        val data = res.data
        val uris: Array<Uri>? = when {
            res.resultCode != RESULT_OK -> null
            data?.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraUri != null -> arrayOf(cameraUri!!)
            else -> null
        }
        cameraUri = null
        cb?.invoke(uris)
    }

    private var voiceCb: ((String) -> Unit)? = null
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        ownSheetOpen = false
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) voiceCb?.invoke(text)
        voiceCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        HistoryStore.init(this)
        Engine.init(this)
        Notifier.ensureChannels(this)
        Engine.runtime.webNotificationDelegate = Notifier
        Engine.runtime.orientationController.delegate = object : org.mozilla.geckoview.OrientationController.OrientationDelegate {
            override fun onOrientationLock(aOrientation: Int): org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.AllowOrDeny> {
                lockOrientation(aOrientation); return org.mozilla.geckoview.GeckoResult.allow()
            }

            override fun onOrientationUnlock() = lockOrientation(null)
        }
        Engine.runtime.serviceWorkerDelegate = org.mozilla.geckoview.GeckoRuntime.ServiceWorkerDelegate { url ->
            val t = Browser.newTab(null)
            org.mozilla.geckoview.GeckoResult.fromValue(Browser.ensureSession(t).also { it.loadUri(url) })
        }
        Browser.host = this
        Browser.init(this)
        Engine.ensureAdBlocker(this)
        if (Prefs.appLock) locked = true
        applyRecentsPrivacy()
        // Fire mode: a fresh start (not a re-creation of a running Ark) erases everything that isn't fireproofed.
        if (Prefs.forget && savedInstanceState == null) Fire.burn()
        if (savedInstanceState == null) Fire.whenReady { handleIntent(intent) }
        setContent {
            ArkTheme {
                // Locked: nothing of the browser is composed at all, so no page, sheet or dialog can show through.
                if (!locked) ArkRoot(this)
                androidx.compose.animation.AnimatedVisibility(
                    Fire.burning,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(450)),
                ) { dev.vis.ark.ui.BurnScreen() }
                if (locked) dev.vis.ark.ui.LockScreen(onUnlock = { unlock() }, onLeave = { moveTaskToBack(true) })
            }
        }
    }

    override fun onDestroy() {
        // Leaving Ark for good (swiped away, or closed by the system for the user) burns right away
        // instead of waiting for the next start.
        if (isFinishing && !isChangingConfigurations && Prefs.forget) Fire.burn()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (locked && !authInFlight) window.decorView.post { if (locked && !isFinishing) unlock() }
    }

    override fun onStop() {
        super.onStop()
        if (Prefs.appLock && !authInFlight && !ownSheetOpen && !isChangingConfigurations) locked = true
    }

    /** With the app lock on, Android 13+ shows a blank card for Ark in Recents. */
    fun applyRecentsPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!Prefs.appLock)
    }

    fun canAuthenticate(): Boolean = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    /** Fingerprint, face or the phone's PIN / pattern / password. */
    fun authenticate(title: String, subtitle: String? = null, cb: (Boolean) -> Unit) {
        if (authInFlight) return
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) { cb(false); return }
        authInFlight = true
        if (Build.VERSION.SDK_INT >= 30) {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle(title)
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            if (subtitle != null) b.setSubtitle(subtitle)
            b.build().authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    authInFlight = false; cb(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    authInFlight = false; cb(false)
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent(title, subtitle)
            if (i == null) { authInFlight = false; cb(true); return }
            authCb = cb
            runCatching { credLauncher.launch(i) }.onFailure { authInFlight = false; authCb = null; cb(false) }
        }
    }

    private fun unlock() {
        if (!Prefs.appLock) { locked = false; return }
        // The phone's screen lock was removed, so there's nothing to check against: let the user in.
        if (!canAuthenticate()) { locked = false; snack("App lock is paused: your phone has no screen lock"); return }
        authenticate("Unlock Ark") { ok -> if (ok) locked = false }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Fire.whenReady { handleIntent(intent) }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_VIEW -> i.dataString?.let { Browser.newTab(it) }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> i.getStringExtra("query")?.let { Browser.open(it, inNewTab = true) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                val url = Regex("https?://\\S+").find(txt)?.value
                if (url != null) Browser.newTab(url) else Browser.open(txt, inNewTab = true)
            }
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { Browser.open(it, inNewTab = true) }
            Intent.ACTION_ASSIST -> pendingSearch = ""
            ACTION_WEB_NOTIFICATION -> Notifier.click(i.getStringExtra("tag"))
            dev.vis.ark.update.Updater.ACTION_SHOW -> pendingUpdate = true
            "dev.vis.ark.NEW_TAB" -> pendingSearch = ""
            "dev.vis.ark.NEW_PRIVATE" -> pendingSearch = "\u0000private"
        }
    }

    override fun onPause() {
        super.onPause()
        Browser.saveNow()
    }

    /** Whether Ark currently handles web links. Refreshed every time the app comes back to the foreground. */
    var isDefaultBrowser by mutableStateOf(false)
        private set

    override fun onResume() {
        super.onResume()
        isDefaultBrowser = checkDefaultBrowser()
        dev.vis.ark.update.Updater.init(this)
        dev.vis.ark.update.Updater.checkIfDue()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7) isDefaultBrowser = checkDefaultBrowser()
    }

    private fun checkDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) return true
        }
        // Some OEM settings apps change the default without going through RoleManager, so also ask the resolver.
        val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val info = if (Build.VERSION.SDK_INT >= 33)
            packageManager.resolveActivity(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        else @Suppress("DEPRECATION") packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Engine.isReady) Engine.runtime.configurationChanged(newConfig)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Browser.fullscreen && Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ------------------------------------------------------------ Host

    override fun requestAndroidPermissions(perms: Array<String>, cb: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { cb(true); return }
        permCb?.invoke(false)
        permCb = cb
        permLauncher.launch(missing.toTypedArray())
    }

    override fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, capture: Boolean, cb: (Array<Uri>?) -> Unit) {
        fileCb?.invoke(null)
        fileCb = cb
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        val extras = mutableListOf<Intent>()
        val wantsImage = mimeTypes.any { it.startsWith("image") || it == "*/*" }
        if (wantsImage && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            val f = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", f)
            extras += Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val launch = if (capture && extras.isNotEmpty()) extras[0] else Intent.createChooser(get, "Choose file").apply {
            if (extras.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        }
        val needCam = extras.isNotEmpty() && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val go = { ownSheetOpen = true; runCatching { fileLauncher.launch(launch) }.onFailure { ownSheetOpen = false; fileCb = null; cb(null) } }
        if (needCam) requestAndroidPermissions(arrayOf(android.Manifest.permission.CAMERA)) { go() } else go()
    }

    override fun openExternal(uri: String): Boolean {
        val intent = runCatching {
            if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply { selector = null; component = null }
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }.getOrNull() ?: return false
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return try { startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    override fun setFullscreen(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            lockOrientation(null)
        }
    }

    override fun lockOrientation(orientation: Int?) {
        requestedOrientation = when (orientation) {
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> if (orientation in 0..14) orientation else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun share(text: String?, url: String?) {
        val body = listOfNotNull(text?.takeIf { it.isNotBlank() && it != url }, url).joinToString("\n")
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        if (text != null) i.putExtra(Intent.EXTRA_SUBJECT, text)
        runCatching { startActivity(Intent.createChooser(i, null)) }
    }

    override fun snack(message: String, action: String?, onAction: (() -> Unit)?) {
        runOnUiThread { snack = Snack(message, action, onAction) }
    }

    fun voice(cb: (String) -> Unit) {
        voiceCb = cb
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or say a website")
        ownSheetOpen = true
        runCatching { voiceLauncher.launch(i) }.onFailure { ownSheetOpen = false; snack("Voice input isn't available on this device") }
    }

    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications) {
            Prefs.askedNotifications = true
            requestAndroidPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    fun addToHomeScreen(url: String, title: String) {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm?.isRequestPinShortcutSupported != true) { snack("Your launcher doesn't support shortcuts"); return }
        val info = android.content.pm.ShortcutInfo.Builder(this, "site-" + url.hashCode())
            .setShortLabel(title.ifBlank { url }.take(24))
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
            .build()
        sm.requestPinShortcut(info, null)
    }

    fun openDownloadsFolder() {
        val i = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { snack("Couldn't open the Downloads app") }
    }

    fun openDefaultBrowserSettings() {
        val i = if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                runCatching { startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 7) }.onSuccess { return }
            }
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else Intent(android.provider.Settings.ACTION_SETTINGS)
        runCatching { startActivity(i) }
    }

    fun downloadsDeps() = Downloads
}
