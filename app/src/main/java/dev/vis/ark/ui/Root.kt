@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package dev.vis.ark.ui

import android.graphics.Bitmap
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.core.view.WindowCompat
import dev.vis.ark.MainActivity
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Downloads
import dev.vis.ark.browser.Req
import dev.vis.ark.data.Prefs
import org.mozilla.geckoview.GeckoView

sealed interface Sheet {
    data class Search(val prefill: String = "", val private: Boolean = false, val newTab: Boolean = true) : Sheet
    data object Menu : Sheet
    data object Summary : Sheet
    data object Translate : Sheet
    data object Site : Sheet
}

@Composable
fun ArkRoot(activity: MainActivity) {
    val cs = MaterialTheme.colorScheme
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var lastSheet by remember { mutableStateOf<Sheet?>(null) }
    var screen by remember { mutableStateOf(Screen.None) }
    var switcher by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var gecko by remember { mutableStateOf<GeckoView?>(null) }
    val tab = Browser.current
    val dark = isAppDark()
    val ime = WindowInsets.isImeVisible
    val snackHost = remember { SnackbarHostState() }
    if (sheet != null) lastSheet = sheet

    // Intents asking for the search sheet (launcher shortcut, assist, …)
    LaunchedEffect(MainActivity.pendingSearch) {
        MainActivity.pendingSearch?.let {
            sheet = if (it == "\u0000private") Sheet.Search(private = true) else Sheet.Search(it)
            MainActivity.pendingSearch = null
        }
    }
    // "Update available" notification → Settings, where the update card lives.
    LaunchedEffect(MainActivity.pendingUpdate) {
        if (MainActivity.pendingUpdate) { sheet = null; switcher = false; screen = Screen.Settings; MainActivity.pendingUpdate = false }
    }
    // Fix a mistyped address: the search sheet with the failed address in it, loading in the same tab.
    fun editFailed(t: dev.vis.ark.browser.Tab) {
        t.editOffered = true
        switcher = false; screen = Screen.None; finding = false
        sheet = Sheet.Search(t.failedUri ?: t.url, t.private, newTab = false)
    }
    // "Edit address" on an error page.
    LaunchedEffect(Browser.editRequest) {
        val id = Browser.editRequest ?: return@LaunchedEffect
        Browser.editRequest = null
        Browser.tabs.firstOrNull { it.id == id }?.takeIf { it.id == Browser.currentId }?.let { editFailed(it) }
    }
    // New tab: Arc's search sheet, or the homepage when one is set (Settings › Home › Homepage).
    fun openNew(private: Boolean) {
        val home = homepageUrl()
        if (home != null) { sheet = null; Browser.newTab(home, private) } else sheet = Sheet.Search(private = private)
    }
    // Fresh install / no tabs: open straight into search like Arc (or load the homepage).
    LaunchedEffect(Unit) { if (Browser.tabs.isEmpty() && sheet == null && homepageUrl() == null) sheet = Sheet.Search() }
    // With a homepage set there's no empty home screen: show the page whenever no tab is open.
    LaunchedEffect(Browser.current == null, switcher, Prefs.homeMode, Prefs.homeUrl) {
        val home = homepageUrl()
        if (Browser.current == null && !switcher && home != null) Browser.newTab(home)
    }

    LaunchedEffect(MainActivity.snack) {
        val s = MainActivity.snack ?: return@LaunchedEffect
        snackHost.currentSnackbarData?.dismiss()
        // Compose's own durations (4 s, or 10 s with a button) linger far too long for quick notes like
        // "Downloading…" or "Closed tab"; time them out ourselves: 2 s, or 3 s when there's a button to reach.
        val res = kotlinx.coroutines.withTimeoutOrNull(if (s.action != null) 3000L else 2000L) {
            snackHost.showSnackbar(s.message, s.action, withDismissAction = false, duration = SnackbarDuration.Indefinite)
        }
        if (res == SnackbarResult.ActionPerformed) s.onAction?.invoke()
        if (MainActivity.snack?.id == s.id) MainActivity.snack = null
    }
    LaunchedEffect(Downloads.active.size) { if (Downloads.active.isNotEmpty()) activity.askNotificationsOnce() }

    val statusColor = if (tab != null && screen == Screen.None && !switcher) tab.themeColor ?: cs.surfaceContainer else cs.surface
    SideEffect {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        c.isAppearanceLightStatusBars = if (switcher || screen != Screen.None || tab == null) !dark else statusColor.luminance() > 0.5f
        c.isAppearanceLightNavigationBars = !dark
    }

    val swipeTarget = remember { arrayOfNulls<Long>(1) }
    fun captureThen(next: () -> Unit) {
        val gv = gecko; val t = Browser.current
        if (gv == null || t == null || gv.session == null) { next(); return }
        runCatching {
            gv.capturePixels().accept({ bmp: Bitmap? ->
                if (bmp != null) t.thumbnail = bmp.scale((bmp.width * 0.45f).toInt().coerceAtLeast(1), (bmp.height * 0.45f).toInt().coerceAtLeast(1)).asImageBitmap()
                next()
            }, { next() })
        }.onFailure { next() }
    }

    fun openSwitcher() = captureThen { switcher = true }

    fun menuAction(a: MenuAction) {
        val t = Browser.current
        when (a) {
            MenuAction.EditUrl -> sheet = Sheet.Search(t?.let { dev.vis.ark.browser.Search.queryOf(it.url) ?: it.url } ?: "", t?.private == true, newTab = t == null)
            MenuAction.Summarize -> sheet = if (t != null) Sheet.Summary else null
            MenuAction.Find -> { sheet = null; finding = t != null }
            MenuAction.Translate -> sheet = if (t != null) Sheet.Translate else null
            MenuAction.SiteSettings -> sheet = if (t != null) Sheet.Site else null
            MenuAction.History -> { sheet = null; screen = Screen.History }
            MenuAction.Downloads -> { sheet = null; screen = Screen.Downloads }
            MenuAction.Extensions -> { sheet = null; screen = Screen.Extensions }
            MenuAction.Settings -> { sheet = null; screen = Screen.Settings }
            MenuAction.PrivateTab -> sheet = Sheet.Search(private = true)
            MenuAction.HomeScreen -> { sheet = null; t?.let { activity.addToHomeScreen(it.url, it.displayTitle) } }
            MenuAction.Pdf -> {
                sheet = null
                t?.session?.saveAsPdf()?.accept({ stream ->
                    if (stream != null) Downloads.saveStream(activity, stream, (t.displayTitle.take(60).replace(Regex("[\\\\/:*?\"<>|]"), "_")) + ".pdf", "application/pdf", -1, t.url, t.private)
                }, { e -> Browser.host?.snack("Couldn't save PDF: ${e?.message}") })
            }
            MenuAction.Print -> { sheet = null; runCatching { t?.session?.printPageContent() }.onFailure { Browser.host?.snack("Printing isn't available") } }
        }
    }

    BackHandler {
        val t = Browser.current
        when {
            sheet != null -> sheet = null
            screen != Screen.None -> screen = Screen.None
            switcher -> switcher = false
            finding -> finding = false
            Browser.fullscreen -> t?.session?.exitFullScreen()
            // A page that failed to load with nowhere to go back to: Back returns to the address bar to fix it.
            t != null && t.failedUri != null && !t.canBack && !t.editOffered -> editFailed(t)
            t != null && t.canBack -> t.session?.goBack()
            t != null && t.parentId != null && Browser.tabs.any { it.id == t.parentId } -> Browser.close(t, undoable = false)
            else -> activity.moveTaskToBack(true)
        }
    }

    // Settings › Appearance › "Hide bar while scrolling": the page runs underneath the bottom bar (GeckoView's
    // dynamic toolbar), and the bar slides away as you scroll down and comes back when you scroll up.
    val barShown = !Browser.fullscreen && !MainActivity.inPip && !(ime && sheet == null && !finding && tab != null)
    val collapsible = Prefs.autoHideBar && barShown
    val barPx = remember { mutableIntStateOf(0) }
    val hide = animateFloatAsState(
        if (collapsible && tab != null && Browser.barHidden && sheet == null && !finding && !switcher && screen == Screen.None) 1f else 0f,
        tween(240, easing = FastOutSlowInEasing), label = "barHide",
    )
    LaunchedEffect(gecko, collapsible, barPx.intValue) {
        val gv = gecko ?: return@LaunchedEffect
        gv.setDynamicToolbarMaxHeight(if (collapsible) barPx.intValue else 0)
        if (!collapsible) { gv.setVerticalClipping(0); return@LaunchedEffect }
        snapshotFlow { (barPx.intValue * hide.value.coerceIn(0f, 1f)).roundToInt() }.collect { gv.setVerticalClipping(-it) }
    }

    androidx.compose.material3.Surface(color = cs.surface, contentColor = cs.onSurface, modifier = Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!Browser.fullscreen && !MainActivity.inPip) Box(Modifier.fillMaxWidth().background(statusColor).windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val epoch = Browser.viewEpoch
                val coverUp = switcher || screen != Screen.None
                val coverColor = cs.surface.toArgb()
                AndroidView(
                    factory = { ctx -> GeckoView(ctx).also { it.coverUntilFirstPaint(coverColor); gecko = it } },
                    update = { v ->
                        @Suppress("UNUSED_EXPRESSION") epoch
                        val s = Browser.current?.session
                        if (s != null && s.isOpen && !coverUp) {
                            if (v.session !== s) { if (v.session != null) v.releaseSession(); v.setSession(s) }
                            v.visibility = View.VISIBLE
                        } else {
                            if (v.session != null && (s == null || !s.isOpen || v.session !== s)) v.releaseSession()
                            v.visibility = View.INVISIBLE
                        }
                    },
                    // While the bar can hide, GeckoView keeps one constant size reaching under the bar (no reflow
                    // per animation frame); the part the bar covers is reported through vertical clipping.
                    modifier = Modifier.fillMaxSize().layout { m, c ->
                        val extra = if (collapsible) (barPx.intValue * (1f - hide.value.coerceIn(0f, 1f))).roundToInt() else 0
                        val h = c.maxHeight + extra
                        val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                    },
                )
                if (tab == null) {
                    if (Prefs.homeMode == 1) BlankHome(onSearch = { sheet = Sheet.Search() })
                    else HomeScreen(activity, onSearch = { sheet = Sheet.Search() })
                }
                if (finding && tab != null) FindBar(tab, onClose = { finding = false }, modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (barShown) {
                BottomBar(
                    hidden = { hide.value },
                    onBarHeight = { barPx.intValue = it },
                    tab = tab,
                    tabCount = Browser.tabs.count { it.private == (tab?.private == true) },
                    onTabs = { openSwitcher() },
                    onTabsLong = { tab?.let { Browser.close(it) } },
                    onNew = { openNew(tab?.private == true) },
                    onNewLong = { sheet = Sheet.Search(private = true) },
                    onMenu = { sheet = Sheet.Menu },
                    onSwipe = { dir ->
                        // Several steps can fire before the (async) thumbnail capture finishes, so step from
                        // the tab we're already heading to rather than the one still on screen.
                        val cur = Browser.current
                        val list = Browser.tabs.filter { it.private == (cur?.private == true) }
                        val i = list.indexOfFirst { it.id == (swipeTarget[0] ?: Browser.currentId) }
                        list.getOrNull(i + dir)?.let { target ->
                            swipeTarget[0] = target.id
                            captureThen { Browser.select(target); if (swipeTarget[0] == target.id) swipeTarget[0] = null }
                        }
                    },
                )
            }
        }

        // ---- Tab switcher
        AnimatedVisibility(
            switcher,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 1.08f),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 1.08f),
        ) {
            TabSwitcher(
                onClose = { switcher = false },
                onNewTab = { priv -> switcher = false; openNew(priv) },
                onSettings = { switcher = false; screen = Screen.Settings },
            )
        }

        // ---- Sheets (Arc-style)
        ArcSheet(sheet != null, onDismiss = { sheet = null }) {
            val t = Browser.current
            when (val s = sheet ?: lastSheet) {
                is Sheet.Search -> SearchSheetContent(activity, s.prefill, s.private, s.newTab, onDone = { sheet = null })
                Sheet.Menu -> MenuSheetContent(t, ::menuAction, onDismiss = { sheet = null })
                Sheet.Summary -> if (t != null) SummaryContent(t)
                Sheet.Translate -> if (t != null) TranslateContent(t, onDone = { sheet = null })
                Sheet.Site -> if (t != null) SiteSettingsContent(t, onDone = { sheet = null })
                null -> Unit
            }
        }

        // ---- Long-press context menu
        val ctxReq = Browser.prompts.firstOrNull() as? Req.Context
        var lastCtx by remember { mutableStateOf<Req.Context?>(null) }
        if (ctxReq != null) lastCtx = ctxReq
        ArcSheet(ctxReq != null, onDismiss = { ctxReq?.let { Browser.pop(it) } }) {
            lastCtx?.let { r -> ContextMenuContent(r.tab, r.el, onDone = { Browser.pop(r) }) }
        }

        // ---- Full-screen pages
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            screen,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "screen",
        ) { sc ->
            when (sc) {
                Screen.None -> Box(Modifier)
                Screen.Settings -> SettingsScreen(activity, onBack = { screen = Screen.None }, onOpen = { screen = it })
                Screen.History -> HistoryScreen(onBack = { screen = Screen.None })
                Screen.Downloads -> DownloadsScreen(activity, onBack = { screen = Screen.None })
                Screen.Extensions -> ExtensionsScreen(onBack = { screen = Screen.None })
            }
        }

        PromptHost()

        // Swipe a snackbar sideways (or tap its text) to get rid of it straight away.
        SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) { data ->
            val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
            androidx.compose.material3.SwipeToDismissBox(
                swipe, backgroundContent = {},
                onDismiss = { data.dismiss() },
            ) {
                androidx.compose.material3.Snackbar(data, Modifier.pointerInputTap { data.dismiss() })
            }
        }
    }
}
}
