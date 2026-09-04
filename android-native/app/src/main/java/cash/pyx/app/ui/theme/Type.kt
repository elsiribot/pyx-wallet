package cash.pyx.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cash.pyx.app.R

// Display face: all numbers/amounts, screen titles, names, key values.
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.Normal),
    Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_600, FontWeight.SemiBold),
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

// UI face: body copy, buttons, chips, labels.
val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

/** Named text styles from docs/design/tokens.md. */
object PyxType {
    val screenTitle = TextStyle(fontFamily = SpaceGrotesk, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val centeredTitle = TextStyle(fontFamily = SpaceGrotesk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    val balanceLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em)
    val assetAmount = TextStyle(fontFamily = SpaceGrotesk, fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em)
    val bigAmount = TextStyle(fontFamily = SpaceGrotesk, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em)
    val sectionLabel = TextStyle(fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.em)
    val body = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val rowSub = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
    val rowAmount = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    val keyValue = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val input = TextStyle(fontFamily = SpaceGrotesk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val inputMono = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val fiat = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

/** Material roles mapped onto the two brand faces so unstyled components inherit the look. */
val PyxTypography = Typography(
    displayLarge = PyxType.balanceLarge,
    displayMedium = PyxType.bigAmount,
    displaySmall = PyxType.assetAmount,
    headlineLarge = PyxType.screenTitle,
    headlineMedium = PyxType.screenTitle,
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 21.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 19.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelLarge = PyxType.button,
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = PyxType.sectionLabel,
)
