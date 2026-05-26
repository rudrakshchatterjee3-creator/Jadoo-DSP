package com.jadoo.amp.ui.theme

import android.app.Activity
import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DefaultDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9AD48F),
    secondary = Color(0xFFFFB4A8),
    tertiary = Color(0xFFBBC7FF),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
    surfaceContainer = Color(0xFF1C231C),
    surfaceContainerHigh = Color(0xFF263026)
)

private val DefaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF276A30),
    secondary = Color(0xFF9A452F),
    tertiary = Color(0xFF4056A5),
    background = Color(0xFFFBFDF7),
    surface = Color(0xFFFBFDF7),
    surfaceContainer = Color(0xFFEFF4EC),
    surfaceContainerHigh = Color(0xFFE6ECE3)
)

@Composable
fun JadOOampTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useMaterialYou: Boolean = true,
    customPrimaryColor: Color = Color(0xFF276A30),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDynamicColor = useMaterialYou
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        useDynamicColor && darkTheme -> DefaultDarkColorScheme
        useDynamicColor -> DefaultLightColorScheme
        else -> customSeedColorScheme(customPrimaryColor, darkTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

private fun customSeedColorScheme(seed: Color, darkTheme: Boolean) = if (darkTheme) {
    darkColorScheme(
        primary = tone(seed, value = 0.86f, saturationMultiplier = 0.92f),
        onPrimary = Color(0xFF10200F),
        primaryContainer = tone(seed, value = 0.34f, saturationMultiplier = 0.78f),
        onPrimaryContainer = tone(seed, value = 0.96f, saturationMultiplier = 0.36f),
        secondary = tone(rotateHue(seed, 22f), value = 0.80f, saturationMultiplier = 0.46f),
        onSecondary = Color(0xFF182017),
        secondaryContainer = tone(rotateHue(seed, 22f), value = 0.28f, saturationMultiplier = 0.40f),
        onSecondaryContainer = tone(rotateHue(seed, 22f), value = 0.92f, saturationMultiplier = 0.30f),
        tertiary = tone(rotateHue(seed, -34f), value = 0.82f, saturationMultiplier = 0.52f),
        background = tone(seed, value = 0.07f, saturationMultiplier = 0.12f),
        onBackground = Color(0xFFE8EDE5),
        surface = tone(seed, value = 0.08f, saturationMultiplier = 0.10f),
        onSurface = Color(0xFFE8EDE5),
        surfaceVariant = tone(seed, value = 0.18f, saturationMultiplier = 0.18f),
        onSurfaceVariant = Color(0xFFC5CBC0),
        surfaceTint = tone(seed, value = 0.86f, saturationMultiplier = 0.92f),
        surfaceContainer = tone(seed, value = 0.12f, saturationMultiplier = 0.14f),
        surfaceContainerHigh = tone(seed, value = 0.16f, saturationMultiplier = 0.16f)
    )
} else {
    lightColorScheme(
        primary = tone(seed, value = 0.48f, saturationMultiplier = 0.90f),
        onPrimary = Color.White,
        primaryContainer = tone(seed, value = 0.90f, saturationMultiplier = 0.38f),
        onPrimaryContainer = tone(seed, value = 0.22f, saturationMultiplier = 0.90f),
        secondary = tone(rotateHue(seed, 22f), value = 0.46f, saturationMultiplier = 0.44f),
        onSecondary = Color.White,
        secondaryContainer = tone(rotateHue(seed, 22f), value = 0.88f, saturationMultiplier = 0.24f),
        onSecondaryContainer = tone(rotateHue(seed, 22f), value = 0.24f, saturationMultiplier = 0.42f),
        tertiary = tone(rotateHue(seed, -34f), value = 0.52f, saturationMultiplier = 0.48f),
        background = tone(seed, value = 0.985f, saturationMultiplier = 0.04f),
        onBackground = Color(0xFF191D18),
        surface = tone(seed, value = 0.985f, saturationMultiplier = 0.04f),
        onSurface = Color(0xFF191D18),
        surfaceVariant = tone(seed, value = 0.90f, saturationMultiplier = 0.12f),
        onSurfaceVariant = Color(0xFF42483F),
        surfaceTint = tone(seed, value = 0.48f, saturationMultiplier = 0.90f),
        surfaceContainer = tone(seed, value = 0.94f, saturationMultiplier = 0.08f),
        surfaceContainerHigh = tone(seed, value = 0.90f, saturationMultiplier = 0.10f)
    )
}

private fun rotateHue(color: Color, degrees: Float): Color {
    val hsv = color.toHsv()
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun tone(
    color: Color,
    value: Float,
    saturationMultiplier: Float
): Color {
    val hsv = color.toHsv()
    hsv[1] = (hsv[1] * saturationMultiplier).coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(
        toArgb(),
        hsv
    )
    return hsv
}
