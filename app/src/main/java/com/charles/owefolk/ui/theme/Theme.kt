package com.charles.owefolk.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Indigo = Color(0xFF5B4BD8)
val IndigoDark = Color(0xFF4436B5)
val Coral = Color(0xFFFF735C)
val Mint = Color(0xFF1F9D82)
val Cream = Color(0xFFFFF8F5)
val Ink = Color(0xFF211E2C)
val Muted = Color(0xFF6F6A7B)
val SoftIndigo = Color(0xFFE9E5FF)
val SoftCoral = Color(0xFFFFE7E1)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = SoftIndigo,
    onPrimaryContainer = Color(0xFF241663),
    secondary = Coral,
    onSecondary = Color.White,
    secondaryContainer = SoftCoral,
    onSecondaryContainer = Color(0xFF5A160A),
    tertiary = Mint,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EDF2),
    onSurfaceVariant = Muted,
    outline = Color(0xFF928D9A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BDFF),
    onPrimary = Color(0xFF2B1C79),
    primaryContainer = Color(0xFF4135A0),
    secondary = Color(0xFFFFB4A6),
    tertiary = Color(0xFF70DDBF),
    background = Color(0xFF17151D),
    surface = Color(0xFF211F28),
    surfaceVariant = Color(0xFF34313B),
)

@Composable
fun OwefolkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = OwefolkTypography, content = content)
}
