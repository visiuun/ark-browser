@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import dev.vis.ark.data.Prefs
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Req
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

@Composable
fun PromptHost() {
    val req = Browser.prompts.firstOrNull() ?: return
    val done = { Browser.pop(req) }
    if (CredentialPrompt(req, done)) return
    when (req) {
        is Req.Alert -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
            title = req.p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            text = { Text(req.p.message ?: "") },
            confirmButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("OK") } },
        )

        is Req.Button -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
            title = req.p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            text = { Text(req.p.message ?: "") },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)); done() }) { Text("OK") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)); done() }) { Text("Cancel") } },
        )

        is Req.Text -> {
            var v by remember(req) { mutableStateOf(req.p.defaultValue ?: "") }
            AlertDialog(
                onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
                title = req.p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                text = { Column { Text(req.p.message ?: ""); Spacer(Modifier.size(12.dp)); OutlinedTextField(v, { v = it }, singleLine = true) } },
                confirmButton = { TextButton({ req.r.complete(req.p.confirm(v)); done() }) { Text("OK") } },
                dismissButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("Cancel") } },
            )
        }

        is Req.Auth -> {
            var user by remember(req) { mutableStateOf(req.p.authOptions.username ?: "") }
            var pass by remember(req) { mutableStateOf(req.p.authOptions.password ?: "") }
            val onlyPass = (req.p.authOptions.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
            AlertDialog(
                onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
                icon = { MIcon(Sym.Password, null) },
                title = { Text(req.p.title?.takeIf { it.isNotBlank() } ?: "Sign in") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(req.p.message ?: "")
                        if (!onlyPass) OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                        OutlinedTextField(
                            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                },
                confirmButton = { TextButton({ req.r.complete(if (onlyPass) req.p.confirm(pass) else req.p.confirm(user, pass)); done() }) { Text("Sign in") } },
                dismissButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("Cancel") } },
            )
        }

        is Req.Choice -> ChoiceDialog(req, done)
        is Req.ColorPick -> ColorDialog(req, done)
        is Req.DateTime -> DateTimeDialog(req, done)

        is Req.Popup -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() },
            icon = { MIcon(Sym.OpenInBrowser, null) },
            title = { Text("Open pop-up?") },
            text = { Text("This site wants to open a pop-up window:\n${req.p.targetUri ?: ""}") },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.ALLOW)); done() }) { Text("Allow") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() }) { Text("Block") } },
        )

        is Req.BeforeUnload -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() },
            title = { Text("Leave page?") },
            text = { Text("Changes you made may not be saved.") },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.ALLOW)); done() }) { Text("Leave") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() }) { Text("Stay") } },
        )

        is Req.Repost -> AlertDialog(
            onDismissRequest = { req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() },
            title = { Text("Resend data?") },
            text = { Text("This page needs to resend information you entered earlier (like a payment or form).") },
            confirmButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.ALLOW)); done() }) { Text("Resend") } },
            dismissButton = { TextButton({ req.r.complete(req.p.confirm(AllowOrDeny.DENY)); done() }) { Text("Cancel") } },
        )

        is Req.Permission -> {
            val (icon, what) = when (req.perm.permission) {
                PermissionDelegate.PERMISSION_GEOLOCATION -> Sym.LocationOn to "know your location"
                PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> Sym.Notifications to "send you notifications"
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> Sym.Extension to "play protected (DRM) content"
                PermissionDelegate.PERMISSION_XR -> Sym.Extension to "use virtual reality devices"
                PermissionDelegate.PERMISSION_STORAGE_ACCESS -> Sym.Language to "use cookies on ${req.perm.thirdPartyOrigin ?: "another site"}"
                PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> Sym.Extension to "access devices on this phone"
                PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> Sym.Language to "access devices on your local network"
                else -> Sym.Extension to "use an extra feature"
            }
            if (req.perm.permission == PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) (Browser.host as? dev.vis.ark.MainActivity)?.askNotificationsOnce()
            PermissionDialog(icon, req.host, what,
                onAllow = { req.r.complete(PermissionDelegate.ContentPermission.VALUE_ALLOW); done() },
                onDeny = { req.r.complete(PermissionDelegate.ContentPermission.VALUE_DENY); done() })
        }

        is Req.Media -> {
            val cam = req.video?.firstOrNull { it.source == PermissionDelegate.MediaSource.SOURCE_CAMERA } ?: req.video?.firstOrNull()
            val mic = req.audio?.firstOrNull { it.source == PermissionDelegate.MediaSource.SOURCE_MICROPHONE } ?: req.audio?.firstOrNull()
            val what = listOfNotNull(cam?.let { "camera" }, mic?.let { "microphone" }).joinToString(" and ").ifBlank { "media devices" }
            PermissionDialog(if (cam != null) Sym.CameraAlt else Sym.Mic, req.host, "use your $what",
                onAllow = { req.cb.grant(cam, mic); done() },
                onDeny = { req.cb.reject(); done() })
        }

        is Req.Confirm -> AlertDialog(
            onDismissRequest = done,
            title = { Text(req.title) },
            text = { Text(req.message) },
            confirmButton = { TextButton({ req.action(); done() }) { Text(req.confirm) } },
            dismissButton = { TextButton(done) { Text("Cancel") } },
        )

        is Req.Fireproof -> {
            val names = req.sites.joinToString(" and ")
            val one = req.sites.size == 1
            AlertDialog(
                onDismissRequest = done,
                icon = { MIcon("*" + Sym.Fire, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Fireproof $names?") },
                text = {
                    Text(
                        "Fireproofed sites keep their cookies, so you stay signed in. If you don't, Ark erases " +
                            (if (one) "this sign-in" else "these sign-ins") + " along with everything else when it closes, and you'll have to sign in again.",
                    )
                },
                confirmButton = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        androidx.compose.material3.Button({ req.sites.forEach { Prefs.setFireproof(it, true) }; done() }, Modifier.fillMaxWidth()) { Text("Fireproof") }
                        TextButton(done, Modifier.fillMaxWidth()) { Text("Not now") }
                        TextButton({ req.sites.forEach { Prefs.neverFireproof(it) }; done() }, Modifier.fillMaxWidth()) {
                            Text(if (one) "Don't ask again for this site" else "Don't ask again for these sites")
                        }
                    }
                },
                properties = DialogProperties(dismissOnClickOutside = false),
            )
        }

        is Req.Context -> Unit // shown as a sheet by ArkRoot
        else -> Unit
    }
}

@Composable
private fun PermissionDialog(icon: String, host: String, what: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = { MIcon(icon, null) },
        title = { Text("Allow $host to $what?") },
        text = { Text("You can change this anytime in Site Settings.") },
        confirmButton = { TextButton(onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onDeny) { Text("Block") } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

@Composable
private fun ChoiceDialog(req: Req.Choice, done: () -> Unit) {
    val p = req.p
    val multiple = p.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
    val flat = remember(req) {
        buildList {
            fun walk(arr: Array<PromptDelegate.ChoicePrompt.Choice>?, depth: Int) {
                arr?.forEach { c -> add(c to depth); if (c.items != null) walk(c.items, depth + 1) }
            }
            walk(p.choices, 0)
        }
    }
    val selected = remember(req) { mutableStateListOf<String>().apply { addAll(flat.filter { it.first.selected && it.first.items == null }.map { it.first.id }) } }
    AlertDialog(
        onDismissRequest = { req.r.complete(p.dismiss()); done() },
        title = p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(flat) { (c, depth) ->
                    when {
                        c.separator -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        c.items != null -> Text(c.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = (depth * 12).dp, top = 10.dp, bottom = 4.dp))
                        else -> Row(
                            Modifier.fillMaxWidth().clip(CircleShape).clickable(enabled = !c.disabled) {
                                if (multiple) { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }
                                else { req.r.complete(p.confirm(c)); done() }
                            }.padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiple) Checkbox(c.id in selected, null, enabled = !c.disabled)
                            else RadioButton(c.selected, null, enabled = !c.disabled)
                            Spacer(Modifier.width(8.dp))
                            Text(c.label, color = if (c.disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) TextButton({ req.r.complete(p.confirm(selected.toTypedArray())); done() }) { Text("OK") }
        },
        dismissButton = { TextButton({ req.r.complete(p.dismiss()); done() }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(req: Req.ColorPick, done: () -> Unit) {
    val presets = listOf(
        "#000000", "#ffffff", "#9e9e9e", "#f44336", "#e91e63", "#9c27b0", "#673ab7", "#3f51b5", "#2196f3", "#03a9f4",
        "#00bcd4", "#009688", "#4caf50", "#8bc34a", "#cddc39", "#ffeb3b", "#ffc107", "#ff9800", "#ff5722", "#795548",
    ) + (req.p.predefinedValues?.toList() ?: emptyList())
    var hex by remember(req) { mutableStateOf(req.p.defaultValue ?: "#000000") }
    AlertDialog(
        onDismissRequest = { req.r.complete(req.p.dismiss()); done() },
        title = { Text("Pick a color") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.distinct().forEach { c ->
                        val col = runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrNull() ?: return@forEach
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(col)
                                .border(if (c.equals(hex, true)) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { hex = c }
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                OutlinedTextField(hex, { hex = it }, label = { Text("Hex") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ req.r.complete(req.p.confirm(hex)); done() }) { Text("OK") } },
        dismissButton = { TextButton({ req.r.complete(req.p.dismiss()); done() }) { Text("Cancel") } },
    )
}

@Composable
private fun DateTimeDialog(req: Req.DateTime, done: () -> Unit) {
    val p = req.p
    val type = p.type
    val needsDate = type != PromptDelegate.DateTimePrompt.Type.TIME
    val needsTime = type == PromptDelegate.DateTimePrompt.Type.TIME || type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL
    val def = p.defaultValue.orEmpty()
    val initialDate: LocalDate = runCatching {
        when (type) {
            PromptDelegate.DateTimePrompt.Type.MONTH -> LocalDate.parse("$def-01")
            PromptDelegate.DateTimePrompt.Type.WEEK -> LocalDate.now()
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> LocalDateTime.parse(def).toLocalDate()
            else -> LocalDate.parse(def)
        }
    }.getOrDefault(LocalDate.now())
    val initialTime: LocalTime = runCatching {
        if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) LocalDateTime.parse(def).toLocalTime() else LocalTime.parse(def)
    }.getOrDefault(LocalTime.now())

    var stage by remember(req) { mutableStateOf(if (needsDate) 0 else 1) }
    var date by remember(req) { mutableStateOf(initialDate) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), initialDisplayMode = DisplayMode.Picker)
    val timeState = rememberTimePickerState(initialTime.hour, initialTime.minute)

    fun finish(d: LocalDate, t: LocalTime) {
        val v = when (type) {
            PromptDelegate.DateTimePrompt.Type.DATE -> d.toString()
            PromptDelegate.DateTimePrompt.Type.MONTH -> d.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            PromptDelegate.DateTimePrompt.Type.WEEK -> "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            PromptDelegate.DateTimePrompt.Type.TIME -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
            else -> d.toString() + "T" + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        req.r.complete(p.confirm(v)); done()
    }

    val cancel = { req.r.complete(p.dismiss()); done() }
    if (stage == 0) {
        DatePickerDialog(
            onDismissRequest = cancel,
            confirmButton = {
                TextButton({
                    val ms = dateState.selectedDateMillis
                    if (ms != null) date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    if (needsTime) stage = 1 else finish(date, initialTime)
                }) { Text(if (needsTime) "Next" else "OK") }
            },
            dismissButton = {
                Row {
                    TextButton({ req.r.complete(p.confirm("")); done() }) { Text("Clear") }
                    TextButton(cancel) { Text("Cancel") }
                }
            },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = cancel,
            text = { TimePicker(timeState) },
            confirmButton = { TextButton({ finish(date, LocalTime.of(timeState.hour, timeState.minute)) }) { Text("OK") } },
            dismissButton = { TextButton(cancel) { Text("Cancel") } },
        )
    }
}
