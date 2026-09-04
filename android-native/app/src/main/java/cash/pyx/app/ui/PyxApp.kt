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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.ActivityDetailState
import cash.pyx.app.data.ActivityState
import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.ui.components.BtcBadge
import cash.pyx.app.ui.components.CardRow
import cash.pyx.app.ui.components.InitialAvatar
import cash.pyx.app.ui.components.PyxCard
import cash.pyx.app.ui.components.PyxDivider
import cash.pyx.app.ui.components.PyxFab
import cash.pyx.app.ui.components.PyxGhostButton
import cash.pyx.app.ui.components.PyxIconButton
import cash.pyx.app.ui.components.PyxPrimaryButton
import cash.pyx.app.ui.components.PyxSegmented
import cash.pyx.app.ui.components.PyxTextLink
import cash.pyx.app.ui.components.PyxTopBar
import cash.pyx.app.ui.components.SectionLabel
import cash.pyx.app.ui.components.StatusDot
import cash.pyx.app.ui.theme.PyxAmber
import cash.pyx.app.ui.theme.PyxBackground
import cash.pyx.app.ui.theme.PyxBorder
import cash.pyx.app.ui.theme.PyxFaint
import cash.pyx.app.ui.theme.PyxGlow
import cash.pyx.app.ui.theme.PyxGreen
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxMuted
import cash.pyx.app.ui.theme.PyxOrange
import cash.pyx.app.ui.theme.PyxRed
import cash.pyx.app.ui.theme.PyxSurface
import cash.pyx.app.ui.theme.PyxSurface2
import cash.pyx.app.ui.theme.PyxText
import cash.pyx.app.ui.theme.PyxTheme
import cash.pyx.app.ui.theme.PyxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** OutlinedTextField restyled to the prototype input: surface fill, 12dp radius, quiet border. */
@Composable
private fun PyxField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        shape = RoundedCornerShape(12.dp),
        textStyle = PyxType.inputMono,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PyxSurface,
            unfocusedContainerColor = PyxSurface,
            disabledContainerColor = PyxSurface,
            errorContainerColor = PyxSurface,
            focusedBorderColor = PyxOrange,
            unfocusedBorderColor = PyxBorder,
            disabledBorderColor = PyxBorder,
            errorBorderColor = PyxRed,
            focusedLabelColor = PyxMuted,
            unfocusedLabelColor = PyxFaint,
            cursorColor = PyxOrange,
        ),
    )
}

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
        containerColor = PyxBackground,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Subtle radial glow at top center, per prototype background.
                    Brush.radialGradient(
                        colors = listOf(PyxGlow, PyxBackground),
                        center = androidx.compose.ui.geometry.Offset(390f, 0f),
                        radius = 900f,
                    ),
                )
                .padding(padding)
                .padding(horizontal = 22.dp),
        ) {
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
                                { navController.open(WalletRoute.JOIN) }, { navController.open(WalletRoute.WALLETS) },
                                { navController.open(WalletRoute.CONTACTS) }, { navController.open(WalletRoute.ADDRESSES) },
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = PyxOrange, trackColor = PyxSurface2)
        Spacer(Modifier.height(16.dp))
        Text(message, style = PyxType.centeredTitle, color = PyxText)
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
        Text("Welcome to Pyx", style = PyxType.screenTitle, color = PyxText,
            modifier = Modifier.semantics { heading() })
        Text("Create a wallet or restore an existing one.", style = PyxType.body, color = PyxMuted,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        if (restoring) {
            words.forEachIndexed { index, word -> PyxField(
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
                SectionLabel("Suggestions for word ${currentIndex + 1}")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { suggestions.forEach { suggestion ->
                    Box(
                        Modifier
                            .background(PyxSurface2, RoundedCornerShape(9.dp))
                            .border(1.dp, PyxBorder, RoundedCornerShape(9.dp))
                            .clickable { words = SeedRestorePresentation.selectSuggestion(words, currentIndex, suggestion) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) { Text(suggestion, style = PyxType.keyValue, color = PyxText) }
                } }
            }
            if (SeedRestorePresentation.checksumError(validation)) {
                Text("Recovery phrase checksum is invalid", style = PyxType.body, color = PyxRed,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            Spacer(Modifier.height(12.dp))
            PyxPrimaryButton(
                "Restore wallet",
                { onRestore(state.session.databaseHandle, words.joinToString(" ")) },
                Modifier.fillMaxWidth(),
                enabled = validation?.valid == true,
            )
            Spacer(Modifier.height(8.dp))
            PyxTextLink("Back", { words = SeedRestorePresentation.cleared(); restoring = false; onClearAssistance() })
        } else {
            PyxPrimaryButton("Create wallet", { onCreate(state.session.databaseHandle) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PyxGhostButton("Restore wallet", { restoring = true }, Modifier.fillMaxWidth())
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
        Text("Save your recovery words", style = PyxType.screenTitle, color = PyxText,
            modifier = Modifier.semantics { heading() })
        Text("Write these down in order and keep them private.", style = PyxType.body, color = PyxMuted,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        if (!reviewComplete) {
            Surface(
                color = PyxRed.copy(alpha = 0.07f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PyxRed.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Anyone with these words can take your funds. Never share them.",
                    style = PyxType.rowSub, color = PyxText, modifier = Modifier.padding(13.dp))
            }
            Spacer(Modifier.height(12.dp))
            PyxCard(padding = 16.dp) {
                Text(
                    state.words.mapIndexed { index, word -> "${index + 1}. $word" }.joinToString("   "),
                    fontFamily = FontFamily.Monospace,
                    style = PyxType.keyValue,
                    color = PyxText,
                )
            }
            Spacer(Modifier.height(16.dp))
            PyxPrimaryButton("I've written down all 12 words", { reviewComplete = true }, Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Verify your backup", style = PyxType.rowTitle, color = PyxText)
            Text("Enter these words from your written copy.", style = PyxType.rowSub, color = PyxMuted)
            cash.pyx.app.security.SeedVerification.positions.forEachIndexed { answerIndex, wordIndex ->
                PyxField(
                    value = answers[answerIndex],
                    onValueChange = { value -> answers = answers.toMutableList().also { it[answerIndex] = value.take(32) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Word ${wordIndex + 1}") },
                    singleLine = true,
                )
            }
            PyxTextLink("Show words again", { reviewComplete = false; answers = List(answers.size) { "" } })
            PyxPrimaryButton(
                "Continue to wallet",
                { onAcknowledge(state.factoryHandle) },
                Modifier.fillMaxWidth(),
                enabled = verified,
            )
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
    // One text node whose full string stays exactly `balanceText`, with the
    // trailing unit rendered smaller and muted like the prototype amount.
    val styledBalance = buildAnnotatedString {
        val unitStart = balanceText.lastIndexOf(" sat")
        if (balanceMasked || unitStart <= 0) append(balanceText)
        else {
            withStyle(SpanStyle(fontSize = 31.sp)) { append(balanceText.substring(0, unitStart)) }
            withStyle(SpanStyle(fontSize = 15.sp, color = PyxMuted)) { append(balanceText.substring(unitStart)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("wallet_home"),
            state = listState,
            contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item(key = "home_icon_bar") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PyxIconButton(PyxIcons.Gear, onClick = { navigate(WalletRoute.SETTINGS) },
                    modifier = Modifier.semantics { text = AnnotatedString("Settings") })
                Spacer(Modifier.weight(1f))
                PyxIconButton(
                    if (balanceMasked) PyxIcons.EyeOff else PyxIcons.Eye,
                    onClick = { onBalanceMasked(!balanceMasked) },
                    modifier = Modifier.semantics {
                        contentDescription = "Balance privacy"
                        stateDescription = if (balanceMasked) "Balance hidden" else "Balance visible"
                    },
                )
                PyxIconButton(
                    PyxIcons.Users,
                    onClick = { navigate(WalletRoute.DETAILS) },
                    enabled = wallet != null,
                    tint = if (wallet != null) PyxText else PyxFaint,
                    borderColor = when {
                        connection == null || wallet == null -> PyxBorder
                        connection.onlineCount >= connection.totalCount -> PyxGreen
                        connection.onlineCount >= connection.requiredCount -> PyxAmber
                        else -> PyxRed
                    },
                    modifier = Modifier.semantics { text = AnnotatedString("Details") },
                )
            }
        }
        item(key = "expanded_balance_header") {
            // Exactly one balance header exposes semantics at a time: once the
            // collapsed overlay is shown, this card (which may still be partly
            // composed at maximum scroll) stops advertising the expanded state.
            PyxCard(
                if (collapsed) Modifier else Modifier.testTag("balance_header_expanded").semantics {
                    stateDescription = "Expanded balance header"
                    contentDescription = balanceSemantics
                },
                padding = 16.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtcBadge()
                    Text(styledBalance, style = PyxType.assetAmount, color = PyxText)
                }
                HomePresentation.fiatText(fiatBalance?.amountDecimal, fiatBalance?.currencyCode.orEmpty(), balanceMasked)?.let {
                    Text("≈ $it", style = PyxType.fiat, color = PyxMuted, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PyxGhostButton("Receive", receive, Modifier.weight(1f), enabled = wallet != null, icon = PyxIcons.ArrowDown)
                    PyxGhostButton("Send", send, Modifier.weight(1f), enabled = wallet != null && pendingIrreversibleOperation == null, icon = PyxIcons.ArrowUp)
                }
            }
        }
        item(key = "wallet_row") {
            CardRow(
                title = wallet?.name ?: "No federation selected",
                subtitle = HomePresentation.connectionDetail(connection),
                chevron = true,
                onClick = { navigate(WalletRoute.WALLETS) },
                trailing = {
                    StatusDot(
                        when {
                            connection == null -> PyxFaint
                            connection.onlineCount >= connection.totalCount -> PyxGreen
                            connection.onlineCount >= connection.requiredCount -> PyxAmber
                            else -> PyxRed
                        },
                    )
                },
            )
        }
        if (recoveryExpiry?.hasPendingRecoveries == true || recovery != null) item {
            val complete = recovery?.aggregateComplete ?: 0
            val total = recovery?.aggregateTotal ?: 0
            Text("Recovery in progress · $complete/$total", style = PyxType.rowTitle, color = PyxOrange,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        recoveryExpiry?.expiresAtEpochSeconds?.let { expiry -> item {
            Text("Federation expires at $expiry", style = PyxType.rowSub, color = PyxMuted)
        } }
        if (refreshStatus is HomeRefreshStatus.Degraded || refreshStatus is HomeRefreshStatus.Offline) item {
            val offline = refreshStatus is HomeRefreshStatus.Offline
            PyxCard {
                CardRow(
                    title = if (offline) "Wallet offline" else "Wallet data may be stale",
                    subtitle = refreshStatus.lastSuccessEpochMillis.let { last ->
                        if (last == null) "No successful refresh yet" else "Last refreshed ${android.text.format.DateUtils.getRelativeTimeSpanString(last)}"
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (offline) PyxRed else PyxAmber)
                            PyxTextLink("Retry", refresh)
                        }
                    },
                )
            }
        }
        if (pendingIrreversibleOperation != null) item {
            PyxCard {
                CardRow(
                    title = "Payment reconciliation required",
                    subtitle = reconciliationMessage(pendingIrreversibleOperation),
                    trailing = { PyxTextLink("Review", { navigate(WalletRoute.ACTIVITY) }) },
                )
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
        }
        if (wallet == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PyxPrimaryButton("Join federation", { navigate(WalletRoute.JOIN) }, Modifier.fillMaxWidth())
                PyxGhostButton("Recover federation", { navigate(WalletRoute.RECOVER) }, Modifier.fillMaxWidth())
            }
        }
        item(key = "activity_section") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Activity", Modifier.weight(1f))
                PyxTextLink("See all", { navigate(WalletRoute.ACTIVITY) })
            }
        }
        if (wallet == null || wallet.payments.isEmpty()) {
            item(key = "activity_empty") { Text("No recent payments", style = PyxType.body, color = PyxMuted) }
        } else {
            items(wallet.payments, key = ActivityPresentation::itemKey) { payment ->
                PaymentRow(payment) { paymentDetails(wallet.clientHandle, payment.operationId) }
            }
        }
        item(key = "home_footer") {
            Column {
                PyxTextLink("Refresh wallet", refresh, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        }
        item(key = "home_bottom_spacer") { Spacer(Modifier.height(72.dp)) }
        }
        AnimatedVisibility(visible = collapsed, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = PyxSurface, border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("balance_header_collapsed")
                        .semantics {
                            stateDescription = "Collapsed balance header"
                            contentDescription = balanceSemantics
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BtcBadge(size = 24.dp)
                    Text(wallet?.name ?: "No federation selected", style = PyxType.rowTitle, color = PyxText, modifier = Modifier.weight(1f))
                    Text(balanceText, style = PyxType.keyValue, color = PyxText)
                }
            }
        }
        if (wallet != null) PyxFab(
            PyxIcons.Scan,
            onClick = scan,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            semanticsModifier = Modifier.semantics { text = AnnotatedString("Scan") },
        )
    }
}

@Composable
private fun ManageContent(
    screen: WalletRoute,
    state: BootstrapState.Home,
    operation: WalletOperation,
    back: () -> Unit,
    openJoin: () -> Unit,
    openWallets: () -> Unit,
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
        PyxTopBar(screen.title, onBack = { clear(); back() }, titleSemantics = Modifier.semantics { heading() })
        when (screen) {
            WalletRoute.ACTIVITY -> {
                if (pendingIrreversibleOperation != null) {
                    PyxCard {
                        Text("Check for a previous payment", style = PyxType.rowTitle, color = PyxText)
                        Text(reconciliationMessage(pendingIrreversibleOperation), style = PyxType.rowSub, color = PyxMuted,
                            modifier = Modifier.padding(vertical = 8.dp))
                        PyxPrimaryButton("Check status", refreshOperationReconciliation)
                    }
                }
                PyxTextLink("Refresh activity", { selected?.let { loadActivityPage(it.clientHandle, true) } }, enabled = !activityState.loading)
                when {
                    activityState.loading && !activityState.initialized -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = PyxOrange)
                        Text("Loading activity…", Modifier.padding(start = 12.dp), style = PyxType.body, color = PyxMuted)
                    }
                    activityState.error != null && !activityState.initialized -> Text(activityState.error, style = PyxType.body, color = PyxRed,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    activityState.initialized && activityState.payments.isEmpty() -> Text("No payment activity yet", style = PyxType.body, color = PyxMuted)
                    else -> ActivityPresentation.group(activityState.payments).forEach { day ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(day.date.toString(), Modifier.padding(end = 12.dp))
                            PyxDivider()
                        }
                        day.payments.forEach { payment -> key(ActivityPresentation.itemKey(payment)) {
                            PaymentRow(payment) { selected?.let { openActivityDetail(it.clientHandle, payment.operationId) } }
                        } }
                    }
                }
                activityState.error?.takeIf { activityState.initialized }?.let {
                    Text(it, style = PyxType.body, color = PyxRed, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                when (val detail = activityState.detail) {
                    is ActivityDetailState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), color = PyxOrange)
                    is ActivityDetailState.Error -> Text(detail.message, style = PyxType.body, color = PyxRed,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    else -> Unit
                }
                if (activityState.initialized && activityState.nextCursor != null) {
                    PyxGhostButton(
                        if (activityState.loading) "Loading…" else "Load more",
                        { selected?.let { loadActivityPage(it.clientHandle, false) } },
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = !activityState.loading,
                    )
                }
            }
            WalletRoute.WALLETS -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.snapshot.federations.forEach { federation ->
                        val active = federation.id == selected?.federationId
                        PyxCard(padding = 0.dp) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !active) { submit("switch", state.factoryHandle, federation.id, 0) }
                                    .padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                InitialAvatar(federation.name)
                                Column(Modifier.weight(1f)) {
                                    Text(federation.name, style = PyxType.rowTitle, color = PyxText)
                                    Text("${federation.guardianCount} guardians", style = PyxType.rowSub, color = PyxMuted)
                                }
                                if (active) Icon(PyxIcons.Check, contentDescription = null, tint = PyxOrange, modifier = Modifier.size(20.dp))
                                else PyxTextLink("Switch", { submit("switch", state.factoryHandle, federation.id, 0) })
                            }
                        }
                    }
                    PyxGhostButton("Add federation", openJoin, Modifier.fillMaxWidth())
                }
            }
            WalletRoute.DETAILS -> {
                selected?.let { wallet ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        InitialAvatar(wallet.name, size = 88.dp)
                    }
                }
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Name", value = selected?.name ?: "No federation selected", valueColor = PyxText)
                        PyxDivider()
                        CardRow(
                            "Guardians",
                            value = connection?.let { "${it.onlineCount} of ${it.totalCount} online" } ?: "Connection status unavailable",
                            subtitle = connection?.let { "Quorum requires ${it.requiredCount} of ${it.totalCount}" },
                            valueColor = when {
                                connection == null -> PyxMuted
                                connection.onlineCount >= connection.totalCount -> PyxGreen
                                connection.onlineCount >= connection.requiredCount -> PyxAmber
                                else -> PyxRed
                            },
                            chevron = true,
                            onClick = openGuardians,
                            enabled = selected != null,
                        )
                    }
                }
                PyxTextLink("Load federation details", { selected?.let { submit("details_connection", it.clientHandle, "", 0) } })
                when (federationState) {
                    is cash.pyx.app.data.FederationState.Selecting -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = PyxOrange, trackColor = PyxSurface2)
                    is cash.pyx.app.data.FederationState.Failed -> Text(
                        federationState.error.userMessage,
                        style = PyxType.body,
                        color = PyxRed,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    is cash.pyx.app.data.FederationState.Ready -> {
                        federationState.details?.let { details ->
                            SectionLabel("Meta")
                            PyxCard(padding = 0.dp) {
                                Column(Modifier.padding(horizontal = 15.dp)) {
                                    CardRow("Federation", value = details.name, valueColor = PyxText)
                                    PyxDivider()
                                    CardRow("Id", value = details.id.take(16) + "…")
                                    PyxDivider()
                                    CardRow("Currency", value = details.currencyCode)
                                    details.stats?.let { stats ->
                                        PyxDivider()
                                        CardRow("Total value", value = "${stats.totalValueSat} sats")
                                        PyxDivider()
                                        CardRow("Blocks", value = "${stats.blockCount}")
                                    }
                                }
                            }
                        }
                        federationState.detailsError?.let { Text(it.userMessage, style = PyxType.body, color = PyxRed) }
                        federationState.connectionError?.let { Text(it.userMessage, style = PyxType.body, color = PyxRed) }
                    }
                    cash.pyx.app.data.FederationState.Idle -> Unit
                }
                recovery?.let {
                    SectionLabel("Recovery")
                    PyxCard {
                        Text("Recovery ${it.aggregateComplete}/${it.aggregateTotal}", style = PyxType.rowTitle, color = PyxText)
                        LinearProgressIndicator(
                            progress = { if (it.aggregateTotal == 0L) 0f else it.aggregateComplete.toFloat() / it.aggregateTotal },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = PyxOrange, trackColor = PyxSurface2,
                        )
                        Text("Module ${it.moduleId}: ${it.complete}/${it.total}", style = PyxType.rowSub, color = PyxMuted)
                    }
                }
                recoveryExpiry?.expiresAtEpochSeconds?.let { Text("Expires at $it", style = PyxType.rowSub, color = PyxMuted, modifier = Modifier.padding(top = 8.dp)) }
                if (RecoveryPresentation.canReviewSuccessor(recoveryExpiry)) PyxPrimaryButton(
                    "Review successor federation",
                    { submit("show_successor", selected?.clientHandle ?: 0, "", 0) },
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Spacer(Modifier.height(16.dp))
                PyxGhostButton("On-chain address history", openAddresses, Modifier.fillMaxWidth(), enabled = selected != null)
                Spacer(Modifier.height(10.dp))
                PyxGhostButton(
                    "Leave federation",
                    { confirmationRoute = WalletModalRoute.LEAVE_FEDERATION },
                    Modifier.fillMaxWidth(),
                    enabled = selected != null,
                    textColor = PyxRed,
                )
            }
            WalletRoute.GUARDIANS -> {
                connection?.let { status ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${status.state.name.lowercase().replaceFirstChar(Char::uppercase)} · ${status.onlineCount}/${status.totalCount} online",
                            style = PyxType.rowTitle, color = PyxText, modifier = Modifier.weight(1f),
                        )
                        Text("Quorum ${status.requiredCount} of ${status.totalCount}", style = PyxType.rowSub, color = PyxMuted)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        status.guardians.forEach { guardian ->
                            PyxCard(padding = 0.dp) {
                                Row(
                                    Modifier.fillMaxWidth().padding(13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    InitialAvatar(guardian.name)
                                    Column(Modifier.weight(1f)) {
                                        Text(guardian.name, style = PyxType.rowTitle, color = PyxText)
                                        Text(
                                            if (guardian.connected) "Online" else "Offline",
                                            style = PyxType.rowSub,
                                            color = if (guardian.connected) PyxGreen else PyxRed,
                                        )
                                    }
                                    StatusDot(if (guardian.connected) PyxGreen else PyxRed)
                                }
                            }
                        }
                    }
                } ?: Text("Guardian connection status is unavailable.", style = PyxType.body, color = PyxMuted)
            }
            WalletRoute.SETTINGS -> {
                SectionLabel("Wallet")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Wallets", value = "${state.snapshot.federations.size}", chevron = true, onClick = openWallets)
                        PyxDivider()
                        CardRow("Currency", value = state.snapshot.currencyCode, chevron = true, onClick = openCurrency)
                        PyxDivider()
                        CardRow("Recovery words", chevron = true, onClick = openSeedBackup)
                        PyxDivider()
                        CardRow("Manage contacts", chevron = true, onClick = openContacts)
                    }
                }
                SectionLabel("Security")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Biometric protection", subtitle = "Configure access protection", chevron = true, onClick = openAccess)
                    }
                }
                SectionLabel("About")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Network", value = "Bitcoin")
                        PyxDivider()
                        CardRow("Version", value = "${cash.pyx.app.BuildConfig.VERSION_NAME} (${cash.pyx.app.BuildConfig.VERSION_CODE})")
                        PyxDivider()
                        CardRow("Build type", value = cash.pyx.app.BuildConfig.BUILD_TYPE)
                    }
                }
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Row(Modifier.padding(top = 8.dp)) {
                    SettingsPresentation.links.forEach { link -> PyxTextLink(link.label, { uriHandler.openUri(link.url) }) }
                }
            }
            WalletRoute.CURRENCY -> {
                LaunchedEffect(Unit) { submit("load_currencies", 0, "", 0) }
                PyxField(value = input, onValueChange = { input = it.take(32) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search currencies") }, singleLine = true)
                if (currencySettingsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = PyxOrange, trackColor = PyxSurface2)
                FeatureMessageContent(currencySettingsState.message, clearCurrencyMessage)
                Spacer(Modifier.height(12.dp))
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        val visible = currencySettingsState.currencies
                            .filter { input.isBlank() || it.code.contains(input, true) || it.name.contains(input, true) }
                            .take(12)
                        visible.forEachIndexed { index, currency ->
                            CardRow(
                                title = currency.code,
                                subtitle = currency.name,
                                value = currency.symbol,
                                onClick = { submit("currency", state.factoryHandle, currency.code, 0) },
                                enabled = currencySettingsState.savingCode == null,
                                trailing = {
                                    when {
                                        currencySettingsState.savingCode == currency.code ->
                                            CircularProgressIndicator(Modifier.size(20.dp), color = PyxOrange)
                                        currency.code == state.snapshot.currencyCode ->
                                            Icon(PyxIcons.Check, contentDescription = null, tint = PyxOrange, modifier = Modifier.size(18.dp))
                                    }
                                },
                            )
                            if (index != visible.lastIndex) PyxDivider()
                        }
                    }
                }
            }
            WalletRoute.ACCESS -> {
                Text(SettingsPresentation.biometricMessage(biometricAvailable, biometricEnabled), style = PyxType.body, color = PyxMuted,
                    modifier = Modifier.padding(bottom = 12.dp))
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow(
                            "Biometric protection",
                            subtitle = "Require a strong biometric before protected actions",
                            trailing = {
                                Switch(
                                    checked = biometricEnabled, onCheckedChange = onBiometricToggle,
                                    enabled = biometricAvailable || biometricEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = PyxOrange, checkedThumbColor = PyxSurface,
                                        uncheckedTrackColor = PyxSurface2, uncheckedThumbColor = PyxMuted,
                                        uncheckedBorderColor = PyxBorder,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            WalletRoute.SEED_BACKUP -> {
                Surface(
                    color = PyxRed.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PyxRed.copy(alpha = 0.32f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Your recovery words restore this wallet. Keep them private and offline.",
                        style = PyxType.body, color = PyxText, modifier = Modifier.padding(15.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                PyxPrimaryButton(
                    "Show recovery words",
                    { onBackup(state.factoryHandle) },
                    Modifier.fillMaxWidth(),
                    enabled = operation !is WalletOperation.Submitting,
                )
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
                if (contactsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PyxOrange, trackColor = PyxSurface2)
                FeatureMessageContent(contactsState.message, clearContactsMessage)
                val validation = ContactsPresentation.validate(name, lnurl, contacts, editing?.lnurl)
                PyxField(query, { query = it.take(256) }, Modifier.fillMaxWidth(), label = { Text("Search contacts") }, singleLine = true)
                SectionLabel(if (editing == null) "Add contact" else "Edit contact")
                PyxField(name, { name = it.take(128); showValidation = true }, Modifier.fillMaxWidth(), label = { Text("Name") },
                    isError = showValidation && validation.nameError != null,
                    supportingText = { if (showValidation) validation.nameError?.let { Text(it) } }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                PyxField(lnurl, { lnurl = it.take(16 * 1024); showValidation = true }, Modifier.fillMaxWidth(), label = { Text("Lightning address or LNURL") },
                    isError = showValidation && validation.paymentError != null,
                    supportingText = { if (editing != null) Text("Payment address cannot be changed while editing")
                        else if (showValidation) validation.paymentError?.let { Text(it) } },
                    enabled = editing == null, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContactSaveButton(editing == null, enabled = contactsState.mutation == null, onClick = {
                        showValidation = true
                        if (validation.valid) {
                            submit("save_contact", state.factoryHandle, "${name.trim()}\u0000${lnurl.trim()}", 0)
                            editing = null; name = ""; lnurl = ""; showValidation = false
                        }
                    })
                    if (editing != null) PyxTextLink("Cancel", { editing = null; name = ""; lnurl = ""; showValidation = false })
                }
                val visibleContacts = ContactsPresentation.filter(contacts, query)
                SectionLabel("Contacts")
                if (contacts.isEmpty()) Text("No contacts yet", style = PyxType.body, color = PyxMuted)
                else if (visibleContacts.isEmpty()) Text("No contacts match your search", style = PyxType.body, color = PyxMuted)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visibleContacts.forEach { contact ->
                        PyxCard(padding = 0.dp) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                InitialAvatar(contact.name, size = 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(contact.name, style = PyxType.rowTitle, color = PyxText)
                                    Text(contact.lnurl, style = PyxType.rowSub, color = PyxMuted, maxLines = 1)
                                }
                                PyxTextLink("Pay", { sendContact(contact.lnurl) })
                                PyxTextLink("Edit", { editing = contact; name = contact.name; lnurl = contact.lnurl; showValidation = false },
                                    enabled = contactsState.mutation == null)
                                PyxTextLink("Delete", { delete = contact; contactModalRoute = WalletModalRoute.CONTACT_DELETE },
                                    enabled = contactsState.mutation == null)
                            }
                        }
                    }
                }
                if (contactModalRoute == WalletModalRoute.CONTACT_DELETE && delete != null) AlertDialog(onDismissRequest = { delete = null; contactModalRoute = null }, title = { Text("Delete contact?") },
                    text = { Text("Remove ${delete!!.name}? This does not send or move any funds.") },
                    confirmButton = { Button(onClick = { submit("delete_contact", state.factoryHandle, delete!!.lnurl, 0); delete = null; contactModalRoute = null }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { delete = null; contactModalRoute = null }) { Text("Cancel") } })
            }
            WalletRoute.ADDRESSES -> {
                LaunchedEffect(selected?.clientHandle) { selected?.let { submit("load_addresses", it.clientHandle, "", 0) } }
                if (addresses.isEmpty()) Text("No on-chain addresses", style = PyxType.body, color = PyxMuted)
                else PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        addresses.forEachIndexed { index, address ->
                            CardRow(
                                title = address.address.take(18) + "…",
                                subtitle = "Index ${address.tweakIndex}",
                                trailing = {
                                    PyxTextLink("Recheck", {
                                        selected?.let { wallet ->
                                            if (addressMutationGate.request { submit("recheck_address", wallet.clientHandle, "", address.tweakIndex) }) {
                                                confirmationRoute = WalletModalRoute.ADDRESS_MUTATION
                                            }
                                        }
                                    })
                                },
                            )
                            if (index != addresses.lastIndex) PyxDivider()
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                PyxPrimaryButton(
                    if (addresses.isEmpty()) "Generate address" else "Generate another address",
                    {
                        selected?.let { wallet ->
                            if (addresses.isEmpty()) {
                                submit("receive_onchain", wallet.clientHandle, "", 0)
                            } else if (addressMutationGate.request { submit("receive_onchain", wallet.clientHandle, "", 0) }) {
                                confirmationRoute = WalletModalRoute.ADDRESS_MUTATION
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = operation !is WalletOperation.Submitting,
                )
                Spacer(Modifier.height(10.dp))
                PyxGhostButton("Refresh", { selected?.let { submit("load_addresses", it.clientHandle, "", 0) } }, Modifier.fillMaxWidth())
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
private fun ContactSaveButton(adding: Boolean, enabled: Boolean, onClick: () -> Unit) {
    PyxPrimaryButton(if (adding) "Add contact" else "Save changes", onClick, enabled = enabled)
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
            style = PyxType.rowSub,
            color = if (message is cash.pyx.app.data.FeatureMessage.Failure) PyxRed else PyxMuted,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        PyxTextLink("Dismiss", clear)
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
        PyxTopBar(if (recover) "Recover federation" else "Join federation", onBack = { invite = ""; back() })
        SectionLabel("Federation invite")
        PyxField(
            value = invite,
            onValueChange = { if (it.length <= 16 * 1024) invite = it },
            modifier = Modifier.fillMaxWidth().testTag("federation_invite"),
            label = { Text("Federation invite") },
            minLines = 3,
        )
        PyxTextLink("Scan invite QR", scan)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Switch(
                checked = recover, onCheckedChange = { recover = it },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = PyxOrange, checkedThumbColor = PyxSurface,
                    uncheckedTrackColor = PyxSurface2, uncheckedThumbColor = PyxMuted,
                    uncheckedBorderColor = PyxBorder,
                ),
            )
            Text(if (recover) "Recover an existing wallet" else "Join as a new wallet",
                style = PyxType.body, color = PyxText, modifier = Modifier.padding(start = 10.dp))
        }
        PyxPrimaryButton(
            if (operation is WalletOperation.Submitting) "Working…" else "Continue",
            { modalRoute = WalletModalRoute.JOIN_CONFIRMATION },
            Modifier.fillMaxWidth(),
            enabled = invite.isNotBlank() && operation !is WalletOperation.Submitting,
        )
        if (operation is WalletOperation.Failure) Text(operation.message, style = PyxType.body, color = PyxRed,
            modifier = Modifier.padding(top = 10.dp))
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
    PyxCard(Modifier.padding(top = 16.dp), padding = 16.dp) {
        Text(result.title, style = PyxType.rowTitle, color = PyxText)
        Spacer(Modifier.height(8.dp))
        if (secure) Surface(
            color = PyxRed.copy(alpha = 0.07f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, PyxRed.copy(alpha = 0.32f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(result.detail, style = PyxType.keyValue, color = PyxText, modifier = Modifier.padding(13.dp))
        } else SelectionContainer { Text(result.detail, style = PyxType.inputMono, color = PyxText) }
        if (!secure) Row {
            ClipboardCopyButton(context, result.title, result.detail, sensitive = false, buttonLabel = "Copy")
            if (result.shareable && PlatformUtilityPolicy.canShare(result.sensitive)) PyxTextLink("Share", {
                context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(result.detail, sensitive = false), "Share"))
            })
        }
        if (secure && result.title == "Recovery words") PyxTextLink("Copy recovery words", {
            modalRoute = WalletModalRoute.SEED_COPY_WARNING
        })
        copyAnnouncement?.let {
            Text(it, style = PyxType.rowSub, color = PyxMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (secure) {
            Spacer(Modifier.height(10.dp))
            PyxPrimaryButton("I saved these securely", dismiss, Modifier.fillMaxWidth())
        }
    }
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
        PyxTextLink(buttonLabel, { announcement = QrPayload.copy(context, label, value, sensitive).announcement })
        announcement?.let {
            Text(it, style = PyxType.rowSub, color = PyxMuted,
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
        PyxTopBar(if (receive) "Receive" else "Send", onBack = back)
        PyxSegmented(labels, tab, { index -> tab = index; text = ""; amount = ""; clear() })
        Spacer(Modifier.height(16.dp))
        val needsAmount = if (receive) tab == 0 else tab != 0
        val needsText = if (receive) tab == 2 else tab != 2
        if (needsText) PyxField(
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
            PyxField(
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
            if (operation is WalletOperation.FiatConverted) Text("${operation.amountSat} sats · ${operation.currencyCode}", style = PyxType.fiat, color = PyxMuted)
            if (operation is WalletOperation.BitcoinParsed) {
                operation.payment.label?.let { Text("Label: $it", style = PyxType.rowSub, color = PyxMuted) }
                operation.payment.message?.let { Text("Message: $it", style = PyxType.rowSub, color = PyxMuted) }
                if (operation.payment.amountSat != null) Text("Amount supplied by Bitcoin URI", style = PyxType.rowSub, color = PyxMuted)
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
        PyxPrimaryButton(
            text = if (operation is WalletOperation.Submitting) "Submitting…" else if (needsAmount && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted) "Convert amount" else if (!receive) "Review and send" else when (tab) {
                1 -> "Get address"; 2 -> "Claim ecash"; else -> "Create invoice"
            },
            onClick = onClickLabel@{
                if (needsAmount && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted) {
                    submit("fiat_to_sats", client, amount, 0)
                    return@onClickLabel
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
        )
        if (receive && tab == 0) PyxGhostButton(
            "Receive without amount (LNURL)",
            { submit("receive_lnurl", client, "", 0) }, enabled = !submitting,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        when (operation) {
            is WalletOperation.Success -> PyxCard(Modifier.padding(top = 16.dp), padding = 16.dp) {
                Column {
                    Text(operation.title, style = PyxType.rowTitle, color = PyxText)
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
                Spacer(Modifier.height(12.dp))
                Text(
                    if (operation.fixedAmount) "Fixed amount: ${operation.minSat} sats" else "Allowed: ${operation.minSat}–${operation.maxSat} sats",
                    style = PyxType.rowSub, color = PyxMuted,
                )
                PyxField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !operation.fixedAmount, label = { Text("Amount (sats)") })
                val resolvedAmount = LnurlQuotePresentation.resolveAmount(operation, amount)
                PyxPrimaryButton("Get fee quote",
                    { resolvedAmount?.let { submit("prepare_lnurl_quote", client, operation.sessionHandle.toString(), it) } },
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = LnurlQuotePresentation.canPrepare(operation, resolvedAmount))
            }
            is WalletOperation.LightningPrepared -> {
                Spacer(Modifier.height(12.dp))
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Amount", value = "${operation.quote.amountSat} sats", valueColor = PyxText)
                        PyxDivider()
                        CardRow("Fee", value = "${operation.quote.feeSat} sats")
                        PyxDivider()
                        if (operation.quote.direct) CardRow("Route", value = "Direct payment")
                        else CardRow("Gateway", subtitle = operation.quote.gatewayUrl)
                    }
                }
                PyxPrimaryButton("Confirm quoted payment", { modalRoute = WalletModalRoute.SEND_CONFIRMATION },
                    Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            is WalletOperation.OnchainPrepared -> {
                Spacer(Modifier.height(12.dp))
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Address", subtitle = operation.quote.address)
                        PyxDivider()
                        CardRow("Amount", value = "${operation.quote.amountSat} sats", valueColor = PyxText)
                        PyxDivider()
                        CardRow("Fee", value = "${operation.quote.feeSat} sats")
                        operation.quote.label?.let { PyxDivider(); CardRow("Label", value = it) }
                        operation.quote.message?.let { PyxDivider(); CardRow("Message", subtitle = it) }
                    }
                }
                PyxPrimaryButton("Confirm quoted payment", { modalRoute = WalletModalRoute.SEND_CONFIRMATION },
                    Modifier.fillMaxWidth().padding(top = 10.dp))
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
    bitmap?.let {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Image(it.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp))
        }
    }
    Surface(color = PyxSurface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(payload, style = PyxType.inputMono, color = PyxMuted, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            ClipboardCopyButton(context, "Pyx wallet payload", payload, sensitive, "Copy")
            if (PlatformUtilityPolicy.canShare(sensitive)) PyxTextLink("Share", {
                context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(payload, sensitive), "Share"))
            })
        }
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
    bitmap?.let {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Image(it.asImageBitmap(), contentDescription = if (shown == payload) "Ecash QR code" else "Animated ecash QR fragment",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp))
        }
    }
    if (reducedMotion && payload.toByteArray().size > EcashFountainPresentation.STATIC_QR_MAX_BYTES) Text(
        "Animation is disabled. Copy and paste the ecash token manually on the receiving device.",
        style = PyxType.rowSub, color = PyxMuted)
    Text("Ecash token · ${payload.length} characters", style = PyxType.rowSub, color = PyxMuted)
    ClipboardCopyButton(context, "Pyx ecash token", payload, sensitive = true, buttonLabel = "Copy ecash token")
}

@Composable
private fun PaymentRow(payment: Payment, onClick: () -> Unit = {}) {
    val incoming = payment.direction.name == "INCOMING"
    Row(
        Modifier.fillMaxWidth().minimumInteractiveComponentSize().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(42.dp).background(PyxSurface2, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                if (incoming) PyxIcons.ArrowDown else PyxIcons.ArrowUp,
                contentDescription = null,
                tint = if (incoming) PyxGreen else PyxMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(payment.type.name.lowercase().replaceFirstChar(Char::uppercase), style = PyxType.rowTitle, color = PyxText)
            Text(payment.status.name.lowercase(), style = PyxType.rowSub, color = PyxMuted)
        }
        Text(
            "${if (incoming) "+" else "−"}${payment.amountSat} sats",
            style = PyxType.rowAmount,
            color = if (incoming) PyxGreen else PyxRed,
        )
    }
}

@Composable
private fun MessageContent(title: String, message: String, retry: () -> Unit, retryable: Boolean) {
    Column(Modifier.fillMaxSize().testTag("bootstrap_content"), verticalArrangement = Arrangement.Center) {
        Text(title, style = PyxType.screenTitle, color = PyxText, modifier = Modifier.semantics { heading() })
        Text(message, style = PyxType.body, color = PyxMuted,
            modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive })
        Spacer(Modifier.height(16.dp))
        PyxPrimaryButton("Try again", retry, Modifier.fillMaxWidth(), enabled = retryable)
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
