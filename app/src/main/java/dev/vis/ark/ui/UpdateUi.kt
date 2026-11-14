@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vis.ark.data.Prefs
import dev.vis.ark.update.UpdateState
import dev.vis.ark.update.Updater

private fun fmtSize(bytes: Long): String = "%.0f MB".format(bytes / 1_048_576.0)

/** True while there's a newer release to show (or one is being installed). */
fun updatePending(): Boolean =
    Updater.state.value in listOf(UpdateState.AVAILABLE, UpdateState.DOWNLOADING, UpdateState.INSTALLING)

/** Banner shown at the top of Settings and on the home screen when a newer release is on GitHub. */
@Composable
fun UpdateCard(onOpenPage: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val rel = Updater.latest.value ?: return
    Surface(shape = RoundedCornerShape(32.dp), color = cs.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.secondary), contentAlignment = Alignment.Center) {
                    MIcon(Sym.Download, null, tint = cs.onSecondary)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Update available", style = MaterialTheme.typography.titleLarge, color = cs.onSecondaryContainer)
                    Text(
                        "Ark ${rel.version} · you have ${Updater.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            UpdateAction(onOpenPage)
        }
    }
}

@Composable
private fun UpdateAction(onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val rel = Updater.latest.value
    when (Updater.state.value) {
        UpdateState.DOWNLOADING -> {
            Text("Downloading… ${(Updater.progress.floatValue * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { Updater.progress.floatValue }, modifier = Modifier.fillMaxWidth())
        }
        UpdateState.INSTALLING -> Text("Confirm the install in the system dialog.", style = MaterialTheme.typography.bodyMedium)
        else -> if (rel != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Updater.install(ctx) }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Update now" + if (rel.apkSize > 0) " (${fmtSize(rel.apkSize)})" else "")
            }
            if (rel.page.isNotEmpty()) OutlinedButton(
                onClick = { onOpenPage(rel.page) },
                modifier = Modifier.height(52.dp),
            ) { Text("What's new") }
        }
    }
    if (Updater.message.value.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(Updater.message.value, style = MaterialTheme.typography.bodySmall, color = cs.error)
    }
}

/** The "Updates" rows in Settings › About. */
@Composable
fun UpdateSection(onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val state = Updater.state.value
    val needsNotify = Build.VERSION.SDK_INT >= 33 && Prefs.autoUpdate &&
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val count = if (needsNotify) 3 else 2

    Surface(shape = segmentShape(0, count), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            UpdateState.AVAILABLE, UpdateState.DOWNLOADING, UpdateState.INSTALLING ->
                                "Ark ${Updater.latest.value?.version} is available"
                            UpdateState.UP_TO_DATE -> "You're up to date"
                            UpdateState.CHECKING -> "Checking for updates…"
                            UpdateState.ERROR -> "Couldn't check for updates"
                            UpdateState.IDLE -> "Updates"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Installed: Ark ${Updater.currentVersion}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (state == UpdateState.CHECKING) LoadingIndicator(Modifier.size(36.dp))
                else if (state != UpdateState.DOWNLOADING && state != UpdateState.INSTALLING)
                    TextButton(onClick = { Updater.check() }) { Text("Check now") }
            }
            if (state == UpdateState.AVAILABLE || state == UpdateState.DOWNLOADING || state == UpdateState.INSTALLING) {
                Spacer(Modifier.height(14.dp))
                UpdateAction(onOpenPage)
            } else if (state == UpdateState.ERROR && Updater.message.value.isNotEmpty()) {
                Text(Updater.message.value, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
    }
    ToggleRow(
        "Check for updates automatically", Prefs.autoUpdate, 1, count,
        "Once a day, from GitHub releases. You get a notification when a new version is out.",
    ) { Prefs.autoUpdate = it; if (it) Updater.checkIfDue() }
    if (needsNotify) {
        Surface(shape = segmentShape(2, count), color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Allow notifications to hear about updates", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
        }
    }
}
