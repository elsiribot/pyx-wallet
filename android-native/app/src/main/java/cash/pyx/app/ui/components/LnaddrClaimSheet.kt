package cash.pyx.app.ui.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.pyx.app.ui.ClaimCheck
import cash.pyx.app.ui.LnAddressStateOwner
import cash.pyx.app.ui.LnaddrClaimPresentation
import cash.pyx.app.ui.LnaddrDomainOption
import cash.pyx.app.ui.theme.PyxBorder
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxGreen
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxMuted
import cash.pyx.app.ui.theme.PyxOrange
import cash.pyx.app.ui.theme.PyxRed
import cash.pyx.app.ui.theme.PyxSurface2
import cash.pyx.app.ui.theme.PyxText
import cash.pyx.app.ui.theme.PyxType
import kotlinx.coroutines.delay

/**
 * Claim-your-address sheet: pick a domain from the discovered [LnAddressStateOwner.state]
 * servers, type a username, and see a debounced availability check before claiming.
 *
 * Quotes and the claim itself go through [owner] rather than a raw [cash.pyx.app.nativeapi.NativeWalletApi]
 * handle, so this composable stays free of native-result plumbing — see
 * [LnAddressStateOwner.quote] and [LnAddressStateOwner.claim].
 */
@Composable
fun LnaddrClaimSheet(
    owner: LnAddressStateOwner,
    clientHandle: Long,
    factoryHandle: Long,
    onDismiss: () -> Unit,
    /** Seeds the username field. Always empty in the app — the debug screenshot fixtures use it
     * to reach the "typed name, availability checked" state without driving the soft keyboard. */
    initialUsername: String = "",
) {
    val state by owner.state.collectAsStateWithLifecycle()
    val options = remember(state.servers) { LnaddrClaimPresentation.domainOptions(state.servers) }
    // First option, not "the one whose domain reads pyx.cash": domains in an announcement are
    // not bound to the announcing origin, so matching on the name would let a hostile server
    // that advertises `pyx.cash` become the pre-selected default. Rust's discover() always
    // emits the built-in server first.
    var selected by remember(options) { mutableStateOf(options.firstOrNull()) }
    // Single source of truth for the name: what is claimed is what is shown. Sanitizing on the
    // way in (rather than displaying the raw text and claiming a sanitized copy) means typing
    // `Eric!` can no longer display `Eric!` while claiming `eric`.
    var username by remember { mutableStateOf(LnaddrClaimPresentation.sanitizeUsername(initialUsername)) }
    var check by remember { mutableStateOf<ClaimCheck>(ClaimCheck.Idle) }
    var claiming by remember { mutableStateOf(false) }
    val currentOwner by rememberUpdatedState(owner)

    LaunchedEffect(Unit) { owner.clearMessage() }

    // Debounced quote, same one-second-after-last-keystroke shape as ReceiveContent's
    // invoice generation (PyxApp.kt's `ReceiveContent`): the check goes to `Checking`
    // immediately so the status line never looks stale while the request is pending.
    LaunchedEffect(username, selected) {
        val option = selected ?: run { check = ClaimCheck.Idle; return@LaunchedEffect }
        if (username.isBlank()) {
            check = ClaimCheck.Idle
            return@LaunchedEffect
        }
        check = ClaimCheck.Checking
        delay(1000)
        check = currentOwner.quote(factoryHandle, option.origin, option.domain, username)
    }

    PyxSheet(onDismiss = onDismiss, modifier = Modifier.testTag("lnaddr_claim_sheet")) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text(
                "Claim your address",
                style = PyxType.centeredTitle.copy(fontSize = 18.sp),
                color = PyxText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Text(
                "Anyone can pay this name from any Lightning wallet. It forwards to your Pyx wallet.",
                style = PyxType.rowSub,
                color = PyxMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            )
            if (options.isNotEmpty()) {
                DomainSelector(options, selected) { selected = it }
                Spacer(Modifier.height(20.dp))
            }
            // Username field: mono input style matching the receive amount field, with the
            // @domain suffix rendered inline in muted text.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PyxSurface2, RoundedCornerShape(12.dp))
                    .border(1.dp, PyxBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The field is sized by a transparent copy of its own content (plus room for the
                // caret) rather than by BasicTextField's ~100dp intrinsic minimum, so the muted
                // "@domain" always sits directly against the name and the two read as one
                // address instead of being separated by a gap the length of the field's minimum.
                Box(Modifier.weight(1f, fill = false)) {
                    Text(
                        username.ifEmpty { "username" },
                        style = PyxType.inputMono,
                        color = Color.Transparent,
                        maxLines = 1,
                        softWrap = false,
                        // Sizing-only: without this the username is announced twice (once here,
                        // once from the field) and shows up twice in accessibility-tree dumps.
                        modifier = Modifier.padding(end = 2.dp).clearAndSetSemantics {},
                    )
                    BasicTextField(
                        value = username,
                        onValueChange = { username = LnaddrClaimPresentation.sanitizeUsername(it) },
                        modifier = Modifier.matchParentSize().testTag("lnaddr_username"),
                        textStyle = PyxType.inputMono.copy(color = PyxText),
                        cursorBrush = SolidColor(PyxOrange),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        decorationBox = { inner ->
                            Box {
                                if (username.isEmpty()) Text("username", style = PyxType.inputMono, color = PyxFaint)
                                inner()
                            }
                        },
                    )
                }
                selected?.let {
                    Text("@${it.domain}", style = PyxType.inputMono, color = PyxMuted)
                }
            }
            Spacer(Modifier.height(14.dp))
            StatusLine(check)
            Spacer(Modifier.height(20.dp))
            val option = selected
            PyxPrimaryButton(
                text = when {
                    claiming -> "Claiming…"
                    option != null && username.isNotBlank() -> "Claim $username@${option.domain}"
                    else -> "Claim name"
                },
                onClick = {
                    val chosen = selected ?: return@PyxPrimaryButton
                    claiming = true
                    currentOwner.claim(clientHandle, factoryHandle, chosen.origin, chosen.domain, username) { done ->
                        claiming = false
                        if (done) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !claiming && option != null && LnaddrClaimPresentation.canClaim(check, username),
            )
            state.message?.let {
                Text(
                    it, style = PyxType.rowSub, color = PyxRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            // "Hosted by" names the *server*, not the domain: they are frequently different,
            // and a hostile announcement can offer a domain it does not own.
            option?.let {
                Text(
                    "Hosted by ${LnaddrClaimPresentation.originHost(it.origin)} · you can release it anytime",
                    style = PyxType.rowSub, color = PyxFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(check: ClaimCheck) {
    val (dot, text, color) = when (check) {
        ClaimCheck.Idle -> return
        ClaimCheck.Checking -> Triple(PyxMuted, "Checking availability…", PyxMuted)
        ClaimCheck.Available -> Triple(PyxGreen, "This name is available", PyxGreen)
        ClaimCheck.Taken -> Triple(PyxRed, "This name is taken", PyxRed)
        ClaimCheck.Reserved -> Triple(PyxRed, "This name is reserved", PyxRed)
        is ClaimCheck.Paid -> Triple(PyxFaint, "Paid — not supported yet", PyxMuted)
        is ClaimCheck.Error -> Triple(PyxRed, check.hint, PyxRed)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (check == ClaimCheck.Checking) {
            CircularProgressIndicator(Modifier.size(10.dp), color = PyxMuted, strokeWidth = 1.5.dp)
        } else {
            StatusDot(dot)
        }
        Text(text, style = PyxType.rowSub, color = color)
    }
}

/** Chip row for up to 3 domains; a scrollable checklist (CurrencyPickerSheet's row style)
 * beyond that, since a wrapping chip row stops reading well past a handful of options.
 *
 * Each option that needs it (see [LnaddrDomainOption.originLabel]) also names the server that
 * would host the address, so two chips reading `pyx.cash` — one genuine, one from an
 * announcement that merely claims the name — can be told apart. */
@Composable
private fun DomainSelector(
    options: List<LnaddrDomainOption>,
    selected: LnaddrDomainOption?,
    onSelect: (LnaddrDomainOption) -> Unit,
) {
    if (options.size <= 3) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                DomainChip(option, option == selected) { onSelect(option) }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(options, key = { it.origin + "|" + it.domain }) { option ->
                DomainRow(option, option == selected) { onSelect(option) }
            }
        }
    }
}

/** Semantics text for one option: the address domain, plus the hosting server when that is
 * not already obvious. Read by tests and by TalkBack, which must not be able to confuse two
 * same-named options either. */
private fun optionSemantics(option: LnaddrDomainOption): String =
    option.originLabel?.let { "${option.domain}, hosted by $it" } ?: option.domain

@Composable
private fun DomainChip(option: LnaddrDomainOption, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .background(if (selected) PyxOrange.copy(alpha = 0.14f) else PyxSurface2, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) PyxOrange else PyxBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { text = AnnotatedString(optionSemantics(option)) },
    ) {
        Text(
            option.domain,
            style = PyxType.rowSub.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (selected) PyxOrange else PyxMuted,
        )
        option.originLabel?.let {
            Text(
                it,
                style = PyxType.rowSub.copy(fontSize = 11.sp),
                color = PyxFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun DomainRow(option: LnaddrDomainOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) PyxSurface2 else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { text = AnnotatedString(optionSemantics(option)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.domain, style = PyxType.keyValue.copy(fontSize = 14.5.sp), color = PyxText)
            option.originLabel?.let {
                Text(
                    it,
                    style = PyxType.rowSub.copy(fontSize = 11.5.sp),
                    color = PyxFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        if (selected) Icon(PyxIcons.Check, contentDescription = "Selected", tint = PyxOrange, modifier = Modifier.size(18.dp))
    }
}
