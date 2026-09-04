package cash.pyx.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PyxColorScheme = darkColorScheme(
    primary = PyxOrange,
    onPrimary = PyxOnOrange,
    background = PyxBackground,
    onBackground = PyxText,
    surface = PyxSurface,
    onSurface = PyxText,
    surfaceVariant = PyxSurfaceVariant,
    onSurfaceVariant = PyxMuted,
    outline = PyxOutline,
    error = PyxError,
)

@Composable
fun PyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PyxColorScheme,
        // Deliberately retain Material's default type scale and component shapes.
        content = content,
    )
}
