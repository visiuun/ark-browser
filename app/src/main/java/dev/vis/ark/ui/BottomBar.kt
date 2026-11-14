@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.vis.ark.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.vis.ark.browser.Tab
import kotlin.math.abs

@Composable
fun BottomBar(
    tab: Tab?,
    tabCount: Int,
    onTabs: () -> Unit,
    onTabsLong: () -> Unit,
    onNew: () -> Unit,
    onNewLong: () -> Unit,
    onMenu: () -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val private = tab?.private == true
    val bg by animateColorAsState(if (private) cs.tertiaryContainer else cs.surfaceContainer, label = "bar")
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .pointerInput(Unit) {
                var dx = 0f; var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        if (dy < -60 && abs(dy) > abs(dx)) onMenu()
                        else if (abs(dx) > 110 && abs(dx) > abs(dy)) onSwipe(if (dx < 0) 1 else -1)
                    },
                ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
            }
            .navigationBarsPadding()
    ) {
        // Top edge: Arc's gradient hairline, which turns into an expressive wavy progress bar while loading.
        Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(tab?.loading == true, label = "progress") { loading ->
                if (loading && tab != null) {
                    LinearWavyProgressIndicator(
                        progress = { (tab.progress / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        color = if (private) cs.onTertiaryContainer else cs.primary,
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).background(
                            Brush.horizontalGradient(
                                if (private) listOf(cs.tertiary, cs.onTertiaryContainer, cs.tertiary)
                                else listOf(cs.primary.copy(alpha = 0.85f), cs.tertiary.copy(alpha = 0.7f), cs.secondary.copy(alpha = 0.85f))
                            )
                        )
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).combinedClickable(onClick = onTabs, onLongClick = onTabsLong),
                contentAlignment = Alignment.Center,
            ) { TabsGlyph(tabCount, tint = if (private) cs.onTertiaryContainer else cs.onSurfaceVariant) }

            Box(
                Modifier
                    .width(118.dp).height(58.dp)
                    .clip(CircleShape)
                    .background(if (private) cs.tertiary.copy(alpha = 0.25f) else cs.surfaceContainerHighest)
                    .combinedClickable(onClick = onNew, onLongClick = onNewLong),
                contentAlignment = Alignment.Center,
            ) { MIcon(Sym.Add, "New tab", Modifier.size(34.dp), tint = if (private) cs.onTertiaryContainer else cs.onSurface) }

            Box(
                Modifier.size(60.dp).clip(CircleShape).combinedClickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { MIcon(Sym.KeyboardArrowUp, "Menu", Modifier.size(36.dp), tint = if (private) cs.onTertiaryContainer else cs.onSurface) }
        }
    }
}
