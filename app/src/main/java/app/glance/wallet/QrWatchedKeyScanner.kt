package app.glance.wallet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.glance.wallet.core.crypto.UnifiedImport
import app.glance.wallet.core.crypto.classifyUnifiedImport
import app.glance.wallet.core.crypto.normalizeWatchedKeyInput
import app.glance.wallet.core.crypto.parseSingleAddress
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Returns a canonical field value only for the narrow watch-only import formats that Glance
 * supports. Delegating to unified import keeps QR and pasted descriptor grammar identical.
 */
internal fun watchedKeyInputFromQr(payload: String): String? = runCatching {
    val normalized = normalizeWatchedKeyInput(payload)
    normalized.takeIf { classifyUnifiedImport(it) !is UnifiedImport.Address }
}.getOrNull()

/** Unified Add Watch Target field accepts either supported key material or a fixed address. */
internal fun unifiedWatchTargetInputFromQr(payload: String): String? =
    singleAddressInputFromQr(payload) ?: watchedKeyInputFromQr(payload)

/** Extracts only the address from a bare-address or BIP21 QR; payment metadata is not imported. */
internal fun singleAddressInputFromQr(payload: String): String? = runCatching {
    val normalized = normalizeWatchedKeyInput(payload)
    val candidate = if (normalized.startsWith("bitcoin:", ignoreCase = true)) {
        normalized.substringAfter(':').substringBefore('?')
    } else normalized
    parseSingleAddress(candidate)
}.getOrNull()

@Composable
internal fun QrCameraPreview(
    modifier: Modifier = Modifier,
    onPayload: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            bindQrCamera(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                analysisExecutor = analysisExecutor,
                mainExecutor = mainExecutor,
                delivered = delivered,
                onPayload = onPayload,
                onUnavailable = onUnavailable,
            )
            previewView
        },
    )
}

private fun bindQrCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: ExecutorService,
    mainExecutor: java.util.concurrent.Executor,
    delivered: AtomicBoolean,
    onPayload: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val provider = runCatching { providerFuture.get() }.getOrElse {
            onUnavailable()
            return@addListener
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    val payload = decodeQrPayload(image)
                    image.close()
                    if (payload != null && delivered.compareAndSet(false, true)) {
                        mainExecutor.execute { onPayload(payload) }
                    }
                }
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.onFailure { onUnavailable() }
    }, mainExecutor)
}

@SuppressLint("UnsafeOptInUsageError")
private fun decodeQrPayload(image: ImageProxy): String? {
    if (image.format != ImageFormat.YUV_420_888 || image.planes.isEmpty()) return null
    return runCatching {
        val source = PlanarYUVLuminanceSource(
            image.lumaBytes(), image.width, image.height, 0, 0, image.width, image.height, false,
        )
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}

private fun ImageProxy.lumaBytes(): ByteArray {
    val plane = planes.first()
    val output = ByteArray(width * height)
    for (row in 0 until height) {
        val rowStart = row * plane.rowStride
        for (column in 0 until width) {
            output[row * width + column] = plane.buffer.get(rowStart + column * plane.pixelStride)
        }
    }
    return output
}
