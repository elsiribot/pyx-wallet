package cash.pyx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cash.pyx.app.ui.theme.PyxBorder
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxMuted
import cash.pyx.app.ui.theme.PyxOnOrange
import cash.pyx.app.ui.theme.PyxOrange
import cash.pyx.app.ui.theme.PyxSurface
import cash.pyx.app.ui.theme.PyxSurface2
import cash.pyx.app.ui.theme.PyxText
import cash.pyx.app.ui.theme.PyxType

/** 44dp bordered icon button (prototype icon-btn: surface, 1px border, 11dp radius). */
@Composable
fun PyxIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PyxText,
    enabled: Boolean = true,
    borderColor: Color = PyxBorder,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier
            .size(44.dp)
            .background(PyxSurface, RoundedCornerShape(11.dp))
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Chevron back control. Carries "Back" text semantics so existing tests and
 * TalkBack keep addressing it by name while rendering icon-only like the prototype.
 */
@Composable
fun PyxBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { text = AnnotatedString("Back") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(PyxIcons.ChevronLeft, contentDescription = null, tint = PyxText, modifier = Modifier.size(24.dp))
    }
}

/** Screen title row: chevron back + 27sp Space Grotesk title, optional trailing actions. */
@Composable
fun PyxTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleSemantics: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) PyxBackButton(onBack)
        Text(title, style = PyxType.screenTitle, color = PyxText, modifier = titleSemantics.weight(1f))
        actions()
    }
}

/**
 * Uppercase 11sp tracking .16em section label. Renders uppercase like the
 * prototype but keeps the natural-case string as its text semantics so tests
 * and screen readers see e.g. "About" rather than "ABOUT".
 */
@Composable
fun SectionLabel(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 22.dp, bottom = 12.dp)
            .semantics { text = AnnotatedString(label) },
    ) {
        Text(
            label.uppercase(),
            style = PyxType.sectionLabel,
            color = PyxFaint,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Bordered surface card, 14dp radius. */
@Composable
fun PyxCard(
    modifier: Modifier = Modifier,
    padding: Dp = 15.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PyxSurface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun PyxDivider() {
    HorizontalDivider(color = PyxBorder, thickness = 1.dp)
}

/** Key/value or navigation row used inside cards (prototype "drow"). */
@Composable
fun CardRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    chevron: Boolean = false,
    valueColor: Color = PyxMuted,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PyxType.rowTitle, color = PyxText)
            if (subtitle != null) Text(subtitle, style = PyxType.rowSub, color = PyxMuted)
        }
        if (value != null) Text(value, style = PyxType.keyValue, color = valueColor)
        if (trailing != null) trailing()
        if (chevron) Icon(PyxIcons.ChevronRight, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(18.dp))
    }
}

/** Filled accent button, 13dp radius. */
@Composable
fun PyxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Box(
        modifier
            .heightIn(min = 50.dp)
            .background(if (enabled) PyxOrange else PyxSurface2, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) PyxOnOrange else PyxFaint, modifier = Modifier.size(18.dp))
            Text(text, style = PyxType.button, color = if (enabled) PyxOnOrange else PyxFaint)
        }
    }
}

/** Ghost surface button with border, 13dp radius. */
@Composable
fun PyxGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    textColor: Color = PyxText,
) {
    Box(
        modifier
            .heightIn(min = 50.dp)
            .background(PyxSurface, RoundedCornerShape(13.dp))
            .border(1.dp, PyxBorder, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) textColor else PyxFaint, modifier = Modifier.size(18.dp))
            Text(text, style = PyxType.button, color = if (enabled) textColor else PyxFaint)
        }
    }
}

/** Quiet inline text action (accent). */
@Composable
fun PyxTextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PyxType.button, color = if (enabled) PyxOrange else PyxFaint)
    }
}

/** Segmented control: surface track 13dp radius, accent pill 9dp radius. */
@Composable
fun PyxSegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(PyxSurface, RoundedCornerShape(13.dp))
            .border(1.dp, PyxBorder, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (active) PyxOrange else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PyxType.button, color = if (active) PyxOnOrange else PyxMuted)
            }
        }
    }
}

/** 28dp Bitcoin badge: accent-tinted rounded square with the currency glyph. */
@Composable
fun BtcBadge(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier.size(size).background(PyxOrange.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("₿", style = PyxType.rowTitle, color = PyxOrange)
    }
}

/** Circular scan FAB, 56dp accent. */
@Composable
fun PyxFab(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, semanticsModifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .background(PyxOrange, CircleShape)
            .clickable(onClick = onClick)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = PyxOnOrange, modifier = Modifier.size(24.dp))
    }
}

private val AvatarPalette = listOf(
    Color(0xFF2C8C76), // teal
    Color(0xFF3E5F8A), // slate blue
    Color(0xFF687C4D), // moss
    Color(0xFF8A6A3B), // ochre
)

/** Colored initial avatar for guardians/federations. */
@Composable
fun InitialAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = AvatarPalette[(name.hashCode().let { if (it < 0) -it else it }) % AvatarPalette.size]
    Box(modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = PyxText)
    }
}

/** Small status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}
