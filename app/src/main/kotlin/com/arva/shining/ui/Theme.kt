package com.arva.shining.ui

import com.arva.shining.lightApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arva.shining.R

/**
 * ARVA design tokens v3 (from ar-va.com brand/arva-tokens.css). Components use these
 * tokens only — never raw hex values.
 */
@Immutable
data class ArvaColors(
    val bg: Color,
    val surface: Color,
    val sunken: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val accentTint: Color,
    val moss: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val ArvaLight = ArvaColors(
    bg = Color(0xFFFDFDFB), surface = Color(0xFFFFFFFF), sunken = Color(0xFFF1F2F6), line = Color(0xFFD8DBE6),
    text = Color(0xFF1F2335), text2 = Color(0xFF565F89),
    accent = Color(0xFF5A57C4), accentHover = Color(0xFF4B45B0), onAccent = Color(0xFFFFFFFF), accentTint = Color(0xFFE4E8FF),
    moss = Color(0xFF237A50), success = Color(0xFF2F7D3A), warning = Color(0xFF8A5D10), error = Color(0xFFC0394B),
)

val ArvaDark = ArvaColors(
    bg = Color(0xFF15161C), surface = Color(0xFF1E2029), sunken = Color(0xFF262935), line = Color(0xFF333645),
    text = Color(0xFFC6CBE4), text2 = Color(0xFF9CA3C4),
    accent = Color(0xFFA2A8FC), accentHover = Color(0xFFB7BDFF), onAccent = Color(0xFF15161C), accentTint = Color(0xFF3F3F75),
    moss = Color(0xFF8BD8A9), success = Color(0xFF9ECE6A), warning = Color(0xFFE0AF68), error = Color(0xFFF7768E),
)

val LocalArva = staticCompositionLocalOf { ArvaLight }

/** Shortcut: `Arva.colors.accent` etc. */
object Arva {
    val colors: ArvaColors @Composable get() = LocalArva.current
}

// Typefaces: Archivo (titles, 600 at 112% width), IBM Plex Sans (everything read or operated),
// IBM Plex Mono (uppercase labels, aligned numbers).
@OptIn(ExperimentalTextApi::class)
val Archivo = FontFamily(
    Font(
        R.font.archivo, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.width(112f)),
    ),
)

@OptIn(ExperimentalTextApi::class)
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.ibm_plex_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.ibm_plex_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val ArvaType = Typography(
    // Titles (Archivo 600, 112%). Mobile sizes from the brand type scale.
    headlineLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    // Item titles under 18 sp stay in Plex Sans (Archivo looks cramped that small).
    titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    // Body and meta (Plex Sans)
    bodyLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    // Buttons (Plex Sans 600, 16)
    labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    // Eyebrow / label (Plex Mono 500, 12, uppercase, +0.08em) — callers uppercase the text.
    labelMedium = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    // Numbers and codes (Plex Mono 400)
    labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
)

/** Builds ARVA tokens from a Material You dynamic scheme, so custom components follow the wallpaper. */
private fun arvaFromDynamic(s: androidx.compose.material3.ColorScheme, dark: Boolean): ArvaColors {
    val brand = if (dark) ArvaDark else ArvaLight
    val hoverTowards = if (dark) Color.White else Color.Black
    return ArvaColors(
        bg = s.background, surface = s.surface, sunken = s.surfaceVariant, line = s.outlineVariant,
        text = s.onBackground, text2 = s.onSurfaceVariant,
        accent = s.primary, accentHover = androidx.compose.ui.graphics.lerp(s.primary, hoverTowards, 0.12f),
        onAccent = s.onPrimary, accentTint = s.primaryContainer,
        moss = s.tertiary, // the dynamic secondary accent takes Moss's role
        success = brand.success, warning = brand.warning, error = s.error,
    )
}

@Composable
fun ShiningTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = context.lightApp.themeStore
    val mode by store.mode.collectAsState()
    val dynamicPref by store.dynamic.collectAsState()
    val dark = when (mode) {
        com.arva.shining.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        com.arva.shining.ThemeMode.LIGHT -> false
        com.arva.shining.ThemeMode.DARK -> true
    }
    val useDynamic = dynamicPref && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val c = when {
        useDynamic -> arvaFromDynamic(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), dark)
        dark -> ArvaDark
        else -> ArvaLight
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Map tokens onto Material roles so stock components inherit the palette.
    val scheme = base.copy(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.accentTint, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        secondaryContainer = c.accentTint, onSecondaryContainer = c.accent,
        tertiary = c.accent, onTertiary = c.onAccent,
        background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text,
        surfaceVariant = c.sunken, onSurfaceVariant = c.text2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.sunken, surfaceContainerHighest = c.sunken,
        surfaceTint = Color.Transparent,
        outline = c.line, outlineVariant = c.line,
        error = c.error, onError = c.onAccent,
        inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.accentTint,
    )
    CompositionLocalProvider(LocalArva provides c) {
        MaterialTheme(colorScheme = scheme, typography = ArvaType, content = content)
    }
}
