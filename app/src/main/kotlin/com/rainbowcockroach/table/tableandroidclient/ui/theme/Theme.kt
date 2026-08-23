package com.rainbowcockroach.table.tableandroidclient.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Above this the system is asking for contrast, not for a brand (`../table-colors.md`). */
private const val HIGH_CONTRAST = 0.5f

/**
 * The one theme, seeded from `../tokens.json`.
 *
 * `../UI.md` §10: Material You dynamic colour is deliberately **not** adopted — it would hand
 * the brand to the wallpaper, which is the opposite of what this palette is for.
 */
@Composable
fun TableClientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkTokens else LightTokens
    val colors = if (systemAsksForContrast()) base.withoutBrand() else base
    CompositionLocalProvider(LocalTableColors provides colors) {
        MaterialTheme(
            colorScheme = colors.scheme(darkTheme),
            typography = Typography,
            content = content,
        )
    }
}

/**
 * Every role Material draws with, so nothing falls through to the baseline purple.
 *
 * The four families keep the jobs `../table-colors.md` gives them: rose is primary because it
 * is what *this device* does, slate is secondary because it is what is moving, butter is
 * tertiary because it is the server filling a file. Errors stay in the rose family — §2 puts
 * them in the notice lane, which is rose-tint, and a stock Material red would be the one
 * colour in the app sampled from nothing.
 */
internal fun TableColors.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = rose,
        onPrimary = onAccent,
        primaryContainer = roseTint,
        onPrimaryContainer = roseText,
        inversePrimary = rosePress,
        secondary = slate,
        onSecondary = onAccent,
        secondaryContainer = slateSurface,
        onSecondaryContainer = slateText,
        tertiary = butter,
        onTertiary = onAccent,
        tertiaryContainer = butterTint,
        onTertiaryContainer = butterText,
        background = paper,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = divider,
        onSurfaceVariant = ink2,
        surfaceTint = rose,
        inverseSurface = ink,
        inverseOnSurface = surface,
        error = roseText,
        onError = onAccent,
        errorContainer = roseTint,
        onErrorContainer = roseText,
        outline = lineStrong,
        outlineVariant = line,
        scrim = ink,
        surfaceBright = surface,
        surfaceDim = divider,
        surfaceContainerLowest = surface,
        surfaceContainerLow = paper,
        surfaceContainer = paper,
        surfaceContainerHigh = divider,
        surfaceContainerHighest = line,
    )
}

/** `UiModeManager.getContrast` is the only forced-contrast signal an app gets; API 34+. */
@Composable
private fun systemAsksForContrast(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val manager = LocalContext.current.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    var contrast by remember { mutableStateOf(manager.contrast) }
    DisposableEffect(manager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(Runnable::run, listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast >= HIGH_CONTRAST
}
