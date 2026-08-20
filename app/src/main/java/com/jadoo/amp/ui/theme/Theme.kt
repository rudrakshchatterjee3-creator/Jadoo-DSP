package com.jadoo.amp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app's single theme entry point.
 *
 * Everything colour-related resolves from one [ThemeSpec]; the token layer
 * (spacing, alpha, motion, extra type) rides alongside it on
 * [LocalJadooTokens].
 */
@Composable
fun JadOOampTheme(
    spec: ThemeSpec = ThemeSpec(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = spec.isDark(systemDark)

    val colorScheme = remember(spec, systemDark, context) {
        buildColorScheme(spec, systemDark, context)
    }

    val motionEnabled = rememberMotionEnabled()
    val shapes = remember { JadooShapes() }
    val tokens = remember(motionEnabled, shapes) {
        JadooTokens(shapes = shapes, motion = JadooMotion(enabled = motionEnabled))
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // ── System bars ───────────────────────────────────────────────────
        // What used to be here was `window.statusBarColor = ...`, which is a
        // NO-OP on API 35+ with targetSdk 36 — the platform draws the bars
        // transparently and ignores the property entirely. `navigationBarColor`
        // was never set at all. So the app has been running edge-to-edge by
        // accident, with only the light/dark icon flag doing anything.
        //
        // Edge-to-edge is now explicit, and the icon appearance flags are set
        // for BOTH bars, keyed on the resolved tone. That last part matters
        // more than it used to: tone is user-overridable now, so a light theme
        // on a dark-mode system has to flip the bar icons or they vanish.
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalJadooTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JadooTypography,
            shapes = tokens.shapes.toMaterialShapes(),
            content = content
        )
    }
}
