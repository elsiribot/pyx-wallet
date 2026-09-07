package cash.pyx.app.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.pyx.app.nativeapi.FederationSummary
import cash.pyx.app.nativeapi.LnAddress
import cash.pyx.app.ui.ConfirmationActionGate
import cash.pyx.app.ui.LnAddressStateOwner
import cash.pyx.app.ui.QrPayload
import cash.pyx.app.ui.WalletModalRoute
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxGreen
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxMuted
import cash.pyx.app.ui.theme.PyxOrange
import cash.pyx.app.ui.theme.PyxRed
import cash.pyx.app.ui.theme.PyxSurface2
import cash.pyx.app.ui.theme.PyxText
import cash.pyx.app.ui.theme.PyxType

/**
 * Settings surface for lightning addresses: a federation-grouped list (star marks the primary
 * within each group) plus the claim sheet reachable from the "+" action. [owner] is consumed
 * directly rather than flattened into state+callback params, matching [LnaddrClaimSheet]'s seam.
 */
@Composable
fun LnaddrListContent(
    owner: LnAddressStateOwner,
    federations: List<FederationSummary>,
    clientHandle: Long?,
    factoryHandle: Long,
    back: () -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    var showClaimSheet by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf<LnAddress?>(null) }
    var releaseTarget by remember { mutableStateOf<LnAddress?>(null) }
    var modalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    // Release is irreversible and the dialog stays composed for a frame after the click, so a
    // fast double-tap dispatched it twice. Same gate the on-chain ADDRESS_MUTATION modal uses.
    val releaseGate = remember { ConfirmationActionGate() }

    // The claim sheet populates its own domain list from `owner.state.servers`, which the
    // refresh inside `prime` discovers; recover is idempotent, so running it unconditionally
    // on every visit costs nothing and picks up addresses claimed from another device.
    // `prime` sequences the two — fired concurrently, the refresh's message reset raced the
    // recover failure's message and could erase it.
    LaunchedEffect(factoryHandle) { owner.prime(factoryHandle) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
            .testTag("lnaddr_settings_list"),
    ) {
        PyxTopBar(
            "Lightning addresses",
            onBack = back,
            titleSemantics = Modifier.semantics { heading() },
            actions = {
                PyxIconButton(
                    PyxIcons.Plus,
                    onClick = { showClaimSheet = true },
                    enabled = clientHandle != null,
                    modifier = Modifier.semantics { text = AnnotatedString("Claim a lightning address") },
                )
            },
        )
        if (state.loading && state.addresses.isEmpty()) LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(top = 6.dp), color = PyxOrange, trackColor = PyxSurface2,
        )
        state.message?.let {
            Text(
                it, style = PyxType.rowSub, color = PyxRed,
                modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.addresses.isEmpty()) {
            if (!state.loading) Column(
                Modifier.fillMaxWidth().padding(vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(PyxIcons.Zap, contentDescription = null, tint = PyxFaint.copy(alpha = 0.55f), modifier = Modifier.size(48.dp))
                Text("No lightning addresses yet", style = PyxType.rowSub, color = PyxMuted, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Claim one with the + button above.", style = PyxType.rowSub, color = PyxFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LnaddrGroupPresentation.grouped(state.addresses, federations).forEach { (label, addresses) ->
                SectionLabel(label)
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        addresses.forEachIndexed { index, address ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(onClickLabel = "Lightning address details") { selectedAddress = address }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (address.isPrimary) Icon(
                                    PyxIcons.Star, contentDescription = "Primary", tint = PyxOrange, modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    address.display, style = PyxType.inputMono.copy(fontSize = 14.sp), color = PyxText,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Icon(PyxIcons.ChevronRight, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(18.dp))
                            }
                            if (index != addresses.lastIndex) PyxDivider()
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showClaimSheet && clientHandle != null) {
        LnaddrClaimSheet(owner, clientHandle, factoryHandle, onDismiss = { showClaimSheet = false })
    }

    selectedAddress?.let { address ->
        LnaddrDetailSheet(
            address = address,
            federationName = LnaddrGroupPresentation.federationName(address.federationId, federations),
            canAssign = clientHandle != null,
            onDismiss = { selectedAddress = null },
            onMakePrimary = { owner.setPrimary(factoryHandle, address); selectedAddress = null },
            onAssign = { clientHandle?.let { owner.repoint(it, factoryHandle, address) }; selectedAddress = null },
            onReleaseRequested = {
                if (releaseGate.request { owner.release(factoryHandle, address) }) {
                    releaseTarget = address
                    selectedAddress = null
                    modalRoute = WalletModalRoute.LNADDR_RELEASE
                }
            },
        )
    }

    val release = releaseTarget
    if (modalRoute == WalletModalRoute.LNADDR_RELEASE && release != null) AlertDialog(
        onDismissRequest = { releaseGate.cancel(); modalRoute = null; releaseTarget = null },
        title = { Text("Release address?") },
        text = { Text("Release ${release.display}? Anyone will be able to claim it.") },
        confirmButton = {
            // confirm() consumes the single pending action, so a double-tap on Release
            // dispatches once — the second call finds nothing pending and is a no-op.
            Button(onClick = { modalRoute = null; releaseTarget = null; releaseGate.confirm() }) { Text("Release") }
        },
        dismissButton = { TextButton(onClick = { releaseGate.cancel(); modalRoute = null; releaseTarget = null }) { Text("Cancel") } },
    )
}

/** Grouping/label logic shared by the list and detail sheet: known federation name, or
 * "Unassigned" for an address with no federation destination. */
private object LnaddrGroupPresentation {
    fun grouped(addresses: List<LnAddress>, federations: List<FederationSummary>): List<Pair<String, List<LnAddress>>> =
        addresses.groupBy { label(it.federationId, federations) }.toList()

    fun federationName(federationId: String?, federations: List<FederationSummary>): String? =
        federationId?.let { id -> federations.firstOrNull { it.id == id }?.name }

    private fun label(federationId: String?, federations: List<FederationSummary>) =
        federationName(federationId, federations) ?: federationId ?: "Unassigned"
}

/** Address detail: big mono address, status line, copy/share, and the primary/assign/release
 * mutations. Bottom-sheet chrome matches [ContactDetailSheet] in PyxApp.kt. */
@Composable
private fun LnaddrDetailSheet(
    address: LnAddress,
    federationName: String?,
    canAssign: Boolean,
    onDismiss: () -> Unit,
    onMakePrimary: () -> Unit,
    onAssign: () -> Unit,
    onReleaseRequested: () -> Unit,
) {
    val context = LocalContext.current
    var announcement by remember(address.display) { mutableStateOf<String?>(null) }
    PyxSheet(onDismiss = onDismiss, modifier = Modifier.testTag("lnaddr_detail_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text(
                address.display,
                style = PyxType.inputMono.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                color = PyxText, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (address.federationId != null) PyxGreen else PyxFaint)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (address.federationId != null) "Active · forwarding to ${federationName ?: address.federationId}" else "Unassigned",
                    style = PyxType.rowSub, color = PyxMuted,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PyxGhostButton(
                    "Copy", {
                        announcement = QrPayload.copy(context, "Lightning address", address.display, sensitive = false).announcement
                    },
                    Modifier.weight(1f), icon = PyxIcons.Copy,
                )
                PyxGhostButton(
                    "Share", {
                        context.startActivity(Intent.createChooser(QrPayload.shareIntent(address.display, false), "Share"))
                    },
                    Modifier.weight(1f), icon = PyxIcons.Share,
                )
            }
            AnnouncementText(announcement, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            if (!address.isPrimary && address.federationId != null) PyxGhostButton(
                "Make primary", onMakePrimary, Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            if (address.federationId == null) PyxGhostButton(
                "Assign to this wallet", onAssign, Modifier.fillMaxWidth().padding(bottom = 10.dp), enabled = canAssign,
            )
            PyxGhostButton("Release address…", onReleaseRequested, Modifier.fillMaxWidth(), textColor = PyxRed)
        }
    }
}
