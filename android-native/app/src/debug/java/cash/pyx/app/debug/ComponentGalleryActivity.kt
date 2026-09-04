package cash.pyx.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cash.pyx.app.ui.QrPayload
import cash.pyx.app.ui.theme.PyxTheme

/** Debug-source-set-only catalog. All displayed values are fixed synthetic fixtures. */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PyxTheme { ComponentGallery() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentGallery() {
    var field by remember { mutableStateOf("Synthetic input") }
    var checked by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = { TopAppBar(title = { Text("Pyx component gallery") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("component_gallery"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GalleryHeading("Color roles") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ColorRole("Primary", colors.primary, colors.onPrimary)
                    ColorRole("Surface", colors.surface, colors.onSurface)
                    ColorRole("Surface variant", colors.surfaceVariant, colors.onSurfaceVariant)
                    ColorRole("Background", colors.background, colors.onBackground)
                    ColorRole("Error", colors.error, colors.onError)
                    ColorRole("Outline", colors.outline, colors.onSurface)
                }
            }
            item { GalleryHeading("Material controls") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Primary") }
                OutlinedButton(onClick = {}) { Text("Outlined") }
                TextButton(onClick = {}) { Text("Text") }
            } }
            item { OutlinedTextField(field, { field = it }, label = { Text("Standard field") }, modifier = Modifier.fillMaxWidth()) }
            item { ListItem(headlineContent = { Text("System list item") }, supportingContent = { Text("Synthetic supporting text") }, trailingContent = { Switch(checked, { checked = it }) }) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected, { selected = !selected }, { Text("Selected chip") })
                AssistChip({}, { Text("Assist chip") })
            } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Standard card", style = MaterialTheme.typography.titleMedium); Text("Uses Material surface and shape defaults.") } } }

            item { GalleryHeading("Status and payment rows") }
            item { StatusCard("Connected", "3 of 3 guardians online", colors.primary) }
            item { StatusCard("Degraded", "2 of 3 guardians online", colors.error) }
            item { PaymentFixture("Lightning · succeeded", "+1,250 sats") }
            item { PaymentFixture("On-chain · pending", "−8,000 sats") }
            item { PaymentFixture("Ecash · failed", "−500 sats") }

            item { GalleryHeading("Reconciliation and guardians") }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check a previous payment", style = MaterialTheme.typography.titleMedium)
                Text("Synthetic operation is ambiguous. No payment will be retried automatically.")
                Button(onClick = {}) { Text("Check status") }
            } } }
            item { ListItem(headlineContent = { Text("Guardian Alpha") }, supportingContent = { Text("Synthetic fixture") }, trailingContent = { Text("Online", color = colors.primary) }) }
            item { ListItem(headlineContent = { Text("Guardian Beta") }, supportingContent = { Text("Synthetic fixture") }, trailingContent = { Text("Offline", color = colors.error) }) }

            item { GalleryHeading("QR safety surface") }
            item { SyntheticQrSurface() }
            item { GalleryHeading("Large text") }
            item { Text("1,234,567 sats", style = MaterialTheme.typography.displayLarge) }
            item { Text("Recovery words and real payment payloads are intentionally excluded.", style = MaterialTheme.typography.headlineLarge) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable private fun GalleryHeading(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

@Composable
private fun ColorRole(name: String, background: Color, foreground: Color) {
    Surface(color = background, contentColor = foreground, modifier = Modifier.fillMaxWidth()) {
        Text(name, Modifier.padding(12.dp))
    }
}

@Composable
private fun StatusCard(title: String, detail: String, accent: Color) = Card(Modifier.fillMaxWidth()) {
    ListItem(headlineContent = { Text(title, color = accent) }, supportingContent = { Text(detail) })
}

@Composable
private fun PaymentFixture(label: String, amount: String) = ListItem(
    headlineContent = { Text(label) }, supportingContent = { Text("Synthetic payment") }, trailingContent = { Text(amount) },
)

@Composable
private fun SyntheticQrSurface() {
    val bitmap = remember { QrPayload.encode("PYX-COMPONENT-GALLERY-NOT-PAYABLE-SYNTHETIC", 320) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(bitmap.asImageBitmap(), "Synthetic non-payable QR code", Modifier.fillMaxWidth().aspectRatio(1f))
        Text("NOT PAYABLE · fixed synthetic gallery data", color = MaterialTheme.colorScheme.error)
        Text("Real invoices, ecash, invites, and addresses must never be used here.")
    } }
}
