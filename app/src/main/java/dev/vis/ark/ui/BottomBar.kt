@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.vis.ark.ui

import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
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
        // Top edge: an expressive wavy progress bar, only while the page is loading (space kept so nothing jumps).
        Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(visible = tab?.loading == true, enter = fadeIn(), exit = fadeOut()) {
                if (tab != null) LinearWavyProgressIndicator(
                    progress = { (tab.progress / 100f).coerceIn(0.05f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    color = if (private) cs.onTertiaryContainer else cs.primary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Tabs button: tap for the switcher, long-press to close, swipe sideways to flick through tabs
            // (one tab per ~44dp of travel, with a haptic tick, the icon following the finger).
            val haptics = LocalHapticFeedback.current
            val nudge = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Box(
                Modifier
                    .size(60.dp)
                    .pointerInput(Unit) {
                        val step = 44.dp.toPx()
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = { scope.launch { nudge.animateTo(0f, spring(dampingRatio = 0.5f)) } },
                            onDragCancel = { scope.launch { nudge.animateTo(0f) } },
                        ) { change, dx ->
                            change.consume()
                            acc += dx
                            scope.launch { nudge.snapTo((nudge.value + dx * 0.35f).coerceIn(-18f * density, 18f * density)) }
                            if (abs(acc) >= step) {
                                onSwipe(if (acc < 0) 1 else -1)
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                acc = 0f
                            }
                        }
                    }
                    .clip(CircleShape)
                    .combinedClickable(onClick = onTabs, onLongClick = onTabsLong),
                contentAlignment = Alignment.Center,
            ) {
                TabsGlyph(
                    tabCount, tint = if (private) cs.onTertiaryContainer else cs.onSurfaceVariant,
                    modifier = Modifier.offset { IntOffset(nudge.value.roundToInt(), 0) },
                )
            }

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
