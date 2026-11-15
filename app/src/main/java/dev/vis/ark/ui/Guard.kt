package dev.vis.ark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Covers the whole app while the app lock is engaged. */
@Composable
fun LockScreen(onUnlock: () -> Unit, onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onLeave)
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(onClick = onUnlock, shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(112.dp)) {
                Box(contentAlignment = Alignment.Center) { MIcon(Sym.Fingerprint, "Unlock", Modifier.size(60.dp), tint = cs.onPrimaryContainer) }
            }
            Spacer(Modifier.height(28.dp))
            Text("Ark is locked", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your fingerprint, face or screen lock to open your tabs.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

/** Shown for the moment fire mode spends erasing the last session. */
@Composable
fun BurnScreen() {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "burn")
    val flicker by t.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "flicker")
    BackHandler {}
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(112.dp).graphicsLayer { scaleX = flicker; scaleY = flicker }.clip(CircleShape).background(cs.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) { MIcon("*" + Sym.Fire, null, Modifier.size(60.dp), tint = cs.onTertiaryContainer) }
            Spacer(Modifier.height(28.dp))
            Text("Starting fresh", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Erasing your last session", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
}
