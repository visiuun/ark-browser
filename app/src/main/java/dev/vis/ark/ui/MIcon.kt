package dev.vis.ark.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols Rounded outlines as vectors, built lazily and cached. */
private object SymbolVectors {
    private val cache = HashMap<Pair<Char, Boolean>, ImageVector>()
    fun get(glyph: Char, filled: Boolean): ImageVector = cache.getOrPut(glyph to filled) {
        val raw = SymPaths.map[glyph]?.let { if (filled) it.second else it.first } ?: ""
        // "@…" paths come straight from the web SVGs, whose viewBox starts at y = -960.
        val web = raw.startsWith("@")
        ImageVector.Builder(
            name = "sym_${glyph.code.toString(16)}${if (filled) "_f" else ""}",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).addGroup(translationY = if (web) 960f else 0f)
            .addPath(addPathNodes(raw.removePrefix("@")), fill = SolidColor(Color.Black))
            .clearGroup().build()
    }
}

/**
 * Material 3 Expressive icon (Material Symbols Rounded).
 * Same parameter order as material3 Icon. Prefix the glyph with "*" (or pass filled = true) for the filled style.
 * Drawn as vector paths, so it renders identically everywhere — independent of system font weight or font hooks.
 */
@Composable
fun MIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
) {
    val isFilled = filled || name.startsWith("*")
    val glyph = name.removePrefix("*").firstOrNull() ?: return
    val vector = remember(glyph, isFilled) { SymbolVectors.get(glyph, isFilled) }
    Icon(vector, contentDescription, modifier.size(24.dp), tint = tint)
}
