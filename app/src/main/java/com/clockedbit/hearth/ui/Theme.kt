package com.clockedbit.hearth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Hearth {
    val bg = Color(0xFF13100E)
    val surface = Color(0xFF1D1815)
    val surfaceHigh = Color(0xFF29221E)
    val amber = Color(0xFFFFA45C)
    val ember = Color(0xFFFF7A45)
    val plum = Color(0xFFB07CFF)
    val teal = Color(0xFF4FD1C5)
    val cream = Color(0xFFF2E6D6)
    val muted = Color(0xFFA8998A)
}

private val scheme = darkColorScheme(
    primary = Hearth.amber,
    onPrimary = Color(0xFF2A1607),
    primaryContainer = Color(0xFF4A2E18),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Hearth.plum,
    onSecondary = Color(0xFF1E0F33),
    secondaryContainer = Color(0xFF3A2A50),
    onSecondaryContainer = Color(0xFFEBDCFF),
    tertiary = Hearth.teal,
    background = Hearth.bg,
    onBackground = Hearth.cream,
    surface = Hearth.surface,
    onSurface = Hearth.cream,
    surfaceVariant = Hearth.surfaceHigh,
    onSurfaceVariant = Hearth.muted,
    surfaceContainer = Color(0xFF221C19),
    surfaceContainerHigh = Hearth.surfaceHigh,
    surfaceContainerHighest = Color(0xFF332A25),
    surfaceContainerLow = Color(0xFF1A1513),
    outline = Color(0xFF5B4E44),
    outlineVariant = Color(0xFF3A312B),
)

private val base = Typography()
private val serif = FontFamily.Serif

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun HearthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
