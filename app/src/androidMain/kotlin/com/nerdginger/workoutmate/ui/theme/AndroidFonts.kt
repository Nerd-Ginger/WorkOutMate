package com.nerdginger.workoutmate.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.nerdginger.workoutmate.R

/**
 * Binds the bundled font files to the design system.
 *
 * This lives in `androidMain` for one reason: it is the only place allowed to
 * touch the generated `R` class. `commonMain` takes a [WomFonts] and never
 * learns where the glyphs came from, which is what keeps adding an iOS target
 * an additive change rather than an unpick.
 *
 * Both faces are **variable** fonts — one file spanning the whole weight axis
 * rather than one file per weight. Google Fonts no longer publishes static
 * instances for either family, and the variable files are smaller than the four
 * static cuts they replace. Android supports the `wght` axis from API 26, which
 * is exactly this app's `minSdk`, so there is no floor to raise and no fallback
 * to write.
 *
 * Both are licensed under the SIL Open Font License 1.1, which permits
 * embedding in an application. The licence texts ship in the APK at
 * `assets/licenses/`, because the OFL requires the licence to travel with the
 * font.
 */

/** Only the weights the type scale actually asks for. */
private val WEIGHTS = listOf(
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
)

// FontVariation is still marked experimental in Compose. It is opted into
// knowingly and in exactly one place: the alternative is four static font files
// per family, which Google Fonts no longer publishes for either of these.
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int): FontFamily = FontFamily(
    WEIGHTS.map { weight ->
        // Declaring the weight twice is not redundant: the FontWeight argument
        // is what Compose matches a style against, while the variation setting
        // is what actually moves the `wght` axis in the file. Supply only the
        // first and every weight renders at the font's default; only the
        // second and Compose cannot tell the four entries apart.
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

/** Archivo and JetBrains Mono, as the design specifies. */
@Composable
fun rememberAppFonts(): WomFonts = WomFonts(
    sans = variableFamily(R.font.archivo),
    mono = variableFamily(R.font.jetbrains_mono),
)
