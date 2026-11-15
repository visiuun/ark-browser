@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package dev.vis.ark.ui

import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.TextButton
import dev.vis.ark.data.Prefs
import dev.vis.ark.browser.AiSummary
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Downloads
import dev.vis.ark.browser.Engine
import dev.vis.ark.browser.Summarizer
import dev.vis.ark.browser.Tab
import dev.vis.ark.browser.Req
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.TranslationsController
import java.text.DateFormat
import java.util.Locale
import kotlin.coroutines.resume

// ======================================================================= Summarize

private data class Extracted(val text: String, val headings: List<String>, val links: Int)

private suspend fun extractPage(tab: Tab): Extracted? {
    val viaExt = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine<JSONObject?> { cont -> if (!tab.requestText { cont.resume(it) }) cont.resume(null) }
    }
    if (viaExt != null && viaExt.optString("text").length > 200) {
        val h = viaExt.optJSONArray("headings")
        return Extracted(viaExt.optString("text"), (0 until (h?.length() ?: 0)).map { h!!.getString(it) }, viaExt.optInt("links"))
    }
    val s = tab.session ?: return null
    val text = withTimeoutOrNull(6000) {
        suspendCancellableCoroutine<String?> { cont ->
            runCatching { s.sessionPageExtractor.pageContent.accept({ cont.resume(it) }, { cont.resume(null) }) }.onFailure { cont.resume(null) }
        }
    }
    return text?.let { Extracted(it, emptyList(), viaExt?.optInt("links") ?: 0) } ?: viaExt?.let { Extracted(it.optString("text"), emptyList(), it.optInt("links")) }
}

@Composable
fun SummaryContent(tab: Tab) {
    val cs = MaterialTheme.colorScheme
    var summary by remember { mutableStateOf<Summarizer.Summary?>(null) }
    var page by remember { mutableStateOf<Extracted?>(null) }
    var failed by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf<AiSummary.Result?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiRun by remember { mutableStateOf(0) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val aiAllowed = !tab.private && Prefs.aiSummary == 1

    LaunchedEffect(tab.id, tab.url) {
        summary = null; failed = false; page = null; ai = null; aiError = null
        val ex = extractPage(tab)
        if (ex == null || ex.text.isBlank()) { failed = true; return@LaunchedEffect }
        page = ex
        summary = withContext(Dispatchers.Default) { Summarizer.summarize(ex.text, ex.headings, ex.links) }
        if (summary!!.bullets.isEmpty() && ex.text.length < 200) failed = true
    }
    LaunchedEffect(page, aiAllowed, aiRun) {
        val ex = page ?: return@LaunchedEffect
        if (!aiAllowed || ex.text.length < 200) return@LaunchedEffect
        aiLoading = true; aiError = null
        runCatching { AiSummary.summarize(tab.displayTitle, tab.url, ex.text) }
            .onSuccess { ai = it }
            .onFailure { aiError = it.message ?: "Couldn't reach the AI service" }
        aiLoading = false
    }

    fun shareText(): String {
        val a = ai
        val body = if (a != null) (if (a.tldr.isNotBlank()) a.tldr + "\n\n" else "") + a.points.joinToString("\n") { "• $it" }
            else summary?.bullets.orEmpty().joinToString("\n") { "• $it" }
        return tab.displayTitle + "\n\n" + body
    }

    Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Favicon(tab.favicon, tab.host, 44.dp, shape = MaterialShapes.Cookie9Sided.toShape(), container = cs.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tab.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tab.host, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))
        val s = summary
        when {
            failed -> Text("This page doesn't have enough readable text to summarize.", color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            s == null -> Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(Modifier.size(64.dp))
                Text("Reading the page…", color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoPill("${s.readingMinutes} min", "read", Modifier.weight(1f))
                    InfoPill("%,d".format(s.words), "words", Modifier.weight(1f))
                    InfoPill("${s.links}", "links", Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))

                // One-time choice: real AI summaries (page text goes to Pollinations) or on-device only.
                if (!tab.private && Prefs.aiSummary == 0) {
                    Surface(shape = RoundedCornerShape(28.dp), color = cs.tertiaryContainer) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Get a smarter summary?", style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
                            Text(
                                "Ark can send this page's text to Pollinations, a free, open-source AI service, to write a real summary. " +
                                    "No account, and never for private tabs. Change it any time in Settings.",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer, modifier = Modifier.padding(top = 6.dp),
                            )
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { Prefs.aiSummary = 1 }) { Text("Use AI") }
                                TextButton(onClick = { Prefs.aiSummary = 2 }) { Text("Keep on-device", color = cs.onTertiaryContainer) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                val a = ai
                if (aiAllowed && (aiLoading || a != null)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHighest) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MIcon(Sym.Bolt, null, Modifier.size(18.dp), tint = cs.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("AI summary", style = MaterialTheme.typography.titleMedium, color = cs.primary)
                            }
                            if (a == null) {
                                Text("Writing the summary…", color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                if (a.tldr.isNotBlank()) Text(a.tldr, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                                a.points.forEach { b -> Bullet(b) }
                            }
                        }
                    }
                    if (a != null && a.topics.isNotEmpty()) Topics(a.topics)
                } else {
                    val err = aiError
                    if (aiAllowed && err != null) {
                        Surface(shape = RoundedCornerShape(20.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(err, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
                                TextButton(onClick = { aiRun++ }) { Text("Retry") }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHighest) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Key sentences", style = MaterialTheme.typography.titleMedium, color = cs.primary)
                            s.bullets.forEach { b -> Bullet(b) }
                        }
                    }
                    if (s.keywords.isNotEmpty()) Topics(s.keywords)
                }
                if (s.headings.isNotEmpty()) {
                    Text("Outline", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    s.headings.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val text = shareText() + "\n\n" + tab.url
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Summary", text))) }
                        Browser.host?.snack("Summary copied")
                    }) { MIcon(Sym.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy") }
                    FilledTonalButton(onClick = { Browser.host?.share(shareText(), tab.url) }) {
                        MIcon(Sym.Share, null); Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                }
                Text(
                    when {
                        a != null -> "Written by AI (${a.model} via Pollinations). It can make mistakes, so check the page for anything important."
                        tab.private -> "Private tab: summarized on your device, nothing is sent anywhere."
                        else -> "Key sentences picked on your device. Nothing is sent anywhere."
                    },
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(MaterialShapes.Cookie4Sided.toShape()).background(cs.primary))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Topics(topics: List<String>) {
    val cs = MaterialTheme.colorScheme
    Text("Topics", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        topics.forEach { k ->
            Surface(shape = CircleShape, color = cs.secondaryContainer, onClick = { Browser.open(k, inNewTab = true) }) {
                Text(k, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun InfoPill(value: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerHighest).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

// ======================================================================= Translate

@Composable
fun TranslateContent(tab: Tab, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var langs by remember { mutableStateOf<List<TranslationsController.Language>>(emptyList()) }
    var target by remember { mutableStateOf(Locale.getDefault().language) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        TranslationsController.RuntimeTranslation.listSupportedLanguages().accept { sup ->
            langs = sup?.toLanguages?.sortedBy { it.localizedDisplayName } ?: emptyList()
            if (langs.isNotEmpty() && langs.none { it.code == target }) target = "en"
        }
    }
    val detected = tab.translation?.detectedLanguages?.docLangTag
    val translated = tab.translation?.requestedTranslationPair != null
    fun name(code: String?) = code?.let { c -> langs.firstOrNull { it.code == c }?.localizedDisplayName ?: Locale.forLanguageTag(c).displayLanguage }

    Text("Translate page", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Uses Firefox's on-device translation — the page never leaves your phone. Language models download once.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 6.dp, bottomEnd = 6.dp), color = cs.surfaceContainerHighest, modifier = Modifier.weight(1f).height(72.dp)) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                Text("From", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(name(detected) ?: "Detecting…", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            Surface(onClick = { menu = true }, shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 26.dp, bottomEnd = 26.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                    Text("To", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
                    Text(name(target) ?: target, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer, maxLines = 1)
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                langs.forEach { l -> DropdownMenuItem(text = { Text(l.localizedDisplayName ?: l.code) }, onClick = { target = l.code; menu = false }) }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    if (tab.translating) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            ContainedLoadingIndicator(Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Text("Translating… (first time downloads the language model)", color = cs.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !tab.translating && tab.session != null,
            onClick = {
                val st = tab.session?.sessionTranslation ?: return@Button
                tab.translating = true
                val opts = TranslationsController.SessionTranslation.TranslationOptions.Builder().downloadModel(true).build()
                st.translate(detected ?: "", target, opts).accept(
                    { tab.translating = false; Browser.host?.snack("Translated to ${name(target)}"); onDone() },
                    { e -> tab.translating = false; Browser.host?.snack("Translation failed: ${e?.message ?: "unsupported language"}") },
                )
            },
            modifier = Modifier.height(56.dp),
        ) { Text("Translate") }
        if (translated) OutlinedButton(onClick = { tab.session?.sessionTranslation?.restoreOriginalPage(); onDone() }, modifier = Modifier.height(56.dp)) { Text("Show original") }
    }
    Text(
        "Open with web translator",
        color = cs.primary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).clip(CircleShape).clickable {
            Browser.newTab("https://translate.google.com/translate?sl=auto&tl=$target&u=" + java.net.URLEncoder.encode(tab.url, "UTF-8"), tab.private)
            onDone()
        }.padding(8.dp),
    )
}

// ======================================================================= Site settings

private fun permName(p: Int) = when (p) {
    PermissionDelegate.PERMISSION_GEOLOCATION -> "Location"
    PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Notifications"
    PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "Persistent storage"
    PermissionDelegate.PERMISSION_XR -> "VR / AR"
    PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "Autoplay (muted)"
    PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "Autoplay (sound)"
    PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "Protected content (DRM)"
    PermissionDelegate.PERMISSION_TRACKING -> "Tracking protection exception"
    PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "Third-party cookies"
    PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "Local device access"
    PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "Local network access"
    else -> "Permission $p"
}

@Composable
fun SiteSettingsContent(tab: Tab, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var perms by remember { mutableStateOf<List<PermissionDelegate.ContentPermission>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab.url, refresh) {
        Engine.runtime.storageController.getPermissions(tab.url, tab.private).accept { perms = it?.filter { p -> p.permission != PermissionDelegate.PERMISSION_TRACKING } ?: emptyList() }
    }
    val sec = tab.security
    Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Favicon(tab.favicon, tab.host, 52.dp, shape = MaterialShapes.Cookie9Sided.toShape(), container = cs.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tab.host.ifBlank { "This page" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tab.url, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))

        // Connection
        val secure = sec?.isSecure == true
        InfoBlock(
            if (secure) Sym.Lock else Sym.LockOpen,
            if (secure) "Connection is secure" else "Connection is not secure",
            if (secure) {
                val cert = sec?.certificate
                listOfNotNull(
                    cert?.issuerX500Principal?.name?.let { n -> Regex("O=([^,]+)").find(n)?.groupValues?.get(1)?.let { "Verified by $it" } },
                    cert?.notAfter?.let { "Certificate valid until ${DateFormat.getDateInstance().format(it)}" },
                    if (sec?.securityMode == GeckoSession.ProgressDelegate.SecurityInformation.SECURITY_MODE_VERIFIED) "Extended validation" else null,
                    if ((sec?.mixedModeActive ?: 0) == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED) "Some insecure content loaded" else null,
                ).joinToString("\n")
            } else "Information you send to this site could be seen by others.",
            tint = if (secure) cs.primary else cs.error, index = 0, count = 3,
        )
        InfoBlock(
            Sym.Shield,
            if (tab.blockedCount > 0) "${tab.blockedCount} trackers blocked" else "No trackers blocked",
            if (tab.blockedHosts.isNotEmpty()) tab.blockedHosts.take(10).joinToString(", ") else "Enhanced Tracking Protection is ${if (tab.trackingProtection) "on" else "off"} for this tab.",
            tint = cs.tertiary, index = 1, count = 3,
        )
        val ubo = Engine.adBlocker
        InfoBlock(
            Sym.Shield,
            if (ubo?.metaData?.enabled == true) "Ad blocker on" else "Ad blocker off",
            if (ubo?.metaData?.enabled == true) "uBlock Origin is filtering ads on this page. Allow ads on this site from Settings › Filters & allowed sites." else "Turn on uBlock Origin in Settings.",
            tint = cs.primary, index = 2, count = 3,
        )

        SectionLabel("Permissions")
        if (perms.isEmpty()) Text("This site hasn't asked for any permissions.", color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        perms.forEachIndexed { i, p ->
            Surface(shape = segmentShape(i, perms.size), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(permName(p.permission), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = p.value == PermissionDelegate.ContentPermission.VALUE_ALLOW, onCheckedChange = { on ->
                        Engine.runtime.storageController.setPermission(p, if (on) PermissionDelegate.ContentPermission.VALUE_ALLOW else PermissionDelegate.ContentPermission.VALUE_DENY)
                        refresh++
                    })
                }
            }
        }

        val site = dev.vis.ark.browser.Fire.siteOf(tab.url)
        if (dev.vis.ark.data.Prefs.forget && !tab.private && site.isNotBlank()) {
            SectionLabel("Fire mode")
            val proof = site in dev.vis.ark.data.Prefs.fireproof
            ToggleRow(
                "Fireproof $site", proof, 0, 1,
                if (proof) "Stays signed in when Ark forgets everything else" else "Will be erased, and signed out, when Ark closes",
            ) { on ->
                if (on) dev.vis.ark.data.Prefs.setFireproof(site, true)
                else Browser.push(Req.Confirm("Stop fireproofing $site?", "You'll be signed out of $site, and its data erased, the next time Ark closes.", "Remove") {
                    dev.vis.ark.data.Prefs.setFireproof(site, false)
                })
            }
        }

        SectionLabel("This tab")
        ToggleRow("JavaScript", tab.javascript, 0, 3) { on ->
            tab.javascript = on; tab.session?.settings?.setAllowJavascript(on); tab.session?.reload()
        }
        ToggleRow("Tracking protection", tab.trackingProtection, 1, 3) { on ->
            tab.trackingProtection = on; tab.session?.settings?.setUseTrackingProtection(on); tab.session?.reload()
        }
        ToggleRow("Desktop site", tab.desktop, 2, 3) { on -> Browser.setDesktop(tab, on) }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                val h = tab.host
                Browser.push(Req.Confirm("Clear data for $h?", "This signs you out of $h and erases its cookies and saved data. It can't be undone.", "Clear") {
                    val f = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.ALL_CACHES
                    Engine.runtime.storageController.clearDataFromBaseDomain(h, f).accept {
                        Browser.host?.snack("Cleared cookies and site data for $h"); tab.session?.reload()
                    }
                })
                onDone()
            }) { MIcon(Sym.Delete, null); Spacer(Modifier.width(8.dp)); Text("Clear site data") }
            OutlinedButton(onClick = { Browser.newTab("view-source:" + tab.url, tab.private); onDone() }) {
                MIcon(Sym.Code, null); Spacer(Modifier.width(8.dp)); Text("Source")
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun InfoBlock(icon: String, title: String, body: String, tint: androidx.compose.ui.graphics.Color, index: Int, count: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = segmentShape(index, count), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Row(Modifier.padding(18.dp)) {
            MIcon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, index: Int, count: Int, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { if (enabled) onChange(!checked) }, shape = segmentShape(index, count), color = cs.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f))
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

// ======================================================================= Long-press menu

@Composable
fun ContextMenuContent(tab: Tab, el: GeckoSession.ContentDelegate.ContextElement, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val link = el.linkUri
    val src = el.srcUri
    fun copy(s: String) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Link", s))) }; Browser.host?.snack("Copied") }

    Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
        if (el.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && src != null) {
            NetImage(src, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(28.dp)))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            el.title?.takeIf { it.isNotBlank() } ?: el.linkText?.takeIf { it.isNotBlank() } ?: link ?: src ?: "",
            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(link ?: src ?: "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        val rows = buildList<Triple<String, String, () -> Unit>> {
            if (link != null) {
                add(Triple(Sym.Tab, "Open in new tab") { Browser.newTab(link, tab.private, parent = tab) })
                add(Triple(Sym.OpenInNew, "Open in background") {
                    Browser.newTab(link, tab.private, select = false, parent = tab); Browser.host?.snack("Opened in background")
                })
                if (!tab.private) add(Triple(Sym.VisibilityOff, "Open in private tab") { Browser.newTab(link, true) })
                add(Triple(Sym.Link, "Copy link") { copy(link) })
                add(Triple(Sym.Share, "Share link") { Browser.host?.share(el.linkText, link) })
                add(Triple(Sym.Download, "Download link") { Downloads.fetch(Browser.app, link, tab.url, tab.private) })
            }
            if (src != null && el.type != GeckoSession.ContentDelegate.ContextElement.TYPE_NONE) {
                val what = when (el.type) { GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "image"; GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "video"; else -> "audio" }
                add(Triple(Sym.Image, "Open $what in new tab") { Browser.newTab(src, tab.private, parent = tab) })
                add(Triple(Sym.Download, "Download $what") { Downloads.fetch(Browser.app, src, tab.url, tab.private) })
                add(Triple(Sym.ContentCopy, "Copy $what address") { copy(src) })
                add(Triple(Sym.Share, "Share $what") { Browser.host?.share(el.altText, src) })
            }
            if (!el.linkText.isNullOrBlank()) add(Triple(Sym.ContentCopy, "Copy link text") { copy(el.linkText!!) })
        }
        rows.forEachIndexed { i, (icon, label, action) ->
            Surface(onClick = { action(); onDone() }, shape = segmentShape(i, rows.size), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MIcon(icon, null, tint = cs.primary)
                    Spacer(Modifier.width(18.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ======================================================================= Find in page

@Composable
fun FindBar(tab: Tab, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GeckoSession.FinderResult?>(null) }
    val focus = remember { FocusRequester() }
    val finder = tab.session?.finder
    LaunchedEffect(Unit) {
        finder?.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        delay(80); runCatching { focus.requestFocus() }
    }
    LaunchedEffect(query) {
        if (query.isEmpty()) { finder?.clear(); result = null; return@LaunchedEffect }
        delay(120)
        finder?.find(query, 0)?.accept { result = it }
    }
    fun step(back: Boolean) {
        if (query.isNotEmpty()) finder?.find(null, if (back) GeckoSession.FINDER_FIND_BACKWARDS else 0)?.accept { result = it }
    }
    Surface(shape = CircleShape, color = cs.surfaceContainerHighest, shadowElevation = 6.dp, modifier = modifier.padding(12.dp).fillMaxWidth().height(64.dp)) {
        Row(Modifier.padding(start = 22.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Find in page", color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { step(false) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val r = result
            if (r != null) Text(if (r.found) "${r.current}/${r.total}" else "0/0", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { step(true) }) { MIcon(Sym.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { step(false) }) { MIcon(Sym.KeyboardArrowDown, "Next") }
            IconButton(onClick = { finder?.clear(); onClose() }) { MIcon(Sym.Close, "Close") }
        }
    }
}
