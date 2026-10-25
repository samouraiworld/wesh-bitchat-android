package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.services.VerificationService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val accent = MaterialTheme.colorScheme.primary
    
    var selectedTab by remember { mutableStateOf(0) } // 0 = My Code, 1 = Scan
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val npub = remember { viewModel.getCurrentNpub() }

    val qrString = remember(nickname, npub) {
        viewModel.buildMyQRString(nickname, npub)
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Header
            VerificationHeader(
                accent = accent,
                onClose = onDismiss,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = accent,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "My QR",
                            fontFamily = BitchatFontFamily,
                            fontSize = 14.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Scan",
                            fontFamily = BitchatFontFamily,
                            fontSize = 14.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Content
            Crossfade(
                targetState = selectedTab, 
                label = "VerificationTabCrossfade",
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    0 -> MyQrTabContent(
                        qrString = qrString,
                        nickname = nickname,
                        accent = accent
                    )
                    1 -> ScanTabContent(
                        accent = accent,
                        onScan = { code ->
                            val qr = VerificationService.verifyScannedQR(code)
                            if (qr != null && viewModel.beginQRVerification(qr)) {
                                selectedTab = 0
                            }
                        }
                    )
                }
            }
            
            // Unverify Action
            val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
            val fingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
            
            if (peerID != null) {
                val fingerprint = viewModel.getMeshPeerFingerprint(peerID!!)
                if (fingerprint != null && fingerprints.contains(fingerprint)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.unverifyFingerprint(peerID!!) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.verify_remove),
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationHeader(
    accent: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.verify_title).uppercase(),
            fontSize = 14.sp,
            fontFamily = BitchatFontFamily,
            color = accent
        )
        val dismiss = LocalSheetDismiss.current
        CloseButton(onClick = { dismiss?.invoke() ?: onClose() })
    }
}

@Composable
private fun MyQrTabContent(
    qrString: String,
    nickname: String,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.verify_my_qr_title),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = BitchatFontFamily,
            color = accent
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        if (qrString.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(20.dp) // Quiet zone
            ) {
                QRCodeImage(data = qrString, size = 260.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.verify_qr_unavailable),
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = Color.Black.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // User Nickname
        Text(
            text = nickname,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Helper text
        Text(
            text = stringResource(R.string.app_name).lowercase(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun ScanTabContent(
    accent: Color,
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val hasCamera = remember(context) { hasQrCamera(context.packageManager) }
    if (!hasCamera) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = accent, modifier = Modifier.size(64.dp))
            Text(
                stringResource(R.string.wesh_camera_unavailable),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wesh_camera_unavailable_body),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (permissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                ScannerView(onScan = onScan)
                
                // Overlay border
                Box(
                    modifier = Modifier
                        .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .border(2.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                )
                
                Text(
                    text = stringResource(R.string.verify_scan_prompt_friend),
                    color = Color.White,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = accent
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.verify_camera_permission),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { permissionState.launchPermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(
                        text = stringResource(R.string.verify_request_camera),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Default
                    )
                }
                androidx.compose.material3.TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.packageName)))
                }) { Text(stringResource(R.string.wesh_verify_app_settings)) }
            }
        }
    }
}

@Composable
private fun ScannerView(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onCodeState = rememberUpdatedState(onScan)
    val analyzer = remember {
        QRCodeAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onCodeState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    val selector = qrCameraSelector(
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
                    ) ?: error("No camera available")
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        analysis
                    )
                }.onFailure {
                    Log.w("VerificationSheet", "Camera unavailable")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun QRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.wesh_verify_qr_description),
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

private class QRCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private var buffer = ByteArray(0)

    override fun analyze(imageProxy: ImageProxy) {
        try {
            // Plane 0 of YUV_420_888 is the luminance; QR finder patterns tolerate any rotation.
            val plane = imageProxy.planes.firstOrNull() ?: return
            val data = plane.buffer
            data.rewind()
            if (buffer.size != data.remaining()) buffer = ByteArray(data.remaining())
            data.get(buffer)
            val text = QrLuminanceDecoder.decode(buffer, imageProxy.width, imageProxy.height, plane.rowStride)
            if (!text.isNullOrBlank()) onCode(text)
        } finally {
            imageProxy.close()
        }
    }
}
