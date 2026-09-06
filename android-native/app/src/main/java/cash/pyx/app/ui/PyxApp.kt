package cash.pyx.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.unit.em
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Consumed directly rather than flattened, matching LnaddrClaimSheet's seam; null only in
    // previews/tests that never navigate into the Lightning addresses screen.
    lnAddressStateOwner: LnAddressStateOwner? = null,
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
                            onRefreshOperationReconciliation, activityState, onLoadActivityPage,
                            onOpenActivityDetail, onDismissActivityDetail,
                            navController::open)
                    }
                    composable(WalletRoute.RECEIVE.route) {
                        ReceiveContent(state, operation, routedInput?.payload, routedInput?.type,
                            { routedInput = null; navController.returnHome() }, onOperation, onClearOperation,
                            ecashFrame, onStartEcashDisplay, onStopEcashDisplay, addresses,
                            { navController.open(WalletRoute.SCAN) }, currencySettingsState)
                    }
                    composable(WalletRoute.SEND.route) {
                        TransferContent(state, operation, routedInput?.payload, routedInput?.type,
                            { routedInput = null; navController.returnHome() }, onOperation, onClearOperation,
                            ecashFrame, onStartEcashDisplay, onStopEcashDisplay,
                            contactsState, { navController.open(WalletRoute.SCAN) })
                    }
                    composable(WalletRoute.SCAN.route) {
                        QrScanner(onResult = onClassify, onBack = { onStopEcashDecoder(); navController.returnHome() }, frameHandler = { frame ->
                            if (frame.startsWith("fedimint1")) { onEcashFrame(frame); ScanFrameDecision.CONTINUE }
                            else { onClassify(frame); ScanFrameDecision.HANDLING }
                        }, progressFrames = ecashDecodeProgress)
                    }
                    listOf(WalletRoute.WALLETS, WalletRoute.DETAILS, WalletRoute.GUARDIANS,
                        WalletRoute.SETTINGS, WalletRoute.CURRENCY, WalletRoute.CONTACTS,
                        WalletRoute.ADDRESSES, WalletRoute.ACCESS, WalletRoute.SEED_BACKUP,
                        WalletRoute.LNADDR).forEach { route ->
                        composable(route.route) {
                            ManageContent(route, state, operation, navController::goBack,
                                { navController.open(WalletRoute.JOIN) }, { navController.open(WalletRoute.WALLETS) },
                                { navController.open(WalletRoute.CONTACTS) }, { navController.open(WalletRoute.ADDRESSES) },
                                { navController.open(WalletRoute.ACCESS) }, { navController.open(WalletRoute.SEED_BACKUP) },
                                { navController.open(WalletRoute.CURRENCY) }, { navController.open(WalletRoute.GUARDIANS) },
                                { navController.open(WalletRoute.DETAILS) }, { navController.open(WalletRoute.LNADDR) },
                                onOperation, onClearOperation, biometricAvailable, biometricEnabled, onBiometricToggle, onBackup,
                                contactsState, onClearContactsMessage, connection, federationState, recovery, recoveryExpiry,
                                currencySettingsState, onClearCurrencyMessage, addresses, onClassify, pendingIrreversibleOperation,
                                onRefreshOperationReconciliation, lnAddressStateOwner)
                        }
                    }
                    listOf(WalletRoute.JOIN, WalletRoute.RECOVER).forEach { route ->
                        composable(route.route) {
                            JoinContent(state.factoryHandle, state.snapshot.federations.size, routedInput?.payload, route == WalletRoute.RECOVER, operation,
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
        if (!restoring) {
            Box(
                Modifier.size(64.dp).background(PyxOrange, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("P", style = PyxType.screenTitle.copy(fontSize = 34.sp), color = cash.pyx.app.ui.theme.PyxOnOrange)
            }
            Spacer(Modifier.height(18.dp))
        }
        Text("Welcome to Pyx", style = PyxType.screenTitle, color = PyxText,
            modifier = Modifier.semantics { heading() })
        Text("Create a wallet or restore an existing one.", style = PyxType.body, color = PyxMuted,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        if (restoring) {
            // Prototype seed-grid anatomy: 2-column numbered cells with inline inputs.
            words.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    pair.forEachIndexed { columnIndex, word ->
                        val index = rowIndex * 2 + columnIndex
                        val invalid = validation?.invalidIndices?.contains(index) == true
                        Row(
                            Modifier.weight(1f)
                                .heightIn(min = 48.dp)
                                .background(PyxSurface, RoundedCornerShape(11.dp))
                                .border(1.dp, if (invalid) PyxRed else PyxBorder, RoundedCornerShape(11.dp))
                                .padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(
                                "${index + 1}", style = PyxType.seedNum, color = PyxFaint,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.widthIn(min = 17.dp),
                            )
                            androidx.compose.foundation.text.BasicTextField(
                                value = word,
                                onValueChange = { value ->
                                    currentIndex = index
                                    words = SeedRestorePresentation.edit(words, index, value)
                                },
                                modifier = Modifier.weight(1f)
                                    .testTag("restore_word_${index + 1}")
                                    .semantics { contentDescription = "Recovery word ${index + 1}" },
                                textStyle = PyxType.seedWord.copy(color = PyxText),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
                                singleLine = true,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (validation?.invalidIndices?.isNotEmpty() == true) Text(
                "Not a valid BIP39 word", style = PyxType.rowSub, color = PyxRed,
                modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
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
            cash.pyx.app.ui.components.SeedWarnBanner(
                "Anyone with these 12 words can spend your funds. Write them down, store them offline, and never share or screenshot them.",
            )
            Spacer(Modifier.height(12.dp))
            cash.pyx.app.ui.components.SeedWordGrid(state.words)
            Spacer(Modifier.height(16.dp))
            PyxPrimaryButton("I've written down all 12 words", { reviewComplete = true }, Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Verify your backup", style = PyxType.rowTitle, color = PyxText)
            Text("Enter these words from your written copy.", style = PyxType.rowSub, color = PyxMuted,
                modifier = Modifier.padding(bottom = 8.dp))
            cash.pyx.app.security.SeedVerification.positions.forEachIndexed { answerIndex, wordIndex ->
                PyxField(
                    value = answers[answerIndex],
                    onValueChange = { value -> answers = answers.toMutableList().also { it[answerIndex] = value.take(32) } },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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

/**
 * Blurred stand-in for the seed grid before authentication. Renders numbered
 * placeholder bars — never real words — under a "Tap to reveal" scrim, so no
 * seed material exists in composition or semantics pre-auth.
 */
@Composable
private fun SeedPlaceholderGrid(onReveal: () -> Unit, enabled: Boolean) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().blur(7.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            repeat(6) { rowIndex ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(2) { columnIndex ->
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
                                Text("${rowIndex * 2 + columnIndex + 1}", style = PyxType.seedNum, color = PyxFaint)
                                Box(Modifier.height(12.dp).weight(0.7f).background(PyxSurface2, RoundedCornerShape(6.dp)))
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.matchParentSize()
                .background(PyxBackground.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                .clickable(enabled = enabled, onClickLabel = "Show recovery words") { onReveal() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PyxIcons.Eye, contentDescription = null, tint = PyxOrange, modifier = Modifier.size(24.dp))
                Text("Tap to reveal", style = PyxType.rowSub.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PyxText)
            }
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
    refreshReconciliation: () -> Unit,
    activityState: ActivityState,
    loadActivityPage: (Long, Boolean) -> Unit,
    openActivityDetail: (Long, String) -> Unit,
    dismissActivityDetail: () -> Unit,
    navigate: (WalletRoute) -> Unit,
) {
    val wallet = state.snapshot.selected
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var collapsed by remember { mutableStateOf(false) }
    var scrolledFar by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            collapsed = index > 0 || offset > 48
            // The scan FAB swaps for the jump-to-top pill once the balance card is gone.
            scrolledFar = index >= 2
        }
    }
    LaunchedEffect(wallet?.clientHandle) {
        wallet?.let { loadActivityPage(it.clientHandle, false) }
    }
    // Infinite scroll: request the next page whenever the viewport nears the list
    // tail. ActivityStateOwner drops redundant calls while loading or exhausted.
    LaunchedEffect(listState, wallet?.clientHandle) {
        val client = wallet?.clientHandle ?: return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 4) loadActivityPage(client, false)
        }
    }
    val balanceText = HomePresentation.balanceText(wallet?.balanceSat ?: 0, balanceMasked)
    val balanceSemantics = if (balanceMasked) "Balance hidden" else "Balance $balanceText"
    // One text node whose full string stays exactly `balanceText`, with the
    // trailing unit rendered smaller and muted like the prototype amount.
    val styledBalance = if (balanceMasked) AnnotatedString(balanceText)
    else cash.pyx.app.ui.components.satAmountAnnotated(
        balanceText,
        unitStyle = SpanStyle(fontSize = 15.sp, color = PyxMuted),
        numberStyle = SpanStyle(fontSize = 31.sp),
    )
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
                    trailing = { PyxTextLink("Check status", refreshReconciliation) },
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
        if (wallet != null) {
            when {
                !activityState.initialized && activityState.error != null -> item(key = "activity_error") {
                    Column {
                        Text(activityState.error, style = PyxType.body, color = PyxRed,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        PyxTextLink("Retry", { loadActivityPage(wallet.clientHandle, true) })
                    }
                }
                !activityState.initialized -> item(key = "activity_loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = PyxOrange)
                        Text("Loading activity…", Modifier.padding(start = 12.dp), style = PyxType.body, color = PyxMuted)
                    }
                }
                activityState.payments.isEmpty() -> item(key = "activity_empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        cash.pyx.app.ui.components.IconTile(
                            PyxIcons.Transfers, tint = PyxMuted, size = 54.dp, cornerRadius = 16.dp, iconSize = 22.dp,
                        )
                        Text("No transaction history yet", style = PyxType.rowTitle, color = PyxMuted,
                            modifier = Modifier.padding(top = 14.dp))
                    }
                }
                else -> {
                    ActivityPresentation.group(activityState.payments).forEach { day ->
                        item(key = "day_${day.date}") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(ActivityPresentation.dayLabel(day.date), Modifier.padding(end = 14.dp))
                                Box(Modifier.weight(1f)) { PyxDivider() }
                            }
                        }
                        itemsIndexed(day.payments, key = { _, payment -> ActivityPresentation.itemKey(payment) }) { index, payment ->
                            PaymentRow(payment, balanceMasked, showDivider = index < day.payments.lastIndex) {
                                openActivityDetail(wallet.clientHandle, payment.operationId)
                            }
                        }
                    }
                    if (activityState.error != null) item(key = "activity_page_error") {
                        Text(activityState.error, style = PyxType.body, color = PyxRed,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    }
                    when {
                        activityState.loading -> item(key = "activity_paging") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = PyxOrange)
                            }
                        }
                        activityState.nextCursor == null -> item(key = "activity_end") {
                            Text("End of history", style = PyxType.rowSub, color = PyxFaint,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
                        }
                        else -> Unit
                    }
                }
            }
        }
        item(key = "home_bottom_spacer") { Spacer(Modifier.height(72.dp)) }
        }
        AnimatedVisibility(visible = collapsed, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = PyxSurface, border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(onClickLabel = "Back to top") { scrollScope.launch { listState.animateScrollToItem(0) } }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("balance_header_collapsed")
                        .semantics {
                            stateDescription = "Collapsed balance header"
                            contentDescription = balanceSemantics
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BtcBadge(size = 24.dp)
                    Text(balanceText, style = PyxType.keyValue, color = PyxText)
                }
            }
        }
        // The scan FAB and the jump-to-top pill share the bottom-centre slot.
        if (wallet != null && !scrolledFar) PyxFab(
            PyxIcons.Scan,
            onClick = scan,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            semanticsModifier = Modifier.semantics { text = AnnotatedString("Scan") },
        )
        if (wallet != null && scrolledFar) Surface(
            onClick = { scrollScope.launch { listState.animateScrollToItem(0) } },
            shape = RoundedCornerShape(999.dp),
            color = PyxSurface2,
            border = androidx.compose.foundation.BorderStroke(1.dp, cash.pyx.app.ui.theme.PyxBorderStrong),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(PyxIcons.ArrowUp, contentDescription = null, tint = PyxText, modifier = Modifier.size(14.dp))
                Text("Top", style = PyxType.rowSub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PyxText)
            }
        }
        PaymentDetailSheet(activityState.detail, balanceMasked, dismissActivityDetail)
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
    openDetails: () -> Unit,
    openLnaddr: () -> Unit,
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
    lnAddressStateOwner: LnAddressStateOwner? = null,
) {
    val selected = state.snapshot.selected
    // The lightning-address screen owns its own top bar/layout (its own "+" action, no
    // WalletOperation concept), so it renders standalone rather than through the generic
    // PyxTopBar + when(screen) body shared by the other management destinations below.
    if (screen == WalletRoute.LNADDR) {
        lnAddressStateOwner?.let {
            cash.pyx.app.ui.components.LnaddrListContent(
                it, state.snapshot.federations, selected?.clientHandle, state.factoryHandle,
                back = { clear(); back() },
            )
        }
        return
    }
    val lnAddressState = lnAddressStateOwner?.state?.collectAsStateWithLifecycle()?.value
    var input by remember { mutableStateOf("") }
    var confirmationRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
    val addressMutationGate = remember { ConfirmationActionGate() }
    val operationModalRoute = if (operation is WalletOperation.SuccessorInvite) WalletModalRoute.SUCCESSOR_REVIEW else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        PyxTopBar(
            screen.title,
            onBack = { clear(); back() },
            titleSemantics = Modifier.semantics { heading() },
            actions = {
                if (screen == WalletRoute.WALLETS) PyxIconButton(
                    PyxIcons.Plus, onClick = openJoin,
                    modifier = Modifier.semantics { text = AnnotatedString("Add a wallet") },
                )
            },
        )
        when (screen) {
            WalletRoute.WALLETS -> {
                Text(
                    "Switch between your wallets. Each wallet is held by a federation — you can keep several with the same provider.",
                    style = PyxType.rowSub, color = PyxMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                state.snapshot.federations.forEach { federation ->
                    val active = federation.id == selected?.federationId
                    // Federation group header: avatar, name, status (known for the
                    // selected federation only), chevron into federation details.
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = active, onClickLabel = "Federation details") { openDetails() }
                            .padding(top = 18.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        InitialAvatar(federation.name, size = 26.dp)
                        Text(federation.name, style = PyxType.rowSub.copy(fontFamily = PyxType.keyValue.fontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PyxText)
                        if (active && connection != null) Text(
                            if (connection.onlineCount >= connection.requiredCount) "Online" else "Offline",
                            style = PyxType.sectionLabel.copy(letterSpacing = 0.02.em),
                            color = if (connection.onlineCount >= connection.requiredCount) PyxGreen else cash.pyx.app.ui.theme.PyxBurnt,
                        )
                        Spacer(Modifier.weight(1f))
                        if (active) Icon(PyxIcons.ChevronRight, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(16.dp))
                    }
                    Surface(
                        color = PyxSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) PyxOrange else PyxBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !active, onClickLabel = "Switch to ${federation.name}") {
                                    submit("switch", state.factoryHandle, federation.id, 0)
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp),
                        ) {
                            cash.pyx.app.ui.components.IconTile(
                                PyxIcons.Wallet, tint = PyxOrange, size = 42.dp, cornerRadius = 12.dp, iconSize = 21.dp,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(federation.name, style = PyxType.rowTitle, color = PyxText)
                                Text(
                                    // Only the selected wallet's balance is known natively.
                                    if (active && selected != null) HomePresentation.balanceText(selected.balanceSat, false)
                                    else "${federation.guardianCount} guardians",
                                    style = PyxType.rowSub, color = PyxMuted,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            if (active) Box(
                                Modifier.size(24.dp).background(PyxOrange, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(PyxIcons.Check, contentDescription = "Active wallet", tint = cash.pyx.app.ui.theme.PyxOnOrange, modifier = Modifier.size(15.dp))
                            } else Icon(PyxIcons.ChevronRight, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            WalletRoute.DETAILS -> {
                selected?.let { wallet ->
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.border(1.dp, PyxBorder, androidx.compose.foundation.shape.CircleShape).padding(1.dp)) {
                            InitialAvatar(wallet.name, size = 122.dp)
                        }
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
                // Prototype loads details on navigation, not behind a link.
                LaunchedEffect(selected?.clientHandle) {
                    selected?.let { submit("details_connection", it.clientHandle, "", 0) }
                }
                SectionLabel("Modules")
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    // Joining validates exactly these module kinds, so the pills are accurate
                    // for any joined federation even though the API doesn't list modules.
                    listOf("On-Chain" to PyxIcons.Link, "Ecash" to PyxIcons.Transfers, "Lightning" to PyxIcons.Zap).forEach { (label, icon) ->
                        Row(
                            Modifier.background(PyxSurface, RoundedCornerShape(11.dp))
                                .border(1.dp, PyxBorder, RoundedCornerShape(11.dp))
                                .padding(start = 11.dp, end = 13.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = PyxOrange, modifier = Modifier.size(17.dp))
                            Text(label, style = PyxType.rowSub.copy(fontSize = 13.5.sp), color = PyxText)
                        }
                    }
                }
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
                    Text(
                        "${status.onlineCount} / ${status.totalCount} online",
                        style = PyxType.rowSub.copy(fontSize = 13.sp), color = PyxFaint,
                        modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        status.guardians.forEach { guardian ->
                            PyxCard(padding = 0.dp) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                                ) {
                                    InitialAvatar(guardian.name, size = 42.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(guardian.name, style = PyxType.rowTitle, color = PyxText)
                                        Text(
                                            if (guardian.connected) "Online" else "Offline",
                                            style = PyxType.rowSub,
                                            color = if (guardian.connected) PyxGreen else cash.pyx.app.ui.theme.PyxBurnt,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                    StatusDot(if (guardian.connected) PyxGreen else cash.pyx.app.ui.theme.PyxBurnt)
                                }
                            }
                        }
                    }
                    Text(
                        "Quorum required: ${status.requiredCount} of ${status.totalCount}",
                        style = PyxType.rowSub.copy(fontSize = 13.sp, letterSpacing = 0.04.em), color = PyxFaint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                } ?: Text("Guardian connection status is unavailable.", style = PyxType.body, color = PyxMuted)
            }
            WalletRoute.SETTINGS -> {
                SectionLabel("Wallet")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow(
                            "Wallets",
                            value = selected?.let { "${state.snapshot.federations.size} · ${it.name}" } ?: "${state.snapshot.federations.size}",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint,
                            chevron = true, onClick = openWallets,
                        )
                        PyxDivider()
                        CardRow("Default Currency", value = "SATS · ${state.snapshot.currencyCode}",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true, onClick = openCurrency)
                        PyxDivider()
                        CardRow("Backup", value = "Recovery words",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true, onClick = openSeedBackup)
                        PyxDivider()
                        CardRow("Manage contacts", chevron = true, onClick = openContacts)
                        PyxDivider()
                        val lnAddressPrimary = selected?.federationId?.let { federationId ->
                            lnAddressState?.addresses?.firstOrNull { it.federationId == federationId && it.isPrimary }
                        }
                        CardRow("Lightning address", value = lnAddressPrimary?.display ?: "Claim",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true, onClick = openLnaddr)
                    }
                }
                SectionLabel("Security")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Access Control", value = if (biometricEnabled) "Biometrics" else "Off",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true, onClick = openAccess)
                    }
                }
                SectionLabel("About")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Network", value = "Bitcoin", valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true)
                        PyxDivider()
                        CardRow("Version", value = "Pyx ${cash.pyx.app.BuildConfig.VERSION_NAME} (${cash.pyx.app.BuildConfig.VERSION_CODE})",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true)
                    }
                }
                SectionLabel("Troubleshooting")
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow("Export debug data", value = "Logs + wallet DB",
                            valueStyle = PyxType.settingsValue, valueColor = PyxFaint, chevron = true,
                            onClick = { submit("export_debug", 0, "", 0) })
                    }
                }
                Text(
                    "The export contains the wallet database and recent logs. Anyone with the file can spend this wallet's balance — share it only with someone you trust.",
                    style = PyxType.rowSub, color = PyxFaint,
                    modifier = Modifier.padding(top = 10.dp),
                )
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Row(Modifier.padding(top = 8.dp)) {
                    SettingsPresentation.links.forEach { link -> PyxTextLink(link.label, { uriHandler.openUri(link.url) }) }
                }
            }
            WalletRoute.CURRENCY -> {
                LaunchedEffect(Unit) { submit("load_currencies", 0, "", 0) }
                CurrencySearchField(input) { input = it.take(32) }
                if (currencySettingsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = PyxOrange, trackColor = PyxSurface2)
                FeatureMessageContent(currencySettingsState.message, clearCurrencyMessage)
                Spacer(Modifier.height(10.dp))
                val sections = CurrencyPickerPresentation.sections(input, currencySettingsState.currencies, includeBtcUnits = false)
                if (sections.empty && currencySettingsState.currencies.isNotEmpty()) Text(
                    "No currency matches \"$input\"",
                    style = PyxType.rowSub.copy(fontSize = 13.sp), color = PyxFaint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
                else Column {
                    sections.fiat.forEach { currency ->
                        CurrencyRow(
                            CurrencyPickerPresentation.flag(currency.code, currency.symbol),
                            currency.code, currency.name,
                            active = currency.code == state.snapshot.currencyCode,
                            saving = currencySettingsState.savingCode == currency.code,
                            enabled = currencySettingsState.savingCode == null,
                        ) { submit("currency", state.factoryHandle, currency.code, 0) }
                    }
                }
            }
            WalletRoute.ACCESS -> {
                Text(SettingsPresentation.biometricMessage(biometricAvailable, biometricEnabled), style = PyxType.body, color = PyxMuted,
                    modifier = Modifier.padding(bottom = 18.dp))
                SectionLabel("Method")
                PyxCard(padding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        cash.pyx.app.ui.components.IconTile(
                            PyxIcons.Fingerprint, tint = PyxOrange, size = 42.dp, cornerRadius = 12.dp, iconSize = 21.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Device biometrics", style = PyxType.rowTitle, color = PyxText)
                            Text("Fingerprint · face unlock", style = PyxType.rowSub, color = PyxMuted)
                        }
                        val (statusText, statusColor) = when {
                            biometricEnabled && biometricAvailable -> "Active" to PyxGreen
                            biometricEnabled -> "Unavailable" to PyxRed
                            else -> "Off" to PyxMuted
                        }
                        Text(statusText, style = PyxType.rowSub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = statusColor)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        CardRow(
                            "Biometric protection",
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
                Spacer(Modifier.height(18.dp))
                Surface(color = PyxSurface2, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Icon(PyxIcons.Info, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(20.dp))
                        Text(
                            "App PIN and password unlock are not available — a strong device biometric protects payments, ecash creation, federation removal, and recovery words.",
                            style = PyxType.rowSub, color = PyxMuted,
                        )
                    }
                }
            }
            WalletRoute.SEED_BACKUP -> {
                cash.pyx.app.ui.components.SeedWarnBanner(
                    "Anyone with these 12 words can spend your funds. Write them down, store them offline, and never share or screenshot them.",
                    Modifier.padding(bottom = 20.dp),
                )
                if (!(operation is WalletOperation.Success && operation.sensitive)) {
                    SeedPlaceholderGrid(
                        onReveal = { onBackup(state.factoryHandle) },
                        enabled = operation !is WalletOperation.Submitting,
                    )
                    Spacer(Modifier.height(20.dp))
                    PyxPrimaryButton(
                        "Show recovery words",
                        { onBackup(state.factoryHandle) },
                        Modifier.fillMaxWidth(),
                        enabled = operation !is WalletOperation.Submitting,
                    )
                }
            }
            WalletRoute.CONTACTS -> {
                val contacts = contactsState.contacts
                var name by remember { mutableStateOf("") }
                var lnurl by remember { mutableStateOf("") }
                var query by remember { mutableStateOf("") }
                var showForm by remember { mutableStateOf(false) }
                var editing by remember { mutableStateOf<cash.pyx.app.nativeapi.Contact?>(null) }
                var viewing by remember { mutableStateOf<cash.pyx.app.nativeapi.Contact?>(null) }
                var delete by remember { mutableStateOf<cash.pyx.app.nativeapi.Contact?>(null) }
                var contactModalRoute by remember { mutableStateOf<WalletModalRoute?>(null) }
                var showValidation by remember { mutableStateOf(false) }
                LaunchedEffect(state.factoryHandle) { submit("load_contacts", state.factoryHandle, "", 0) }
                if (contactsState.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PyxOrange, trackColor = PyxSurface2)
                FeatureMessageContent(contactsState.message, clearContactsMessage)
                val validation = ContactsPresentation.validate(name, lnurl, contacts, editing?.lnurl)
                PyxField(query, { query = it.take(256) }, Modifier.fillMaxWidth(), label = { Text("Search contacts") }, singleLine = true)
                if (showForm) {
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
                            showForm = false; editing = null; name = ""; lnurl = ""; showValidation = false
                        }
                    })
                    PyxTextLink("Cancel", { showForm = false; editing = null; name = ""; lnurl = ""; showValidation = false })
                }
                }
                val visibleContacts = ContactsPresentation.filter(contacts, query)
                SectionLabel("Contacts")
                when {
                    contacts.isEmpty() -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No contacts yet", style = PyxType.rowTitle, color = PyxMuted)
                        Text("Save a Lightning address to pay it again quickly", style = PyxType.rowSub, color = PyxFaint,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    visibleContacts.isEmpty() -> Text(
                        "No contacts match your search", style = PyxType.rowSub, color = PyxFaint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visibleContacts.forEach { contact ->
                            PyxCard(padding = 0.dp) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(onClickLabel = "Contact details") { viewing = contact }
                                        .padding(15.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    InitialAvatar(contact.name, size = 40.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(contact.name, style = PyxType.rowTitle, color = PyxText)
                                        Text(contact.lnurl, style = PyxType.inputMono.copy(fontSize = 13.sp), color = PyxMuted,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    Icon(PyxIcons.ChevronRight, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                if (!showForm) PyxGhostButton(
                    "Add contact", { showForm = true; editing = null; name = ""; lnurl = ""; showValidation = false },
                    Modifier.fillMaxWidth().padding(top = 12.dp), icon = PyxIcons.Plus,
                )
                viewing?.let { contact ->
                    ContactDetailSheet(
                        contact = contact,
                        mutationLocked = contactsState.mutation != null,
                        onPay = { viewing = null; sendContact(contact.lnurl) },
                        onEdit = {
                            viewing = null; showForm = true; editing = contact
                            name = contact.name; lnurl = contact.lnurl; showValidation = false
                        },
                        onDelete = { viewing = null; delete = contact; contactModalRoute = WalletModalRoute.CONTACT_DELETE },
                        dismiss = { viewing = null },
                    )
                }
                if (contactModalRoute == WalletModalRoute.CONTACT_DELETE && delete != null) AlertDialog(onDismissRequest = { delete = null; contactModalRoute = null }, title = { Text("Delete contact?") },
                    text = { Text("Remove ${delete!!.name}? This does not send or move any funds.") },
                    confirmButton = { Button(onClick = { submit("delete_contact", state.factoryHandle, delete!!.lnurl, 0); delete = null; contactModalRoute = null }) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = { delete = null; contactModalRoute = null }) { Text("Cancel") } })
            }
            WalletRoute.ADDRESSES -> {
                LaunchedEffect(selected?.clientHandle) { selected?.let { submit("load_addresses", it.clientHandle, "", 0) } }
                val addressContext = androidx.compose.ui.platform.LocalContext.current
                var copyAnnouncement by remember { mutableStateOf<String?>(null) }
                val currentTweak = addresses.maxByOrNull { it.tweakIndex }?.tweakIndex
                if (addresses.isEmpty()) Column(
                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(PyxIcons.QrGlyph, contentDescription = null, tint = PyxFaint.copy(alpha = 0.55f), modifier = Modifier.size(48.dp))
                    Text("No on-chain addresses yet", style = PyxType.rowSub, color = PyxMuted, modifier = Modifier.padding(top = 12.dp))
                }
                else PyxCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 15.dp)) {
                        addresses.forEachIndexed { index, address ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(onClickLabel = "Copy address") {
                                        copyAnnouncement = QrPayload.copy(addressContext, "On-chain address", address.address, sensitive = false).announcement
                                    }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                cash.pyx.app.ui.components.IconTile(
                                    PyxIcons.Link, tint = PyxMuted, size = 38.dp, cornerRadius = 11.dp, iconSize = 18.dp,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(address.address, style = PyxType.inputMono.copy(fontSize = 13.sp), color = PyxText,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(
                                        if (address.tweakIndex == currentTweak) "Index ${address.tweakIndex} · Shown on Receive"
                                        else "Index ${address.tweakIndex}",
                                        style = PyxType.rowSub, color = PyxFaint,
                                    )
                                }
                                Icon(PyxIcons.Copy, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(14.dp))
                                PyxTextLink("Recheck", {
                                    selected?.let { wallet ->
                                        if (addressMutationGate.request { submit("recheck_address", wallet.clientHandle, "", address.tweakIndex) }) {
                                            confirmationRoute = WalletModalRoute.ADDRESS_MUTATION
                                        }
                                    }
                                })
                            }
                            if (index != addresses.lastIndex) PyxDivider()
                        }
                    }
                }
                cash.pyx.app.ui.components.AnnouncementText(copyAnnouncement, Modifier.padding(top = 6.dp))
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
        when (operation) {
            WalletOperation.Submitting -> CircularProgressIndicator()
            is WalletOperation.Success -> SensitiveResult(operation, operation.sensitive, clear)
            is WalletOperation.Failure -> Text(operation.message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            else -> Unit
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

/** Prototype tx-detail bottom sheet: centred head, status pill, drow cards, wrench-gated technical rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentDetailSheet(detail: ActivityDetailState, masked: Boolean, dismiss: () -> Unit) {
    if (detail is ActivityDetailState.Closed) return
    cash.pyx.app.ui.components.PyxSheet(
        onDismiss = dismiss,
        modifier = Modifier.testTag("payment_detail_sheet"),
    ) {
        when (detail) {
            is ActivityDetailState.Loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = PyxOrange, trackColor = PyxSurface2) }
            is ActivityDetailState.Error -> Text(
                detail.message, style = PyxType.body, color = PyxRed,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
            is ActivityDetailState.Open -> PaymentDetailSheetContent(detail.payment, masked)
            ActivityDetailState.Closed -> Unit
        }
    }
}

@Composable
private fun PaymentDetailSheetContent(payment: Payment, masked: Boolean) {
    val fields = ActivityPresentation.technicalFields(payment)
    var showTech by remember(payment.operationId) { mutableStateOf(false) }
    if (showTech && fields.any { it.sensitive }) SecureScreen()
    val context = androidx.compose.ui.platform.LocalContext.current
    val incoming = payment.direction == cash.pyx.app.nativeapi.PaymentDirection.INCOMING
    val (statusLabel, statusColor) = when (payment.status) {
        cash.pyx.app.nativeapi.PaymentStatus.SUCCEEDED -> "Confirmed" to PyxGreen
        cash.pyx.app.nativeapi.PaymentStatus.PENDING -> "Pending" to PyxAmber
        cash.pyx.app.nativeapi.PaymentStatus.FAILED -> "Failed" to cash.pyx.app.ui.theme.PyxBurnt
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
        Box(Modifier.fillMaxWidth()) {
            PyxIconButton(
                PyxIcons.Wrench,
                onClick = { showTech = !showTech },
                tint = if (showTech) PyxOrange else PyxText,
                borderColor = if (showTech) PyxOrange else PyxBorder,
                modifier = Modifier.align(Alignment.TopEnd).semantics {
                    text = AnnotatedString("Technical details")
                    stateDescription = if (showTech) "Shown" else "Hidden"
                },
            )
            Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                cash.pyx.app.ui.components.IconTile(
                    if (incoming) PyxIcons.ArrowDown else PyxIcons.ArrowUp, tint = if (incoming) PyxGreen else PyxMuted, size = 54.dp, cornerRadius = 16.dp, iconSize = 22.dp,
                )
                Text(
                    "${paymentTypeLabel(payment.type)} ${if (incoming) "received" else "sent"}",
                    style = PyxType.centeredTitle, color = PyxText, modifier = Modifier.padding(top = 12.dp),
                )
                // One text node whose full string stays the amount exactly, with the
                // trailing unit rendered small and muted like the prototype big-amt.
                val amountText = if (masked) "•••" else ActivityPresentation.amount(payment)
                val styledAmount = cash.pyx.app.ui.components.satAmountAnnotated(
                    amountText, unitStyle = SpanStyle(fontSize = 16.sp, color = PyxMuted),
                )
                Text(
                    styledAmount,
                    style = PyxType.bigAmount,
                    color = if (incoming) PyxGreen else PyxRed,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!masked) ActivityPresentation.historicalFiat(payment)?.let {
                    Text(it, style = PyxType.fiat, color = PyxFaint, modifier = Modifier.padding(top = 2.dp))
                }
                Row(
                    Modifier.padding(top = 10.dp)
                        .background(PyxSurface2, RoundedCornerShape(8.dp))
                        .border(1.dp, PyxBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    StatusDot(statusColor)
                    Text(statusLabel, style = PyxType.rowSub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = PyxText)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PyxCard(padding = 0.dp) { Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            DetailSheetRow("Date", ActivityPresentation.timestamp(payment))
            payment.feeSat?.let { PyxDivider(); DetailSheetRow("Fee", if (masked) "•••" else "$it sats") }
        } }
        if (showTech) {
            var announcement by remember { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(12.dp))
            PyxCard(padding = 0.dp) { Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text("Technical details".uppercase(), style = PyxType.sectionLabel, color = PyxFaint,
                    modifier = Modifier.padding(top = 12.dp).semantics { text = AnnotatedString("Technical details") })
                fields.forEach { field ->
                    PyxDivider()
                    if (field.copyable) {
                        DetailSheetRow(field.label, field.value, mono = true) {
                            announcement = QrPayload.copy(context, field.label, field.value, sensitive = false).announcement
                        }
                    } else {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(field.label.uppercase(), style = PyxType.sectionLabel, color = PyxFaint,
                                modifier = Modifier.semantics { text = AnnotatedString(field.label) })
                            Text(field.value, style = PyxType.rowSub.copy(fontFamily = PyxType.inputMono.fontFamily), color = PyxText,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                PyxDivider()
                DetailSheetRow("Operation ID", payment.operationId, mono = true) {
                    announcement = QrPayload.copy(context, "Operation ID", payment.operationId, sensitive = false).announcement
                }
            } }
            cash.pyx.app.ui.components.AnnouncementText(announcement, Modifier.padding(top = 8.dp))
        }
    }
}

/** Contact bottom sheet: avatar head, copyable address drow, then Pay / Edit / Delete actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailSheet(
    contact: cash.pyx.app.nativeapi.Contact,
    mutationLocked: Boolean,
    onPay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var announcement by remember(contact.lnurl) { mutableStateOf<String?>(null) }
    cash.pyx.app.ui.components.PyxSheet(
        onDismiss = dismiss,
        modifier = Modifier.testTag("contact_detail_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                InitialAvatar(contact.name, size = 54.dp)
                Text(contact.name, style = PyxType.centeredTitle, color = PyxText, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(16.dp))
            PyxCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    DetailSheetRow("Lightning address", contact.lnurl, mono = true) {
                        announcement = QrPayload.copy(context, "Lightning address", contact.lnurl, sensitive = false).announcement
                    }
                }
            }
            cash.pyx.app.ui.components.AnnouncementText(announcement, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            PyxPrimaryButton("Pay", onPay, Modifier.fillMaxWidth())
            PyxGhostButton("Edit name", onEdit, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !mutationLocked)
            PyxGhostButton("Delete contact", onDelete, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !mutationLocked, textColor = PyxRed)
        }
    }
}

/** One prototype "drow": small uppercase key, right-aligned value; tappable copy when [onCopy] set. */
@Composable
private fun DetailSheetRow(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onCopy != null) it.clickable(onClickLabel = "Copy $label") { onCopy() } else it }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = PyxType.sectionLabel, color = PyxFaint,
            modifier = Modifier.semantics { text = AnnotatedString(label) })
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = if (mono) PyxType.inputMono.copy(fontSize = 13.sp) else PyxType.keyValue,
            color = PyxText,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            Icon(PyxIcons.Copy, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(14.dp))
        }
    }
}

private fun paymentTypeLabel(type: cash.pyx.app.nativeapi.PaymentType): String = when (type) {
    cash.pyx.app.nativeapi.PaymentType.LIGHTNING -> "Lightning"
    cash.pyx.app.nativeapi.PaymentType.ONCHAIN -> "On-chain"
    cash.pyx.app.nativeapi.PaymentType.ECASH -> "Ecash"
}

@Composable
private fun JoinContent(
    factoryHandle: Long,
    federationCount: Int,
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // A successful join grows the federation list; leave the form once it lands.
    val initialFederationCount = remember { federationCount }
    LaunchedEffect(federationCount) {
        if (federationCount > initialFederationCount) back()
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        PyxTopBar(if (recover) "Recover federation" else "Join federation", onBack = { invite = ""; back() })
        PyxSegmented(
            listOf("Join new", "Recover existing"),
            if (recover) 1 else 0,
            { index -> recover = index == 1 },
        )
        Spacer(Modifier.height(16.dp))
        if (invite.isBlank()) {
            // Scan-first, like the prototype's "Scan a federation invite" sheet.
            cash.pyx.app.ui.components.ScanPromptFrame("Scan a federation invite", onClickLabel = "Scan a federation invite", onClick = scan)
            Spacer(Modifier.height(16.dp))
            PyxGhostButton(
                "Paste from clipboard",
                {
                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                    if (!clip.isNullOrBlank() && clip.length <= 16 * 1024) invite = clip
                },
                Modifier.fillMaxWidth(),
                icon = PyxIcons.Copy,
            )
            Spacer(Modifier.height(8.dp))
        } else PyxTextLink("Scan a different code", scan)
        PyxField(
            value = invite,
            onValueChange = { if (it.length <= 16 * 1024) invite = it },
            modifier = Modifier.fillMaxWidth().testTag("federation_invite"),
            label = { Text("Or paste a federation invite") },
            minLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        PyxPrimaryButton(
            if (operation is WalletOperation.Submitting) "Working…" else "Continue",
            { modalRoute = WalletModalRoute.JOIN_CONFIRMATION },
            Modifier.fillMaxWidth(),
            enabled = invite.isNotBlank() && operation !is WalletOperation.Submitting,
        )
        if (operation is WalletOperation.Failure) Text(operation.message, style = PyxType.body, color = PyxRed,
            modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive })
    }
    if (modalRoute == WalletModalRoute.JOIN_CONFIRMATION) AlertDialog(onDismissRequest = { modalRoute = null }, title = { Text(if (recover) "Confirm recovery" else "Confirm join") },
        text = { Column {
            Text(
                if (recover) "Recover an existing wallet — this restores history and may take a while."
                else "Join as a new wallet. Only continue if you trust the federation invite source.",
            )
            Text(
                invite.take(40) + if (invite.length > 40) "…" else "",
                style = PyxType.inputMono.copy(fontSize = 13.sp), color = PyxMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        } },
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
    if (secure) SecureScreen()
    PyxCard(Modifier.padding(top = 16.dp), padding = 16.dp) {
        Text(result.title, style = PyxType.rowTitle, color = PyxText)
        Spacer(Modifier.height(8.dp))
        when {
            secure && result.title == "Recovery words" ->
                cash.pyx.app.ui.components.SeedWordGrid(result.detail.split(' '))
            secure -> Surface(
                color = PyxRed.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PyxRed.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(result.detail, style = PyxType.keyValue, color = PyxText, modifier = Modifier.padding(13.dp))
            }
            else -> SelectionContainer { Text(result.detail, style = PyxType.inputMono, color = PyxText) }
        }
        if (!secure) Row {
            ClipboardCopyButton(context, result.title, result.detail, sensitive = false, buttonLabel = "Copy")
            if (result.shareable && PlatformUtilityPolicy.canShare(result.sensitive)) PyxTextLink("Share", {
                context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(result.detail, sensitive = false), "Share"))
            })
        }
        if (secure && result.title == "Recovery words") PyxGhostButton(
            "Copy recovery words",
            { modalRoute = WalletModalRoute.SEED_COPY_WARNING },
            Modifier.fillMaxWidth().padding(top = 16.dp),
            icon = PyxIcons.Copy,
        )
        copyAnnouncement?.let {
            Text(it, style = PyxType.rowSub, color = PyxMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (secure) {
            Spacer(Modifier.height(10.dp))
            PyxPrimaryButton("I've written it down", dismiss, Modifier.fillMaxWidth())
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
        cash.pyx.app.ui.components.AnnouncementText(announcement)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class TransferAmountUnit { SATS, BTC, FIAT }

/**
 * Prototype receive screen: big live amount + unit chip, and a code that is always
 * generated for the current state — reusable LNURL when amountless, a BOLT11
 * invoice one second after typing stops, the reusable on-chain address (BIP21 once
 * an amount is set). Ecash is received by scanning, so that tab leads with the scanner.
 */
@Composable
private fun ReceiveContent(
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
    addresses: List<cash.pyx.app.nativeapi.OnchainAddress>,
    scan: () -> Unit,
    currencySettingsState: cash.pyx.app.data.CurrencySettingsState = cash.pyx.app.data.CurrencySettingsState(),
) {
    val client = state.snapshot.selected?.clientHandle ?: return
    var pendingFiat by remember { mutableStateOf<String?>(null) }
    var tab by remember(initialPayload, initialType) {
        mutableIntStateOf(if (initialType == cash.pyx.app.nativeapi.InputType.ECASH) 2 else 0)
    }
    var ecashToken by remember(initialPayload) {
        mutableStateOf(if (initialType == cash.pyx.app.nativeapi.InputType.ECASH) initialPayload.orEmpty() else "")
    }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(TransferAmountUnit.SATS) }
    var unitSheet by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var convertedSats by remember { mutableStateOf<Long?>(null) }
    val currentOperation by rememberUpdatedState(operation)
    val currentAddresses by rememberUpdatedState(addresses)
    LaunchedEffect(initialPayload, initialType) { clear() }
    // Debounced generation, prototype-style: the reusable LNURL is requested as soon
    // as Lightning is amountless; an invoice is requested one second after the last
    // keystroke (fiat amounts convert to sats first, then chain into the invoice).
    LaunchedEffect(tab, amount, unit, client) {
        typing = false
        if (tab == 2) return@LaunchedEffect
        if (amount.isBlank()) {
            convertedSats = null
            val existing = currentOperation
            if (tab == 0 &&
                !(existing is WalletOperation.Success && existing.title == "LNURL receive") &&
                existing !is WalletOperation.Submitting
            ) {
                clear(); submit("receive_lnurl", client, "", 0)
            }
            return@LaunchedEffect
        }
        // On-chain BIP21 with a sats/BTC amount derives locally; only fiat needs a round-trip.
        if (tab == 1 && unit != TransferAmountUnit.FIAT) return@LaunchedEffect
        if (tab == 0) typing = true
        convertedSats = null
        delay(1000)
        typing = false
        when (unit) {
            TransferAmountUnit.FIAT ->
                if (amount.toBigDecimalOrNull()?.signum() == 1) { clear(); submit("fiat_to_sats", client, amount, 0) }
            TransferAmountUnit.BTC ->
                BitcoinAmountPresentation.toSats(amount)?.takeIf { it > 0 }?.let { clear(); submit("receive_lightning", client, "", it) }
            TransferAmountUnit.SATS ->
                amount.toLongOrNull()?.takeIf { it > 0 }?.let { clear(); submit("receive_lightning", client, "", it) }
        }
    }
    // The reusable address is fetched once: the newest known address is shown
    // instantly, and one is generated only when the wallet has none yet.
    LaunchedEffect(tab, client) {
        if (tab != 1) return@LaunchedEffect
        val existing = currentOperation
        val known = currentAddresses.isNotEmpty() ||
            (existing is WalletOperation.Success && existing.title == "On-chain address")
        if (!known && existing !is WalletOperation.Submitting) { clear(); submit("receive_onchain", client, "", 0) }
    }
    LaunchedEffect(operation) {
        val converted = operation as? WalletOperation.FiatConverted ?: return@LaunchedEffect
        if (amount.isNotBlank() && unit == TransferAmountUnit.FIAT) {
            convertedSats = converted.amountSat
            if (tab == 0 && converted.amountSat > 0) submit("receive_lightning", client, "", converted.amountSat)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        PyxTopBar("Receive Bitcoin", onBack = back)
        cash.pyx.app.ui.components.AssetPill()
        Spacer(Modifier.height(14.dp))
        PyxSegmented(listOf("Lightning", "On-Chain", "Ecash"), tab, { index -> tab = index; amount = ""; convertedSats = null; clear() })
        if (tab == 2) {
            EcashReceivePane(client, operation, ecashToken, { ecashToken = it }, submit, clear, scan, ecashFrame, startEcashDisplay, stopEcashDisplay)
        } else {
            // recv-amt-row: borderless display-type amount with the unit chip beside it.
            Spacer(Modifier.height(30.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = amount,
                    onValueChange = { value ->
                        amount = if (unit == TransferAmountUnit.SATS) value.filter(Char::isDigit)
                        else value.filter(BitcoinAmountPresentation::inputCharacterAllowed)
                    },
                    modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 34.dp).testTag("receive_amount"),
                    textStyle = PyxType.bigAmount.copy(color = PyxText, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (unit == TransferAmountUnit.SATS) androidx.compose.ui.text.input.KeyboardType.Number
                        else androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (amount.isEmpty()) Text("0", style = PyxType.bigAmount, color = PyxFaint)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier
                        .background(PyxSurface2, RoundedCornerShape(10.dp))
                        .border(1.dp, PyxBorder, RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = "Change unit") { unitSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        when (unit) {
                            TransferAmountUnit.SATS -> "SATS"
                            TransferAmountUnit.BTC -> "BTC"
                            TransferAmountUnit.FIAT -> state.snapshot.currencyCode
                        },
                        style = PyxType.keyValue.copy(fontSize = 14.sp), color = PyxMuted,
                    )
                    Icon(PyxIcons.ChevronDown, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(13.dp))
                }
            }
            // sats equivalent under the amount; reserved height so the QR never jumps
            Text(
                when {
                    unit == TransferAmountUnit.BTC && amount.isNotBlank() ->
                        BitcoinAmountPresentation.toSats(amount)?.let { "≈ ${LocalePresentation.integer(it)} sats" } ?: " "
                    unit == TransferAmountUnit.FIAT && amount.isNotBlank() ->
                        convertedSats?.let { "≈ ${LocalePresentation.integer(it)} sats" } ?: " "
                    else -> " "
                },
                style = PyxType.fiat, color = PyxMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp),
            )
            val success = operation as? WalletOperation.Success
            val latestAddress = success?.takeIf { it.title == "On-chain address" }?.detail?.substringBefore("\n")
                ?: addresses.maxByOrNull { it.tweakIndex }?.address
            val hasAmount = amount.isNotBlank()
            val payload: String? = when {
                typing -> null
                tab == 0 -> success?.takeIf {
                    if (hasAmount) it.title == "Lightning invoice" else it.title == "LNURL receive"
                }?.detail?.substringBefore("\n")
                else -> latestAddress?.let { addr ->
                    val sats = when (unit) {
                        TransferAmountUnit.SATS -> amount.toLongOrNull()
                        TransferAmountUnit.BTC -> BitcoinAmountPresentation.toSats(amount)
                        TransferAmountUnit.FIAT -> convertedSats
                    }
                    if (sats != null && sats > 0) Bip21Presentation.uri(addr, sats) else addr
                }
            }
            ReceiveQrZone(
                payload = payload,
                loading = operation is WalletOperation.Submitting,
                bolt = tab == 0,
                hint = if (operation is WalletOperation.Submitting) "Generating…" else null,
            )
            payload?.let { CodeField(it) }
            // faint centred note describing the shown code
            val note: String? = when {
                operation is WalletOperation.Failure -> null // rendered separately in red
                operation is WalletOperation.Submitting -> "Generating…"
                typing -> "Pause typing to generate"
                tab == 0 && payload != null && !hasAmount -> "Reusable code — the sender chooses the amount"
                tab == 1 && payload != null && !hasAmount -> "Reusable address — pay any amount to your wallet"
                else -> null
            }
            success?.expiresAtEpochSeconds?.takeIf { tab == 0 && payload != null }?.let { expiry ->
                val nowMillis by produceState(System.currentTimeMillis(), expiry) {
                    while (value.floorDiv(1_000) < expiry) {
                        delay(1_000)
                        value = System.currentTimeMillis()
                    }
                }
                val expired = LightningInvoiceExpiryPresentation.remainingSeconds(expiry, nowMillis) == 0L
                Text(
                    LightningInvoiceExpiryPresentation.text(expiry, nowMillis),
                    style = PyxType.rowSub, color = if (expired) PyxRed else PyxFaint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics {
                        stateDescription = if (expired) "Invoice expired" else "Invoice active"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            note?.let {
                Text(it, style = PyxType.rowSub, color = PyxFaint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = if (success?.expiresAtEpochSeconds != null) 4.dp else 16.dp))
            }
            if (operation is WalletOperation.Failure) Text(
                operation.message, style = PyxType.rowSub, color = PyxRed,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
    LaunchedEffect(unitSheet) { if (unitSheet) submit("load_currencies", 0, "", 0) }
    LaunchedEffect(state.snapshot.currencyCode, pendingFiat) {
        // A newly picked default currency lands asynchronously; adopt it once saved.
        if (pendingFiat != null && state.snapshot.currencyCode == pendingFiat) {
            unit = TransferAmountUnit.FIAT; amount = ""; convertedSats = null; pendingFiat = null; unitSheet = false
        }
    }
    if (unitSheet) CurrencyPickerSheet(
        title = "Default currency",
        currencies = currencySettingsState.currencies,
        activeCode = when (unit) {
            TransferAmountUnit.SATS -> "SATS"
            TransferAmountUnit.BTC -> "BTC"
            TransferAmountUnit.FIAT -> state.snapshot.currencyCode
        },
        savingCode = currencySettingsState.savingCode,
        includeBtcUnits = true,
        onPick = { code ->
            when (code) {
                "SATS" -> { unit = TransferAmountUnit.SATS; amount = ""; convertedSats = null; clear(); unitSheet = false }
                "BTC" -> { unit = TransferAmountUnit.BTC; amount = ""; convertedSats = null; clear(); unitSheet = false }
                state.snapshot.currencyCode -> { unit = TransferAmountUnit.FIAT; amount = ""; convertedSats = null; clear(); unitSheet = false }
                else -> { pendingFiat = code; submit("currency", state.factoryHandle, code, 0) }
            }
        },
        dismiss = { unitSheet = false },
    )
}

/** Prototype search box: surface-2 fill, leading search icon, placeholder-only. */
@Composable
private fun CurrencySearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(PyxSurface2, RoundedCornerShape(12.dp))
            .border(1.dp, PyxBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(PyxIcons.Search, contentDescription = null, tint = PyxFaint, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            textStyle = PyxType.body.copy(color = PyxText),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search currency or code", style = PyxType.body, color = PyxFaint)
                    inner()
                }
            },
        )
    }
}

/** One currency/unit row: flag or ₿ glyph, code column, name, active check or saving spinner. */
@Composable
private fun CurrencyRow(
    flag: String?,
    code: String,
    name: String,
    active: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) PyxSurface2 else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (flag == null) Text("₿", style = PyxType.keyValue.copy(fontSize = 18.sp), color = PyxOrange)
            else Text(flag, style = PyxType.body.copy(fontSize = 19.sp))
        }
        Text(code, style = PyxType.keyValue.copy(fontSize = 14.5.sp), color = PyxText, modifier = Modifier.widthIn(min = 50.dp))
        Text(name, style = PyxType.rowSub.copy(fontSize = 13.5.sp), color = PyxMuted,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            saving -> CircularProgressIndicator(Modifier.size(20.dp), color = PyxOrange)
            active -> Icon(PyxIcons.Check, contentDescription = "Selected", tint = PyxOrange, modifier = Modifier.size(18.dp))
        }
    }
}

/** Prototype currency picker sheet: search, pinned BTC units, flagged fiat list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPickerSheet(
    title: String,
    currencies: List<cash.pyx.app.nativeapi.FiatCurrency>,
    activeCode: String,
    savingCode: String?,
    includeBtcUnits: Boolean,
    onPick: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    cash.pyx.app.ui.components.PyxSheet(
        onDismiss = dismiss,
        modifier = Modifier.testTag("currency_picker_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text(title, style = PyxType.centeredTitle.copy(fontSize = 18.sp), color = PyxText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            CurrencySearchField(query) { query = it.take(32) }
            Spacer(Modifier.height(10.dp))
            val sections = CurrencyPickerPresentation.sections(query, currencies, includeBtcUnits)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(sections.btcUnits.size, key = { "unit_${sections.btcUnits[it].code}" }) { index ->
                    val unit = sections.btcUnits[index]
                    CurrencyRow(null, unit.code, unit.name, active = activeCode == unit.code, saving = false, enabled = savingCode == null) {
                        onPick(unit.code)
                    }
                }
                if (sections.separator) item(key = "separator") {
                    Box(Modifier.padding(vertical = 6.dp)) { PyxDivider() }
                }
                items(sections.fiat.size, key = { "fiat_${sections.fiat[it].code}" }) { index ->
                    val currency = sections.fiat[index]
                    CurrencyRow(
                        CurrencyPickerPresentation.flag(currency.code, currency.symbol),
                        currency.code, currency.name,
                        active = activeCode == currency.code,
                        saving = savingCode == currency.code,
                        enabled = savingCode == null,
                    ) { onPick(currency.code) }
                }
                if (sections.empty) item(key = "empty") {
                    Text(
                        "No currency matches \"$query\"",
                        style = PyxType.rowSub.copy(fontSize = 13.sp), color = PyxFaint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/** White prototype QR panel: bolt badge for Lightning, dashed placeholder or spinner otherwise. */
@Composable
private fun ReceiveQrZone(payload: String?, loading: Boolean, bolt: Boolean, hint: String?) {
    if (loading || payload == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .background(PyxSurface, RoundedCornerShape(18.dp))
                .dashedBorder(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                if (loading) CircularProgressIndicator(color = PyxOrange, trackColor = PyxSurface2)
                else Icon(PyxIcons.QrGlyph, contentDescription = null, tint = PyxFaint.copy(alpha = 0.55f), modifier = Modifier.size(48.dp))
                hint?.let { Text(it, style = PyxType.rowSub, color = PyxFaint) }
            }
        }
        return
    }
    val bitmap = remember(payload) { runCatching { QrPayload.encode(payload) }.getOrNull() } ?: return
    Box(Modifier.fillMaxWidth()) {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Image(bitmap.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(18.dp))
        }
        if (bolt) Box(
            Modifier.align(Alignment.Center).size(50.dp)
                .border(6.dp, Color.White, RoundedCornerShape(15.dp))
                .padding(3.dp)
                .background(PyxOrange, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PyxIcons.Zap, contentDescription = null, tint = cash.pyx.app.ui.theme.PyxOnOrange, modifier = Modifier.size(22.dp))
        }
    }
}

private fun Modifier.dashedBorder() = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = cash.pyx.app.ui.theme.PyxBorderStrong,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
        )
    },
)

/** Prototype code-field: truncated tap-to-copy code with inline copy and share actions. */
@Composable
private fun CodeField(payload: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var announcement by remember(payload) { mutableStateOf<String?>(null) }
    val copyPayload = { announcement = QrPayload.copy(context, "Pyx receive code", payload, sensitive = false).announcement }
    Surface(
        color = PyxSurface, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PyxBorder),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                payload, style = PyxType.inputMono.copy(fontSize = 14.sp), color = PyxText,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClickLabel = "Copy") { copyPayload() },
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 1.dp, height = 28.dp).background(PyxBorder))
            IconButton(onClick = copyPayload, modifier = Modifier.semantics { text = AnnotatedString("Copy") }) {
                Icon(PyxIcons.Copy, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { context.startActivity(android.content.Intent.createChooser(QrPayload.shareIntent(payload, false), "Share")) },
                modifier = Modifier.semantics { text = AnnotatedString("Share") },
            ) {
                Icon(PyxIcons.Share, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
    cash.pyx.app.ui.components.AnnouncementText(announcement, Modifier.padding(top = 6.dp))
}

/** Ecash arrives as an animated QR from the sender, so receiving leads with the scanner. */
@Composable
private fun EcashReceivePane(
    client: Long,
    operation: WalletOperation,
    token: String,
    onToken: (String) -> Unit,
    submit: (String, Long, String, Long) -> Unit,
    clear: () -> Unit,
    scan: () -> Unit,
    ecashFrame: String?,
    startEcashDisplay: (String, Boolean) -> Unit,
    stopEcashDisplay: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    // Like the prototype's claim stage, a result replaces the scanner entirely.
    if (operation is WalletOperation.Success) {
        PyxCard(padding = 16.dp) {
            Column {
                Text(operation.title, style = PyxType.rowTitle, color = PyxText)
                if (operation.title == "Ecash token") EcashQrResult(operation.detail.substringBefore("\n"), ecashFrame, startEcashDisplay, stopEcashDisplay)
                else SelectionContainer { Text(operation.detail, style = PyxType.body, color = PyxGreen) }
            }
        }
        return
    }
    // scan-frame: dark viewfinder with accent corner brackets, tap to open the scanner
    cash.pyx.app.ui.components.ScanPromptFrame("Point at the sender's Ecash QR", onClickLabel = "Scan ecash", onClick = scan)
    Spacer(Modifier.height(16.dp))
    PyxField(
        value = token, onValueChange = { onToken(it); clear() },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Or paste an ecash token") }, minLines = 2,
        enabled = operation !is WalletOperation.Submitting,
    )
    PyxPrimaryButton(
        if (operation is WalletOperation.Submitting) "Submitting…" else "Claim ecash",
        { submit("claim_ecash", client, token, 0) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = token.isNotBlank() && operation !is WalletOperation.Submitting,
    )
    if (operation is WalletOperation.Failure) Text(operation.message, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive })
}

@Composable
private fun TransferContent(
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
    contactsState: cash.pyx.app.data.ContactsFeatureState = cash.pyx.app.data.ContactsFeatureState(),
    scan: () -> Unit = {},
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
    var maxFee by remember { mutableStateOf("5") }
    var unitSheet by remember { mutableStateOf(false) }
    var ecashReview by remember { mutableStateOf(false) }
    val labels = listOf("Lightning", "On-chain", "Ecash")
    LaunchedEffect(initialPayload, initialType) {
        clear()
        if (initialType == cash.pyx.app.nativeapi.InputType.BITCOIN && !initialPayload.isNullOrBlank()) submit("parse_bitcoin", client, initialPayload, 0)
    }
    LaunchedEffect(tab) { if (tab == 0) submit("load_contacts", state.factoryHandle, "", 0) }
    LaunchedEffect(operation) {
        if (operation is WalletOperation.BitcoinParsed) {
            tab = 1; text = operation.payment.address
            amount = operation.payment.amountSat?.toString().orEmpty(); amountUnit = TransferAmountUnit.SATS
        } else if (operation is WalletOperation.LnurlPrepared) {
            amountUnit = TransferAmountUnit.SATS
            if (operation.fixedAmount) amount = operation.minSat.toString()
        }
    }
    // Client-side gating only; the authoritative amount is always the native quote.
    val destination = if (tab == 0) SendDestinationPresentation.parse(text) else null
    val lockedSats: Long? = when {
        tab == 0 && destination?.lockedAmountSat != null -> destination.lockedAmountSat
        tab == 0 && operation is WalletOperation.LnurlPrepared && operation.fixedAmount -> operation.minSat
        tab == 1 -> (operation as? WalletOperation.BitcoinParsed)?.payment?.amountSat
        else -> null
    }
    val amountBlocked = tab == 0 && destination?.valid != true
    val maxFeeSat = maxFee.toLongOrNull()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        PyxTopBar("Send", onBack = back)
        cash.pyx.app.ui.components.AssetPill()
        Spacer(Modifier.height(14.dp))
        PyxSegmented(labels, tab, { index -> tab = index; text = ""; amount = ""; ecashReview = false; clear() })
        val needsText = tab != 2
        if (needsText) {
            FieldLabel("To")
            SendInputSurface(disabled = false) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(16 * 1024); ecashReview = false; clear() },
                    modifier = Modifier.weight(1f).testTag("send_destination"),
                    enabled = operation !is WalletOperation.Submitting && operation !is WalletOperation.BitcoinParsed,
                    textStyle = PyxType.inputMono.copy(color = PyxText),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(
                                if (tab == 0) "LN address, LNURL, invoice or BOLT12" else "Bitcoin address",
                                style = PyxType.inputMono, color = PyxFaint,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    PyxIcons.Scan, contentDescription = null, tint = PyxMuted,
                    modifier = Modifier.size(20.dp)
                        .clickable(onClickLabel = "Scan destination") { scan() }
                        .semantics { this.text = AnnotatedString("Scan destination") },
                )
            }
            // Address book: filtered contacts appear while the destination is incomplete.
            if (tab == 0 && destination?.valid != true && contactsState.contacts.isNotEmpty()) {
                val matches = ContactsPresentation.filter(contactsState.contacts, text)
                Surface(
                    color = PyxSurface2,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cash.pyx.app.ui.theme.PyxBorderStrong),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Column {
                        if (matches.isEmpty()) Text(
                            "No saved contacts match", style = PyxType.rowSub.copy(fontSize = 13.sp), color = PyxFaint,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                        )
                        matches.take(4).forEach { contact ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(onClickLabel = "Use ${contact.name}") { text = contact.lnurl; clear() }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                InitialAvatar(contact.name, size = 34.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(contact.name, style = PyxType.rowTitle.copy(fontSize = 14.sp), color = PyxText)
                                    Text(contact.lnurl, style = PyxType.rowSub, color = PyxMuted,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        run {
            FieldLabel("Amount")
            SendInputSurface(disabled = amountBlocked) {
                androidx.compose.foundation.text.BasicTextField(
                    value = if (lockedSats != null) LocalePresentation.integer(lockedSats) else amount,
                    onValueChange = { value ->
                        if (lockedSats != null) return@BasicTextField
                        amount = if (amountUnit == TransferAmountUnit.SATS) value.filter(Char::isDigit)
                        else value.filter(BitcoinAmountPresentation::inputCharacterAllowed)
                        ecashReview = false
                        clear()
                    },
                    modifier = Modifier.weight(1f).testTag("send_amount"),
                    enabled = operation !is WalletOperation.Submitting && lockedSats == null && !amountBlocked,
                    readOnly = lockedSats != null,
                    textStyle = PyxType.input.copy(color = PyxText),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (amountUnit == TransferAmountUnit.SATS) androidx.compose.ui.text.input.KeyboardType.Number
                        else androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (lockedSats == null && amount.isEmpty()) Text(
                                if (amountBlocked) "Enter a destination first" else "0",
                                style = PyxType.input, color = PyxFaint,
                            )
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                if (lockedSats != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(PyxIcons.Lock, contentDescription = "Amount locked by invoice", tint = PyxFaint, modifier = Modifier.size(15.dp))
                    Text("SATS", style = PyxType.keyValue.copy(fontSize = 14.sp), color = PyxMuted)
                } else Row(
                    Modifier.clickable(enabled = !amountBlocked, onClickLabel = "Change unit") { unitSheet = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        when (amountUnit) {
                            TransferAmountUnit.SATS -> "SATS"
                            TransferAmountUnit.BTC -> "BTC"
                            TransferAmountUnit.FIAT -> state.snapshot.currencyCode
                        },
                        style = PyxType.keyValue.copy(fontSize = 14.sp), color = PyxMuted,
                    )
                    Icon(PyxIcons.ChevronDown, contentDescription = null, tint = PyxMuted, modifier = Modifier.size(13.dp))
                }
            }
            if (operation is WalletOperation.FiatConverted) Text(
                "\u2248 ${LocalePresentation.integer(operation.amountSat)} sats \u00b7 ${operation.currencyCode}",
                style = PyxType.fiat, color = PyxMuted, modifier = Modifier.padding(top = 8.dp),
            )
            if (operation is WalletOperation.BitcoinParsed) {
                operation.payment.label?.let { Text("Label: $it", style = PyxType.rowSub, color = PyxMuted, modifier = Modifier.padding(top = 6.dp)) }
                operation.payment.message?.let { Text("Message: $it", style = PyxType.rowSub, color = PyxMuted) }
                if (operation.payment.amountSat != null) Text("Amount supplied by Bitcoin URI", style = PyxType.rowSub, color = PyxMuted)
            }
        }
        if (tab != 2) {
            FieldLabel("Max fee")
            SendInputSurface(disabled = false) {
                androidx.compose.foundation.text.BasicTextField(
                    value = maxFee,
                    onValueChange = { maxFee = it.filter(Char::isDigit).take(9) },
                    modifier = Modifier.weight(1f).testTag("send_max_fee"),
                    textStyle = PyxType.body.copy(color = PyxText),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PyxOrange),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                Text("SATS", style = PyxType.keyValue.copy(fontSize = 14.sp), color = PyxMuted)
            }
        }
        Spacer(Modifier.height(20.dp))
        val submitting = operation is WalletOperation.Submitting
        val textValid = !needsText || text.isNotBlank()
        val convertedAmount = (operation as? WalletOperation.FiatConverted)?.amountSat
        val amountValid = when {
            tab == 0 -> true // Lightning amounts resolve from the invoice or the LNURL step
            lockedSats != null -> true
            convertedAmount != null -> convertedAmount > 0
            amountUnit == TransferAmountUnit.FIAT -> amount.toBigDecimalOrNull()?.signum() == 1
            amountUnit == TransferAmountUnit.BTC -> BitcoinAmountPresentation.toSats(amount) != null
            else -> (amount.toLongOrNull() ?: 0L) > 0
        }
        val quoteReady = operation is WalletOperation.LightningPrepared || operation is WalletOperation.OnchainPrepared
        if (!quoteReady && !(tab == 2 && ecashReview)) PyxPrimaryButton(
            text = if (submitting) "Submitting\u2026" else if (lockedSats == null && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted && tab != 0) "Convert amount" else "Review and send",
            onClick = onClickLabel@{
                if (lockedSats == null && amountUnit == TransferAmountUnit.FIAT && operation !is WalletOperation.FiatConverted && tab != 0) {
                    submit("fiat_to_sats", client, amount, 0)
                    return@onClickLabel
                }
                val enteredSat = lockedSats
                    ?: convertedAmount
                    ?: (if (amountUnit == TransferAmountUnit.BTC) BitcoinAmountPresentation.toSats(amount) else amount.toLongOrNull())
                    ?: 0L
                when (tab) {
                    0 -> submit(
                        if (initialType == cash.pyx.app.nativeapi.InputType.LNURL || destination?.kind == SendDestinationPresentation.Kind.LNURL || destination?.kind == SendDestinationPresentation.Kind.LN_ADDRESS) "prepare_lnurl" else "prepare_lightning",
                        client, text, 0,
                    )
                    1 -> submit("prepare_onchain", client, (operation as? WalletOperation.BitcoinParsed)?.destination ?: text, enteredSat)
                    else -> ecashReview = true
                }
            },
            enabled = !submitting && textValid && amountValid && (tab != 2 || amountValid),
            modifier = Modifier.fillMaxWidth(),
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
                    if (operation.fixedAmount) "Fixed amount: ${operation.minSat} sats" else "Allowed: ${operation.minSat}\u2013${operation.maxSat} sats",
                    style = PyxType.rowSub, color = PyxMuted,
                )
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
                val feeOk = maxFeeSat == null || operation.quote.feeSat <= maxFeeSat
                if (!feeOk) Text(
                    "The quoted fee exceeds your max fee.", style = PyxType.rowSub, color = PyxRed,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                cash.pyx.app.ui.components.PyxSlideToConfirm(
                    enabled = feeOk && !submitting,
                    done = submitting,
                    onConfirm = { submit("execute_lightning", client, operation.quote.quoteHandle.toString(), 0) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
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
                val feeOk = maxFeeSat == null || operation.quote.feeSat <= maxFeeSat
                if (!feeOk) Text(
                    "The quoted fee exceeds your max fee.", style = PyxType.rowSub, color = PyxRed,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                cash.pyx.app.ui.components.PyxSlideToConfirm(
                    enabled = feeOk && !submitting,
                    done = submitting,
                    onConfirm = { submit("execute_onchain", client, operation.quote.quoteHandle.toString(), 0) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            is WalletOperation.FiatConverted -> Unit
            is WalletOperation.BitcoinParsed -> Unit
            else -> Unit
        }
        if (tab == 2 && ecashReview && operation !is WalletOperation.Success) {
            Spacer(Modifier.height(12.dp))
            PyxCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 15.dp)) {
                    CardRow("Create ecash", value = "${(convertedAmount ?: (if (amountUnit == TransferAmountUnit.BTC) BitcoinAmountPresentation.toSats(amount) else amount.toLongOrNull()) ?: 0L)} sats", valueColor = PyxText)
                }
            }
            cash.pyx.app.ui.components.PyxSlideToConfirm(
                enabled = !submitting,
                done = submitting,
                label = "Slide to create",
                doneLabel = "Creating\u2026",
                onConfirm = {
                    val sats = convertedAmount
                        ?: (if (amountUnit == TransferAmountUnit.BTC) BitcoinAmountPresentation.toSats(amount) else amount.toLongOrNull())
                        ?: 0L
                    submit("create_ecash", client, text, sats)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
    // Send conversions always use the wallet's display currency (fiat_to_sats takes
    // no currency code), so the unit sheet offers SATS/BTC plus that one fiat.
    if (unitSheet) CurrencyPickerSheet(
        title = "Amount currency",
        currencies = listOf(
            cash.pyx.app.nativeapi.FiatCurrency(state.snapshot.currencyCode, "Display currency", state.snapshot.currencyCode, 2),
        ),
        activeCode = when (amountUnit) {
            TransferAmountUnit.SATS -> "SATS"
            TransferAmountUnit.BTC -> "BTC"
            TransferAmountUnit.FIAT -> state.snapshot.currencyCode
        },
        savingCode = null,
        includeBtcUnits = true,
        onPick = { code ->
            amountUnit = when (code) {
                "SATS" -> TransferAmountUnit.SATS
                "BTC" -> TransferAmountUnit.BTC
                else -> TransferAmountUnit.FIAT
            }
            amount = ""; clear(); unitSheet = false
        },
        dismiss = { unitSheet = false },
    )
}

/** Uppercase micro-label rendered above a prototype input (natural-case semantics). */
@Composable
private fun FieldLabel(label: String) {
    Box(
        Modifier.padding(top = 16.dp, bottom = 8.dp)
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

/** Prototype input surface: surface fill, 1dp border, 12dp radius, 15dp padding. */
@Composable
private fun SendInputSurface(disabled: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .alpha(if (disabled) 0.5f else 1f)
            .background(PyxSurface, RoundedCornerShape(12.dp))
            .border(1.dp, PyxBorder, RoundedCornerShape(12.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
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
private fun PaymentRow(payment: Payment, masked: Boolean = false, showDivider: Boolean = false, onClick: () -> Unit = {}) {
    val incoming = payment.direction == cash.pyx.app.nativeapi.PaymentDirection.INCOMING
    Column {
    Row(
        Modifier.fillMaxWidth().minimumInteractiveComponentSize().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp)) {
            cash.pyx.app.ui.components.IconTile(
                if (incoming) PyxIcons.ArrowDown else PyxIcons.ArrowUp, tint = if (incoming) PyxGreen else PyxMuted, size = 42.dp, cornerRadius = 11.dp, iconSize = 18.dp,
            )
            // Small ₿ badge on the icon corner, occluding the icon box like the prototype.
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(17.dp)
                    .border(2.dp, PyxBackground, RoundedCornerShape(5.dp))
                    .padding(2.dp)
                    .background(PyxOrange, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("₿", style = PyxType.rowSub.copy(fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = cash.pyx.app.ui.theme.PyxOnOrange)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${paymentTypeLabel(payment.type)} ${if (incoming) "received" else "sent"}",
                style = PyxType.rowTitle, color = PyxText,
            )
            when (payment.status) {
                cash.pyx.app.nativeapi.PaymentStatus.PENDING -> Text("Pending", style = PyxType.rowSub, color = PyxAmber)
                cash.pyx.app.nativeapi.PaymentStatus.FAILED -> Text("Failed", style = PyxType.rowSub, color = PyxRed)
                else -> Text(ActivityPresentation.time(payment), style = PyxType.rowSub, color = PyxMuted)
            }
        }
        // Full string stays exactly the amount; the unit renders small and faint.
        val amountText = if (masked) "•••" else ActivityPresentation.amount(payment)
        val styledRowAmount = cash.pyx.app.ui.components.satAmountAnnotated(
            amountText, unitStyle = SpanStyle(fontSize = 11.sp, color = PyxFaint),
        )
        Text(styledRowAmount, style = PyxType.rowAmount, color = if (incoming) PyxGreen else PyxRed)
    }
    if (showDivider) PyxDivider()
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
    val exportScope = rememberCoroutineScope()
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
        lnAddressStateOwner = viewModel.lnAddressStateOwner,
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
            "leave" -> guarded("Leave federation") { viewModel.leave(client, text) }
            "prepare_lnurl" -> viewModel.prepareLnurl(text)
            "prepare_lnurl_quote" -> text.toLongOrNull()?.let { viewModel.prepareLnurlQuote(client, it, amount) }
            "load_contacts" -> viewModel.loadContacts(client)
            "save_contact" -> text.split('\u0000', limit = 2).takeIf { it.size == 2 }?.let { viewModel.saveContact(client, it[1], it[0]) }
            "delete_contact" -> viewModel.deleteContact(client, text)
            "details_connection" -> viewModel.loadFederationDetails(client)
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
            "export_debug" -> guarded("Export wallet debug data") {
                exportScope.launch {
                    runCatching {
                        val zip = withContext(Dispatchers.IO) { cash.pyx.app.data.DebugExport.create(context) }
                        context.startActivity(cash.pyx.app.data.DebugExport.shareIntent(context, zip))
                    }.onFailure { viewModel.operationFailure("The debug export could not be created.") }
                }
            }
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
