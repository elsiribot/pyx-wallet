package cash.pyx.app

import android.os.Bundle
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.RootFailureScreen
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.ui.WalletBootstrapViewModel
import cash.pyx.app.ui.theme.PyxTheme
import cash.pyx.app.security.DataStoreSeedBackupState
import cash.pyx.app.security.IrreversibleOperationReconciliation
import cash.pyx.app.security.SharedPreferencesIrreversibleOperationJournal
import cash.pyx.app.security.SharedPreferencesDeepLinkConsumptionStore
import cash.pyx.app.ui.IntentConsumptionGate

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as PyxApplication
        if (application.hasRootCrashMarker()) {
            setContent {
                PyxTheme {
                    RootFailureScreen {
                        if (application.clearRootCrashMarker()) recreate()
                    }
                }
            }
            return
        }
        val walletViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WalletBootstrapViewModel(
                    WalletBootstrapRepository(JniNativeWalletApi(), filesDir.absolutePath),
                    seedBackupState = DataStoreSeedBackupState(applicationContext),
                    operationReconciliation = IrreversibleOperationReconciliation(
                        SharedPreferencesIrreversibleOperationJournal(applicationContext),
                    ),
                    intentConsumptionGate = IntentConsumptionGate(
                        SharedPreferencesDeepLinkConsumptionStore(applicationContext),
                    ),
                ) as T
        })[WalletBootstrapViewModel::class.java]
        walletViewModel.queueInvite(intent?.dataString)
        setContent {
            PyxTheme {
                PyxApp(walletViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptNewIntent(intent)
    }

    internal fun acceptNewIntent(intent: Intent) {
        setIntent(intent)
        ViewModelProvider(this)[WalletBootstrapViewModel::class.java].queueInvite(intent.dataString)
    }
}
