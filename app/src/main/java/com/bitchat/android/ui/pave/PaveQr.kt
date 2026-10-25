package com.bitchat.android.ui.pave

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.ui.qrCameraSelector
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decodes a QR code from a camera luminance plane. Kept behind an interface so the scanner
 * does not depend on one decoder; the default runs on the device, without Play services or network.
 */
fun interface QrFrameDecoder {
    /**
     * [luma] holds [height] rows of [rowStride] bytes; only the first [width] bytes of a row are
     * pixels. Only [crop] is read: the part of the frame under the viewfinder.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int, crop: QrCrop): String?
}

/** A rectangle of a camera frame, in buffer pixels. */
data class QrCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** The viewfinder square the Échanger screen draws over the camera; only codes inside it are read. */
val PAVE_QR_VIEWFINDER = 160.dp

/**
 * The part of a camera frame under the viewfinder. The preview shows the frame rotated by
 * [rotationDegrees] and center-cropped to fill a [viewWidth] x [viewHeight] box, with a centered
 * square of [finderSize] (view sizes in pixels). The rotation decides which frame side fills the box
 * width; since the square is centered, it maps to a centered square of the frame. Fails closed:
 * null (the frame is skipped) while the view size is unknown or smaller than the viewfinder, or when
 * the square does not fit inside the frame; never the whole frame.
 */
internal fun viewfinderCrop(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    viewWidth: Float,
    viewHeight: Float,
    finderSize: Float,
): QrCrop? {
    if (imageWidth <= 0 || imageHeight <= 0 || finderSize <= 0f) return null
    if (viewWidth < finderSize || viewHeight < finderSize) return null
    val quarterTurn = rotationDegrees % 180 != 0
    val uprightWidth = if (quarterTurn) imageHeight else imageWidth
    val uprightHeight = if (quarterTurn) imageWidth else imageHeight
    val scale = maxOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
    val half = finderSize / scale / 2f
    val left = floor(imageWidth / 2f - half).toInt()
    val top = floor(imageHeight / 2f - half).toInt()
    val right = ceil(imageWidth / 2f + half).toInt()
    val bottom = ceil(imageHeight / 2f + half).toInt()
    if (left < 0 || top < 0 || right > imageWidth || bottom > imageHeight || right <= left || bottom <= top) return null
    return QrCrop(left, top, right - left, bottom - top)
}

/**
 * On-device ZXing decoder. Also reads light-on-dark codes. A crop showing two different codes is
 * ambiguous in a crowd, so it gives nothing rather than one of them.
 */
object ZxingQrFrameDecoder : QrFrameDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    override fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int, crop: QrCrop): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        if (crop.width <= 0 || crop.height <= 0 || crop.left < 0 || crop.top < 0 ||
            crop.left + crop.width > width || crop.top + crop.height > height
        ) return null
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, crop.left, crop.top, crop.width, crop.height, false)
        val texts = HashSet<String>()
        // Dark-on-light and light-on-dark codes: both passes count toward the "only one code" rule.
        for (candidate in listOf(source, source.invert())) {
            val found = try {
                QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(candidate)), hints)
            } catch (_: ReaderException) {
                emptyArray()
            } catch (_: RuntimeException) {
                emptyArray()
            }
            found.mapNotNullTo(texts) { it.text }
        }
        return texts.singleOrNull()
    }
}

/** QR modules for [data], one cell per module, no quiet zone (drawn by the caller). Null if it cannot be encoded. */
fun encodeQrModules(data: String): BitMatrix? {
    if (data.isBlank()) return null
    return runCatching {
        QRCodeWriter().encode(
            data, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }.getOrNull()
}

/** Draws [modules] dark on light. The quiet zone comes from the surrounding padding. */
@Composable
fun QrModules(modules: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / modules.width
        drawRect(WeshTokens.QrBackground, size = Size(cell * modules.width, cell * modules.height))
        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (modules[x, y]) {
                    // Slight overlap avoids hairline gaps between modules.
                    drawRect(WeshTokens.QrModule, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/**
 * Keeps a late camera callback from binding or reporting after the scanner left the composition.
 * `ProcessCameraProvider.getInstance` can complete after disposal; every bind and every decoded
 * result goes through this guard, and [dispose] releases whatever was bound.
 */
internal class ScannerBindingGuard {
    private var disposed = false
    private var release: (() -> Unit)? = null

    val isDisposed: Boolean @Synchronized get() = disposed

    /** Runs [bind] only while active; [bind] returns how to release what it bound. */
    @Synchronized
    fun bindIfActive(bind: () -> (() -> Unit)): Boolean {
        if (disposed) return false
        release?.invoke()
        release = bind()
        return true
    }

    /** Runs [deliver] only while active. */
    @Synchronized
    fun deliverIfActive(deliver: () -> Unit) {
        if (!disposed) deliver()
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        release?.invoke()
        release = null
    }
}

/**
 * Camera preview that reports each new decoded text once. Needs the CAMERA permission.
 * Only the centered [viewfinder] square of the preview is decoded, so a code elsewhere in view
 * (a stranger's, in a crowd) is never read.
 */
@Composable
fun PaveQrScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoder: QrFrameDecoder = ZxingQrFrameDecoder,
    viewfinder: Dp = PAVE_QR_VIEWFINDER,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState()
    val onCodeState = rememberUpdatedState(onCode)
    val finderPx = with(LocalDensity.current) { viewfinder.toPx() }
    // Written on the main thread by layout, read by the analyzer thread.
    val viewSize = remember { AtomicReference(IntSize.Zero) }

    DisposableEffect(lifecycleOwner, decoder, finderPx) {
        val guard = ScannerBindingGuard()
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        var last: String? = null
        val future = ProcessCameraProvider.getInstance(context)
        var buffer = ByteArray(0)
        val analyzer = ImageAnalysis.Analyzer { image: ImageProxy ->
            try {
                if (guard.isDisposed) return@Analyzer
                // Plane 0 of YUV_420_888 is luminance; QR finder patterns tolerate any rotation,
                // but the crop must follow it to match the viewfinder.
                val plane = image.planes.firstOrNull() ?: return@Analyzer
                val data = plane.buffer
                data.rewind()
                if (buffer.size != data.remaining()) buffer = ByteArray(data.remaining())
                data.get(buffer)
                val view = viewSize.get()
                val crop = viewfinderCrop(
                    image.width, image.height, image.imageInfo.rotationDegrees,
                    view.width.toFloat(), view.height.toFloat(), finderPx,
                ) ?: return@Analyzer
                val text = decoder.decode(buffer, image.width, image.height, plane.rowStride, crop)
                if (!text.isNullOrBlank()) main.post {
                    guard.deliverIfActive {
                        if (text != last) {
                            last = text
                            onCodeState.value(text)
                        }
                    }
                }
            } finally {
                image.close()
            }
        }
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            guard.bindIfActive {
                // Same aspect ratio for preview and analysis, so the viewfinder maps onto the analysed frame.
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(ratio).build()
                    .also { it.setSurfaceProvider { r -> surfaceRequests.value = r } }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                runCatching {
                    val selector = qrCameraSelector(
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
                    ) ?: error("No camera available")
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }.onFailure { Log.w("PaveQrScanner", "Camera unavailable") }
                val release: () -> Unit = {
                    analysis.clearAnalyzer()
                    preview.setSurfaceProvider(null)
                    runCatching { provider.unbindAll() }
                }
                release
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            guard.dispose()
            surfaceRequests.value = null
            executor.shutdown()
        }
    }

    surfaceRequest?.let {
        // Default center crop: the mapping in viewfinderCrop assumes it.
        CameraXViewfinder(
            surfaceRequest = it,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = modifier.onSizeChanged { size -> viewSize.set(size) },
        )
    }
}
