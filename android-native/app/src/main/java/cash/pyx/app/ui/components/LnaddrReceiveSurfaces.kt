package cash.pyx.app.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.pyx.app.ui.QrPayload
import cash.pyx.app.ui.theme.PyxBorder
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxMuted
import cash.pyx.app.ui.theme.PyxOnOrange
import cash.pyx.app.ui.theme.PyxOrange
import cash.pyx.app.ui.theme.PyxSurface
import cash.pyx.app.ui.theme.PyxText
import cash.pyx.app.ui.theme.PyxType

/**
 * Receive-screen lightning-address surfaces. Both only appear in the amountless reusable-LNURL
 * state (see `ReceiveLnaddrPresentation.surface`): [LnaddrClaimBanner] invites a claim when the
 * wallet has no primary address, [LnaddrReceiveAddressRow] takes over from the raw LNURL
 * `CodeField` as the primary share surface once it has one.
 */
@Composable
fun LnaddrClaimBanner(onClaim: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag("receive_lnaddr_banner")
            .background(PyxOrange.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, PyxOrange.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            PyxIcons.AtSign,
            tint = PyxOrange,
            size = 38.dp,
            cornerRadius = 11.dp,
            iconSize = 19.dp,
            background = PyxOrange.copy(alpha = 0.16f),
        )
        Column(Modifier.weight(1f)) {
            Text("Claim your Lightning address", style = PyxType.rowTitle.copy(fontSize = 14.sp), color = PyxText)
            Text(
                "Get paid at a name, not a code",
                style = PyxType.rowSub, color = PyxMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .heightIn(min = 34.dp)
                .background(PyxOrange, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Claim a lightning address", onClick = onClaim)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Claim", style = PyxType.button.copy(fontSize = 13.5.sp), color = PyxOnOrange)
        }
    }
}

/**
 * The primary lightning address as the thing to share: mono `username` + muted `@domain`, with
 * the same tap-to-copy / copy / share affordances the LNURL `CodeField` carries, and a toggle
 * that reveals the raw LNURL underneath for wallets that need it. The QR keeps encoding the
 * LNURL — this row replaces the code text, not the code.
 */
@Composable
fun LnaddrReceiveAddressRow(
    address: String,
    lnurlShown: Boolean,
    onToggleLnurl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var announcement by remember(address) { mutableStateOf<String?>(null) }
    val copyAddress = { announcement = QrPayload.copy(context, "Lightning address", address, sensitive = false).announcement }
    Column(modifier.fillMaxWidth().testTag("receive_lnaddr_row")) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(PyxSurface, RoundedCornerShape(14.dp))
                .border(1.dp, PyxBorder, RoundedCornerShape(14.dp))
                .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                PyxIcons.AtSign,
                tint = PyxOrange,
                size = 32.dp,
                cornerRadius = 10.dp,
                iconSize = 17.dp,
                background = PyxOrange.copy(alpha = 0.16f),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).clickable(onClickLabel = "Copy") { copyAddress() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    address.substringBefore('@'),
                    style = PyxType.inputMono.copy(fontSize = 14.5.sp), color = PyxText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "@" + address.substringAfter('@'),
                    style = PyxType.inputMono.copy(fontSize = 14.5.sp), color = PyxMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 1.dp, height = 28.dp).background(PyxBorder))
            IconButton(onClick = copyAddress, modifier = Modifier.semantics { text = AnnotatedString("Copy") }) {
                Icon(PyxIcons.Copy, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { context.startActivity(Intent.createChooser(QrPayload.shareIntent(address, false), "Share")) },
                modifier = Modifier.semantics { text = AnnotatedString("Share") },
            ) {
                Icon(PyxIcons.Share, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(18.dp))
            }
        }
        AnnouncementText(announcement, Modifier.padding(top = 6.dp))
        Text(
            "Reusable — share it anywhere. Tap to copy.",
            style = PyxType.rowSub, color = PyxFaint,
            modifier = Modifier.padding(top = 8.dp),
        )
        PyxTextLink(if (lnurlShown) "Hide LNURL" else "Show LNURL", onToggleLnurl, Modifier.padding(top = 2.dp))
    }
}
