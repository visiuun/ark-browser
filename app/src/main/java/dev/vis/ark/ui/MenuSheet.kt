@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import kotlin.math.roundToInt
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Tab
import dev.vis.ark.data.Prefs
import kotlinx.coroutines.launch

enum class MenuAction { EditUrl, Summarize, Find, Translate, SiteSettings, History, Downloads, Extensions, Settings, PrivateTab, HomeScreen, Pdf, Print }

@Composable
fun MenuSheetContent(tab: Tab?, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    // The menu is a fixed grid of tiles: follow the system text size, but only up to 1.15x, past that the
    // labels can't fit their tiles any more (they also shrink to fit on their own).
    val d = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale.coerceAtMost(1.15f)),
    ) { MenuSheetBody(tab, onAction, onDismiss) }
}

@Composable
private fun MenuSheetBody(tab: Tab?, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // The sheet ends at the "More" handle; History, Downloads and the other tools sit below the fold.
    var fold by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .then(if (fold > 0) Modifier.heightIn(max = with(density) { fold.toDp() }) else Modifier)
            .verticalScroll(scroll)
    ) {
        // ---- URL pill: back · forward · domain · copy link · reload
        Surface(shape = CircleShape, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().height(76.dp)) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tab?.session?.goBack(); onDismiss() }, enabled = tab?.canBack == true) {
                    MIcon(Sym.ArrowBack, "Back", Modifier.size(28.dp))
                }
                IconButton(onClick = { tab?.session?.goForward(); onDismiss() }, enabled = tab?.canForward == true) {
                    MIcon(Sym.ArrowForward, "Forward", Modifier.size(28.dp))
                }
                Row(
                    Modifier.weight(1f).height(56.dp).clickable { onAction(MenuAction.EditUrl) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    if (tab?.isSecure == true) MIcon(Sym.Lock, null, Modifier.size(15.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    // Shrinks (down to 14sp) before it has to cut the domain short.
                    androidx.compose.foundation.text.BasicText(
                        if (tab == null) "Search" else tab.host.removePrefix("www.").ifBlank { tab.url },
                        style = MaterialTheme.typography.titleLarge.copy(color = cs.onSurface, textAlign = TextAlign.Center),
                        maxLines = 1, overflow = if (tab == null) TextOverflow.Ellipsis else TextOverflow.StartEllipsis,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                    )
                }
                IconButton(onClick = {
                    tab?.let { t ->
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("URL", t.url))) }
                        Browser.host?.snack("Link copied")
                    }
                }, enabled = tab != null) { MIcon(Sym.Link, "Copy link", Modifier.size(28.dp)) }
                IconButton(onClick = {
                    tab?.session?.let { if (tab.loading) it.stop() else it.reload() }; onDismiss()
                }, enabled = tab != null) {
                    MIcon(if (tab?.loading == true) Sym.Close else Sym.Refresh, "Reload", Modifier.size(28.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Four connected tiles
        val pinned = tab != null && Prefs.isPinned(tab.url)
        ConnectedRow(
            listOf(
                Triple(Sym.FormatListBulleted, "Summarize") { onAction(MenuAction.Summarize) },
                Triple(if (pinned) "*" + Sym.PushPin else Sym.PushPin, if (pinned) "Unpin" else "Pin") {
                    tab?.let { t ->
                        val now = Prefs.togglePin(t.url, t.displayTitle)
                        Browser.host?.snack(if (now) "Pinned ${t.host}" else "Unpinned ${t.host}")
                    }
                },
                Triple(Sym.FindInPage, "Find") { onAction(MenuAction.Find) },
                Triple(Sym.Share, "Share") { tab?.let { Browser.host?.share(it.title, it.url) } },
            ),
            selected = { it == 1 && pinned }, height = 84.dp, iconSize = 26.dp,
        )

        Spacer(Modifier.height(6.dp))

        // ---- List rows
        val tr = tab?.translation
        val translateSub = when {
            tab?.translating == true -> "Translating…"
            tr?.requestedTranslationPair != null -> "Translated to ${tr.requestedTranslationPair!!.toLanguage}"
            tr?.detectedLanguages?.docLangTag != null -> "Page language: ${tr.detectedLanguages!!.docLangTag}"
            tab?.offerTranslate == true -> "Available for this page"
            else -> null
        }
        MenuRow(Sym.Translate, "Translate", translateSub, onClick = { onAction(MenuAction.Translate) })
        MenuRow(Sym.ZoomOutMap, "Zoom", "${((tab?.zoom ?: 1f) * 100).toInt()}%", onClick = null) {
            IconButton(onClick = { tab?.let { Browser.setZoom(it, it.zoom + 0.1f) } }, enabled = tab != null) { MIcon(Sym.Add, "Zoom in", Modifier.size(26.dp)) }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { tab?.let { Browser.setZoom(it, it.zoom - 0.1f) } }, enabled = tab != null) { MIcon(Sym.Remove, "Zoom out", Modifier.size(26.dp)) }
        }
        MenuRow(Sym.DesktopWindows, "Desktop Site", null, onClick = { tab?.let { Browser.setDesktop(it, !it.desktop) } }) {
            Checkbox(checked = tab?.desktop == true, onCheckedChange = { c -> tab?.let { Browser.setDesktop(it, c) } }, enabled = tab != null)
        }
        MenuRow(Sym.Settings, "Site Settings", tab?.let { t ->
            buildString {
                append(if (t.isSecure) "Secure" else "Not secure")
                if (t.blockedCount > 0) append(" · ${t.blockedCount} trackers blocked")
            }
        }, onClick = { onAction(MenuAction.SiteSettings) })

        // ---- "More" handle: tap (or just scroll) to reach the extra tools, tap again to go back up.
        val opened = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
        Row(
            Modifier
                .fillMaxWidth()
                .onPlaced { fold = (it.positionInParent().y + it.size.height).roundToInt() }
                .padding(top = 4.dp)
                .height(40.dp)
                .clip(CircleShape)
                .clickable { scope.launch { scroll.animateScrollTo(if (opened > 0.5f) 0 else scroll.maxValue) } },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text(if (opened > 0.5f) "Less" else "More", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            MIcon(Sym.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(180f * opened), tint = cs.onSurfaceVariant)
        }

        Spacer(Modifier.height(6.dp))

        // ---- Extra tools: one 2x4 connected grid (outer corners round, the rows meet tight)
        ConnectedRow(
            listOf(
                Triple(Sym.History, "History") { onAction(MenuAction.History) },
                Triple(Sym.Download, "Downloads") { onAction(MenuAction.Downloads) },
                Triple(Sym.Extension, "Add-ons") { onAction(MenuAction.Extensions) },
                Triple(Sym.Settings, "Settings") { onAction(MenuAction.Settings) },
            ), height = 70.dp, iconSize = 24.dp, container = cs.surfaceContainerHigh, bottomRow = false,
        )
        Spacer(Modifier.height(3.dp))
        ConnectedRow(
            listOf(
                Triple(Sym.VisibilityOff, "Private") { onAction(MenuAction.PrivateTab) },
                Triple(Sym.AddToHomeScreen, "Home") { onAction(MenuAction.HomeScreen) },
                Triple(Sym.PictureAsPdf, "PDF") { onAction(MenuAction.Pdf) },
                Triple(Sym.Print, "Print") { onAction(MenuAction.Print) },
            ), height = 70.dp, iconSize = 24.dp, container = cs.surfaceContainerHigh, topRow = false,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MenuRow(icon: String, title: String, subtitle: String?, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Box { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } }
    }
}
