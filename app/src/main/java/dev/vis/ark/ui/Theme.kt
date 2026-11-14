package dev.vis.ark.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.vis.ark.data.Prefs

private val ArkLight = lightColorScheme(
    primary = Color(0xFF8E3F63), onPrimary = Color.White, primaryContainer = Color(0xFFFFD8E6), onPrimaryContainer = Color(0xFF3A0020),
    secondary = Color(0xFF735760), onSecondary = Color.White, secondaryContainer = Color(0xFFFFD9E2), onSecondaryContainer = Color(0xFF2A151D),
    tertiary = Color(0xFF7C5635), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFDCC2), onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFFF8F8), onBackground = Color(0xFF22191C), surface = Color(0xFFFFF8F8), onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF2DDE2), onSurfaceVariant = Color(0xFF514347), outline = Color(0xFF837377), outlineVariant = Color(0xFFD5C2C6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0F3), surfaceContainer = Color(0xFFFAEAED),
    surfaceContainerHigh = Color(0xFFF4E4E7), surfaceContainerHighest = Color(0xFFEEDEE2),
)

private val ArkDark = darkColorScheme(
    primary = Color(0xFFFFB0CD), onPrimary = Color(0xFF561D3A), primaryContainer = Color(0xFF722751), onPrimaryContainer = Color(0xFFFFD8E6),
    secondary = Color(0xFFE2BDC7), onSecondary = Color(0xFF422931), secondaryContainer = Color(0xFF5A3F48), onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFFEFBD94), onTertiary = Color(0xFF48290B), tertiaryContainer = Color(0xFF623F20), onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF191114), onBackground = Color(0xFFEFDFE2), surface = Color(0xFF191114), onSurface = Color(0xFFEFDFE2),
    surfaceVariant = Color(0xFF514347), onSurfaceVariant = Color(0xFFD5C2C6), outline = Color(0xFF9E8C91), outlineVariant = Color(0xFF514347),
    surfaceContainerLowest = Color(0xFF130C0E), surfaceContainerLow = Color(0xFF22191C), surfaceContainer = Color(0xFF261D20),
    surfaceContainerHigh = Color(0xFF31282A), surfaceContainerHighest = Color(0xFF3C3235),
)

/** True-black variant for OLED screens. Keeps accents, flattens every surface to black/near-black. */
private fun ColorScheme.oled() = copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF070707), surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF161616), surfaceContainerHighest = Color(0xFF1F1F1F), surfaceBright = Color(0xFF262626),
    surfaceVariant = Color(0xFF1A1A1A),
)

@Composable
fun isAppDark(): Boolean = when {
    Prefs.oled -> true
    Prefs.themeMode == 1 -> false
    Prefs.themeMode == 2 -> true
    else -> isSystemInDarkTheme()
}

private val base = Typography()
private val ArkType = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge, bodyMedium = base.bodyMedium, bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArkTheme(content: @Composable () -> Unit) {
    val dark = isAppDark()
    val ctx = LocalContext.current
    var scheme = when {
        Prefs.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> ArkDark
        else -> ArkLight
    }
    if (dark && Prefs.oled) scheme = scheme.oled()
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = ArkType,
        content = content,
    )
}
