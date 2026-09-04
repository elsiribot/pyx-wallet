package cash.pyx.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.ActivityDetailState
import cash.pyx.app.data.ActivityState
import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.ui.theme.PyxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun NavHostController.open(destination: WalletRoute) {
    if (currentDestination == null) return
    navigate(destination.route) { launchSingleTop = true }
}

private fun NavHostController.returnHome() {
    // During Activity recreation ON_STOP can arrive after Compose has detached
    // the old NavHost graph. State/secret cleanup must still run, but navigating
    // that disposed controller would crash the process.
    if (currentDestination == null) return
    navigate(WalletRoute.HOME.route) {
        popUpTo(WalletRoute.HOME.route) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavHostController.goBack() {
    if (currentDestination == null) return
    if (!popBackStack()) returnHome()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyxApp(
    state: BootstrapState = BootstrapState.Unavailable("Wallet services are not available in this preview."),
    onRetry: () -> Unit = {},
    onCreate: (Long) -> Unit = {},
    onRestore: (Long, String) -> Unit = { _, _ -> },
    onAcknowledgeSeed: (Long) -> Unit = {},
    operation: WalletOperation = WalletOperation.Idle,
    onOperation: (String, Long, String, Long) -> Unit = { _, _, _, _ -> },
    onClearOperation: () -> Unit = {},
    onClearInvite: () -> Unit = {},
    onJoin: (Long, String, Boolean) -> Unit = { _, _, _ -> },
    biometricAvailable: Boolean = false,
    biometricEnabled: Boolean = false,
    onBiometricToggle: (Boolean) -> Unit = {},
    onBackup: (Long) -> Unit = {},
    onClearSendPayload: () -> Unit = {},
    contactsState: cash.pyx.app.data.ContactsFeatureState = cash.pyx.app.data.ContactsFeatureState(),
    onClearContactsMessage: () -> Unit = {},
    connection: cash.pyx.app.nativeapi.GuardianConnectionSnapshot? = null,
    federationState: cash.pyx.app.data.FederationState = cash.pyx.app.data.FederationState.Idle,
    refreshStatus: HomeRefreshStatus? = null,
    onRefreshHome: () -> Unit = {},
    currencySettingsState: cash.pyx.app.data.CurrencySettingsState = cash.pyx.app.data.CurrencySettingsState(),
    onClearCurrencyMessage: () -> Unit = {},
    addresses: List<cash.pyx.app.nativeapi.OnchainAddress> = emptyList(),
    onLifecycleResumed: (Boolean) -> Unit = {},
    classifiedInput: cash.pyx.app.nativeapi.ClassifiedInput? = null,
    onClassify: (String) -> Unit = {},
    onConsumeClassified: () -> Unit = {},
    fiatBalance: cash.pyx.app.nativeapi.FiatDisplay? = null,
    recovery: cash.pyx.app.nativeapi.RecoveryEvent? = null,
    recoveryExpiry: cash.pyx.app.nativeapi.RecoveryExpirySnapshot? = null,
    paymentNotice: PaymentNotice? = null,
    onPaymentNoticeShown: () -> Unit = {},
    seedValidation: cash.pyx.app.nativeapi.SeedPhraseValidation? = null,
    seedSuggestions: List<String> = emptyList(),
    onValidateSeed: (List<String>) -> Unit = {},
    onSuggestSeed: (String) -> Unit = {},
    onClearSeedAssistance: () -> Unit = {},
    ecashFrame: String? = null,
    ecashDecodeProgress: Int = 0,
    onStartEcashDisplay: (String, Boolean) -> Unit = { _, _ -> },
    onStopEcashDisplay: () -> Unit = {},
    onEcashFrame: (String) -> Unit = {},
    onStopEcashDecoder: () -> Unit = {},
    pendingIrreversibleOperation: cash.pyx.app.security.PendingIrreversibleOperation? = null,
    onRefreshOperationReconciliation: () -> Unit = {},
    activityState: ActivityState = ActivityState(),
    onLoadActivityPage: (Long, Boolean) -> Unit = { _, _ -> },
    onOpenActivityDetail: (Long, String) -> Unit = { _, _ -> },
    onDismissActivityDetail: () -> Unit = {},
) {
    val navController = rememberNavController()
    var routedInput by remember { mutableStateOf<IncomingRequest?>(null) }
    var balanceMasked by remember { mutableStateOf(false) }
    var sensitiveUiEpoch by remember { mutableIntStateOf(0) }
    val currentWalletRoute by navController.currentBackStackEntryAsState()
    var rootResetPending by remember(navController) { mutableStateOf(true) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onLifecycleResumed(true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                sensitiveUiEpoch++
                onLifecycleResumed(false)
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                onLifecycleResumed(false)
                navController.returnHome()
                onClearInvite(); onClearSendPayload(); onClearOperation(); onDismissActivityDetail(); onStopEcashDecoder()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onLifecycleResumed(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        onDispose { onLifecycleResumed(false); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.activity.compose.BackHandler(enabled = currentWalletRoute?.destination?.route != null && currentWalletRoute?.destination?.route != WalletRoute.HOME.route) {
        navController.goBack(); routedInput = null; onClearInvite(); onClearSendPayload(); onClearOperation();
        onDismissActivityDetail(); onStopEcashDecoder()
    }
    // The previous screen state was intentionally not saveable. Keep that
    // security/lifecycle contract when NavController restores its own state:
    // every new PyxApp composition starts at the non-sensitive wallet root.
    LaunchedEffect(currentWalletRoute?.destination?.route, state is BootstrapState.Home) {
        if (state is BootstrapState.Home && rootResetPending && navController.currentDestination != null) {
            navController.returnHome()
            rootResetPending = false
        }
    }
    LaunchedEffect(classifiedInput, state) {
        val input = classifiedInput ?: return@LaunchedEffect
        if (state is BootstrapState.Home) {
            routedInput = IncomingRequest.from(input)
            navController.open(WalletRoute.forInput(input.type))
            onConsumeClassified()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(paymentNotice?.identity) {
        paymentNotice?.let { snackbarHostState.showSnackbar(it.message, duration = SnackbarDuration.Short); onPaymentNoticeShown() }
    }
    Scaffold(
        modifier = Modifier.testTag("pyx_app"),
        topBar = { TopAppBar(title = { Text("Pyx Wallet") }) },
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            when (state) {
                BootstrapState.Loading, BootstrapState.Creating, BootstrapState.Restoring,
                BootstrapState.LoadingWallet -> LoadingContent(
                    when (state) {
                        BootstrapState.Creating -> "Creating wallet…"
                        BootstrapState.Restoring -> "Restoring wallet…"
                        BootstrapState.LoadingWallet -> "Loading wallet…"
                        else -> "Starting wallet…"
                    },
                )
                is BootstrapState.Onboarding -> key(sensitiveUiEpoch) {
                    OnboardingContent(state, onCreate, onRestore, seedValidation, seedSuggestions,
                        onValidateSeed, onSuggestSeed, onClearSeedAssistance)
                }
                is BootstrapState.SeedConfirmation -> key(sensitiveUiEpoch) {
                    SeedConfirmationContent(state, onAcknowledgeSeed)
                }
                is BootstrapState.Home -> NavHost(navController, startDestination = WalletRoute.HOME.route) {
                    composable(WalletRoute.HOME.route) {
                        HomeContent(state, connection, refreshStatus, fiatBalance, recovery, recoveryExpiry, balanceMasked, { balanceMasked = it },
                            { navController.open(WalletRoute.RECEIVE) }, { navController.open(WalletRoute.SEND) },
                            { navController.open(WalletRoute.SCAN) }, onRefreshHome, pendingIrreversibleOperation,
                            { client, operationId -> navController.open(WalletRoute.ACTIVITY); onOpenActivityDetail(client, operationId) },
                            navController::open)
                    }
                    composable(WalletRoute.RECEIVE.route) {
                        TransferContent(true, state, operation, routedInput?.payload, routedInput?.type,
                            { routedInput = null; navController.returnHome() }, onOperation, onClearOperation, ecashFrame, onStartEcashDisplay, onStopEcashDisplay)
                    }
                    composable(WalletRoute.SEND.route) {
                        TransferContent(false, state, operation, routedInput?.payload, routedInput?.type,
                            { routedInput = null; navController.returnHome() }, onOperation, onClearOperation, ecashFrame, onStartEcashDisplay, onStopEcashDisplay)
                    }
                    composable(WalletRoute.SCAN.route) {
                        QrScanner(onResult = onClassify, onBack = { onStopEcashDecoder(); navController.returnHome() }, frameHandler = { frame ->
                            if (frame.startsWith("fedimint1")) { onEcashFrame(frame); ScanFrameDecision.CONTINUE }
                            else { onClassify(frame); ScanFrameDecision.HANDLING }
                        }, progressFrames = ecashDecodeProgress)
                    }
                    listOf(WalletRoute.ACTIVITY, WalletRoute.WALLETS, WalletRoute.DETAILS, WalletRoute.GUARDIANS,
                        WalletRoute.SETTINGS, WalletRoute.CURRENCY, WalletRoute.CONTACTS,
                        WalletRoute.ADDRESSES, WalletRoute.ACCESS, WalletRoute.SEED_BACKUP).forEach { route ->
                        composable(route.route) {
                            ManageContent(route, state, operation, navController::goBack,
                                { navController.open(WalletRoute.JOIN) }, { navController.open(WalletRoute.CONTACTS) }, { navController.open(WalletRoute.ADDRESSES) },
                                { navController.open(WalletRoute.ACCESS) }, { navController.open(WalletRoute.SEED_BACKUP) },
                                { navController.open(WalletRoute.CURRENCY) }, { navController.open(WalletRoute.GUARDIANS) },
                                onOperation, onClearOperation, biometricAvailable, biometricEnabled, onBiometricToggle, onBackup,
                                contactsState, onClearContactsMessage, connection, federationState, recovery, recoveryExpiry,
                                currencySettingsState, onClearCurrencyMessage, addresses, onClassify, pendingIrreversibleOperation,
                                onRefreshOperationReconciliation, activityState, onLoadActivityPage,
                                onOpenActivityDetail, onDismissActivityDetail)
                        }
                    }
                    listOf(WalletRoute.JOIN, WalletRoute.RECOVER).forEach { route ->
                        composable(route.route) {
                            JoinContent(state.factoryHandle, routedInput?.payload, route == WalletRoute.RECOVER, operation,
                                { routedInput = null; navController.returnHome(); onClearInvite() }, onJoin, { navController.open(WalletRoute.SCAN) })
                        }
                    }
                }
                is BootstrapState.Error -> MessageContent("Wallet unavailable", state.message, onRetry, state.retryable)
                is BootstrapState.Unavailable -> MessageContent("Native wallet preview", state.message, onRetry, true)
            }
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun OnboardingContent(
    state: BootstrapState.Onboarding,
    onCreate: (Long) -> Unit,
    onRestore: (Long, String) -> Unit,
    validation: cash.pyx.app.nativeapi.SeedPhraseValidation?,
    suggestions: List<String>,
    onValidate: (List<String>) -> Unit,
    onSuggest: (String) -> Unit,
    onClearAssistance: () -> Unit,
) {
    var restoring by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf(List(12) { "" }) }
    var currentIndex by remember { mutableIntStateOf(0) }
    if (restoring && words.any(String::isNotBlank)) SecureScreen()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, restoring) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && restoring) {
                words = SeedRestorePresentation.cleared(); currentIndex = 0; onClearAssistance()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(words, currentIndex, restoring) {
        if (!restoring) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        onSuggest(words[currentIndex])
        onValidate(words)
    }
    Column(
        Modifier.fillMaxSize().testTag("bootstrap_content").verticalScroll(rememberScrollState()),
        verticalArrangement = if (restoring) Arrangement.Top else Arrangement.Center,
    ) {
        Text("Welcome to Pyx", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text("Create a wallet or restore an existing one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (restoring) {
            words.forEachIndexed { index, word -> OutlinedTextField(
                value = word,
                onValueChange = { value ->
                    currentIndex = index
                    words = SeedRestorePresentation.edit(words, index, value)
                },
                modifier = Modifier.fillMaxWidth().testTag("restore_word_${index + 1}"),
                label = { Text("Recovery word ${index + 1}") }, singleLine = true,
                isError = validation?.invalidIndices?.contains(index) == true,
                supportingText = { if (validation?.invalidIndices?.contains(index) == true) Text("Not a valid BIP39 word") },
            ) }
            if (suggestions.isNotEmpty()) {
                Text("Suggestions for word ${currentIndex + 1}", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) { suggestions.forEach { suggestion ->
                    AssistChip(onClick = { words = SeedRestorePresentation.selectSuggestion(words, currentIndex, suggestion) }, label = { Text(suggestion) })
                } }
            }
            if (SeedRestorePresentation.checksumError(validation)) {
                Text("Recovery phrase checksum is invalid", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onRestore(state.session.databaseHandle, words.joinToString(" ")) },
                enabled = validation?.valid == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Restore wallet") }
            TextButton(onClick = { words = SeedRestorePresentation.cleared(); restoring = false; onClearAssistance() }) { Text("Back") }
        } else {
            Button(
                onClick = { onCreate(state.session.databaseHandle) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Create wallet") }
            OutlinedButton(onClick = { restoring = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Restore wallet")
            }
        }
    }
}

@Composable
private fun SeedConfirmationContent(
    state: BootstrapState.SeedConfirmation,
    onAcknowledge: (Long) -> Unit,
) {
    SecureScreen()
    var reviewComplete by remember { mutableStateOf(false) }
    var answers by remember { mutableStateOf(List(cash.pyx.app.security.SeedVerification.positions.size) { "" }) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                reviewComplete = false
                answers = List(cash.pyx.app.security.SeedVerification.positions.size) { "" }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val verified = cash.pyx.app.security.SeedVerification.verify(state.words, answers)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp).testTag("seed_confirmation")) {
        Text("Save your recovery words", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text("Write these down in order and keep them private.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (!reviewComplete) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    state.words.mapIndexed { index, word -> "${index + 1}. $word" }.joinToString("   "),
                    modifier = Modifier.padding(20.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(onClick = { reviewComplete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("I've written down all 12 words")
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Verify your backup", style = MaterialTheme.typography.titleMedium)
            Text("Enter these words from your written copy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            cash.pyx.app.security.SeedVerification.positions.forEachIndexed { answerIndex, wordIndex ->
                OutlinedTextField(
                    value = answers[answerIndex],
                    onValueChange = { value -> answers = answers.toMutableList().also { it[answerIndex] = value.take(32) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Word ${wordIndex + 1}") },
                    singleLine = true,
                )
            }
            TextButton(onClick = { reviewComplete = false; answers = List(answers.size) { "" } }) { Text("Show words again") }
            Button(
                onClick = { onAcknowledge(state.factoryHandle) },
                enabled = verified,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue to wallet") }
        }
    }
}

/** Prevent screenshots, screen recording, and recents thumbnails while seed words are visible. */
@Composable
private fun SecureScreen() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    state: BootstrapState.Home,
    connection: cash.pyx.app.nativeapi.GuardianConnectionSnapshot?,
    refreshStatus: HomeRefreshStatus?,
    fiatBalance: cash.pyx.app.nativeapi.FiatDisplay?,
    recovery: cash.pyx.app.nativeapi.RecoveryEvent?,
    recoveryExpiry: cash.pyx.app.nativeapi.RecoveryExpirySnapshot?,
    balanceMasked: Boolean,
    onBalanceMasked: (Boolean) -> Unit,
    receive: () -> Unit,
    send: () -> Unit,
    scan: () -> Unit,
    refresh: () -> Unit,
    pendingIrreversibleOperation: cash.pyx.app.security.PendingIrreversibleOperation?,
    paymentDetails: (Long, String) -> Unit,
    navigate: (WalletRoute) -> Unit,
) {
    val wallet = state.snapshot.selected
    val listState = rememberLazyListState()
    var collapsed by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            collapsed = index > 0 || offset > 48
        }
    }
    val balanceText = HomePresentation.balanceText(wallet?.balanceSat ?: 0, balanceMasked)
    val balanceSemantics = if (balanceMasked) "Balance hidden" else "Balance $balanceText"
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("wallet_home"),
            state = listState,
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item(key = "expanded_balance_header") {
            Column(
                Modifier.fillMaxWidth().testTag("balance_header_expanded").semantics {
                    stateDescription = "Expanded balance header"
                    contentDescription = balanceSemantics
                },
            ) {
                Text(wallet?.name ?: "No federation selected", style = MaterialTheme.typography.titleLarge)
                Text(balanceText, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                HomePresentation.fiatText(fiatBalance?.amountDecimal, fiatBalance?.currencyCode.orEmpty(), balanceMasked)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Hide balance", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = balanceMasked,
                        onCheckedChange = onBalanceMasked,
                        modifier = Modifier.semantics {
                            contentDescription = "Balance privacy"
                            stateDescription = if (balanceMasked) "Balance hidden" else "Balance visible"
                        },
                    )
                }
            }
        }
        if (recoveryExpiry?.hasPendingRecoveries == true || recovery != null) item {
            val complete = recovery?.aggregateComplete ?: 0
            val total = recovery?.aggregateTotal ?: 0
            Text("Recovery in progress · $complete/$total", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        recoveryExpiry?.expiresAtEpochSeconds?.let { expiry -> item {
            Text("Federation expires at $expiry", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(HomePresentation.connectionTitle(connection)) },
                    supportingContent = { Text(HomePresentation.connectionDetail(connection)) },
                )
            }
        }
        if (refreshStatus is HomeRefreshStatus.Degraded || refreshStatus is HomeRefreshStatus.Offline) item {
            val offline = refreshStatus is HomeRefreshStatus.Offline
            ListItem(
                headlineContent = { Text(if (offline) "Wallet offline" else "Wallet data may be stale") },
                supportingContent = {
                    val last = refreshStatus.lastSuccessEpochMillis
                    Text(if (last == null) "No successful refresh yet" else "Last refreshed ${android.text.format.DateUtils.getRelativeTimeSpanString(last)}")
                },
                trailingContent = { TextButton(onClick = refresh) { Text("Retry") } },
            )
        }
        if (pendingIrreversibleOperation != null) item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Payment reconciliation required") },
                    supportingContent = { Text(reconciliationMessage(pendingIrreversibleOperation)) },
                    trailingContent = { TextButton(onClick = { navigate(WalletRoute.ACTIVITY) }) { Text("Review") } },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
        if (wallet == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { navigate(WalletRoute.JOIN) }, modifier = Modifier.fillMaxWidth()) { Text("Join federation") }
                OutlinedButton(onClick = { navigate(WalletRoute.RECOVER) }, modifier = Modifier.fillMaxWidth()) { Text("Recover federation") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { navigate(WalletRoute.ACTIVITY) }, modifier = Modifier.weight(1f)) { Text("Activity") }
                    TextButton(onClick = { navigate(WalletRoute.WALLETS) }, modifier = Modifier.weight(1f)) { Text("Wallets") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { navigate(WalletRoute.DETAILS) }, enabled = wallet != null, modifier = Modifier.weight(1f)) { Text("Details") }
                    TextButton(onClick = { navigate(WalletRoute.SETTINGS) }, modifier = Modifier.weight(1f)) { Text("Settings") }
                }
                TextButton(onClick = { navigate(WalletRoute.CONTACTS) }, modifier = Modifier.fillMaxWidth()) { Text("Contacts") }
            }
        }
        item { OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh wallet") } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = receive, enabled = wallet != null, modifier = Modifier.weight(1f)) { Text("Receive") }
                OutlinedButton(onClick = send, enabled = wallet != null && pendingIrreversibleOperation == null, modifier = Modifier.weight(1f)) { Text("Send") }
            }
            TextButton(onClick = scan, enabled = wallet != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scan") }
        }
        item { Text("Recent payments", style = MaterialTheme.typography.titleMedium) }
        if (wallet == null || wallet.payments.isEmpty()) {
            item { Text("No recent payments", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(wallet.payments, key = ActivityPresentation::itemKey) { payment ->
                PaymentRow(payment) { paymentDetails(wallet.clientHandle, payment.operationId) }
            }
        }
        }
        AnimatedVisibility(visible = collapsed, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .testTag("balance_header_collapsed")
                        .semantics {
                            stateDescription = "Collapsed balance header"
                            contentDescription = balanceSemantics
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(wallet?.name ?: "No federation selected", style = MaterialTheme.typography.titleMedium)
                    Text(balanceText, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ManageContent(
    screen: WalletRoute,
    state: BootstrapState.Home,
    operation: WalletOperation,
    back: () -> Unit,
    openJoin: () -> Unit,
    openContacts: () -> Unit,
    openAddresses: () -> Unit,
    openAccess: () -> Unit,
    openSeedBackup: () -> Unit,
    openCurrency: () -> Unit,
    openGuardians: () -> Unit,
    submit: (String, Long, String, Long) -> Unit,
    clear: () -> Unit,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricToggle: (Boolean) -> Unit,
    onBackup: (Long) -> Unit,
    contactsState: cash.pyx.app.data.ContactsFeatureState,
    clearContactsMessage: () -> Unit,
    connection: cash.pyx.app.nativeapi.GuardianConnectionSnapshot?,
    federationState: cash.pyx.app.data.FederationState,
    recovery: cash.pyx.app.nativeapi.RecoveryEvent?,
    recoveryExpiry: cash.pyx.app.nativeapi.RecoveryExpirySnapshot?,
    currencySettingsState: cash.pyx.app.data.CurrencySettingsState,
    clearCurrencyMessage: () -> Unit,
    addresses: List<cash.pyx.app.nativeapi.OnchainAddress>,
    sendContact: (String) -> Unit,
    pendingIrreversibleOperation: cash.pyx.app.security.PendingIrreversibleOperation?,
    refreshOperationReconciliation: () -> Unit,
    activityState: ActivityState,
    loadActivityPage: (Long, Boolean) -> Unit,
    openActivityDetail: (Long, String) -> Unit,
    dismissActivityDetail: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var confirmationRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    val addressMutationGate = remember { ConfirmationActionGate() }
    val selected = state.snapshot.selected
    val operationModalRoute = if (operation is WalletOperation.SuccessorInvite) WalletModalRoute.SUCCESSOR_REVIEW else null
    LaunchedEffect(screen, selected?.clientHandle) {
        if (screen == WalletRoute.ACTIVITY) selected?.let { loadActivityPage(it.clientHandle, false) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        TextButton(onClick = { clear(); back() }) { Text("Back") }
        Text(screen.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        when (screen) {
            WalletRoute.ACTIVITY -> {
                if (pendingIrreversibleOperation != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Check for a previous payment", style = MaterialTheme.typography.titleMedium)
                            Text(reconciliationMessage(pendingIrreversibleOperation))
                            Button(onClick = refreshOperationReconciliation) { Text("Check status") }
                        }
                    }
                }
                TextButton(onClick = { selected?.let { loadActivityPage(it.clientHandle, true) } }, enabled = !activityState.loading) { Text("Refresh activity") }
                when {
                    activityState.loading && !activityState.initialized -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Text("Loading activity…", Modifier.padding(start = 12.dp))
                    }
                    activityState.error != null && !activityState.initialized -> Text(activityState.error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    activityState.initialized && activityState.payments.isEmpty() -> Text("No payment activity yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> ActivityPresentation.group(activityState.payments).forEach { day ->
                        Text(day.date.toString(), style = MaterialTheme.typography.titleMedium)
                        day.payments.forEach { payment -> key(ActivityPresentation.itemKey(payment)) {
                            PaymentRow(payment) { selected?.let { openActivityDetail(it.clientHandle, payment.operationId) } }
                        } }
                    }
                }
                activityState.error?.takeIf { activityState.initialized }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                when (val detail = activityState.detail) {
                    is ActivityDetailState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    is ActivityDetailState.Error -> Text(detail.message, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    else -> Unit
                }
                if (activityState.initialized && activityState.nextCursor != null) {
                    OutlinedButton(onClick = { selected?.let { loadActivityPage(it.clientHandle, false) } }, enabled = !activityState.loading) {
                        if (activityState.loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Load more")
                    }
                }
            }
            WalletRoute.WALLETS -> state.snapshot.federations.forEach { federation ->
                ListItem(
                    headlineContent = { Text(federation.name) },
                    supportingContent = { Text("${federation.guardianCount} guardians") },
                    trailingContent = { TextButton(onClick = {
                        submit("switch", state.factoryHandle, federation.id, 0)
                    }) { Text(if (federation.id == selected?.federationId) "Selected" else "Switch") } },
                )
            }.also { TextButton(onClick = openJoin) { Text("Add federation") } }
            WalletRoute.DETAILS -> {
                Button(onClick = { selected?.let { submit("details_connection", it.clientHandle, "", 0) } }) { Text("Load federation details") }
                when (federationState) {
                    is cash.pyx.app.data.FederationState.Selecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is cash.pyx.app.data.FederationState.Failed -> Text(
                        federationState.error.userMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    is cash.pyx.app.data.FederationState.Ready -> {
                        federationState.details?.let { details ->
                            Text(details.name, style = MaterialTheme.typography.titleMedium)
                            Text("${details.id} · ${details.currencyCode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            details.stats?.let { Text("Total ${it.totalValueSat} sats · ${it.blockCount} blocks") }
                        }
                        federationState.detailsError?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) }
                        federationState.connectionError?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) }
                    }
                    cash.pyx.app.data.FederationState.Idle -> Unit
                }
                ListItem(
                    headlineContent = { Text("Guardians") },
                    supportingContent = { Text(connection?.let { "${it.onlineCount} of ${it.totalCount} online" } ?: "Connection status unavailable") },
                    modifier = Modifier.clickable(enabled = selected != null, onClick = openGuardians),
                )
                OutlinedButton(onClick = { confirmationRoute = WalletModalRoute.LEAVE_FEDERATION }, enabled = selected != null) { Text("Leave federation") }
                TextButton(onClick = openAddresses, enabled = selected != null) { Text("On-chain address history") }
                recovery?.let {
                    Text("Recovery ${it.aggregateComplete}/${it.aggregateTotal}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { if (it.aggregateTotal == 0L) 0f else it.aggregateComplete.toFloat() / it.aggregateTotal })
                    Text("Module ${it.moduleId}: ${it.complete}/${it.total}")
                }
                recoveryExpiry?.expiresAtEpochSeconds?.let { Text("Expires at $it") }
                if (RecoveryPresentation.canReviewSuccessor(recoveryExpiry)) Button(onClick = {
                    submit("show_successor", selected?.clientHandle ?: 0, "", 0)
                }) { Text("Review successor federation") }
            }
            WalletRoute.GUARDIANS -> {
                connection?.let { status ->
                    Text("${status.state.name.lowercase().replaceFirstChar(Char::uppercase)} · ${status.onlineCount}/${status.totalCount} online")
                    Text("Quorum requires ${status.requiredCount} of ${status.totalCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    status.guardians.forEach { guardian ->
                        ListItem(
                            headlineContent = { Text(guardian.name) },
                            trailingContent = { Text(if (guardian.connected) "Online" else "Offline") },
                        )
                    }
                } ?: Text("Guardian connection status is unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WalletRoute.SETTINGS -> {
                ListItem(
                    headlineContent = { Text("Currency") },
                    supportingContent = { Text(state.snapshot.currencyCode) },
                    modifier = Modifier.clickable(onClick = openCurrency),
                )
                ListItem(headlineContent = { Text("Biometric protection") }, supportingContent = { Text("Configure access protection") },
                    modifier = Modifier.clickable(onClick = openAccess))
                OutlinedButton(onClick = openSeedBackup) { Text("Recovery words") }
                TextButton(onClick = openContacts) { Text("Manage contacts") }
                HorizontalDivider()
                Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text("Network: Bitcoin")
                Text("Pyx Wallet ${cash.pyx.app.BuildConfig.VERSION_NAME} (${cash.pyx.app.BuildConfig.VERSION_CODE})")
                Text("Build type: ${cash.pyx.app.BuildConfig.BUILD_TYPE}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                SettingsPresentation.links.forEach { link -> TextButton(onClick = { uriHandler.openUri(link.url) },
                    modifier = Modifier.minimumInteractiveComponentSize()) { Text(link.label) } }
            }
            WalletRoute.CURRENCY -> {
                LaunchedEffect(Unit) { submit("load_currencies", 0, "", 0) }
                OutlinedTextField(value = input, onValueChange = { input = it.take(32) }, label = { Text("Search currencies") })
                if (currencySettingsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                FeatureMessageContent(currencySettingsState.message, clearCurrencyMessage)
                currencySettingsState.currencies.filter { input.isBlank() || it.code.contains(input, true) || it.name.contains(input, true) }
                    .take(12).forEach { currency -> ListItem(
                        headlineContent = { Text("${currency.code} · ${currency.name}") },
                        supportingContent = { Text(currency.symbol) },
                        trailingContent = { if (currencySettingsState.savingCode == currency.code) CircularProgressIndicator(Modifier.size(20.dp)) },
                        modifier = Modifier.clickable(enabled = currencySettingsState.savingCode == null) {
                            submit("currency", state.factoryHandle, currency.code, 0)
                        },
                    ) }
            }
            WalletRoute.ACCESS -> {
                Text(SettingsPresentation.biometricMessage(biometricAvailable, biometricEnabled), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(headlineContent = { Text("Biometric protection") },
                    supportingContent = { Text("Require a strong biometric before protected actions") },
                    trailingContent = { Switch(checked = biometricEnabled, onCheckedChange = onBiometricToggle,
                        enabled = biometricAvailable || biometricEnabled) })
            }
            WalletRoute.SEED_BACKUP -> {
                Text("Your recovery words restore this wallet. Keep them private and offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onBackup(state.factoryHandle) }, enabled = operation !is WalletOperation.Submitting) { Text("Show recovery words") }
            }
            WalletRoute.CONTACTS -> {
                val contacts = contactsState.contacts
                var name by remember { mutableStateOf("") }
                var lnurl by remember { mutableStateOf("") }
                var query by remember { mutableStateOf("") }
                var editing by remember { mutableStateOf<cash.pyx.app.nativeapi.Contact?>(null) }
                var delete by remember { mutableStateOf<cash.pyx.app.nativeapi.Contact?>(null) }
                var contactModalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
                var showValidation by remember { mutableStateOf(false) }
                LaunchedEffect(state.factoryHandle) { submit("load_contacts", state.factoryHandle, "", 0) }
                if (contactsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                FeatureMessageContent(contactsState.message, clearContactsMessage)
                val validation = ContactsPresentation.validate(name, lnurl, contacts, editing?.lnurl)
                OutlinedTextField(query, { query = it.take(256) }, label = { Text("Search contacts") }, singleLine = true)
                Text(if (editing == null) "Add contact" else "Edit contact", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it.take(128); showValidation = true }, label = { Text("Name") },
                    isError = showValidation && validation.nameError != null,
                    supportingText = { if (showValidation) validation.nameError?.let { Text(it) } }, singleLine = true)
                OutlinedTextField(lnurl, { lnurl = it.take(16 * 1024); showValidation = true }, label = { Text("Lightning address or LNURL") },
                    isError = showValidation && validation.paymentError != null,
                    supportingText = { if (editing != null) Text("Payment address cannot be changed while editing")
                        else if (showValidation) validation.paymentError?.let { Text(it) } },
                    enabled = editing == null, singleLine = true)
                Row {
                    Button(enabled = contactsState.mutation == null, onClick = {
                        showValidation = true
                        if (validation.valid) {
                            submit("save_contact", state.factoryHandle, "${name.trim()}\u0000${lnurl.trim()}", 0)
                            editing = null; name = ""; lnurl = ""; showValidation = false
                        }
                    }) { Text(if (editing == null) "Add contact" else "Save changes") }
                    if (editing != null) TextButton(onClick = { editing = null; name = ""; lnurl = ""; showValidation = false }) { Text("Cancel") }
                }
                val visibleContacts = ContactsPresentation.filter(contacts, query)
                if (contacts.isEmpty()) Text("No contacts yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (visibleContacts.isEmpty()) Text("No contacts match your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
                visibleContacts.forEach { contact -> ListItem(
                    headlineContent = { Text(contact.name) }, supportingContent = { Text(contact.lnurl, maxLines = 1) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = { Row {
                        TextButton(onClick = { sendContact(contact.lnurl) }) { Text("Pay") }
                        TextButton(enabled = contactsState.mutation == null, onClick = { editing = contact; name = contact.name; lnurl = contact.lnurl; showValidation = false }) { Text("Edit") }
                        TextButton(enabled = contactsState.mutation == null, onClick = { delete = contact; contactModalRoute = WalletModalRoute.CONTACT_DELETE }) { Text("Delete") }
                    } },
                ) }
                if (contactModalRoute == WalletModalRoute.CONTACT_DELETE && delete != null) AlertDialog(onDismissRequest = { delete = null; contactModalRoute = null }, title = { Text("Delete contact?") },
                    text = { Text("Remove ${delete!!.name}? This does not send or move any funds.") },
                    confirmButton = { Button(onClick = { submit("delete_contact", state.factoryHandle, delete!!.lnurl, 0); delete = null; contactModalRoute = null }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { delete = null; contactModalRoute = null }) { Text("Cancel") } })
            }
            WalletRoute.ADDRESSES -> {
                LaunchedEffect(selected?.clientHandle) { selected?.let { submit("load_addresses", it.clientHandle, "", 0) } }
                if (addresses.isEmpty()) Text("No on-chain addresses")
                addresses.forEach { address -> ListItem(
                    headlineContent = { Text(address.address, maxLines = 1) },
                    supportingContent = { Text("Index ${address.tweakIndex}") },
                    trailingContent = { TextButton(onClick = {
                        selected?.let { wallet ->
                            if (addressMutationGate.request { submit("recheck_address", wallet.clientHandle, "", address.tweakIndex) }) {
                                confirmationRoute = WalletModalRoute.ADDRESS_MUTATION
                            }
                        }
                    }) { Text("Recheck") } },
                ) }
                Button(onClick = {
                    selected?.let { wallet ->
                        if (addresses.isEmpty()) {
                            submit("receive_onchain", wallet.clientHandle, "", 0)
                        } else if (addressMutationGate.request { submit("receive_onchain", wallet.clientHandle, "", 0) }) {
                            confirmationRoute = WalletModalRoute.ADDRESS_MUTATION
                        }
                    }
                }, enabled = operation !is WalletOperation.Submitting) { Text(if (addresses.isEmpty()) "Generate address" else "Generate another address") }
                OutlinedButton(onClick = { selected?.let { submit("load_addresses", it.clientHandle, "", 0) } }) { Text("Refresh") }
            }
            else -> Unit // ManageContent is registered only for management destinations.
        }
        when {
            activityState.detail is ActivityDetailState.Open -> PaymentDetailDialog(
                (activityState.detail as ActivityDetailState.Open).payment,
                dismissActivityDetail,
            )
            screen == WalletRoute.ACTIVITY -> Unit
            else -> when (operation) {
            WalletOperation.Submitting -> CircularProgressIndicator()
            is WalletOperation.Success -> SensitiveResult(operation, operation.sensitive, clear)
            is WalletOperation.Failure -> Text(operation.message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            else -> Unit
            }
        }
    }
    if (confirmationRoute == WalletModalRoute.LEAVE_FEDERATION) AlertDialog(onDismissRequest = { confirmationRoute = null }, title = { Text("Leave federation?") },
        text = { Text("This removes the federation from this wallet.") },
        confirmButton = { Button(onClick = { confirmationRoute = null; selected?.let { submit("leave", state.factoryHandle, it.federationId, 0) } }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmationRoute = null }) { Text("Cancel") } })
    if (confirmationRoute == WalletModalRoute.ADDRESS_MUTATION) AlertDialog(
        onDismissRequest = { addressMutationGate.cancel(); confirmationRoute = null },
        title = { Text("Confirm on-chain address action") },
        text = { Text("This asks the federation to generate or recheck an on-chain address. Continue?") },
        confirmButton = { Button(onClick = { confirmationRoute = null; addressMutationGate.confirm() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { addressMutationGate.cancel(); confirmationRoute = null }) { Text("Cancel") } },
    )
    if (operationModalRoute == WalletModalRoute.SUCCESSOR_REVIEW && operation is WalletOperation.SuccessorInvite) AlertDialog(
        onDismissRequest = clear,
        title = { Text("Review successor federation?") },
        text = { Text("The successor invite will be checked before you can choose whether to join. Nothing is joined or copied automatically.") },
        confirmButton = { Button(onClick = { val payload = operation.payload; clear(); sendContact(payload) }) { Text("Review invite") } },
        dismissButton = { TextButton(onClick = clear) { Text("Cancel") } },
    )
}

@Composable
private fun FeatureMessageContent(message: cash.pyx.app.data.FeatureMessage?, clear: () -> Unit) {
    if (message == null) return
    val text = when (message) {
        is cash.pyx.app.data.FeatureMessage.Success -> "${message.title}: ${message.detail}"
        is cash.pyx.app.data.FeatureMessage.Failure -> message.text
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = if (message is cash.pyx.app.data.FeatureMessage.Failure) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = clear) { Text("Dismiss") }
    }
}

@Composable
private fun PaymentDetailDialog(payment: Payment, dismiss: () -> Unit) {
    val fields = ActivityPresentation.technicalFields(payment)
    val sensitive = fields.any { it.sensitive }
    if (sensitive) SecureScreen()
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Payment details") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${payment.type.name.lowercase().replaceFirstChar(Char::uppercase)} · ${payment.status.name.lowercase()}")
            Text(ActivityPresentation.amount(payment), style = MaterialTheme.typography.titleLarge)
            ActivityPresentation.historicalFiat(payment)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            payment.feeSat?.let { Text("Fee: $it sats") }
            Text("Direction: ${payment.direction.name.lowercase()}")
            Text("Date: ${ActivityPresentation.timestamp(payment)}")
            Text("Operation ID: ${payment.operationId}")
            fields.forEach { field -> Column(Modifier.padding(top = 12.dp)) {
                Text(field.label, style = MaterialTheme.typography.labelMedium)
                if (field.copyable) {
                    SelectionContainer { Text(field.value) }
                    ClipboardCopyButton(context, field.label, field.value, sensitive = false, buttonLabel = "Copy ${field.label}")
                } else Text(field.value)
            } }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

@Composable
private fun JoinContent(
    factoryHandle: Long,
    queuedInvite: String?,
    initialRecover: Boolean,
    operation: WalletOperation,
    back: () -> Unit,
    join: (Long, String, Boolean) -> Unit,
    scan: () -> Unit,
) {
    var invite by remember(queuedInvite) { mutableStateOf(queuedInvite.orEmpty()) }
    var recover by remember { mutableStateOf(initialRecover) }
    var modalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        TextButton(onClick = { invite = ""; back() }) { Text("Back") }
        Text(if (recover) "Recover federation" else "Join federation", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = invite,
            onValueChange = { if (it.length <= 16 * 1024) invite = it },
            modifier = Modifier.fillMaxWidth().testTag("federation_invite"),
            label = { Text("Federation invite") },
            minLines = 3,
        )
        TextButton(onClick = scan) { Text("Scan invite QR") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = recover, onCheckedChange = { recover = it })
            Text(if (recover) "Recover an existing wallet" else "Join as a new wallet", modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = { modalRoute = WalletModalRoute.JOIN_CONFIRMATION }, enabled = invite.isNotBlank() && operation !is WalletOperation.Submitting,
            modifier = Modifier.fillMaxWidth()) { Text(if (operation is WalletOperation.Submitting) "Working…" else "Continue") }
        if (operation is WalletOperation.Failure) Text(operation.message, color = MaterialTheme.colorScheme.error)
    }
    if (modalRoute == WalletModalRoute.JOIN_CONFIRMATION) AlertDialog(onDismissRequest = { modalRoute = null }, title = { Text(if (recover) "Confirm recovery" else "Confirm join") },
        text = { Text("Only continue if you trust the federation invite source.") },
        // Preserve the invite after retryable/offline failures so retry does not require
        // rescanning or re-entering a potentially long value.
        confirmButton = { Button(onClick = { modalRoute = null; join(factoryHandle, invite, recover) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { modalRoute = null }) { Text("Cancel") } })
}

@Composable
private fun SensitiveResult(result: WalletOperation.Success, secure: Boolean, dismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var modalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    var copyAnnouncement by remember { mutableStateOf<String?>(null) }
    DisposableEffect(secure) {
        val window = context.findActivity()?.window
        if (secure) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) { Column(Modifier.padding(16.dp)) {
        Text(result.title, style = MaterialTheme.typography.titleMedium)
        if (secure) Text(result.detail) else SelectionContainer { Text(result.detail) }
        if (!secure) Row {
            ClipboardCopyButton(context, result.title, result.detail, sensitive = false, buttonLabel = "Copy")
            if (result.shareable && PlatformUtilityPolicy.canShare(result.sensitive)) TextButton(onClick = {
                context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(result.detail, sensitive = false), "Share"))
            }) { Text("Share") }
        }
        if (secure && result.title == "Recovery words") TextButton(onClick = {
            modalRoute = WalletModalRoute.SEED_COPY_WARNING
        }) { Text("Copy recovery words") }
        copyAnnouncement?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (secure) Button(onClick = dismiss) { Text("I saved these securely") }
    } }
    if (modalRoute == WalletModalRoute.SEED_COPY_WARNING) AlertDialog(
        onDismissRequest = { modalRoute = null },
        title = { Text("Copy recovery words?") },
        text = { Text("Any app allowed to read the clipboard could access your recovery words. Pyx will clear this copy after one minute if it has not been replaced.") },
        confirmButton = { Button(onClick = {
            modalRoute = null
            copyAnnouncement = QrPayload.copy(context, "Pyx recovery words", result.detail, sensitive = true).announcement
        }) { Text("Copy for one minute") } },
        dismissButton = { TextButton(onClick = { modalRoute = null }) { Text("Cancel") } },
    )
}

@Composable
private fun ClipboardCopyButton(
    context: Context,
    label: String,
    value: String,
    sensitive: Boolean,
    buttonLabel: String,
) {
    var announcement by remember(value) { mutableStateOf<String?>(null) }
    Column {
        TextButton(onClick = { announcement = QrPayload.copy(context, label, value, sensitive).announcement }) {
            Text(buttonLabel)
        }
        announcement?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class TransferAmountUnit { SATS, BTC, FIAT }

@Composable
private fun TransferContent(
    receive: Boolean,
    state: BootstrapState.Home,
    operation: WalletOperation,
    initialPayload: String?,
    initialType: cash.pyx.app.nativeapi.InputType?,
    back: () -> Unit,
    submit: (String, Long, String, Long) -> Unit,
    clear: () -> Unit,
    ecashFrame: String?,
    startEcashDisplay: (String, Boolean) -> Unit,
    stopEcashDisplay: () -> Unit,
) {
    val client = state.snapshot.selected?.clientHandle ?: return
    var tab by remember(initialPayload, initialType) {
        mutableIntStateOf(when (initialType) {
            cash.pyx.app.nativeapi.InputType.BITCOIN -> 1
            cash.pyx.app.nativeapi.InputType.ECASH -> 2
            else -> 0
        })
    }
    var text by remember(initialPayload) { mutableStateOf(initialPayload.orEmpty()) }
    var amount by remember { mutableStateOf("") }
    var amountUnit by remember { mutableStateOf(TransferAmountUnit.SATS) }
    var modalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    val anotherAddressGate = remember { ConfirmationActionGate() }
    val labels = listOf("Lightning", "On-chain", "Ecash")
    LaunchedEffect(initialPayload, initialType) {
        clear()
        if (initialType == cash.pyx.app.nativeapi.InputType.BITCOIN && !initialPayload.isNullOrBlank()) submit("parse_bitcoin", client, initialPayload, 0)
    }
    LaunchedEffect(operation) {
        if (operation is WalletOperation.BitcoinParsed) {
            tab = 1; text = operation.payment.address
            amount = operation.payment.amountSat?.toString().orEmpty(); amountUnit = TransferAmountUnit.SATS
        } else if (operation is WalletOperation.LnurlPrepared && operation.fixedAmount) {
            amount = operation.minSat.toString()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        TextButton(onClick = back) { Text("Back") }
        Text(if (receive) "Receive" else "Send", style = MaterialTheme.typography.headlineMedium)
        TabRow(selectedTabIndex = tab) { labels.forEachIndexed { index, label ->
            Tab(selected = tab == index, onClick = { tab = index; text = ""; amount = ""; clear() }, text = { Text(label) })
        } }
        Spacer(Modifier.height(16.dp))
        val needsAmount = if (receive) tab == 0 else tab != 0
        val needsText = if (receive) tab == 2 else tab != 2
        if (needsText) OutlinedTextField(
            value = text, onValueChange = { text = it; clear() }, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (tab == 0) "Lightning invoice" else if (tab == 1) "Bitcoin address" else "Ecash token") },
            minLines = 2,
            enabled = operation !is WalletOperation.Submitting && operation !is WalletOperation.BitcoinParsed,
        )
        if (needsAmount) {
            val uriAmountLocked = (operation as? WalletOperation.BitcoinParsed)?.payment?.amountSat != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferAmountUnit.entries.forEach { unit ->
                    FilterChip(
                        selected = amountUnit == unit,
                        onClick = { amountUnit = unit; amount = ""; clear() },
                        enabled = !uriAmountLocked,
                        label = { Text(when (unit) { TransferAmountUnit.SATS -> "Sats"; TransferAmountUnit.BTC -> "BTC"; TransferAmountUnit.FIAT -> "Fiat" }) },
                    )
                }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { value ->
                    amount = if (amountUnit == TransferAmountUnit.SATS) value.filter(Char::isDigit)
                    else value.filter(BitcoinAmountPresentation::inputCharacterAllowed)
                    clear()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(when (amountUnit) { TransferAmountUnit.SATS -> "Amount (sats)"; TransferAmountUnit.BTC -> "Amount (BTC)"; TransferAmountUnit.FIAT -> "Amount (fiat)" }) },
                enabled = operation !is WalletOperation.Submitting && !uriAmountLocked,
            )
            if (operation is WalletOperation.FiatConverted) Text("${operation.amountSat} sats · ${operation.currencyCode}")
            if (operation is WalletOperation.BitcoinParsed) {
                operation.payment.label?.let { Text("Label: $it") }
                operation.payment.message?.let { Text("Message: $it") }
                if (operation.payment.amountSat != null) Text("Amount supplied by Bitcoin URI")
            }
        }
        Spacer(Modifier.height(12.dp))
        val submitting = operation is WalletOperation.Submitting
        val textValid = !needsText || text.isNotBlank()
        val convertedAmount = (operation as? WalletOperation.FiatConverted)?.amountSat
        val amountValid = !needsAmount || when {
            convertedAmount != null -> convertedAmount > 0
            amountUnit == TransferAmountUnit.FIAT -> amount.toBigDecimalOrNull()?.signum() == 1
            amountUnit == TransferAmountUnit.BTC -> BitcoinAmountPresentation.toSats(amount) != null
            else -> (amount.toLongOrNull() ?: 0L) > 0
        }
        Button(
            onClick = {
                if (needsAmount && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted) {
                    submit("fiat_to_sats", client, amount, 0)
                    return@Button
                }
                val enteredSat = (operation as? WalletOperation.FiatConverted)?.amountSat
                    ?: (if (amountUnit == TransferAmountUnit.BTC) BitcoinAmountPresentation.toSats(amount) else amount.toLongOrNull())
                    ?: 0L
                if (receive) {
                    val action = when (tab) { 0 -> "receive_lightning"; 1 -> "receive_onchain"; else -> "claim_ecash" }
                    val existingAddress = tab == 1 && operation is WalletOperation.Success && operation.title == "On-chain address"
                    if (existingAddress) {
                        if (anotherAddressGate.request { submit(action, client, text, enteredSat) }) modalRoute = WalletModalRoute.ANOTHER_ADDRESS
                    } else submit(action, client, text, enteredSat)
                } else when (tab) {
                    0 -> submit(if (initialType == cash.pyx.app.nativeapi.InputType.LNURL) "prepare_lnurl" else "prepare_lightning", client, text, 0)
                    1 -> submit("prepare_onchain", client, (operation as? WalletOperation.BitcoinParsed)?.destination ?: text, enteredSat)
                    else -> modalRoute = WalletModalRoute.SEND_CONFIRMATION
                }
            },
            enabled = !submitting && textValid && amountValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (submitting) "Submitting…" else if (needsAmount && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted) "Convert amount" else if (!receive) "Review and send" else when (tab) {
            1 -> "Get address"; 2 -> "Claim ecash"; else -> "Create invoice"
        }) }
        if (receive && tab == 0) OutlinedButton(
            onClick = { submit("receive_lnurl", client, "", 0) }, enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Receive without amount (LNURL)") }
        when (operation) {
            is WalletOperation.Success -> Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(operation.title, style = MaterialTheme.typography.titleMedium)
                    operation.expiresAtEpochSeconds?.let { expiry ->
                        val nowMillis by produceState(System.currentTimeMillis(), expiry) {
                            while (value.floorDiv(1_000) < expiry) {
                                delay(1_000)
                                value = System.currentTimeMillis()
                            }
                        }
                        val expired = LightningInvoiceExpiryPresentation.remainingSeconds(expiry, nowMillis) == 0L
                        Text(
                            LightningInvoiceExpiryPresentation.text(expiry, nowMillis),
                            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics {
                                stateDescription = if (expired) "Invoice expired" else "Invoice active"
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                    if (operation.title in listOf("Lightning invoice", "LNURL receive", "On-chain address", "Ecash token")) {
                        if (operation.title == "Ecash token") EcashQrResult(operation.detail.substringBefore("\n"), ecashFrame, startEcashDisplay, stopEcashDisplay)
                        else PayloadQrResult(operation.detail.substringBefore("\n"), false)
                    } else SelectionContainer { Text(operation.detail) }
                }
            }
            is WalletOperation.Failure -> Text(operation.message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            is WalletOperation.LnurlPrepared -> {
                Text(if (operation.fixedAmount) "Fixed amount: ${operation.minSat} sats" else "Allowed: ${operation.minSat}–${operation.maxSat} sats")
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                    enabled = !operation.fixedAmount, label = { Text("Amount (sats)") })
                val resolvedAmount = LnurlQuotePresentation.resolveAmount(operation, amount)
                Button(onClick = { resolvedAmount?.let { submit("prepare_lnurl_quote", client, operation.sessionHandle.toString(), it) } },
                    enabled = LnurlQuotePresentation.canPrepare(operation, resolvedAmount)) { Text("Get fee quote") }
            }
            is WalletOperation.LightningPrepared -> {
                Text("Amount: ${operation.quote.amountSat} sats\nFee: ${operation.quote.feeSat} sats")
                Text(if (operation.quote.direct) "Direct payment" else "Gateway: ${operation.quote.gatewayUrl}")
                Button(onClick = { modalRoute = WalletModalRoute.SEND_CONFIRMATION }) { Text("Confirm quoted payment") }
            }
            is WalletOperation.OnchainPrepared -> {
                Text("Address: ${operation.quote.address}\nAmount: ${operation.quote.amountSat} sats\nFee: ${operation.quote.feeSat} sats")
                operation.quote.label?.let { Text("Label: $it") }
                operation.quote.message?.let { Text("Message: $it") }
                Button(onClick = { modalRoute = WalletModalRoute.SEND_CONFIRMATION }) { Text("Confirm quoted payment") }
            }
            is WalletOperation.FiatConverted -> Unit
            is WalletOperation.BitcoinParsed -> Unit
            else -> Unit
        }
    }
    if (modalRoute == WalletModalRoute.SEND_CONFIRMATION) AlertDialog(
        onDismissRequest = { modalRoute = null },
        title = { Text("Confirm send") },
        text = { Text("Review the destination and amount carefully. This action cannot be undone.") },
        confirmButton = { Button(onClick = {
            modalRoute = null
            if (operation is WalletOperation.LightningPrepared) {
                submit("execute_lightning", client, operation.quote.quoteHandle.toString(), 0)
            } else if (operation is WalletOperation.OnchainPrepared) {
                submit("execute_onchain", client, operation.quote.quoteHandle.toString(), 0)
            } else {
                submit("create_ecash", client, text, amount.toLongOrNull() ?: 0)
            }
        }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { modalRoute = null }) { Text("Cancel") } },
    )
    if (modalRoute == WalletModalRoute.ANOTHER_ADDRESS) AlertDialog(
        onDismissRequest = { anotherAddressGate.cancel(); modalRoute = null },
        title = { Text("Generate another address?") },
        text = { Text("The current address remains valid. Generate a new on-chain receive address?") },
        confirmButton = { Button(onClick = { modalRoute = null; anotherAddressGate.confirm() }) { Text("Generate address") } },
        dismissButton = { TextButton(onClick = { anotherAddressGate.cancel(); modalRoute = null }) { Text("Cancel") } },
    )
}

@Composable
private fun PayloadQrResult(payload: String, sensitive: Boolean) {
    if (sensitive) SecureScreen()
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(payload) { runCatching { QrPayload.encode(payload) }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
    SelectionContainer { Text(payload) }
    Row {
        ClipboardCopyButton(context, "Pyx wallet payload", payload, sensitive, "Copy")
        if (PlatformUtilityPolicy.canShare(sensitive)) TextButton(onClick = {
            context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(payload, sensitive), "Share"))
        }) { Text("Share") }
    }
}

@Composable
private fun EcashQrResult(payload: String, frame: String?, start: (String, Boolean) -> Unit, stop: () -> Unit) {
    SecureScreen()
    val context = androidx.compose.ui.platform.LocalContext.current
    val reducedMotion = remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    DisposableEffect(payload, reducedMotion) {
        start(payload, !reducedMotion)
        onDispose(stop)
    }
    val shown = frame ?: payload
    val bitmap = remember(shown) { runCatching { QrPayload.encode(shown) }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = if (shown == payload) "Ecash QR code" else "Animated ecash QR fragment",
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
    if (reducedMotion && payload.toByteArray().size > EcashFountainPresentation.STATIC_QR_MAX_BYTES) Text("Animation is disabled. Copy and paste the ecash token manually on the receiving device.")
    Text("Ecash token · ${payload.length} characters", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ClipboardCopyButton(context, "Pyx ecash token", payload, sensitive = true, buttonLabel = "Copy ecash token")
}

@Composable
private fun PaymentRow(payment: Payment, onClick: () -> Unit = {}) {
    ListItem(
        headlineContent = { Text(payment.type.name.lowercase().replaceFirstChar(Char::uppercase)) },
        supportingContent = { Text(payment.status.name.lowercase()) },
        trailingContent = { Text("${if (payment.direction.name == "INCOMING") "+" else "−"}${payment.amountSat} sats") },
        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().clickable(onClick = onClick),
    )
}

@Composable
private fun MessageContent(title: String, message: String, retry: () -> Unit, retryable: Boolean) {
    Column(Modifier.fillMaxSize().testTag("bootstrap_content"), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = retry,
            enabled = retryable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Try again") }
    }
}

/** Lifecycle-owned gate used by production and deterministic Compose security tests. */
@Composable
fun ProtectedActionHost(
    authenticator: cash.pyx.app.security.ProtectedActionAuthenticator,
    onFailure: (String) -> Unit,
    content: @Composable (
        configured: Boolean?,
        available: Boolean,
        setConfigured: (Boolean) -> Unit,
        guarded: (String, () -> Unit) -> Unit,
    ) -> Unit,
) {
    val actionGate = remember { cash.pyx.app.security.OneShotActionGate() }
    var configured by remember(authenticator) { mutableStateOf<Boolean?>(null) }
    val preferenceScope = rememberCoroutineScope()
    LaunchedEffect(authenticator) { authenticator.configured.collect { configured = it } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, actionGate) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) actionGate.clear()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { actionGate.clear(); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val guard: (String, () -> Unit) -> Unit = { title, action ->
        when (val enabled = configured) {
            null -> onFailure("Security settings are still loading. Try again.")
            false -> action()
            true -> if (!authenticator.available) {
                onFailure("Biometric protection is enabled, but a strong biometric is unavailable. Enroll one or turn protection off in Settings.")
            } else actionGate.request(action) { success, failure ->
                authenticator.authenticate(title, success) { message -> failure(); onFailure(message) }
            }
        }
    }
    content(configured, authenticator.available, { enabled ->
        if ((!enabled || authenticator.available) && configured != null) preferenceScope.launch {
            authenticator.setConfigured(enabled)
        }
    }, guard)
}

@Composable
fun PyxApp(
    viewModel: WalletBootstrapViewModel,
    authenticatorFactory: (androidx.fragment.app.FragmentActivity) -> cash.pyx.app.security.ProtectedActionAuthenticator =
        { cash.pyx.app.security.BiometricGate(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val contactsState by viewModel.contactsState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val federationState by viewModel.federationState.collectAsStateWithLifecycle()
    val refreshStatus by viewModel.refreshStatus.collectAsStateWithLifecycle()
    val currencySettingsState by viewModel.currencySettingsState.collectAsStateWithLifecycle()
    val addresses by viewModel.addresses.collectAsStateWithLifecycle()
    val classifiedInput by viewModel.classifiedInput.collectAsStateWithLifecycle()
    val fiatBalance by viewModel.fiatBalance.collectAsStateWithLifecycle()
    val recovery by viewModel.recovery.collectAsStateWithLifecycle()
    val recoveryExpiry by viewModel.recoveryExpiry.collectAsStateWithLifecycle()
    val paymentNotice by viewModel.paymentNotice.collectAsStateWithLifecycle()
    val seedValidation by viewModel.seedValidation.collectAsStateWithLifecycle()
    val seedSuggestions by viewModel.seedSuggestions.collectAsStateWithLifecycle()
    val ecashQrState by viewModel.ecashQrState.collectAsStateWithLifecycle()
    val ecashFrame = ecashQrState.displayFrame
    val ecashDecodeProgress = ecashQrState.decodeProgress
    val pendingIrreversibleOperation by viewModel.pendingIrreversibleOperation.collectAsStateWithLifecycle()
    val activityState by viewModel.activityState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as androidx.fragment.app.FragmentActivity
    val authenticator = remember(activity, authenticatorFactory) { authenticatorFactory(activity) }
    LaunchedEffect(ecashQrState.error) {
        ecashQrState.error?.let(viewModel::operationFailure)
    }
    ProtectedActionHost(authenticator, viewModel::operationFailure) { biometricPolicyValue, biometricAvailable, setBiometric, guarded ->
    val biometricEnabled = biometricPolicyValue ?: false
    PyxApp(
        state = state,
        onRetry = viewModel::refresh,
        onCreate = viewModel::createWallet,
        onRestore = viewModel::restoreWallet,
        onAcknowledgeSeed = viewModel::acknowledgeSeed,
        operation = operation,
        onClearOperation = viewModel::clearOperation,
        onClearInvite = viewModel::clearInvite,
        onJoin = viewModel::join,
        onClearSendPayload = viewModel::clearSendPayload,
        contactsState = contactsState,
        onClearContactsMessage = viewModel::clearContactsMessage,
        connection = connection,
        federationState = federationState,
        refreshStatus = refreshStatus,
        onRefreshHome = viewModel::refreshHome,
        currencySettingsState = currencySettingsState,
        onClearCurrencyMessage = viewModel::clearCurrencyMessage,
        addresses = addresses,
        onLifecycleResumed = viewModel::setResumed,
        classifiedInput = classifiedInput,
        onClassify = viewModel::classifyInput,
        onConsumeClassified = viewModel::consumeClassifiedInput,
        fiatBalance = fiatBalance,
        recovery = recovery,
        recoveryExpiry = recoveryExpiry,
        paymentNotice = paymentNotice,
        onPaymentNoticeShown = viewModel::consumePaymentNotice,
        seedValidation = seedValidation,
        seedSuggestions = seedSuggestions,
        onValidateSeed = viewModel::validateSeed,
        onSuggestSeed = viewModel::suggestSeed,
        onClearSeedAssistance = viewModel::clearSeedAssistance,
        ecashFrame = ecashFrame,
        ecashDecodeProgress = ecashDecodeProgress,
        onStartEcashDisplay = viewModel::startEcashDisplay,
        onStopEcashDisplay = viewModel::stopEcashDisplay,
        onEcashFrame = viewModel::feedEcashFrame,
        onStopEcashDecoder = viewModel::stopEcashDecoder,
        pendingIrreversibleOperation = pendingIrreversibleOperation,
        onRefreshOperationReconciliation = viewModel::refreshOperationReconciliation,
        activityState = activityState,
        onLoadActivityPage = viewModel::loadActivityPage,
        onOpenActivityDetail = viewModel::paymentDetails,
        onDismissActivityDetail = viewModel::dismissPaymentDetails,
        biometricAvailable = biometricAvailable,
        biometricEnabled = biometricEnabled,
        onBiometricToggle = { enabled ->
            setBiometric(enabled)
        },
        onBackup = { factory ->
            guarded("Unlock recovery words") { viewModel.backup(factory) }
        },
        onOperation = { action, client, text, amount -> when (action) {
            "receive_lightning" -> viewModel.receiveLightning(client, amount)
            "receive_lnurl" -> viewModel.receiveLnurl(client)
            "receive_onchain" -> viewModel.receiveOnchain(client)
            "claim_ecash" -> viewModel.claimEcash(client, text)
            "create_ecash" -> guarded("Create ecash token") { viewModel.createEcash(client, amount) }
            "switch" -> viewModel.switchFederation(client, text)
            "currency" -> viewModel.setCurrency(client, text)
            "backup" -> guarded("Unlock recovery words") { viewModel.backup(client) }
            "leave" -> guarded("Leave federation") { viewModel.leave(client, text) }
            "details" -> viewModel.details(client)
            "prepare_lnurl" -> viewModel.prepareLnurl(text)
            "prepare_lnurl_quote" -> text.toLongOrNull()?.let { viewModel.prepareLnurlQuote(client, it, amount) }
            "load_contacts" -> viewModel.loadContacts(client)
            "save_contact" -> text.split('\u0000', limit = 2).takeIf { it.size == 2 }?.let { viewModel.saveContact(client, it[1], it[0]) }
            "delete_contact" -> viewModel.deleteContact(client, text)
            "connection" -> viewModel.loadConnection(client)
            "details_connection" -> viewModel.loadFederationDetails(client)
            "refresh_home" -> viewModel.refreshHome()
            "fiat_to_sats" -> viewModel.convertFiat(client, text)
            "show_successor" -> viewModel.showSuccessorInvite()
            "load_currencies" -> viewModel.loadCurrencies()
            "load_addresses" -> viewModel.loadAddresses(client)
            "recheck_address" -> viewModel.recheckAddress(client, amount)
            "prepare_lightning" -> viewModel.prepareLightning(client, text)
            "prepare_onchain" -> viewModel.prepareOnchain(client, text, amount)
            "parse_bitcoin" -> viewModel.parseBitcoin(text)
            "execute_lightning" -> text.toLongOrNull()?.let { quote -> guarded("Send Lightning payment") { viewModel.executeLightning(client, quote) } }
            "execute_onchain" -> text.toLongOrNull()?.let { quote -> guarded("Send on-chain payment") { viewModel.executeOnchain(client, quote) } }
        } },
    )
    }
}

private fun cash.pyx.app.security.PendingIrreversibleOperation.Kind.displayName(): String = when (this) {
    cash.pyx.app.security.PendingIrreversibleOperation.Kind.LIGHTNING_SEND -> "Lightning payment"
    cash.pyx.app.security.PendingIrreversibleOperation.Kind.ONCHAIN_SEND -> "on-chain payment"
    cash.pyx.app.security.PendingIrreversibleOperation.Kind.ECASH_CREATE -> "created ecash token"
    cash.pyx.app.security.PendingIrreversibleOperation.Kind.ECASH_CLAIM -> "claimed ecash token"
}

private fun reconciliationMessage(operation: cash.pyx.app.security.PendingIrreversibleOperation): String = when {
    operation.correlationId == null || operation.federationId == null ->
        "An older ${operation.kind.displayName()} record cannot be reconciled automatically. Do not retry; contact support."
    operation.reconciliationStatus == cash.pyx.app.security.PendingIrreversibleOperation.ReconciliationStatus.IN_FLIGHT ->
        "The ${operation.kind.displayName()} is still being submitted. Pyx will not retry it automatically."
    operation.reconciliationStatus == cash.pyx.app.security.PendingIrreversibleOperation.ReconciliationStatus.PENDING ->
        "The ${operation.kind.displayName()} is pending. Wait for a final result before making another payment."
    operation.reconciliationStatus == cash.pyx.app.security.PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS ->
        "The ${operation.kind.displayName()} result is ambiguous. Do not retry; contact support."
    else -> "Pyx must reconcile a previous ${operation.kind.displayName()} before another payment can be made."
}

@Preview(showBackground = true)
@Composable
private fun PyxAppPreview() { PyxTheme { PyxApp() } }
