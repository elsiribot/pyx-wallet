package cash.pyx.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.*
import cash.pyx.app.ui.HomeRefreshStatus
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
        operation = cash.pyx.app.ui.WalletOperation.Success(
            "LNURL receive",
            "lnurl1dp68gurn8ghj7urjdajzuun9vd6hyunfdenkgtnkv95nze3wdaexwtmvdemrztmsv9ukxmmyv4ej7wf58pjrse34xg6rwc3e8ycx2d34xcckywpcvsmkyenzvvck2dnzxccrvc3k893kzwpkv93nycmrvd3rsdf3xesnyv3nxscrwefsvdjq8xmtrv",
        ),
    )
    "home_tx_sheet" -> PyxApp(
        state = syntheticHome(),
        fiatBalance = FiatDisplay("25.00", "USD", "US Dollar", "\$", 2),
        activityState = syntheticActivity().let { activity ->
            activity.copy(detail = cash.pyx.app.data.ActivityDetailState.Open(activity.payments[1]))
        },
    )
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
