package cash.pyx.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.*
import cash.pyx.app.ui.HomeRefreshStatus
import cash.pyx.app.ui.LnAddressStateOwner
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.theme.PyxTheme

/** Debug-only, fixed synthetic states for screenshot certification. Never accepts wallet payloads. */
class ScreenshotFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent.getStringExtra(EXTRA_FIXTURE).orEmpty()
        setContent { PyxTheme { ScreenshotFixture(fixture) } }
    }

    companion object { const val EXTRA_FIXTURE = "fixture" }
}

@Composable
private fun ScreenshotFixture(fixture: String) = when (fixture) {
    "onboarding", "large_font_narrow" -> PyxApp(
        state = BootstrapState.Onboarding(BootstrapSession.Uninitialized("synthetic", 1L)),
    )
    "home_status_activity" -> PyxApp(
        state = syntheticHome(),
        connection = GuardianConnectionSnapshot(
            guardians = listOf(GuardianStatus("Guardian Alpha", true), GuardianStatus("Guardian Beta", true), GuardianStatus("Guardian Gamma", false)),
            onlineCount = 2, totalCount = 3, requiredCount = 2, state = ConnectionState.DEGRADED,
        ),
        fiatBalance = FiatDisplay("25.00", "USD", "US Dollar", "\$", 2),
        refreshStatus = HomeRefreshStatus.Degraded(1_788_000_000_000L, 2),
        activityState = syntheticActivity(),
    )
    "receive_lnurl" -> PyxApp(
        state = syntheticHome(),
        fiatBalance = FiatDisplay("25.00", "USD", "US Dollar", "\$", 2),
        operation = cash.pyx.app.ui.WalletOperation.Success("LNURL receive", SYNTHETIC_LNURL),
    )
    "home_tx_sheet" -> PyxApp(
        state = syntheticHome(),
        fiatBalance = FiatDisplay("25.00", "USD", "US Dollar", "\$", 2),
        activityState = syntheticActivity().let { activity ->
            activity.copy(detail = cash.pyx.app.data.ActivityDetailState.Open(activity.payments[1]))
        },
    )
    // Lightning-address fixtures. All four drive the real screens through a fake
    // NativeWalletApi (see [FixtureLnaddrApi]) rather than a live wallet, so the canned
    // addresses/servers below are the only data that ever reaches them.
    "receive_lnaddr_banner" -> LnaddrReceiveFixture(addresses = emptyList())
    "receive_lnaddr_claimed" -> LnaddrReceiveFixture(addresses = listOf(SYNTHETIC_PRIMARY))
    "lnaddr_claim_sheet" -> LnaddrFixtureScope(addresses = emptyList()) { owner, state ->
        if (state.servers.isNotEmpty()) cash.pyx.app.ui.components.LnaddrClaimSheet(
            owner, clientHandle = 3L, factoryHandle = 2L, onDismiss = {}, initialUsername = "eric",
        )
    }
    "lnaddr_settings_list" -> LnaddrFixtureScope(
        addresses = listOf(SYNTHETIC_PRIMARY, SYNTHETIC_UNASSIGNED),
    ) { owner, _ ->
        cash.pyx.app.ui.components.LnaddrListContent(
            owner, syntheticHome().snapshot.federations, clientHandle = 3L, factoryHandle = 2L, back = {},
        )
    }
    "send_receive_confirmation" -> FixtureList("Send and receive review — synthetic") {
        item { SafetyCard("Receive Lightning", "1,250 sats · fee 5 sats", "Synthetic request · NOT PAYABLE") }
        item { SafetyCard("Review send", "8,000 sats · fee 12 sats", "No payment will be submitted from this fixture") }
        item { Button(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Confirm synthetic review") } }
    }
    "settings_access_seed_safe" -> FixtureList("Settings and access — synthetic") {
        item { ListItem(headlineContent = { Text("Biometric protection") }, supportingContent = { Text("Strong biometric available") }, trailingContent = { Switch(true, {}) }) }
        item { SafetyCard("Recovery words", "Hidden until authentication succeeds", "No seed words are present in this fixture") }
        item { OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Authenticate to reveal") } }
    }
    "error_offline" -> FixtureList("Offline and error states — synthetic") {
        item { SafetyCard("Wallet is offline", "Showing cached information", "Last refresh unavailable") }
        item { Text("Could not reach federation guardians", color = MaterialTheme.colorScheme.error) }
        item { Button(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Try again") } }
    }
    else -> FixtureList("Unknown synthetic fixture") { item { Text("Fixture rejected") } }
}

/** The claimed primary address, bound to the synthetic federation so `primaryFor` finds it. */
private val SYNTHETIC_PRIMARY = LnAddress(
    domain = "pyx.cash", username = "eric", serverOrigin = "https://lnaddr.pyx.cash",
    federationId = "synthetic-fed", destination = "lnurl1synthetic", isPrimary = true,
    claimedAtSecs = 1_788_000_000L,
)

/** A second address with no federation, so the settings list shows its "Unassigned" group. */
private val SYNTHETIC_UNASSIGNED = LnAddress(
    domain = "tips.example.org", username = "backup", serverOrigin = "https://tips.example.org",
    federationId = null, destination = "lnurl1synthetic", isPrimary = false,
    claimedAtSecs = 1_787_900_000L,
)

private val SYNTHETIC_SERVERS = listOf(
    LnaddrServer("https://lnaddr.pyx.cash", "Pyx addresses", listOf("pyx.cash"), listOf("pyx.cash")),
    LnaddrServer("https://tips.example.org", "Example tips", listOf("tips.example.org"), listOf("tips.example.org")),
)

/**
 * Canned lightning-address backend: the only reads the state owner makes are a snapshot and a
 * discovery, and the only write the fixtures reach is a quote. Everything else keeps the
 * interface's `unavailable()` default, so a fixture can never mutate anything.
 */
private class FixtureLnaddrApi(private val addresses: List<LnAddress>) : NativeWalletApi {
    override suspend fun lnaddrSnapshotAsync(factoryHandle: Long): NativeResult<LnAddressSnapshot> =
        NativeResult.Success(LnAddressSnapshot(addresses))

    override suspend fun lnaddrDiscoverAsync(factoryHandle: Long): NativeResult<LnaddrDiscovery> =
        NativeResult.Success(LnaddrDiscovery(SYNTHETIC_SERVERS))

    override suspend fun lnaddrQuoteAsync(
        factoryHandle: Long, origin: String, domain: String, username: String,
    ): NativeResult<LnaddrQuote> = NativeResult.Success(LnaddrQuote.Free)

    // Recovery is a no-op success: the settings screen runs it on entry and would otherwise
    // paint a red failure message across the fixture.
    override suspend fun lnaddrRecoverAsync(factoryHandle: Long): NativeResult<LnaddrRecovery> =
        NativeResult.Success(LnaddrRecovery(0L))

    // The three non-suspend members carry no interface default; nothing in these fixtures uses them.
    override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
    override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
    override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
}

/** Builds a primed [LnAddressStateOwner] over [FixtureLnaddrApi] and renders [content] in the
 * app's own screen chrome, so the fixture matches what the wallet actually draws. */
@Composable
private fun LnaddrFixtureScope(
    addresses: List<LnAddress>,
    content: @Composable (LnAddressStateOwner, cash.pyx.app.ui.LnAddressState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val owner = remember(addresses) { LnAddressStateOwner(FixtureLnaddrApi(addresses), scope) }
    LaunchedEffect(owner) { owner.refresh(2L) }
    val state by owner.state.collectAsStateWithLifecycle()
    cash.pyx.app.ui.PyxAppChrome { content(owner, state) }
}

/** The receive screen's Lightning tab holding the amountless reusable LNURL — the one state
 * where the claim banner (no primary) or the address row (primary) appears. */
@Composable
private fun LnaddrReceiveFixture(addresses: List<LnAddress>) = LnaddrFixtureScope(addresses) { owner, _ ->
    cash.pyx.app.ui.ReceiveContent(
        state = syntheticHome(),
        operation = cash.pyx.app.ui.WalletOperation.Success("LNURL receive", SYNTHETIC_LNURL),
        initialPayload = null,
        initialType = null,
        back = {},
        submit = { _, _, _, _ -> },
        clear = {},
        ecashFrame = null,
        startEcashDisplay = { _, _ -> },
        stopEcashDisplay = {},
        addresses = emptyList(),
        scan = {},
        lnAddressStateOwner = owner,
    )
}

private const val SYNTHETIC_LNURL =
    "lnurl1dp68gurn8ghj7urjdajzuun9vd6hyunfdenkgtnkv95nze3wdaexwtmvdemrztmsv9ukxmmyv4ej7wf58pjrse34xg" +
        "6rwc3e8ycx2d34xcckywpcvsmkyenzvvck2dnzxccrvc3k893kzwpkv93nycmrvd3rsdf3xesnyv3nxscrwefsvdjq8xmtrv"

private fun syntheticHome() = BootstrapState.Home(
    factoryHandle = 2L,
    snapshot = WalletSnapshot(
        currencyCode = "USD",
        federations = listOf(FederationSummary("synthetic-fed", "Example federation", 3)),
        selected = SelectedWallet(
            clientHandle = 3L, federationId = "synthetic-fed", name = "Example federation", balanceSat = 50_000L,
            payments = emptyList(),
        ),
    ),
)

/** Multi-day synthetic history exercising pending/failed rows and the detail sheet. */
private fun syntheticActivity() = cash.pyx.app.data.ActivityState(
    clientHandle = 3L,
    payments = listOf(
        Payment("synthetic-pending", PaymentDirection.OUTGOING, PaymentType.ONCHAIN, 8_000L, feeSat = 12L,
            timestampMillis = 1_788_000_500_000L, status = PaymentStatus.PENDING,
            txid = "f3a9c1d7e5b24680aa55cc11dd22ee33ff44aa55bb66cc77dd88ee99ff001122",
            address = "bc1qsyntheticfixtureaddressxu3tq7"),
        Payment("synthetic-in", PaymentDirection.INCOMING, PaymentType.LIGHTNING, 1_250L,
            timestampMillis = 1_788_000_000_000L, status = PaymentStatus.SUCCEEDED,
            fiat = FiatAmount("0.75", "USD")),
        Payment("synthetic-ecash", PaymentDirection.INCOMING, PaymentType.ECASH, 21_000L,
            timestampMillis = 1_787_950_000_000L, status = PaymentStatus.SUCCEEDED),
        Payment("synthetic-failed", PaymentDirection.OUTGOING, PaymentType.LIGHTNING, 4_400L,
            timestampMillis = 1_787_900_000_000L, status = PaymentStatus.FAILED),
        Payment("synthetic-old-1", PaymentDirection.OUTGOING, PaymentType.LIGHTNING, 900L, feeSat = 2L,
            timestampMillis = 1_787_800_000_000L, status = PaymentStatus.SUCCEEDED),
        Payment("synthetic-old-2", PaymentDirection.INCOMING, PaymentType.ONCHAIN, 105_000L,
            timestampMillis = 1_787_700_000_000L, status = PaymentStatus.SUCCEEDED),
    ),
    nextCursor = null,
    initialized = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixtureList(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pyx Wallet") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

@Composable
private fun SafetyCard(title: String, detail: String, safety: String) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail)
        Text(safety, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
