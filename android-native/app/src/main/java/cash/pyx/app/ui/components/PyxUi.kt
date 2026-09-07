package cash.pyx.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.pyx.app.ui.theme.PyxBorder
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxGreen
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxOnGreen
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

/**
 * Screen title row: chevron back + Space Grotesk title, optional trailing actions.
 *
 * The title, the chevron and the actions are `Row` siblings, so a wrapped title never
 * overlaps them — it grows the bar and leaves the chevron and the action buttons centred
 * against a two-line block, which reads as misaligned. A title that does not fit beside
 * its chevron and actions at `screenTitle` (e.g. "Lightning addresses" plus a "+" action)
 * therefore steps down to the existing `centeredTitle` size to stay on one line. Short
 * titles keep the full `screenTitle` size, and a title that cannot fit on one line even
 * at the smaller size — long titles at large font scales — still wraps rather than being
 * truncated, which is why the wrapped branch keeps the display size and allows a second
 * line. No new type token: both sizes come from `PyxType`.
 */
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
        BoxWithConstraints(titleSemantics.weight(1f)) {
            val measurer = rememberTextMeasurer()
            val available = constraints.maxWidth
            // Measured against the width the chevron and the actions actually leave, so the
            // step-down happens only on the screens that need it.
            fun fitsOneLine(style: TextStyle) =
                measurer.measure(text = title, style = style, softWrap = false, maxLines = 1).size.width <= available
            val display = PyxType.screenTitle
            val compact = display.copy(fontSize = PyxType.centeredTitle.fontSize)
            val style = if (fitsOneLine(display) || !fitsOneLine(compact)) display else compact
            Text(title, style = style, color = PyxText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
    valueStyle: TextStyle = PyxType.keyValue,
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
        if (value != null) Text(value, style = valueStyle, color = valueColor)
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
    // 145-degree gradient toward a darker shade, like the prototype avatars.
    val gradient = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(color, Color(color.red * 0.72f, color.green * 0.72f, color.blue * 0.72f)),
    )
    val initialSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.36f).toSp() }
    Box(modifier.size(size).background(gradient, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = initialSize),
            color = PyxText,
        )
    }
}

/** Small status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** Prototype seed-warn banner: red-tinted surface with a warning triangle. */
@Composable
fun SeedWarnBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = cash.pyx.app.ui.theme.PyxRed.copy(alpha = 0.07f),
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cash.pyx.app.ui.theme.PyxRed.copy(alpha = 0.32f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(PyxIcons.Warn, contentDescription = null, tint = cash.pyx.app.ui.theme.PyxRed,
                modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(text, style = PyxType.rowSub, color = PyxText)
        }
    }
}

/**
 * Prototype 2-column numbered seed grid. Semantics collapse to the joined
 * phrase so assistive tech (and tests) read the words as one ordered string.
 */
@Composable
fun SeedWordGrid(words: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(words.joinToString(" ")) },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEachIndexed { columnIndex, word ->
                    Surface(
                        color = PyxSurface,
                        shape = RoundedCornerShape(11.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(
                                "${rowIndex * 2 + columnIndex + 1}",
                                style = PyxType.seedNum, color = PyxFaint,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.widthIn(min = 17.dp),
                            )
                            Text(word, style = PyxType.seedWord, color = PyxText)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Prototype scan viewfinder: dark radial square, accent corner brackets, and an
 * optional sweeping accent line. [content] (e.g. a camera preview) fills the frame
 * beneath the overlay. Pass sweep = false under reduced motion.
 */
@Composable
fun ScanFrame(
    modifier: Modifier = Modifier,
    sweep: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val sweepFraction = if (sweep) {
        rememberInfiniteTransition(label = "scan-sweep").animateFloat(
            initialValue = 0.07f,
            targetValue = 0.91f,
            animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sweep",
        ).value
    } else -1f
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF10161F), Color(0xFF070A0E))))
            .border(1.dp, PyxBorder, RoundedCornerShape(24.dp)),
    ) {
        content()
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val inset = 14.dp.toPx()
            val arm = 34.dp.toPx()
            val w = size.width
            val h = size.height
            listOf(
                Path().apply { moveTo(inset, inset + arm); lineTo(inset, inset); lineTo(inset + arm, inset) },
                Path().apply { moveTo(w - inset - arm, inset); lineTo(w - inset, inset); lineTo(w - inset, inset + arm) },
                Path().apply { moveTo(inset, h - inset - arm); lineTo(inset, h - inset); lineTo(inset + arm, h - inset) },
                Path().apply { moveTo(w - inset - arm, h - inset); lineTo(w - inset, h - inset); lineTo(w - inset, h - inset - arm) },
            ).forEach { path -> drawPath(path, color = PyxOrange, style = stroke) }
            if (sweepFraction >= 0f) {
                val y = h * sweepFraction
                val lineBrush = Brush.horizontalGradient(listOf(Color.Transparent, PyxOrange, Color.Transparent))
                val glowBrush = Brush.horizontalGradient(
                    listOf(Color.Transparent, PyxOrange.copy(alpha = 0.25f), Color.Transparent),
                )
                drawRect(brush = glowBrush, topLeft = Offset(18.dp.toPx(), y - 4.dp.toPx()), size = Size(w - 36.dp.toPx(), 8.dp.toPx()))
                drawRect(brush = lineBrush, topLeft = Offset(18.dp.toPx(), y - 1.dp.toPx()), size = Size(w - 36.dp.toPx(), 2.dp.toPx()))
            }
        }
    }
}

/**
 * Prototype slide-to-send: a 58dp track whose accent knob must be dragged to the
 * far end to fire [onConfirm] exactly once. Partial drags snap back. Exposes an
 * accessibility custom action so switch/TalkBack users can confirm without a drag.
 */
@Composable
fun PyxSlideToConfirm(
    enabled: Boolean,
    done: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Slide to send",
    doneLabel: String = "Sending\u2026",
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val knobWidthPx = with(density) { 66.dp.toPx() }
    val endSlackPx = with(density) { 6.dp.toPx() }
    val dragOffset = remember { Animatable(0f) }
    var fired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(done) {
        if (!done) { fired = false; dragOffset.snapTo(0f) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .alpha(if (enabled || done) 1f else 0.45f)
            .background(if (done) PyxGreen else PyxSurface, RoundedCornerShape(15.dp))
            .border(1.dp, if (done) PyxGreen else PyxBorder, RoundedCornerShape(15.dp))
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Send") {
                        if (enabled && !fired) { fired = true; onConfirm(); true } else false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (done) doneLabel else label,
            style = PyxType.button.copy(letterSpacing = 0.02.em),
            color = if (done) PyxOnGreen else PyxMuted,
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(dragOffset.value.roundToInt() + with(density) { 3.dp.roundToPx() }, 0) }
                .padding(vertical = 3.dp)
                .size(width = 66.dp, height = 52.dp)
                .background(if (done) PyxGreen else PyxOrange, RoundedCornerShape(14.dp))
                .pointerInput(enabled, trackWidthPx) {
                    if (!enabled) return@pointerInput
                    val maxPx = (trackWidthPx - knobWidthPx - endSlackPx).coerceAtLeast(0f)
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (dragOffset.value >= maxPx - 1f && !fired && maxPx > 0f) {
                                    fired = true
                                    dragOffset.snapTo(maxPx)
                                    onConfirm()
                                } else {
                                    dragOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { dragOffset.animateTo(0f, tween(280)) } },
                    ) { _, dragAmount ->
                        scope.launch {
                            val maxNow = (trackWidthPx - knobWidthPx - endSlackPx).coerceAtLeast(0f)
                            dragOffset.snapTo((dragOffset.value + dragAmount).coerceIn(0f, maxNow))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PyxIcons.ArrowRight,
                contentDescription = null,
                tint = if (done) PyxOnGreen else PyxOnOrange,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** House bottom-sheet chrome: surface fill, 26dp top radius, 40x4 grip, dark scrim. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PyxSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PyxSurface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        scrimColor = Color(0xFF040609).copy(alpha = 0.55f),
        dragHandle = {
            Box(
                Modifier.padding(top = 6.dp, bottom = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(cash.pyx.app.ui.theme.PyxBorderStrong, RoundedCornerShape(2.dp)),
            )
        },
        modifier = modifier,
        content = content,
    )
}

/** Rounded surface-2 icon tile (prototype .tx .ic / .wallet-ic / method tiles). */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = PyxMuted,
    size: Dp = 42.dp,
    cornerRadius: Dp = 12.dp,
    iconSize: Dp = 21.dp,
    background: Color = PyxSurface2,
) {
    Box(
        modifier.size(size).background(background, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Bordered ₿-badge pill naming the asset (prototype .asset-pill). */
@Composable
fun AssetPill(modifier: Modifier = Modifier, label: String = "Bitcoin") {
    Row(
        modifier
            .background(PyxSurface, RoundedCornerShape(10.dp))
            .border(1.dp, PyxBorder, RoundedCornerShape(10.dp))
            .padding(start = 8.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        BtcBadge(size = 24.dp)
        Text(label, style = PyxType.rowSub.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PyxText)
    }
}

/** Live-region announcement line under copy/paste affordances. */
@Composable
fun AnnouncementText(
    text: String?,
    modifier: Modifier = Modifier,
    mode: androidx.compose.ui.semantics.LiveRegionMode = androidx.compose.ui.semantics.LiveRegionMode.Polite,
) {
    text ?: return
    Text(
        text, style = PyxType.rowSub, color = PyxMuted,
        modifier = modifier.semantics { liveRegion = mode },
    )
}

/** Tap-to-open scan viewfinder with a centred prompt (join + ecash receive). */
@Composable
fun ScanPromptFrame(caption: String, onClickLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ScanFrame(modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(PyxIcons.Scan, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(44.dp))
            Text(caption, style = PyxType.rowSub, color = PyxMuted)
        }
    }
}

/**
 * Amount text whose trailing " sats" unit renders small and muted while the FULL
 * string stays exactly [text] — tests and semantics match the amount verbatim.
 */
fun satAmountAnnotated(
    text: String,
    unitStyle: androidx.compose.ui.text.SpanStyle,
    numberStyle: androidx.compose.ui.text.SpanStyle? = null,
): AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val unitStart = text.lastIndexOf(" sat")
    if (unitStart <= 0) append(text)
    else {
        if (numberStyle != null) withStyle(numberStyle) { append(text.substring(0, unitStart)) }
        else append(text.substring(0, unitStart))
        withStyle(unitStyle) { append(text.substring(unitStart)) }
    }
}
