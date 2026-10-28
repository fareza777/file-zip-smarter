@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.hatchstudio.archive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.R

val Bone = Color(0xFFF4F1E8)
val BoneBright = Color(0xFFFCFAF3)
val Ink = Color(0xFF121510)
val InkSoft = Color(0xFF1B1F18)
val Moss = Color(0xFF5D6355)
val Line = Color(0xFFDCD6C6)
val Lime = Color(0xFFC8F04B)
val LimeDeep = Color(0xFFA9D42E)
val Clay = Color(0xFFD76A46)
val Sea = Color(0xFFB9D8D2)

val SpaceGrotesk = FontFamily(
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_italic, FontWeight.Normal),
)

val SpaceMono = FontFamily(
    Font(R.font.space_mono, FontWeight.Normal),
)

val HatchTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 46.sp,
        lineHeight = 47.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Bone,
    primaryContainer = Lime,
    onPrimaryContainer = Ink,
    secondary = LimeDeep,
    onSecondary = Ink,
    tertiary = Clay,
    onTertiary = Bone,
    background = Bone,
    onBackground = Ink,
    surface = BoneBright,
    onSurface = Ink,
    // Dialogs and menus use the container roles; keep them on warm paper
    // instead of Material's default lavender-tinted neutrals.
    surfaceContainer = Color(0xFFF8F4E8),
    surfaceContainerHigh = BoneBright,
    surfaceContainerHighest = Color(0xFFF2EDDF),
    surfaceVariant = Color(0xFFEAE5D6),
    onSurfaceVariant = Moss,
    outline = Line,
    outlineVariant = Color(0xFFE7E1D2),
    error = Color(0xFFB3261E),
    onError = Bone,
)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = Ink,
    primaryContainer = Color(0xFF30391D),
    onPrimaryContainer = Lime,
    secondary = Lime,
    onSecondary = Ink,
    tertiary = Color(0xFFFFA07B),
    onTertiary = Ink,
    background = Color(0xFF0F120E),
    onBackground = Color(0xFFF2F0E6),
    surface = Color(0xFF181C16),
    onSurface = Color(0xFFF2F0E6),
    surfaceContainer = Color(0xFF1D221A),
    surfaceContainerHigh = Color(0xFF232921),
    surfaceContainerHighest = Color(0xFF2A3126),
    surfaceVariant = Color(0xFF242A20),
    onSurfaceVariant = Color(0xFFABB2A2),
    outline = Color(0xFF3A4134),
    outlineVariant = Color(0xFF2A3026),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun HatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = HatchTypography,
        content = content,
    )
}
