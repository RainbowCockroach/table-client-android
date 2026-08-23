package com.rainbowcockroach.table.tableandroidclient.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The tokens of `../table-colors.md`, one field per row of its tables.
 *
 * `../UI.md` §11 rule 15: this is the only place hex appears in the app, and rule 16: every
 * token's dark value is authored beside its light one rather than derived by inversion.
 */
data class TableColors(
    val paper: Color,
    val surface: Color,
    val divider: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val roseTint: Color,
    val roseLine: Color,
    val rose: Color,
    val rosePress: Color,
    val roseText: Color,
    val butterTint: Color,
    val butterLine: Color,
    val butter: Color,
    val butterText: Color,
    val slateSurface: Color,
    val slate: Color,
    val slateText: Color,
    /** What sits on a `rose` or `butter` fill; the palette has no token for it. */
    val onAccent: Color,
)

internal val LightTokens = TableColors(
    paper = Color(0xFFF9F6F2),
    surface = Color(0xFFFEFDFB),
    divider = Color(0xFFECE9E4),
    line = Color(0xFFE1DDD6),
    lineStrong = Color(0xFFCCC6BD),
    ink = Color(0xFF27221A),
    ink2 = Color(0xFF6D6860),
    ink3 = Color(0xFF908B84),
    roseTint = Color(0xFFFFEAEF),
    roseLine = Color(0xFFFFD4E0),
    rose = Color(0xFFBC4C75),
    rosePress = Color(0xFFAA3B65),
    roseText = Color(0xFFA33C63),
    butterTint = Color(0xFFF6EFB5),
    butterLine = Color(0xFFEBDC8E),
    butter = Color(0xFF947208),
    butterText = Color(0xFF705801),
    slateSurface = Color(0xFFECF9FB),
    slate = Color(0xFF4C7E86),
    slateText = Color(0xFF3A666D),
    onAccent = Color(0xFFFFFFFF),
)

internal val DarkTokens = TableColors(
    paper = Color(0xFF13100C),
    surface = Color(0xFF1C1913),
    divider = Color(0xFF26221C),
    line = Color(0xFF39352D),
    lineStrong = Color(0xFF4C473E),
    ink = Color(0xFFF0ECE7),
    ink2 = Color(0xFFAFAAA3),
    ink3 = Color(0xFF8A857E),
    roseTint = Color(0xFF44212D),
    roseLine = Color(0xFF6A3446),
    rose = Color(0xFFE1789B),
    rosePress = Color(0xFFEF90AE),
    roseText = Color(0xFFF696B4),
    butterTint = Color(0xFF332B05),
    butterLine = Color(0xFF52480E),
    butter = Color(0xFFE0CD48),
    butterText = Color(0xFFE7D760),
    slateSurface = Color(0xFF233033),
    slate = Color(0xFF7EA7AD),
    slateText = Color(0xFF9BBFC5),
    onAccent = Color(0xFF13100C),
)

/**
 * `../table-colors.md`, "Adopting it per platform": where the OS asks for high contrast the
 * brand layer is dropped rather than blended, so every family collapses onto the neutrals.
 */
internal fun TableColors.withoutBrand() = copy(
    roseTint = surface,
    roseLine = ink,
    rose = ink,
    rosePress = ink,
    roseText = ink,
    butterTint = surface,
    // Still a fill and a track: they carry ink now, so they cannot become ink themselves.
    butterLine = divider,
    butter = ink,
    butterText = ink,
    slateSurface = surface,
    slate = ink,
    slateText = ink,
    line = ink2,
    lineStrong = ink,
    ink2 = ink,
    ink3 = ink,
    onAccent = surface,
)

val LocalTableColors = staticCompositionLocalOf { LightTokens }

/** The brand roles Material has no slot for; the rest ride in `MaterialTheme.colorScheme`. */
val tableColors: TableColors
    @Composable @ReadOnlyComposable get() = LocalTableColors.current
