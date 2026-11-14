@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vis.ark.net.Net
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class Snack(val message: String, val action: String?, val onAction: (() -> Unit)?, val id: Long = System.nanoTime())

/**
 * Bottom sheet that mirrors Arc's menu sheet: big rounded top corners, no handle, scrim, drag to dismiss,
 * sits above the keyboard. Animated with the M3 Expressive spatial springs.
 */
@Composable
fun ArcSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(visible, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInputTap(onDismiss)
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it },
        exit = slideOutVertically(motion.fastSpatialSpec()) { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (visible) BackHandler(onBack = onDismiss)
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
                color = color,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > 220f || v > 2200f) onDismiss()
                            drag.animateTo(0f, motion.fastSpatialSpec())
                        },
                    )
            ) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)
                ) { content() }
            }
        }
    }
}

fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures { onTap() } }

/** Favicon in a tinted container; falls back to the site's first letter. */
@Composable
fun Favicon(url: String?, host: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(size / 3.2f), container: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    var img by remember(url) { mutableStateOf(url?.let { Net.cachedImage(it) }) }
    LaunchedEffect(url) { if (img == null && url != null) img = Net.image(url) }
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null && i.width > 1) {
            Image(i, null, Modifier.size(size * 0.6f).clip(RoundedCornerShape(size / 8)))
        } else {
            Text(
                host.removePrefix("www.").firstOrNull()?.uppercase() ?: "•",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** Arc's two tilted squares, with the tab count in the front one. */
@Composable
fun TabsGlyph(count: Int, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            val s = size.minDimension * 0.62f
            rotate(-14f, Offset(size.width * 0.42f, size.height * 0.58f)) {
                drawRoundRect(tint.copy(alpha = 0.45f), topLeft = Offset(size.width * 0.08f, size.height * 0.28f), size = Size(s, s), cornerRadius = CornerRadius(s * 0.24f))
            }
            rotate(10f, Offset(size.width * 0.6f, size.height * 0.42f)) {
                drawRoundRect(tint.copy(alpha = 0.85f), topLeft = Offset(size.width * 0.3f, size.height * 0.1f), size = Size(s, s), cornerRadius = CornerRadius(s * 0.24f))
            }
        }
        if (count > 0) Text(
            if (count > 99) "∞" else count.toString(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.offset(x = 4.dp, y = (-5).dp),
        )
    }
}

/**
 * One segment of an M3 Expressive *connected button group*: outer corners stay round,
 * inner corners are tight and morph rounder while pressed.
 */
@Composable
fun ConnectedTile(
    index: Int,
    count: Int,
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 104.dp,
    iconSize: Dp = 30.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val outer = 30.dp
    val inner by animateDpAsState(if (pressed) 22.dp else 6.dp, motion.fastSpatialSpec(), label = "inner")
    val first = index == 0
    val last = index == count - 1
    val shape = RoundedCornerShape(
        topStart = if (first) outer else inner, bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner, bottomEnd = if (last) outer else inner,
    )
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) cs.primaryContainer else container,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        interactionSource = interaction,
        modifier = modifier.height(height),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            MIcon(icon, null, Modifier.size(iconSize))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ConnectedRow(items: List<Triple<String, String, () -> Unit>>, modifier: Modifier = Modifier, height: Dp = 104.dp, selected: (Int) -> Boolean = { false }, iconSize: Dp = 30.dp, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, (icon, label, action) ->
            ConnectedTile(i, items.size, icon, label, action, Modifier.weight(1f), selected(i), height, iconSize, container)
        }
    }
}

/** Expressive segmented list group: first/last items get large corners, inner ones small. */
@Composable
fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable SegmentScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val scope = SegmentScope()
        scope.content()
    }
}

class SegmentScope

@Composable
fun segmentShape(index: Int, count: Int): Shape {
    val big = 26.dp; val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 22.dp, bottom = 10.dp)
    )
}
