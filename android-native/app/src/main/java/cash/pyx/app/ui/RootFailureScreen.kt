package cash.pyx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Redacted next-launch recovery surface for an uncaught prior-process failure. */
@Composable
fun RootFailureScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pyx needs to restart", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text("The previous app process stopped unexpectedly. Your wallet data was not cleared.")
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Restart wallet")
        }
    }
}
