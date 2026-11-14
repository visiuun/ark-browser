@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.vis.ark.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import dev.vis.ark.browser.Browser
import dev.vis.ark.browser.Tab
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun TabSwitcher(onClose: () -> Unit, onNewTab: (Boolean) -> Unit, onSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var privateMode by rememberSaveable { mutableStateOf(Browser.current?.private == true) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val list = Browser.tabs.filter { it.private == privateMode }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (privateMode) cs.tertiaryContainer else cs.primaryContainer.copy(alpha = 0.55f),
                        cs.surface,
                        if (privateMode) cs.tertiaryContainer.copy(alpha = 0.6f) else cs.secondaryContainer.copy(alpha = 0.5f),
                    )
                )
            )
            .clickable(interactionSource = null, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Header: Tabs / Private toggle group
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    val normalCount = Browser.tabs.count { !it.private }
                    val privCount = Browser.tabs.count { it.private }
                    ToggleButton(checked = !privateMode, onCheckedChange = { privateMode = false }, shapes = androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()) {
                        Text("Tabs · $normalCount")
                    }
                    ToggleButton(checked = privateMode, onCheckedChange = { privateMode = true }, shapes = androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                        Text("Private · $privCount")
                    }
                }
                IconButton(onClick = onSettings) { MIcon(Sym.Settings, "Settings") }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (list.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(140.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { MIcon(Sym.Add, null, Modifier.size(56.dp), tint = cs.onPrimaryContainer) }
                        Spacer(Modifier.height(20.dp))
                        Text(if (privateMode) "No private tabs" else "No open tabs", style = MaterialTheme.typography.headlineSmall)
                        Text("Tap + to start browsing", color = cs.onSurfaceVariant)
                    }
                } else if (grid) {
                    LazyVerticalGrid(
                        GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(list, key = { it.id }) { t ->
                            TabCard(t, compact = true, onOpen = { Browser.select(t); onClose() }, onCloseTab = { Browser.close(t) })
                        }
                    }
                } else {
                    val start = list.indexOfFirst { it.id == Browser.currentId }.coerceAtLeast(0)
                    val pager = rememberPagerState(initialPage = start) { list.size }
                    HorizontalPager(
                        state = pager,
                        contentPadding = PaddingValues(horizontal = 64.dp),
                        pageSpacing = 14.dp,
                        modifier = Modifier.fillMaxSize(),
                        key = { list[it].id },
                    ) { page ->
                        val t = list[page]
                        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                val s = 1f - 0.08f * offset.coerceIn(0f, 1f)
                                scaleX = s; scaleY = s; alpha = 1f - 0.35f * offset.coerceIn(0f, 1f)
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            TabCard(t, compact = false, onOpen = { Browser.select(t); onClose() }, onCloseTab = { Browser.close(t) })
                        }
                    }
                }
            }

            // Bottom bar: view mode · new tab · clear all (Arc's switcher layout)
            Row(
                Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    IconButton(onClick = { grid = !grid }, modifier = Modifier.size(56.dp)) {
                        MIcon(if (grid) Sym.ViewCarousel else Sym.GridView, if (grid) "Card view" else "Grid view", Modifier.size(28.dp))
                    }
                }
                Surface(onClick = { onNewTab(privateMode) }, shape = CircleShape, color = cs.surfaceContainerHighest, modifier = Modifier.width(118.dp).height(58.dp)) {
                    Box(contentAlignment = Alignment.Center) { MIcon(Sym.Add, "New tab", Modifier.size(30.dp)) }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    androidx.compose.material3.TextButton(
                        enabled = list.isNotEmpty(),
                        onClick = {
                            val n = list.size
                            Browser.closeAll(privateMode)
                            Browser.host?.snack(if (n == 1) "Closed 1 tab" else "Closed $n tabs")
                        },
                    ) { Text("Clear", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@Composable
private fun TabCard(t: Tab, compact: Boolean, onOpen: () -> Unit, onCloseTab: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    Column(
        Modifier
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .graphicsLayer { alpha = 1f - (-drag.value / 900f).coerceIn(0f, 0.8f) }
            .pointerInput(t.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (drag.value < -260f) { drag.animateTo(-2200f, motion.fastSpatialSpec()); onCloseTab() }
                            else drag.animateTo(0f, motion.defaultSpatialSpec())
                        }
                    },
                ) { change, dy -> change.consume(); scope.launch { drag.snapTo((drag.value + dy).coerceAtMost(40f)) } }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Favicon(t.favicon, t.host, if (compact) 22.dp else 30.dp, container = cs.surfaceContainerHigh)
            Spacer(Modifier.width(10.dp))
            Text(
                t.displayTitle, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
                color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (compact) MIcon(Sym.Close, "Close", Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onCloseTab), tint = cs.onSurfaceVariant)
        }
        val shape = RoundedCornerShape(if (compact) 24.dp else 40.dp)
        Surface(
            onClick = onOpen, shape = shape, color = cs.surfaceContainerHigh,
            border = if (t.id == Browser.currentId) androidx.compose.foundation.BorderStroke(3.dp, cs.primary) else null,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 0.72f else 0.6f),
        ) {
            val thumb = t.thumbnail
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Favicon(t.favicon, t.host, if (compact) 56.dp else 88.dp, shape = MaterialShapes.Cookie9Sided.toShape(), container = cs.primaryContainer)
                    Spacer(Modifier.height(14.dp))
                    Text(t.host, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
