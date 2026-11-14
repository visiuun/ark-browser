@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package dev.vis.ark.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.vis.ark.MainActivity
import dev.vis.ark.browser.Browser
import dev.vis.ark.data.HistoryEntry
import dev.vis.ark.data.HistoryStore
import dev.vis.ark.data.Prefs
import dev.vis.ark.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val shapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided,
    MaterialShapes.Pentagon, MaterialShapes.Gem, MaterialShapes.Flower, MaterialShapes.SoftBurst,
)

@Composable
fun HomeScreen(activity: MainActivity, onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(15_000) } }
    val greeting = when (now.hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Good night" }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleMedium,
            color = cs.primary,
        )
        Text(greeting, style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 4.dp))
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 92.sp),
            fontWeight = FontWeight.Black, color = cs.onSurface.copy(alpha = 0.92f),
        )
        Spacer(Modifier.height(16.dp))

        // Search pill
        Surface(onClick = onSearch, shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon(Sym.Search, null, tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text("Search or type a URL", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                MIcon(Sym.Mic, "Voice", tint = cs.onSurfaceVariant, modifier = Modifier.clip(CircleShape).clickable { activity.voice { Browser.open(it, inNewTab = true) } }.padding(6.dp))
            }
        }

        // Pinned sites with expressive shapes
        if (Prefs.pins.isNotEmpty()) {
            SectionLabel("Pinned")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
                Prefs.pins.forEachIndexed { i, pin ->
                    val host = android.net.Uri.parse(pin.url).host?.removePrefix("www.") ?: ""
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).combinedClickable(
                            onClick = { Browser.newTab(pin.url) },
                            onLongClick = { Prefs.removePin(pin); Browser.host?.snack("Unpinned ${pin.title}") },
                        ).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Favicon(Net.faviconFor(host), host, 62.dp, shape = shapes[i % shapes.size].toShape(), container = if (i % 2 == 0) cs.primaryContainer else cs.tertiaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(pin.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat((4 - Prefs.pins.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (Prefs.homeWeather) WeatherCard(activity)
        if (Prefs.homeWiki) WikiCards()
        if (Prefs.homeHN) HackerNewsCard()
        if (Prefs.homeRecent) RecentCard()

        Text(
            "Weather: Open-Meteo.com (CC BY 4.0) · Places: © OpenStreetMap contributors · Wikipedia (CC BY-SA) · Hacker News API · Engine: Mozilla GeckoView",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 28.dp, bottom = 36.dp),
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(32.dp), content: @Composable () -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) { content() }
}

// ------------------------------------------------------------------ weather

private sealed interface WeatherState {
    data object Loading : WeatherState
    data object NeedsLocation : WeatherState
    data class Ready(val w: Net.Weather) : WeatherState
    data class Error(val msg: String) : WeatherState
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(ctx: Context): Location? = withContext(Dispatchers.Main) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val providers = lm.getProviders(true)
    val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 3 * 3600_000) return@withContext last
    val provider = when {
        LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
        LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
        else -> providers.firstOrNull()
    } ?: return@withContext last
    if (Build.VERSION.SDK_INT >= 30) {
        withTimeoutOrNull(9000) {
            suspendCancellableCoroutine { cont ->
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        } ?: last
    } else last
}

@Composable
private fun WeatherCard(activity: MainActivity) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<WeatherState>(WeatherState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(reload, Prefs.weatherCity, Prefs.fahrenheit) {
        state = WeatherState.Loading
        state = runCatching {
            val city = Prefs.weatherCity.trim()
            if (city.isNotEmpty()) {
                val (lat, lon, name) = Net.geocode(city) ?: return@runCatching WeatherState.Error("Couldn't find \"$city\"")
                WeatherState.Ready(Net.weather(lat, lon, name, Prefs.fahrenheit))
            } else if (!hasPerm) WeatherState.NeedsLocation
            else {
                val loc = currentLocation(ctx) ?: return@runCatching WeatherState.Error("Location unavailable — turn on location or set a city in Settings")
                val place = Net.placeName(loc.latitude, loc.longitude) ?: "Your location"
                WeatherState.Ready(Net.weather(loc.latitude, loc.longitude, place, Prefs.fahrenheit))
            }
        }.getOrElse { WeatherState.Error("Weather unavailable (${it.message})") }
    }

    SectionLabel("Weather")
    Card {
        when (val s = state) {
            WeatherState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            WeatherState.NeedsLocation -> Column(Modifier.padding(24.dp)) {
                Text("Local weather", style = MaterialTheme.typography.titleLarge)
                Text("Allow location to see weather where you are. Location stays on your device and is only sent to Open-Meteo as coordinates.", color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                FilledTonalButton(onClick = {
                    activity.requestAndroidPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { reload++ }
                }) { MIcon(Sym.LocationOn, null); Spacer(Modifier.width(8.dp)); Text("Use my location") }
            }
            is WeatherState.Error -> Column(Modifier.padding(24.dp)) {
                Text(s.msg, color = cs.onSurfaceVariant)
                FilledTonalButton(onClick = { reload++ }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
            }
            is WeatherState.Ready -> WeatherBody(s.w)
        }
    }
}

@Composable
private fun WeatherBody(w: Net.Weather) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(Sym.LocationOn, null, Modifier.size(16.dp), tint = cs.primary)
                    Text(w.place, style = MaterialTheme.typography.titleMedium, color = cs.primary, modifier = Modifier.padding(start = 4.dp))
                }
                Text("${w.temp.roundToInt()}${w.unit}", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
                Text("${Net.weatherText(w.code)} · feels ${w.feels.roundToInt()}°", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
            }
            Box(
                Modifier.size(104.dp).clip((if (w.isDay) MaterialShapes.Sunny else MaterialShapes.Cookie12Sided).toShape()).background(if (w.isDay) cs.tertiaryContainer else cs.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(weatherEmoji(w.code, w.isDay), fontSize = 46.sp) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(Sym.WaterDrop, "${w.humidity}%", "Humidity", Modifier.weight(1f))
            Stat(Sym.Air, "${w.wind.roundToInt()} ${if (w.unit == "°F") "mph" else "km/h"}", "Wind", Modifier.weight(1f))
            Stat(Sym.WbSunny, "UV ${w.uv.roundToInt()}", "${w.sunrise}–${w.sunset}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(w.hours) { h ->
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(h.time, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text(weatherEmoji(h.code, true), fontSize = 20.sp)
                    Text("${h.temp.roundToInt()}°", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        w.days.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.width(52.dp), style = MaterialTheme.typography.titleSmall)
                Text(weatherEmoji(d.code, true), Modifier.width(34.dp))
                Text(if (d.rainChance > 0) "${d.rainChance}%" else "", Modifier.width(44.dp), color = cs.primary, style = MaterialTheme.typography.labelMedium)
                Text("${d.min.roundToInt()}°", color = cs.onSurfaceVariant, modifier = Modifier.width(40.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(cs.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(0.4f + 0.6f * ((d.max - d.min) / 20.0).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(cs.primary))
                }
                Text("${d.max.roundToInt()}°", modifier = Modifier.width(44.dp).padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun Stat(icon: String, value: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(12.dp)) {
        MIcon(icon, null, Modifier.size(18.dp), tint = cs.primary)
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

fun weatherEmoji(code: Int, day: Boolean) = when (code) {
    0 -> if (day) "☀️" else "🌙"; 1 -> if (day) "🌤️" else "🌙"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
    in 51..57 -> "🌦️"; in 61..67 -> "🌧️"; in 71..77 -> "🌨️"; in 80..82 -> "🌧️"; 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "🌡️"
}

// ------------------------------------------------------------------ wikipedia

@Composable
private fun WikiCards() {
    val cs = MaterialTheme.colorScheme
    var feed by remember { mutableStateOf<Net.WikiFeed?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Prefs.wikiLang) { feed = runCatching { Net.wikipedia(Prefs.wikiLang) }.onFailure { failed = true }.getOrNull() }
    val f = feed
    if (f == null) {
        if (!failed) { SectionLabel("Today on Wikipedia"); Card { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() } } }
        return
    }
    f.featured?.let { a ->
        SectionLabel("Featured article")
        Card(Modifier.clickable { Browser.newTab(a.url) }) {
            Column {
                a.image?.let { img -> NetImage(img, Modifier.fillMaxWidth().aspectRatio(16 / 9f)) }
                Column(Modifier.padding(20.dp)) {
                    Text(a.title, style = MaterialTheme.typography.headlineSmall)
                    Text(a.extract, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    if (f.news.isNotEmpty()) {
        SectionLabel("In the news")
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                f.news.forEach { (text, url) ->
                    Text(
                        text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = url.isNotBlank()) { Browser.newTab(url) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    if (f.mostRead.isNotEmpty()) {
        SectionLabel("Most read")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(f.mostRead) { a ->
                Surface(onClick = { Browser.newTab(a.url) }, shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerLow, modifier = Modifier.width(220.dp)) {
                    Column {
                        if (a.image != null) NetImage(a.image, Modifier.fillMaxWidth().height(110.dp))
                        Column(Modifier.padding(14.dp)) {
                            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(a.extract, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (f.onThisDay.isNotEmpty()) {
        SectionLabel("On this day")
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                f.onThisDay.forEach { (year, p) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = p.second.isNotBlank()) { Browser.newTab(p.second) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text("$year", style = MaterialTheme.typography.titleMedium, color = cs.primary, modifier = Modifier.width(64.dp))
                        Text(p.first, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun NetImage(url: String, modifier: Modifier) {
    var img by remember(url) { mutableStateOf(Net.cachedImage(url)) }
    LaunchedEffect(url) { if (img == null) img = Net.image(url) }
    val i = img
    if (i != null) Image(i, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

// ------------------------------------------------------------------ hacker news

@Composable
private fun HackerNewsCard() {
    val cs = MaterialTheme.colorScheme
    var stories by remember { mutableStateOf<List<Net.Story>?>(null) }
    LaunchedEffect(Unit) { stories = runCatching { Net.hackerNews(8) }.getOrDefault(emptyList()) }
    SectionLabel("Hacker News")
    Card {
        val s = stories
        if (s == null) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        else Column(Modifier.padding(vertical = 8.dp)) {
            s.forEachIndexed { i, st ->
                Row(
                    Modifier.fillMaxWidth().clickable { Browser.newTab(st.url) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = cs.primary, fontWeight = FontWeight.Black, modifier = Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${st.score} points · ${st.comments} comments · ${android.net.Uri.parse(st.url).host?.removePrefix("www.") ?: ""}",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.clickable { Browser.newTab("https://news.ycombinator.com/item?id=${st.id}") },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ recent

@Composable
private fun RecentCard() {
    val cs = MaterialTheme.colorScheme
    var recent by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val v = HistoryStore.version.intValue
    LaunchedEffect(v) { recent = withContext(Dispatchers.IO) { runCatching { HistoryStore.recent(30) }.getOrDefault(emptyList()) }.distinctBy { android.net.Uri.parse(it.url).host }.take(6) }
    if (recent.isEmpty()) return
    SectionLabel("Jump back in")
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            recent.forEach { h ->
                val host = android.net.Uri.parse(h.url).host?.removePrefix("www.") ?: ""
                Row(Modifier.fillMaxWidth().clickable { Browser.newTab(h.url) }.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(Net.faviconFor(host), host, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title.ifBlank { host }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private val unusedPadding = PaddingValues(0.dp)
