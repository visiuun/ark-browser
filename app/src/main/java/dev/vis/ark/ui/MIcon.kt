package dev.vis.ark.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.vis.ark.R

/** Material Symbols Rounded variable font, one FontFamily per (FILL, wght, opsz) combo. */
private object SymbolFonts {
    private val cache = HashMap<Triple<Boolean, Int, Int>, FontFamily>()
    fun get(filled: Boolean, weight: Int, opsz: Int): FontFamily = cache.getOrPut(Triple(filled, weight, opsz)) {
        FontFamily(
            Font(
                R.font.material_symbols,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.Setting("FILL", if (filled) 1f else 0f),
                    FontVariation.Setting("GRAD", 0f),
                    FontVariation.Setting("opsz", opsz.toFloat()),
                ),
            )
        )
    }
}

/**
 * Material 3 Expressive icon: renders a Material Symbols Rounded glyph.
 * Same parameter order as material3 Icon. Prefix the glyph with "*" (or pass filled = true) for the filled style.
 * Size follows the modifier's size (defaults to 24dp).
 */
@Composable
fun MIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
    weight: Int = 450,
) {
    val isFilled = filled || name.startsWith("*")
    val glyph = name.removePrefix("*")
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .size(24.dp)
            .clearAndSetSemantics {
                if (contentDescription != null) { this.contentDescription = contentDescription; role = Role.Image }
            },
        contentAlignment = Alignment.Center,
    ) {
        val px: Dp = min(maxWidth, maxHeight)
        val opsz = px.value.toInt().coerceIn(20, 48)
        val family = remember(isFilled, weight, opsz) { SymbolFonts.get(isFilled, weight, opsz) }
        // Convert dp -> sp without the user's font scale, so icons don't balloon with large text.
        val fontSize = (px.value / density.fontScale).sp
        Text(
            glyph,
            color = tint,
            style = TextStyle(
                fontFamily = family,
                fontSize = fontSize,
                lineHeight = fontSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
