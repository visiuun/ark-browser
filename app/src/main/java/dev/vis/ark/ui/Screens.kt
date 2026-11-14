@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.vis.ark.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vis.ark.MainActivity
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Downloads
import dev.vis.ark.browser.Engine
import dev.vis.ark.browser.Search
import dev.vis.ark.data.HistoryEntry
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import dev.vis.ark.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.StorageController
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

enum class Screen { None, Settings, History, Downloads, Extensions }

@Composable
fun FullScreen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { MIcon(Sym.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// ======================================================================= Settings

@Composable
fun SettingsScreen(activity: MainActivity, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    var engineDialog by remember { mutableStateOf(false) }
    var dohDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    fun apply() = Engine.applySettings()
    LaunchedEffect(Unit) { Engine.refreshExtensions() }

    FullScreen("Settings", onBack) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp), modifier = Modifier.navigationBarsPadding()) {
            if (updatePending()) {
                item { UpdateCard(onOpenPage = { Browser.open(it, inNewTab = true); onBack() }) }
                item { Spacer(Modifier.height(12.dp)) }
            }
            if (activity.isDefaultBrowser) item {
                Surface(onClick = { activity.openDefaultBrowserSettings() }, shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        MIcon(Sym.Check, null, tint = cs.primary)
                        Spacer(Modifier.width(14.dp))
                        Text("Ark is your default browser", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else item {
                Surface(onClick = { activity.openDefaultBrowserSettings() }, shape = RoundedCornerShape(32.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The real app icon: its background colour plus the adaptive foreground (72dp visible of 108dp).
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.res.colorResource(dev.vis.ark.R.color.ic_bg)),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(dev.vis.ark.R.drawable.ic_launcher_fg), null,
                                Modifier.requiredSize(78.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Make Ark your default browser", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                            Text("Open links from every app in Ark", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                        }
                    }
                }
            }

            item { SectionLabel("Appearance") }
            item {
                ChoiceGroup("Theme", listOf("System", "Light", "Dark"), Prefs.themeMode, index = 0, count = 4) {
                    Prefs.themeMode = it; apply()
                }
            }
            item { ToggleRow("OLED black", Prefs.oled, 1, 4, "Pure black in dark mode — saves battery on OLED screens") { Prefs.oled = it; apply() } }
            item {
                ToggleRow("Dynamic color", Prefs.dynamicColor, 2, 4, "Match your wallpaper (Material You)", enabled = Build.VERSION.SDK_INT >= 31) { Prefs.dynamicColor = it }
            }
            item { ChoiceGroup("Websites", listOf("Auto", "Light", "Dark"), Prefs.webTheme, index = 3, count = 4) { Prefs.webTheme = it; apply() } }
            item {
                SettingBlock("Text size · ${(Prefs.fontScale * 100).toInt()}%", 0, 1) {
                    Slider(value = Prefs.fontScale, onValueChange = { Prefs.fontScale = (Math.round(it * 20) / 20f) }, onValueChangeFinished = { apply() }, valueRange = 0.7f..1.8f)
                }
            }

            item { SectionLabel("Search") }
            item { ClickRow("Search engine", Search.engine.name, 0, 2) { engineDialog = true } }
            item { ToggleRow("Search suggestions", Prefs.suggestions, 1, 2, "From DuckDuckGo autocomplete; never in private tabs") { Prefs.suggestions = it } }

            item { SectionLabel("Home") }
            item {
                ChoiceGroup("Homepage", listOf("Ark home", "Blank", "A page"), Prefs.homeMode, index = 0, count = if (Prefs.homeMode == 2) 2 else 1) { Prefs.homeMode = it }
            }
            if (Prefs.homeMode == 2) item {
                TextRow("Homepage address", Prefs.homeUrl, "e.g. duckduckgo.com", 1, 2) { Prefs.homeUrl = it }
            }
            item {
                Text(
                    when (Prefs.homeMode) {
                        1 -> "New tabs show just the Ark logo, like Arc. Tap it to search."
                        2 -> "New tabs open this page. Long-press + for a private search."
                        else -> "Clock, weather, pinned sites, Wikipedia and Hacker News. Customise it below."
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
                )
            }
            item { ChoiceGroup("Clock", listOf("System", "12-hour", "24-hour"), Prefs.clock, index = 0, count = 7) { Prefs.clock = it } }
            item { ToggleRow("Weather", Prefs.homeWeather, 1, 7, "Open-Meteo, no account needed") { Prefs.homeWeather = it } }
            item { TextRow("Weather city", Prefs.weatherCity, "Leave empty to use your location", 2, 7) { Prefs.weatherCity = it } }
            item { ToggleRow("Fahrenheit", Prefs.fahrenheit, 3, 7) { Prefs.fahrenheit = it } }
            item { ToggleRow("Wikipedia", Prefs.homeWiki, 4, 7, "Featured article, news, on this day") { Prefs.homeWiki = it } }
            item { ToggleRow("Hacker News", Prefs.homeHN, 5, 7) { Prefs.homeHN = it } }
            item { ToggleRow("Recently visited", Prefs.homeRecent, 6, 7) { Prefs.homeRecent = it } }

            item { SectionLabel("Ad blocker") }
            item {
                val ubo = Engine.adBlocker
                ToggleRow(
                    "Block ads", Prefs.adBlock && ubo?.metaData?.enabled != false, 0, 2,
                    when {
                        Engine.adBlockInstalling.value -> "Setting up uBlock Origin…"
                        ubo != null -> "uBlock Origin ${ubo.metaData.version} · ads, trackers, malware & annoyances"
                        else -> "uBlock Origin — open-source, built in"
                    },
                ) { Engine.setAdBlock(activity, it) }
            }
            item {
                ClickRow("Filters & allowed sites", "Choose filter lists, allow ads on sites you support", 1, 2) {
                    val url = Engine.adBlocker?.metaData?.optionsPageUrl
                    if (url != null) { Browser.newTab(url); onBack() } else Browser.host?.snack("Turn on the ad blocker first")
                }
            }

            item { SectionLabel("Summarize") }
            item {
                ToggleRow(
                    "AI summaries", Prefs.aiSummary == 1, 0, 1,
                    "Sends the page's text to Pollinations, a free, open-source AI service, for a real summary. Off: key sentences are picked on your device. Never used in private tabs.",
                ) { Prefs.aiSummary = if (it) 1 else 2 }
            }

            item { SectionLabel("Privacy & security") }
            item { ChoiceGroup("Tracking protection", listOf("Off", "Standard", "Strict"), Prefs.etp, index = 0, count = 6) { Prefs.etp = it; apply() } }
            item { ToggleRow("HTTPS-only mode", Prefs.httpsOnly, 1, 6, "Upgrade every connection to HTTPS") { Prefs.httpsOnly = it; apply() } }
            item { ToggleRow("Global Privacy Control", Prefs.gpc, 2, 6, "Tell sites not to sell or share your data") { Prefs.gpc = it; apply() } }
            item { ToggleRow("Safe Browsing", Prefs.safeBrowsing, 3, 6, "Warn about phishing and malware") { Prefs.safeBrowsing = it; apply() } }
            item { ClickRow("Secure DNS (DNS over HTTPS)", Engine.DOH.getOrElse(Prefs.doh) { Engine.DOH[0] }.name, 4, 6) { dohDialog = true } }
            item { ClickRow("Clear browsing data", "History, cookies, cache, permissions", 5, 6) { clearDialog = true } }

            item { SectionLabel("Sites") }
            item { ToggleRow("JavaScript", Prefs.javascript, 0, 5) { Prefs.javascript = it; apply() } }
            item { ToggleRow("Always allow zoom", Prefs.forceZoom, 1, 5, "Pinch-zoom even when a site blocks it") { Prefs.forceZoom = it; apply() } }
            item { ChoiceGroup("Autoplay", listOf("Allow", "No sound", "Block"), Prefs.autoplay, index = 2, count = 5) { Prefs.autoplay = it } }
            item { ToggleRow("Desktop site by default", Prefs.desktopDefault, 3, 5) { Prefs.desktopDefault = it } }
            item { ToggleRow("Restore tabs on launch", Prefs.restoreTabs, 4, 5) { Prefs.restoreTabs = it } }

            item { SectionLabel("Your stuff") }
            item { ClickRow("History", "${runCatching { HistoryStore.count() }.getOrDefault(0)} pages", 0, 3) { onOpen(Screen.History) } }
            item { ClickRow("Downloads", "${Downloads.all().size} files", 1, 3) { onOpen(Screen.Downloads) } }
            item { ClickRow("Add-ons", "${Engine.extensions.size} installed · uBlock Origin, Dark Reader…", 2, 3) { onOpen(Screen.Extensions) } }

            item { SectionLabel("Advanced") }
            item {
                ToggleRow("Show Ark in user agent", Prefs.uaArk, 0, 3, "Websites see “Ark/${dev.vis.ark.BuildConfig.VERSION_NAME}” after Firefox. Turn off if a site misbehaves.") {
                    Prefs.uaArk = it; Browser.applyUserAgent()
                }
            }
            item { ToggleRow("Remote debugging", Prefs.remoteDebugging, 1, 3, "Debug pages from desktop Firefox via USB") { Prefs.remoteDebugging = it; apply() } }
            item { ClickRow("about:config", "Every Gecko preference, for experts", 2, 3) { Browser.newTab("about:config"); onBack() } }

            item { SectionLabel("Updates") }
            item { UpdateSection(onOpenPage = { Browser.open(it, inNewTab = true); onBack() }) }

            item { SectionLabel("About") }
            item {
                SettingBlock("Ark ${dev.vis.ark.BuildConfig.VERSION_NAME}", 0, 1) {
                    Text(
                        "Engine: Mozilla GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION} (Firefox)\n" +
                            "UI: Material 3 Expressive\n" +
                            "Data: Open-Meteo, OpenStreetMap Nominatim, Wikipedia, Hacker News, DuckDuckGo suggestions — all free, no accounts.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (engineDialog) AlertDialog(
        onDismissRequest = { engineDialog = false },
        title = { Text("Search engine") },
        text = {
            Column {
                Search.engines.forEach { e ->
                    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable { Prefs.searchEngine = e.id; engineDialog = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(Prefs.searchEngine == e.id, null); Spacer(Modifier.width(10.dp)); Text(e.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ engineDialog = false }) { Text("Done") } },
    )

    if (dohDialog) AlertDialog(
        onDismissRequest = { dohDialog = false },
        title = { Text("Secure DNS") },
        text = {
            Column {
                Engine.DOH.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable { Prefs.doh = i; Engine.applySettings(); dohDialog = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(Prefs.doh == i, null); Spacer(Modifier.width(10.dp)); Text(d.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ dohDialog = false }) { Text("Done") } },
    )

    if (clearDialog) ClearDataDialog { clearDialog = false }
}

@Composable
private fun ClearDataDialog(onDone: () -> Unit) {
    val labels = listOf("Browsing history", "Cookies & site data", "Cached images and files", "Site permissions", "Download list", "Open tabs")
    val checked = remember { mutableStateListOf(true, true, true, false, false, false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                labels.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked[i], null); Spacer(Modifier.width(8.dp)); Text(l)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (checked[0]) HistoryStore.clear()
                var flags = 0L
                if (checked[1]) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
                if (checked[2]) flags = flags or StorageController.ClearFlags.ALL_CACHES
                if (checked[3]) flags = flags or StorageController.ClearFlags.PERMISSIONS
                if (flags != 0L) Engine.runtime.storageController.clearData(flags)
                if (checked[4]) Downloads.clear()
                if (checked[5]) { Browser.closeAll(false); Browser.closeAll(true) }
                Browser.host?.snack("Browsing data cleared")
                onDone()
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
private fun SettingBlock(title: String, index: Int, count: Int, content: @Composable () -> Unit) {
    Surface(shape = segmentShape(index, count), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun ChoiceGroup(title: String, options: List<String>, selected: Int, enabled: Boolean = true, index: Int, count: Int, onSelect: (Int) -> Unit) {
    SettingBlock(title, index, count) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, o ->
                ToggleButton(
                    checked = i == selected, onCheckedChange = { onSelect(i) }, enabled = enabled,
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(o, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String?, index: Int, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = segmentShape(index, count), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TextRow(title: String, value: String, hint: String, index: Int, count: Int, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(value) }
    SettingBlock(title, index, count) {
        OutlinedTextField(
            v, { v = it }, placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onChange(v.trim()) }),
            trailingIcon = { if (v.trim() != value) TextButton({ onChange(v.trim()) }) { Text("Save") } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ======================================================================= History

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val v = HistoryStore.version.intValue
    LaunchedEffect(query, v) {
        items = withContext(Dispatchers.IO) { if (query.isBlank()) HistoryStore.recent(500) else HistoryStore.search(query, 300) }
    }
    var confirm by remember { mutableStateOf(false) }
    FullScreen("History", onBack, actions = { IconButton(onClick = { confirm = true }) { MIcon(Sym.DeleteSweep, "Clear history") } }) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search history") }, singleLine = true, shape = CircleShape,
                    leadingIcon = { MIcon(Sym.Search, null) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            val groups = items.groupBy { Instant.ofEpochMilli(it.lastVisit).atZone(ZoneId.systemDefault()).toLocalDate() }
            groups.forEach { (day, list) ->
                item(key = "d$day") {
                    SectionLabel(
                        when (day) {
                            LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"
                            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
                        }
                    )
                }
                itemsIndexed(list, key = { _, h -> h.url }) { i, h ->
                    val host = android.net.Uri.parse(h.url).host?.removePrefix("www.") ?: ""
                    Surface(onClick = { Browser.newTab(h.url); onBack() }, shape = segmentShape(i, list.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Favicon(Net.faviconFor(host), host, 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.title.ifBlank { host }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.url, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { HistoryStore.delete(h.url) }) { MIcon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant) }
                        }
                    }
                }
            }
            if (items.isEmpty()) item { Text("Nothing here yet.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear all history?") },
        confirmButton = { TextButton({ HistoryStore.clear(); confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

// ======================================================================= Downloads

@Composable
fun DownloadsScreen(activity: MainActivity, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val list = Downloads.all()
    FullScreen("Downloads", onBack, actions = { IconButton(onClick = { activity.openDownloadsFolder() }) { MIcon(Sym.FolderOpen, "Open folder") } }) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), modifier = Modifier.navigationBarsPadding()) {
            val active = Downloads.active.entries.toList()
            if (active.isNotEmpty()) {
                item { SectionLabel("In progress") }
                items(active, key = { "a" + it.key }) { (_, v) ->
                    Surface(shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text(v.first, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            if (v.second < 0) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                            else LinearWavyProgressIndicator(progress = { v.second }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (list.isNotEmpty()) item { SectionLabel("Files") }
            itemsIndexed(list, key = { _, d -> d.id }) { i, d ->
                Surface(onClick = { Downloads.open(activity, d) }, shape = segmentShape(i, list.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                            MIcon(Sym.Description, null, tint = cs.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${android.text.format.Formatter.formatShortFileSize(activity, d.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.time))}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { Downloads.remove(activity, d, deleteFile = true) }) { MIcon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant) }
                    }
                }
            }
            if (list.isEmpty() && active.isEmpty()) item {
                Text("Downloads you start in Ark show up here. Files are saved to your phone's Downloads folder.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

// ======================================================================= Add-ons

private data class Featured(val name: String, val desc: String, val xpi: String)

private val featured = listOf(
    Featured("uBlock Origin", "Efficient, open-source ad & tracker blocker", "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"),
    Featured("Dark Reader", "Dark mode for every website", "https://addons.mozilla.org/firefox/downloads/latest/darkreader/latest.xpi"),
    Featured("SponsorBlock", "Skip sponsor segments on YouTube", "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi"),
    Featured("Bitwarden", "Open-source password manager", "https://addons.mozilla.org/firefox/downloads/latest/bitwarden-password-manager/latest.xpi"),
    Featured("Privacy Badger", "Learns to block invisible trackers", "https://addons.mozilla.org/firefox/downloads/latest/privacy-badger17/latest.xpi"),
)

@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Engine.refreshExtensions() }
    FullScreen("Add-ons", onBack) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                Text(
                    "Ark runs real Firefox add-ons. Install from the list below, or browse addons.mozilla.org and tap \"Add to Firefox\".",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (Engine.extensions.isNotEmpty()) {
                item { SectionLabel("Installed") }
                itemsIndexed(Engine.extensions, key = { _, e -> e.id }) { i, e ->
                    val md = e.metaData
                    Surface(shape = segmentShape(i, Engine.extensions.size), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            MIcon(Sym.Extension, null, tint = cs.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(md.name ?: e.id, style = MaterialTheme.typography.titleMedium)
                                Text("v${md.version}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            if (md.optionsPageUrl != null) IconButton(onClick = { Browser.newTab(md.optionsPageUrl); onBack() }) { MIcon(Sym.Settings, "Options") }
                            Switch(checked = md.enabled, onCheckedChange = { on ->
                                val ctrl = Engine.runtime.webExtensionController
                                (if (on) ctrl.enable(e, org.mozilla.geckoview.WebExtensionController.EnableSource.USER) else ctrl.disable(e, org.mozilla.geckoview.WebExtensionController.EnableSource.USER))
                                    .accept { Engine.refreshExtensions() }
                            })
                            IconButton(onClick = { Engine.runtime.webExtensionController.uninstall(e).accept { Engine.refreshExtensions() } }) { MIcon(Sym.Delete, "Remove") }
                        }
                    }
                }
            }
            item { SectionLabel("Recommended") }
            itemsIndexed(featured) { i, f ->
                val installed = Engine.extensions.any { it.metaData.name.equals(f.name, true) }
                Surface(shape = segmentShape(i, featured.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                            Text(f.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        FilledTonalButton(enabled = !installed && busy == null, onClick = {
                            busy = f.name
                            Engine.install(f.xpi) { err ->
                                busy = null
                                Browser.host?.snack(if (err == null) "${f.name} installed" else "Couldn't install ${f.name}: ${err.message}")
                            }
                        }) { Text(if (installed) "Added" else if (busy == f.name) "…" else "Add") }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { Browser.newTab("https://addons.mozilla.org/android/"); onBack() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Browse all add-ons")
                }
            }
        }
    }
}
