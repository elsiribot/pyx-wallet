package cash.pyx.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Feather/Lucide-style stroke icons (24x24 viewport, 2px stroke, round caps)
// built inline so the locked dependency graph stays unchanged.
private fun strokeIcon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    paths.forEach { data ->
        builder.addPath(
            pathData = addPathNodes(data),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

object PyxIcons {
    val ChevronLeft = strokeIcon("chevron-left", "M15 18l-6-6 6-6")
    val ChevronRight = strokeIcon("chevron-right", "M9 18l6-6-6-6")
    val Gear = strokeIcon(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    )
    val Eye = strokeIcon(
        "eye",
        "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7",
        "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    )
    val EyeOff = strokeIcon(
        "eye-off",
        "M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94",
        "M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19",
        "M14.12 14.12a3 3 0 1 1-4.24-4.24",
        "M1 1l22 22",
    )
    val Users = strokeIcon(
        "users",
        "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2",
        "M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
        "M23 21v-2a4 4 0 0 0-3-3.87",
        "M16 3.13a4 4 0 0 1 0 7.75",
    )
    val Scan = strokeIcon(
        "scan-line",
        "M3 7V5a2 2 0 0 1 2-2h2",
        "M17 3h2a2 2 0 0 1 2 2v2",
        "M21 17v2a2 2 0 0 1-2 2h-2",
        "M7 21H5a2 2 0 0 1-2-2v-2",
        "M7 12h10",
    )
    val ArrowDown = strokeIcon("arrow-down", "M12 5v14", "M19 12l-7 7-7-7")
    val ArrowUp = strokeIcon("arrow-up", "M12 19V5", "M5 12l7-7 7 7")
    val Copy = strokeIcon(
        "copy",
        "M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z",
        "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1",
    )
    val Share = strokeIcon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val Zap = strokeIcon("zap", "M13 2L3 14h9l-1 8 10-12h-9l1-8")
    val Check = strokeIcon("check", "M20 6L9 17l-5-5")
    val Link = strokeIcon(
        "link",
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    )
    val Search = strokeIcon("search", "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0", "M21 21l-4.35-4.35")
    val Close = strokeIcon("x", "M18 6L6 18", "M6 6l12 12")
    val Wrench = strokeIcon(
        "wrench",
        "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z",
    )
    val Transfers = strokeIcon("transfers", "M7 10l-3 3 3 3", "M4 13h13", "M17 8l3-3-3-3", "M20 5H8")
    val ChevronDown = strokeIcon("chevron-down", "M6 9l6 6 6-6")
    val Plus = strokeIcon("plus", "M12 5v14", "M5 12h14")
    val Lock = strokeIcon(
        "lock",
        "M6 11h12a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z",
        "M8 11V8a4 4 0 0 1 8 0v3",
    )
    val ArrowRight = strokeIcon("arrow-right", "M5 12h13", "M13 6l6 6-6 6")
    val SquarePlus = strokeIcon(
        "square-plus",
        "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z",
        "M12 8v8",
        "M8 12h8",
    )
    val Wallet = strokeIcon(
        "wallet",
        "M19 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z",
        "M3 5v0a2 2 0 0 0 2 2h14",
        "M16 14h.01",
    )
    val Fingerprint = strokeIcon(
        "fingerprint",
        "M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4",
        "M14 13.12c0 2.38 0 6.38-1 8.88",
        "M17.29 21.02c.12-.6.43-2.3.5-3.02",
        "M2 12a10 10 0 0 1 18-6",
        "M2 16h.01",
        "M21.8 16c.2-2 .131-5.354 0-6",
        "M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2",
        "M8.65 22c.21-.66.45-1.32.57-2",
        "M9 6.8a6 6 0 0 1 9 5.2v2",
    )
    val Info = strokeIcon("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18", "M12 11v5", "M12 7.5h.01")
    val Warn = strokeIcon(
        "triangle-alert",
        "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
        "M12 9v4",
        "M12 17h.01",
    )
    val QrGlyph = strokeIcon(
        "qr-glyph",
        "M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z",
        "M14 14h3v3", "M20 14v.01", "M14 20v.01", "M20 20v.01", "M17 17v3",
    )
    val AtSign = strokeIcon(
        "at-sign",
        "M16 12a4 4 0 1 0-8 0 4 4 0 0 0 8 0",
        "M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8",
    )
    val Star = strokeIcon(
        "star",
        "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904" +
            "l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0" +
            "L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906" +
            "l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z",
    )
}
