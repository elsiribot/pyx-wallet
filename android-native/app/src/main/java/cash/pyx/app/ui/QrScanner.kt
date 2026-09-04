package cash.pyx.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cash.pyx.app.data.appPreferences
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class CameraPermissionUi { GRANTED, FIRST_REQUEST, RATIONALE, DENIED, PERMANENTLY_DENIED }
enum class ScanFrameDecision { CONTINUE, HANDLING }

class ScanFrameGate {
    private val handling = AtomicBoolean(false)
    private val lastFrame = AtomicReference<String?>(null)
    fun isHandling(): Boolean = handling.get()
    fun accept(value: String, handler: (String) -> ScanFrameDecision): Boolean {
        if (handling.get() || value == lastFrame.getAndSet(value)) return false
        if (handler(value) == ScanFrameDecision.HANDLING) handling.set(true)
        return true
    }
}

object CameraPermissionStateMachine {
    fun initial(granted: Boolean, asked: Boolean, showRationale: Boolean): CameraPermissionUi = when {
        granted -> CameraPermissionUi.GRANTED
        !asked -> CameraPermissionUi.FIRST_REQUEST
        showRationale -> CameraPermissionUi.RATIONALE
        else -> CameraPermissionUi.PERMANENTLY_DENIED
    }
    fun afterRequest(granted: Boolean, showRationale: Boolean): CameraPermissionUi = when {
        granted -> CameraPermissionUi.GRANTED
        showRationale -> CameraPermissionUi.DENIED
        else -> CameraPermissionUi.PERMANENTLY_DENIED
    }
}

@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
    frameHandler: (String) -> ScanFrameDecision = { onResult(it); ScanFrameDecision.HANDLING },
    progressFrames: Int = 0,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val preferenceStore = context.appPreferences
    val askedFlow = remember(preferenceStore) { preferenceStore.data.map { it[CAMERA_PERMISSION_ASKED] ?: false } }
    val asked by askedFlow.collectAsState(initial = false)
    val coroutineScope = rememberCoroutineScope()
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun rationale() = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    var permission by remember { mutableStateOf(CameraPermissionStateMachine.initial(granted(), asked, rationale())) }
    var bindFailure by remember { mutableStateOf(false) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    // DataStore is asynchronous. Re-evaluate once its persisted value arrives;
    // otherwise a prior denial can briefly and incorrectly look like a first
    // request for the entire lifetime of this composition.
    LaunchedEffect(asked) {
        permission = CameraPermissionStateMachine.initial(granted(), asked, rationale())
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        coroutineScope.launch { preferenceStore.edit { it[CAMERA_PERMISSION_ASKED] = true } }
        permission = CameraPermissionStateMachine.afterRequest(allowed, rationale())
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permission = CameraPermissionStateMachine.initial(granted(), asked, rationale())
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) { Text("Back") }
        when (permission) {
            CameraPermissionUi.GRANTED -> if (bindFailure) {
                Text("The camera could not be started.", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                Button(onClick = { bindFailure = false; retryGeneration++ }) { Text("Retry camera") }
            } else {
                if (progressFrames > 0) Text("Ecash fragments scanned: $progressFrames", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                key(retryGeneration) { CameraPreview(frameHandler) { bindFailure = true } }
            }
            CameraPermissionUi.FIRST_REQUEST -> PermissionMessage("Camera access is needed to scan QR codes.", "Allow camera") {
                launcher.launch(Manifest.permission.CAMERA)
            }
            CameraPermissionUi.RATIONALE -> PermissionMessage("Pyx uses the camera only while this scanner is open.", "Continue") {
                launcher.launch(Manifest.permission.CAMERA)
            }
            CameraPermissionUi.DENIED -> PermissionMessage("Camera permission was denied. You can try again or go back.", "Try again") {
                launcher.launch(Manifest.permission.CAMERA)
            }
            CameraPermissionUi.PERMANENTLY_DENIED -> PermissionMessage("Camera permission is disabled in Android settings.", "Open app settings") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
}

private val CAMERA_PERMISSION_ASKED = booleanPreferencesKey("asked")

@Composable
private fun PermissionMessage(message: String, action: String, onClick: () -> Unit) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(action) }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onFrame: (String) -> ScanFrameDecision, onBindFailure: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val frameGate = remember { ScanFrameGate() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); scanner.close(); executor.shutdown() } }
    AndroidView(factory = { viewContext -> PreviewView(viewContext).also { view ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null || frameGate.isHandling()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstNotNullOfOrNull { it.rawValue }?.takeIf { it.length <= 16 * 1024 }
                            if (value != null) frameGate.accept(value, onFrame)
                        }.addOnCompleteListener { proxy.close() }
                }
                provider!!.unbindAll()
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { onBindFailure() }
        }, ContextCompat.getMainExecutor(context))
    } }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "QR scanner camera preview" })
}
