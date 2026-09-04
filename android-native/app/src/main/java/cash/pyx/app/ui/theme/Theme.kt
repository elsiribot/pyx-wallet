package cash.pyx.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PyxColorScheme = darkColorScheme(
    primary = PyxOrange,
    onPrimary = PyxOnOrange,
    background = PyxBackground,
    onBackground = PyxText,
    surface = PyxSurface,
    onSurface = PyxText,
    surfaceVariant = PyxSurfaceVariant,
    onSurfaceVariant = PyxMuted,
    surfaceContainer = PyxSurface,
    surfaceContainerHigh = PyxSurface2,
    surfaceContainerHighest = PyxSurface3,
    secondary = PyxMuted,
    onSecondary = PyxBackground,
    secondaryContainer = PyxSurface2,
    onSecondaryContainer = PyxText,
    outline = PyxOutline,
    outlineVariant = PyxBorder,
    error = PyxError,
    onError = PyxBackground,
)

// Radii from docs/design/tokens.md: cards 14, buttons 13, inputs 12, icon buttons 11.
private val PyxShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun PyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PyxColorScheme,
        typography = PyxTypography,
        shapes = PyxShapes,
        content = content,
    )
}
