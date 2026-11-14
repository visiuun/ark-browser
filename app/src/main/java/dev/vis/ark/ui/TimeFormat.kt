package dev.vis.ark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.vis.ark.data.Prefs
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** True when times should use the 24-hour clock (Settings › Clock, or the system setting). */
@Composable
fun use24h(): Boolean {
    val ctx = LocalContext.current
    return when (Prefs.clock) {
        1 -> false
        2 -> true
        else -> android.text.format.DateFormat.is24HourFormat(ctx)
    }
}

private val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val h12Short = DateTimeFormatter.ofPattern("h a", Locale.getDefault())
private val h24 = DateTimeFormatter.ofPattern("HH:mm")

/** Formats a time; [compact] drops ":00" minutes in 12-hour mode (for hourly forecasts). */
fun formatTime(t: LocalTime, use24: Boolean, compact: Boolean = false): String = when {
    use24 -> t.format(h24)
    compact && t.minute == 0 -> t.format(h12Short)
    else -> t.format(h12)
}

/** Formats an "HH:mm" string from an API. Returns the input unchanged if it can't be parsed. */
fun formatTime(hhmm: String, use24: Boolean, compact: Boolean = false): String =
    runCatching { formatTime(LocalTime.parse(hhmm), use24, compact) }.getOrDefault(hhmm)
