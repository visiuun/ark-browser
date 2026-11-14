@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.vis.ark.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vis.ark.MainActivity
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Search
import dev.vis.ark.data.HistoryEntry
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import dev.vis.ark.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchSheetContent(
    activity: MainActivity,
    prefill: String,
    startPrivate: Boolean,
    newTab: Boolean,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(prefill, TextRange(0, prefill.length))) }
    var private by remember { mutableStateOf(startPrivate) }
    val focus = remember { FocusRequester() }
    var remote by remember { mutableStateOf(listOf<String>()) }
    var local by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var clip by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val q = value.text.trim()

    LaunchedEffect(Unit) {
        delay(60); runCatching { focus.requestFocus() }
        clip = runCatching { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() }.getOrNull()
            ?.trim()?.takeIf { it.length < 2000 && Search.isUrlLike(it) }
    }
    LaunchedEffect(q) {
        if (q.isEmpty()) { remote = emptyList(); local = emptyList(); return@LaunchedEffect }
        local = withContext(Dispatchers.IO) { runCatching { HistoryStore.search(q, 4) }.getOrDefault(emptyList()) }
        if (Prefs.suggestions && !private) { delay(140); remote = Net.suggestions(q) }
    }

    fun go(text: String) {
        if (text.isBlank()) return
        Browser.open(text, inNewTab = newTab, private = private)
        onDone()
    }

    // ---- Search pill: field · mic · private toggle
    Surface(shape = CircleShape, color = if (private) cs.tertiaryContainer else cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().height(76.dp)) {
        Row(Modifier.padding(start = 28.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.text.isEmpty()) Text(
                    if (private) "Search privately…" else "Search…",
                    style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant,
                )
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { go(value.text) }, onGo = { go(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (value.text.isNotEmpty()) IconButton(onClick = { value = TextFieldValue("") }) { MIcon(Sym.Close, "Clear") }
            IconButton(onClick = { activity.voice { go(it) } }) { MIcon(Sym.Mic, "Voice search", Modifier.size(28.dp), tint = cs.onSurfaceVariant) }
            IconButton(onClick = { private = !private }) {
                MIcon(Sym.VisibilityOff, "Private", Modifier.size(26.dp), tint = if (private) cs.onTertiaryContainer else cs.onSurfaceVariant)
            }
        }
    }

    if (q.isEmpty()) {
        if (clip != null) {
            Spacer(Modifier.height(12.dp))
            SuggestionRow(Sym.ContentPaste, "Open copied link", clip!!, onClick = { go(clip!!) })
        }
        Text("Pinned", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 26.dp, bottom = 14.dp))
        if (Prefs.pins.isEmpty()) {
            Text("Pin sites from the menu to see them here.", color = cs.onSurfaceVariant, modifier = Modifier.padding(6.dp))
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(Prefs.pins, key = { it.url }) { pin ->
                val host = android.net.Uri.parse(pin.url).host?.removePrefix("www.") ?: pin.url
                Surface(
                    shape = RoundedCornerShape(30.dp), color = cs.surfaceContainerHighest,
                    modifier = Modifier.size(width = 150.dp, height = 140.dp).combinedClickable(
                        onClick = { go(pin.url) },
                        onLongClick = { Prefs.removePin(pin); Browser.host?.snack("Unpinned ${pin.title}") },
                    ),
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Favicon(Net.faviconFor(host), host, 44.dp, container = cs.surfaceContainerLow)
                        Spacer(Modifier.height(12.dp))
                        Text(pin.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    } else {
        Spacer(Modifier.height(10.dp))
        val openTabs = Browser.tabs.filter { (it.title.contains(q, true) || it.url.contains(q, true)) && it.id != Browser.currentId }.take(2)
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            if (Search.isUrlLike(q)) item {
                SuggestionRow(Sym.Public, Search.resolve(q), "Go to site", onClick = { go(q) })
            }
            items(openTabs, key = { "t" + it.id }) { t ->
                SuggestionRow(Sym.Tab, t.displayTitle, "Switch to tab · ${t.host}", onClick = { Browser.select(t); onDone() })
            }
            items(local, key = { "h" + it.url }) { h ->
                SuggestionRow(Sym.History, h.title.ifBlank { h.url }, h.url, onClick = { go(h.url) })
            }
            items(remote, key = { "s$it" }) { s ->
                SuggestionRow(Sym.Search, s, null, onClick = { go(s) }, onFill = { value = TextFieldValue("$s ", TextRange(s.length + 1)) })
            }
            if (remote.isEmpty() && !Search.isUrlLike(q)) item {
                SuggestionRow(Sym.Search, q, "Search with ${Search.engine.name}", onClick = { go(q) })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SuggestionRow(icon: String, title: String, subtitle: String?, onClick: () -> Unit, onFill: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip24().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(cs.surfaceContainerHigh, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            MIcon(icon, null, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onFill != null) IconButton(onClick = onFill) { MIcon(Sym.NorthWest, "Fill", tint = cs.onSurfaceVariant) }
        else MIcon(Sym.ArrowForward, null, Modifier.padding(end = 10.dp).size(20.dp), tint = cs.onSurfaceVariant)
    }
}

private fun Modifier.clip24() = this.then(Modifier.clip(RoundedCornerShape(24.dp)))
