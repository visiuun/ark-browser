@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.vis.ark.MainActivity
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Req
import dev.vis.ark.data.Prefs
import dev.vis.ark.data.Vault
import dev.vis.ark.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete

// ======================================================================= prompts from web pages

/** Dialogs for saved passwords, cards, addresses, passkeys and "Sign in with…". Returns false if [req] isn't one. */
@Composable
fun CredentialPrompt(req: Req, done: () -> Unit): Boolean {
    when (req) {
        is Req.LoginSave -> {
            val opt = req.p.options.first()
            val l = opt.value
            AlertDialog(
                onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
                icon = { MIcon(Sym.Password, null) },
                title = { Text(if (req.update) "Update password for ${req.host}?" else "Save password for ${req.host}?") },
                text = {
                    Column {
                        if (!l.username.isNullOrBlank()) Text(l.username!!, style = MaterialTheme.typography.titleMedium)
                        Text("•".repeat((l.password?.length ?: 8).coerceIn(6, 16)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(8.dp))
                        Text("Saved passwords are encrypted on this phone and filled in for you next time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        Button({ req.r.complete(req.p.confirm(opt)); done(); Browser.host?.snack(if (req.update) "Password updated" else "Password saved") }, Modifier.fillMaxWidth()) {
                            Text(if (req.update) "Update" else "Save")
                        }
                        TextButton({ req.r.complete(req.p.dismiss()); done() }, Modifier.fillMaxWidth()) { Text("Not now") }
                        if (!req.update) TextButton({ Prefs.setNeverSave(req.host, true); req.r.complete(req.p.dismiss()); done() }, Modifier.fillMaxWidth()) {
                            Text("Never for this site")
                        }
                    }
                },
                properties = DialogProperties(dismissOnClickOutside = false),
            )
        }

        is Req.LoginSelect -> PickList(
            title = "Sign in to ${req.host}",
            icon = Sym.Password,
            items = req.p.options.toList(),
            label = { o ->
                if (o.hint and Autocomplete.SelectOption.Hint.GENERATED != 0) "Use a strong password" to (o.value.password ?: "")
                else (o.value.username?.takeIf { it.isNotBlank() } ?: "(no username)") to Vault.hostOf(o.value.origin)
            },
            onPick = { o -> req.r.complete(req.p.confirm(o)); done() },
            onCancel = { req.r.complete(req.p.dismiss()); done() },
        )

        is Req.CardSave -> {
            val opt = req.p.options.first()
            val c = opt.value
            AlertDialog(
                onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
                icon = { MIcon(Sym.Lock, null) },
                title = { Text("Save this card?") },
                text = { Text("${Vault.brandOf(c.number.orEmpty())} •••• ${c.number.orEmpty().filter { it.isDigit() }.takeLast(4)}" + expiry(c.expirationMonth, c.expirationYear) + "\n\nStored encrypted on this phone. Ark asks for your fingerprint or screen lock before filling it in.") },
                confirmButton = { TextButton({ req.r.complete(req.p.confirm(opt)); done() }) { Text("Save") } },
                dismissButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("Not now") } },
            )
        }

        is Req.CardSelect -> PickList(
            title = "Fill a payment card",
            icon = Sym.Lock,
            items = req.p.options.toList(),
            label = { o -> "${Vault.brandOf(o.value.number.orEmpty())} •••• ${o.value.number.orEmpty().filter { it.isDigit() }.takeLast(4)}" to (o.value.name.orEmpty() + expiry(o.value.expirationMonth, o.value.expirationYear)) },
            onPick = { o ->
                done()
                val host = Browser.host
                if (host == null) req.r.complete(req.p.dismiss())
                else host.verifyUser("Fill your card") { ok -> req.r.complete(if (ok) req.p.confirm(o) else req.p.dismiss()) }
            },
            onCancel = { req.r.complete(req.p.dismiss()); done() },
        )

        is Req.AddressSave -> {
            val opt = req.p.options.first()
            val a = Vault.Address("", Vault.addressFields(opt.value))
            AlertDialog(
                onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
                icon = { MIcon(Sym.LocationOn, null) },
                title = { Text("Save this address?") },
                text = { Text(a.label + "\n\nArk can fill it in on forms next time.") },
                confirmButton = { TextButton({ req.r.complete(req.p.confirm(opt)); done() }) { Text("Save") } },
                dismissButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("Not now") } },
            )
        }

        is Req.AddressSelect -> PickList(
            title = "Fill an address",
            icon = Sym.LocationOn,
            items = req.p.options.toList(),
            label = { o -> Vault.Address("", Vault.addressFields(o.value)).let { it.label to it["email"] } },
            onPick = { o -> req.r.complete(req.p.confirm(o)); done() },
            onCancel = { req.r.complete(req.p.dismiss()); done() },
        )

        is Req.RelatedOrigin -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() },
            icon = { MIcon(Sym.Fingerprint, null) },
            title = { Text(if (req.p.isCreate) "Create a passkey for ${req.p.rpId}?" else "Use your ${req.p.rpId} passkey?") },
            text = { Text("${Vault.hostOf(req.p.origin)} says it belongs to ${req.p.rpId} and wants to use the same passkey.") },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.ALLOW)); done() }) { Text("Continue") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() }) { Text("Cancel") } },
        )

        is Req.IdpProvider -> PickList(
            title = "Sign in with",
            icon = Sym.Public,
            items = req.p.providers.toList(),
            label = { it.name to it.domain },
            onPick = { req.r.complete(req.p.confirm(it.id)); done() },
            onCancel = { req.r.complete(req.p.dismiss()); done() },
        )

        is Req.IdpAccount -> PickList(
            title = "Sign in with ${req.p.provider.name}",
            icon = Sym.Public,
            items = req.p.accounts.toList(),
            label = { it.name to it.email },
            onPick = { req.r.complete(req.p.confirm(it.id)); done() },
            onCancel = { req.r.complete(req.p.dismiss()); done() },
        )

        is Req.IdpPolicy -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.confirm(false)); done() },
            icon = { MIcon(Sym.Public, null) },
            title = { Text("Sign in to ${req.p.host} with ${req.p.providerDomain}?") },
            text = {
                Column {
                    Text("${req.p.providerDomain} will share your name, email address and profile picture with ${req.p.host}.")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        req.p.privacyPolicyUrl?.let { u -> TextButton({ Browser.open(u, inNewTab = true) }) { Text("Privacy policy") } }
                        req.p.termsOfServiceUrl?.let { u -> TextButton({ Browser.open(u, inNewTab = true) }) { Text("Terms") } }
                    }
                }
            },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(true)); done() }) { Text("Continue") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(false)); done() }) { Text("Cancel") } },
        )

        else -> return false
    }
    return true
}

private fun expiry(m: String?, y: String?) = if (!m.isNullOrBlank() && !y.isNullOrBlank()) " · ${m.padStart(2, '0')}/${y.takeLast(2)}" else ""

@Composable
private fun <T> PickList(title: String, icon: String, items: List<T>, label: (T) -> Pair<String, String>, onPick: (T) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { MIcon(icon, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, it ->
                    val (a, b) = label(it)
                    Surface(onClick = { onPick(it) }, shape = segmentShape(i, items.size), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(a, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (b.isNotBlank()) Text(b, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

// ======================================================================= Settings › Passwords

private enum class VaultTab { Passwords, Cards, Addresses }

fun copySecret(ctx: android.content.Context, label: String, text: String, sensitive: Boolean) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Browser.host?.snack("$label copied")
}

@Composable
fun PasswordsScreen(activity: MainActivity, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var unlocked by remember { mutableStateOf(!activity.canAuthenticate()) }
    var tab by remember { mutableStateOf(VaultTab.Passwords) }
    var query by remember { mutableStateOf("") }
    var openLogin by remember { mutableStateOf<Vault.Login?>(null) }
    var addLogin by remember { mutableStateOf(false) }
    var addCard by remember { mutableStateOf(false) }
    var addAddress by remember { mutableStateOf<Vault.Address?>(null) }
    var neverDialog by remember { mutableStateOf(false) }
    val v = Vault.version.intValue

    LaunchedEffect(Unit) { if (!unlocked) activity.verifyUser("Show saved passwords") { unlocked = it } }

    FullScreen("Passwords", onBack, actions = {
        if (unlocked) IconButton(onClick = {
            when (tab) {
                VaultTab.Passwords -> addLogin = true
                VaultTab.Cards -> addCard = true
                VaultTab.Addresses -> addAddress = Vault.Address("", emptyMap())
            }
        }) { MIcon(Sym.Add, "Add") }
    }) {
        if (!unlocked) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MIcon(Sym.Lock, null, Modifier.size(48.dp), tint = cs.primary)
                Spacer(Modifier.size(16.dp))
                Text("Your passwords are locked", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(16.dp))
                Button({ activity.verifyUser("Show saved passwords") { unlocked = it } }) { Text("Unlock") }
            }
            return@FullScreen
        }
        @Suppress("UNUSED_EXPRESSION") v
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                ChoiceGroup("Show", listOf("Passwords", "Cards", "Addresses"), tab.ordinal, index = 0, count = 1) { tab = VaultTab.entries[it] }
            }
            when (tab) {
                VaultTab.Passwords -> {
                    item { SectionLabel("Ark password manager") }
                    item { ToggleRow("Offer to save passwords", Prefs.savePasswords, 0, 4, "After you sign in to a site") { Prefs.savePasswords = it } }
                    item { ToggleRow("Fill in saved passwords", Prefs.fillLogins, 1, 4, "Pick an account when you tap a sign-in field") { Prefs.fillLogins = it; dev.vis.ark.browser.Engine.applySettings() } }
                    item { ToggleRow("Suggest strong passwords", Prefs.suggestPasswords, 2, 4, "On sign-up and change-password forms") { Prefs.suggestPasswords = it; dev.vis.ark.browser.Engine.applySettings() } }
                    item {
                        val n = Prefs.neverSave.size
                        ClickRow("Never saved", if (n == 0) "No sites" else "$n ${if (n == 1) "site" else "sites"}", 3, 4) { neverDialog = true }
                    }

                    item { SectionLabel("Passkeys & other password managers") }
                    item {
                        ToggleRow(
                            "Android autofill in pages", Prefs.systemAutofill, 0, 4,
                            "Let your autofill service (Google, Bitwarden, 1Password, Proton Pass, Samsung Pass…) fill sign-in forms",
                        ) { Prefs.systemAutofill = it; Browser.viewEpoch++ }
                    }
                    item {
                        ClickRow("Autofill service", "Choose which password manager fills apps and pages", 1, 4) {
                            val i = Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(android.net.Uri.parse("package:${activity.packageName}"))
                            runCatching { activity.startActivity(i) }.onFailure { runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                        }
                    }
                    item {
                        ClickRow(
                            "Passkeys",
                            if (Build.VERSION.SDK_INT >= 34) "Sites use the passkey providers you pick in Android: Google Password Manager, Bitwarden, 1Password…"
                            else "Passkeys come from Google Password Manager on this Android version",
                            2, 4,
                        ) {
                            val i = if (Build.VERSION.SDK_INT >= 34) Intent("android.settings.CREDENTIAL_PROVIDER").setData(android.net.Uri.parse("package:${activity.packageName}"))
                            else Intent(android.provider.Settings.ACTION_SETTINGS)
                            runCatching { activity.startActivity(i) }.onFailure { runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                        }
                    }
                    item {
                        ClickRow("Import or export", "CSV from Chrome, Firefox, Bitwarden, 1Password, Proton Pass…", 3, 4) {
                            activity.pickFiles(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "*/*"), false, false) { uris ->
                                val u = uris?.firstOrNull() ?: return@pickFiles
                                Thread {
                                    val text = runCatching { activity.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                                    activity.runOnUiThread {
                                        val n = text?.let { Vault.importCsv(it) } ?: 0
                                        Browser.host?.snack(if (n > 0) "Imported $n ${if (n == 1) "password" else "passwords"}" else "No passwords found in that file", if (Vault.logins.isNotEmpty()) "Export" else null) {
                                            activity.verifyUser("Export passwords") { ok -> if (ok) activity.exportPasswords(Vault.exportCsv()) }
                                        }
                                    }
                                }.start()
                            }
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Saved (${Vault.logins.size})", Modifier.weight(1f))
                            if (Vault.logins.isNotEmpty()) TextButton({ activity.verifyUser("Export passwords") { ok -> if (ok) activity.exportPasswords(Vault.exportCsv()) } }, Modifier.padding(top = 12.dp)) {
                                Text("Export")
                            }
                        }
                    }
                    if (Vault.logins.size > 6) item {
                        OutlinedTextField(
                            query, { query = it }, placeholder = { Text("Search passwords") }, singleLine = true, shape = CircleShape,
                            leadingIcon = { MIcon(Sym.Search, null) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                    val list = Vault.logins.filter { query.isBlank() || it.host.contains(query, true) || it.username.contains(query, true) }
                        .sortedWith(compareBy({ it.host }, { it.username }))
                    itemsIndexed(list, key = { _, l -> l.guid }) { i, l ->
                        Surface(onClick = { openLogin = l }, shape = segmentShape(i, list.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Favicon(Net.faviconFor(l.host), l.host, 40.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(l.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(l.username.ifBlank { "(no username)" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { copySecret(activity, "Password", l.password, true) }) { MIcon(Sym.ContentCopy, "Copy password", tint = cs.onSurfaceVariant) }
                            }
                        }
                    }
                    if (Vault.logins.isEmpty()) item {
                        Text("When you sign in to a site, Ark offers to save the password here. You can also import them from another browser or password manager.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }

                VaultTab.Cards -> {
                    item { SectionLabel("Payment methods") }
                    item { ToggleRow("Save and fill payment cards", Prefs.autofillCards, 0, 1, "Ark asks for your fingerprint or screen lock before filling a card") { Prefs.autofillCards = it; dev.vis.ark.browser.Engine.applySettings() } }
                    item { SectionLabel("Cards (${Vault.cards.size})") }
                    itemsIndexed(Vault.cards, key = { _, c -> c.guid }) { i, c ->
                        Surface(shape = segmentShape(i, Vault.cards.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                            Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${c.brand} •••• ${c.last4}", style = MaterialTheme.typography.titleSmall)
                                    Text(c.name + expiry(c.expMonth, c.expYear), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { Vault.deleteCard(c.guid) }) { MIcon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant) }
                            }
                        }
                    }
                    if (Vault.cards.isEmpty()) item { Text("Cards you enter on checkout pages can be saved here.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                }

                VaultTab.Addresses -> {
                    item { SectionLabel("Addresses") }
                    item { ToggleRow("Save and fill addresses", Prefs.autofillAddresses, 0, 1, "Shipping, billing, phone and email") { Prefs.autofillAddresses = it; dev.vis.ark.browser.Engine.applySettings() } }
                    item { SectionLabel("Saved (${Vault.addresses.size})") }
                    itemsIndexed(Vault.addresses, key = { _, a -> a.guid }) { i, a ->
                        Surface(onClick = { addAddress = a }, shape = segmentShape(i, Vault.addresses.size), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                            Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(a["email"], a["tel"]).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { Vault.deleteAddress(a.guid) }) { MIcon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant) }
                            }
                        }
                    }
                    if (Vault.addresses.isEmpty()) item { Text("Addresses you fill in on forms can be saved here.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }

    openLogin?.let { l -> LoginDialog(l, onDismiss = { openLogin = null }, onOpenSite = { Browser.open(l.origin, inNewTab = true); onBack() }) }
    if (addLogin) LoginDialog(null, onDismiss = { addLogin = false }, onOpenSite = {})
    if (addCard) CardDialog(onDismiss = { addCard = false })
    addAddress?.let { a -> AddressDialog(a, onDismiss = { addAddress = null }) }
    if (neverDialog) AlertDialog(
        onDismissRequest = { neverDialog = false },
        title = { Text("Never saved") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (Prefs.neverSave.isEmpty()) Text("Sites where you tap “Never for this site” show up here.")
                Prefs.neverSave.toList().forEach { site ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(site, Modifier.weight(1f))
                        IconButton(onClick = { Prefs.setNeverSave(site, false) }) { MIcon(Sym.Close, "Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ neverDialog = false }) { Text("Done") } },
    )
}

@Composable
private fun LoginDialog(login: Vault.Login?, onDismiss: () -> Unit, onOpenSite: () -> Unit) {
    val ctx = LocalContext.current
    var site by remember { mutableStateOf(login?.origin ?: "https://") }
    var user by remember { mutableStateOf(login?.username ?: "") }
    var pass by remember { mutableStateOf(login?.password ?: "") }
    var show by remember { mutableStateOf(login == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val origin = runCatching {
        val u = android.net.Uri.parse(if ("://" in site) site.trim() else "https://${site.trim()}")
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.authority}"
    }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(login?.host ?: "Add password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (login == null) OutlinedTextField(site, { site = it }, label = { Text("Website") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(
                    user, { user = it }, label = { Text("Username") }, singleLine = true,
                    trailingIcon = { if (user.isNotEmpty()) IconButton(onClick = { copySecret(ctx, "Username", user, false) }) { MIcon(Sym.ContentCopy, "Copy username") } },
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { show = !show }) { MIcon(if (show) Sym.VisibilityOff else Sym.Password, if (show) "Hide" else "Show") }
                            IconButton(onClick = { copySecret(ctx, "Password", pass, true) }) { MIcon(Sym.ContentCopy, "Copy password") }
                        }
                    },
                )
                if (login != null) Row {
                    TextButton(onOpenSite) { Text("Open site") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = origin != null && pass.isNotEmpty(), onClick = {
                Vault.upsertLogin(origin ?: return@TextButton, user, pass, login?.formActionOrigin, login?.httpRealm, login?.guid)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && login != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this password?") },
        text = { Text("${login.username.ifBlank { "(no username)" }} on ${login.host}") },
        confirmButton = { TextButton({ Vault.deleteLogin(login.guid); confirmDelete = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CardDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val digits = number.filter { it.isDigit() }
    val ok = digits.length in 12..19 && month.toIntOrNull() in 1..12 && (year.toIntOrNull() ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(number, { number = it }, label = { Text("Card number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(name, { name = it }, label = { Text("Name on card") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(month, { month = it.take(2) }, label = { Text("MM") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(year, { year = it.take(4) }, label = { Text("YYYY") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val y = if (year.length == 2) "20$year" else year
                Vault.upsertCard(name.trim(), digits, month.toInt().toString(), y); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private val ADDRESS_LABELS = mapOf(
    "name" to "Full name", "organization" to "Organization", "streetAddress" to "Street address", "addressLevel2" to "City",
    "addressLevel1" to "State / province", "postalCode" to "Postal code", "country" to "Country (e.g. US, IT)", "tel" to "Phone", "email" to "Email",
)

@Composable
private fun AddressDialog(a: Vault.Address, onDismiss: () -> Unit) {
    val values = remember { mutableStateOf(ADDRESS_LABELS.keys.associateWith { a[it] }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a.guid.isBlank()) "Add address" else "Edit address") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ADDRESS_LABELS.forEach { (k, label) ->
                    OutlinedTextField(
                        values.value[k].orEmpty(), { values.value = values.value + (k to it) }, label = { Text(label) },
                        singleLine = k != "streetAddress",
                        keyboardOptions = KeyboardOptions(keyboardType = when (k) { "tel" -> KeyboardType.Phone; "email" -> KeyboardType.Email; else -> KeyboardType.Text }),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = values.value.values.any { it.isNotBlank() }, onClick = {
                Vault.upsertAddress(a.fields + values.value, a.guid.ifBlank { null }); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
