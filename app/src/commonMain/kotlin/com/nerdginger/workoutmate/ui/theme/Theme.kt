package com.nerdginger.workoutmate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system, transcribed from `Black and orange palette views.zip`.
 *
 * Every value here is lifted verbatim from the prototype rather than
 * approximated, because "close enough" across ten screens is what makes a build
 * look unlike its design. The named roles below are the ones the prototype
 * actually uses; where it reached for a one-off colour, that is noted.
 *
 * This is deliberately **not** a Material 3 colour scheme. The design is a
 * bespoke dark palette with a single accent, and mapping it onto M3's
 * primary/secondary/tertiary roles would either lose colours or invent
 * relationships the design does not have. `MaterialTheme` is still installed
 * underneath so stock components (ripples, text selection) behave, but screens
 * read from [WomTheme] instead.
 */

/** The palette. Names describe the role, values are the prototype's hex codes. */
@Immutable
data class WomColors(
    /** App background, behind everything. */
    val background: Color = Color(0xFF0D0C0B),
    /** Raised card surface — the default container for content. */
    val surface: Color = Color(0xFF171512),
    /** Sunken inset inside a card: previous-set strips, completed set rows. */
    val surfaceSunken: Color = Color(0xFF100F0D),
    /** Chip and pill background. */
    val surfaceChip: Color = Color(0xFF201D19),
    /** Hairline around cards and between sections. */
    val border: Color = Color(0xFF2C2823),
    /** A slightly warmer border, used on the routine-builder rows. */
    val borderWarm: Color = Color(0xFF3A322B),

    /** The single accent. Used for actions, records, and the live rest timer. */
    val accent: Color = Color(0xFFFF6A17),
    /** Accent, lightened — hover and pressed states. */
    val accentBright: Color = Color(0xFFFF8A45),
    /** Text and icons sitting *on* the accent. Near-black, not white. */
    val onAccent: Color = Color(0xFF140B04),
    /** Dimmed accent used as a border on ghost buttons and badges. */
    val accentBorder: Color = Color(0xFF4A2A10),
    /** Accent-tinted surface: the backup nag and the rest-timer bar. */
    val accentSurface: Color = Color(0xFF1C1410),
    /** The rest-timer bar's own background — a touch warmer than [accentSurface]. */
    val accentSurfaceWarm: Color = Color(0xFF1B1410),

    /** Primary text. Warm off-white, never pure white. */
    val textPrimary: Color = Color(0xFFF4F1EC),
    /** Body copy inside notices. */
    val textSecondary: Color = Color(0xFFD8CFC6),
    /** Data values and chip labels. */
    val textMuted: Color = Color(0xFFB8B1A9),
    /** Monospace readouts sitting on a sunken surface. */
    val textData: Color = Color(0xFFA39C94),
    /** Labels, captions, section headers. */
    val textDim: Color = Color(0xFF918B83),
    /** The quietest text the design uses: set tags, TARGET/PREV rubrics. */
    val textFaint: Color = Color(0xFF6E6862),
    /** Inactive tab labels. */
    val textInactive: Color = Color(0xFF7C766F),
    /** Inactive tab icon outline. */
    val iconInactive: Color = Color(0xFF5A554F),
)

/**
 * The two families the design uses.
 *
 * Defaults are the platform's own, so `commonMain` stays buildable and
 * previewable with no font files and, more importantly, with no reference to a
 * generated Android `R` class. The real families are supplied by the Android
 * layer — see `AndroidFonts.kt` — which is what keeps an iOS target additive.
 */
@Immutable
data class WomFonts(
    /** Archivo. Interface text. */
    val sans: FontFamily = FontFamily.SansSerif,
    /** JetBrains Mono. Every number. */
    val mono: FontFamily = FontFamily.Monospace,
)

/**
 * Type styles.
 *
 * The prototype pairs Archivo for interface text with JetBrains Mono for every
 * number — weights, reps, durations, volumes. That split is not decoration: it
 * is what makes a column of set results scan as a column, and it is why [mono]
 * is a first-class role here rather than a variant of the body style.
 */
@Immutable
data class WomTypography(
    val display: TextStyle,
    val title: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /**
     * The uppercase rubric above a section — "LAST SESSION", "TARGET".
     * The wide tracking is load-bearing; at 12sp without it these read as
     * shouting rather than as a label.
     */
    val overline: TextStyle,
    val caption: TextStyle,
    val button: TextStyle,
    val tab: TextStyle,
    /** Every number in the app. */
    val mono: TextStyle,
    /** A logged set's result line. */
    val monoSet: TextStyle,
    /** The big figure on a stat tile, and the rest-timer clock. */
    val monoStat: TextStyle,
    /** The quiet monospace rubric: "PREV", set index tags. */
    val monoTiny: TextStyle,
)

/** Builds the type scale over a pair of families. Sizes are the prototype's. */
fun womTypography(fonts: WomFonts): WomTypography = WomTypography(
    display = TextStyle(fonts.sans, FontWeight.Bold, 30.sp, letterSpacing = (-0.02).em),
    title = TextStyle(fonts.sans, FontWeight.Bold, 16.sp),
    cardTitle = TextStyle(fonts.sans, FontWeight.SemiBold, 15.sp),
    body = TextStyle(fonts.sans, FontWeight.Normal, 13.sp, lineHeight = 19.sp),
    label = TextStyle(fonts.sans, FontWeight.SemiBold, 12.sp),
    overline = TextStyle(fonts.sans, FontWeight.SemiBold, 12.sp, letterSpacing = 0.14.em),
    caption = TextStyle(fonts.sans, FontWeight.Normal, 11.sp, lineHeight = 14.sp),
    button = TextStyle(fonts.sans, FontWeight.Bold, 16.sp),
    tab = TextStyle(fonts.sans, FontWeight.Bold, 10.5.sp),
    mono = TextStyle(fonts.mono, FontWeight.Normal, 12.sp),
    monoSet = TextStyle(fonts.mono, FontWeight.Medium, 14.5.sp),
    monoStat = TextStyle(fonts.mono, FontWeight.Bold, 22.sp),
    monoTiny = TextStyle(fonts.mono, FontWeight.Bold, 10.sp, letterSpacing = 0.1.em),
)

/** Positional-argument helper so the scale above reads as a table. */
private fun TextStyle(
    family: FontFamily,
    weight: FontWeight,
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/** Corner radii, as used by the prototype. */
@Immutable
data class WomShapes(
    /** The primary content card. */
    val card: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** Secondary cards — last session, exercise blocks. */
    val cardSmall: RoundedCornerShape = RoundedCornerShape(14.dp),
    /** Stat tiles, notices, the primary button. */
    val tile: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Ghost buttons. */
    val button: RoundedCornerShape = RoundedCornerShape(9.dp),
    /** Set rows and inset strips. */
    val inset: RoundedCornerShape = RoundedCornerShape(8.dp),
    /** Chips and badges. */
    val chip: RoundedCornerShape = RoundedCornerShape(6.dp),
    /** The smallest: tab icons, accent badges. */
    val badge: RoundedCornerShape = RoundedCornerShape(5.dp),
)

/**
 * Spacing.
 *
 * The prototype is built on a 14dp screen gutter with 16dp card padding, which
 * is why those two are named rather than left as magic numbers at call sites.
 */
@Immutable
data class WomSpacing(
    val gutter: androidx.compose.ui.unit.Dp = 14.dp,
    val cardPadding: androidx.compose.ui.unit.Dp = 16.dp,
    val tight: androidx.compose.ui.unit.Dp = 6.dp,
    val small: androidx.compose.ui.unit.Dp = 10.dp,
    val medium: androidx.compose.ui.unit.Dp = 12.dp,
    val large: androidx.compose.ui.unit.Dp = 18.dp,
    /** The primary call-to-action height, e.g. "Start session". */
    val actionHeight: androidx.compose.ui.unit.Dp = 52.dp,
    /** A logged set row. Sized for a thumb between sets. */
    val setRowHeight: androidx.compose.ui.unit.Dp = 44.dp,
)

private val LocalWomColors = staticCompositionLocalOf { WomColors() }
private val LocalWomTypography = staticCompositionLocalOf { womTypography(WomFonts()) }
private val LocalWomShapes = staticCompositionLocalOf { WomShapes() }
private val LocalWomSpacing = staticCompositionLocalOf { WomSpacing() }

/** Accessor for the design system. `WomTheme.colors.accent`, and so on. */
object WomTheme {
    val colors: WomColors
        @Composable @ReadOnlyComposable get() = LocalWomColors.current
    val type: WomTypography
        @Composable @ReadOnlyComposable get() = LocalWomTypography.current
    val shapes: WomShapes
        @Composable @ReadOnlyComposable get() = LocalWomShapes.current
    val spacing: WomSpacing
        @Composable @ReadOnlyComposable get() = LocalWomSpacing.current
}

/**
 * Installs the design system.
 *
 * There is no light palette. The design is a single dark theme by intent — a
 * gym is not a place where a theme toggle earns its keep — so [isSystemInDarkTheme]
 * is deliberately not consulted, and the status bar is told as much by the
 * activity rather than being inferred here.
 */
@Composable
fun WorkOutMateTheme(
    fonts: WomFonts = WomFonts(),
    content: @Composable () -> Unit,
) {
    val colors = WomColors()
    CompositionLocalProvider(
        LocalWomColors provides colors,
        LocalWomTypography provides womTypography(fonts),
        LocalWomShapes provides WomShapes(),
        LocalWomSpacing provides WomSpacing(),
    ) {
        MaterialTheme(
            // Only the roles stock components actually reach for. Screens use
            // WomTheme; this exists so ripples and selection handles are the
            // right colour rather than Material purple.
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                outline = colors.border,
            ),
            typography = Typography(),
            content = content,
        )
    }
}

/** `sp`-relative letter spacing, so tracking scales with the type size. */
private val Double.em: androidx.compose.ui.unit.TextUnit get() = this.toFloat().em
private val Float.em: androidx.compose.ui.unit.TextUnit
    get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Em)
