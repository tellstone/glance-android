package app.glance.wallet

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.biometric.BiometricManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Shield
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.navigation.NavType
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.withTransaction
import app.glance.wallet.core.crypto.normalizeWatchedKeyInput
import app.glance.wallet.core.crypto.parseSingleAddress
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.crypto.classifyUnifiedImport
import app.glance.wallet.core.crypto.UnifiedImport
import app.glance.wallet.core.data.db.*
import app.glance.wallet.core.data.sync.RoomWalletSyncStore
import app.glance.wallet.core.data.sync.SyncConfig
import app.glance.wallet.core.data.sync.XpubDiscoveryResult
import app.glance.wallet.core.network.MempoolFiatPriceClient
import app.glance.wallet.core.security.*
import app.glance.wallet.presentation.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.text.DateFormat
import kotlin.math.roundToInt


@Composable
internal fun QrScannerDialog(
    onDismiss: () -> Unit,
    onPayload: (String) -> Unit,
    onUnavailable: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit = { modifier ->
        QrCameraPreview(modifier, onPayload, onUnavailable)
    },
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = qrScannerCardColor,
            shape = qrScannerCardShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeScreenGutter)
                .testTag("qr_scanner_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_scanner),
                        contentDescription = null,
                        tint = GlanceMandarin,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Scan watch target",
                        color = GlanceText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close QR scanner",
                            tint = GlanceText,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(qrScannerPreviewShape)
                        .background(GlanceBackground)
                        .testTag("qr_scanner_preview"),
                ) {
                    cameraPreview(Modifier.fillMaxSize())
                    QrScannerFrame(
                        Modifier
                            .matchParentSize()
                            .padding(qrScannerPreviewInset),
                    )
                }
                Text(
                    "Point the camera at a Bitcoin address, public key, or descriptor QR code.",
                    color = GlanceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun QrScannerFrame(modifier: Modifier = Modifier) = Canvas(modifier) {
    val cornerLength = 32.dp.toPx()
    val strokeWidth = 2.dp.toPx()
    val width = size.width
    val height = size.height
    fun corner(from: Offset, horizontal: Offset, vertical: Offset) {
        drawLine(qrScannerFrameColor, from, horizontal, strokeWidth)
        drawLine(qrScannerFrameColor, from, vertical, strokeWidth)
    }
    corner(Offset.Zero, Offset(cornerLength, 0f), Offset(0f, cornerLength))
    corner(Offset(width, 0f), Offset(width - cornerLength, 0f), Offset(width, cornerLength))
    corner(Offset(0f, height), Offset(cornerLength, height), Offset(0f, height - cornerLength))
    corner(Offset(width, height), Offset(width - cornerLength, height), Offset(width, height - cornerLength))
}

internal data class FallbackFormatSelection(
    val available: List<ScriptType>,
    val selected: ScriptType? = null,
) {
    init {
        require(available.isNotEmpty()) { "At least one fallback format must be available" }
    }

    val canConfirm: Boolean get() = selected in available

    fun select(format: ScriptType): FallbackFormatSelection =
        if (format in available) copy(selected = format) else this
}

internal fun importErrorMessage(exception: Exception): String =
    when {
        exception is TorDiscoveryUnavailableException -> exception.message!!
        exception.message?.contains("version does not match script type") == true ->
            "This key is valid, but its prefix does not match the selected address format. A plain xpub can use any supported format; ypub is only Nested SegWit and zpub is only Native SegWit."
        exception.message?.contains("script type is required") == true ->
            "Select a script type for this extended public key."
        exception.message?.contains("Invalid extended public key") == true ->
            "The key could not be decoded. Paste a complete mainnet xpub, ypub, or zpub without a derivation path or quotes."
        exception.message?.contains("Only mainnet") == true ->
            "Only mainnet xpub, ypub, and zpub keys are supported."
        exception.message?.contains("Bitcoin address") == true -> "Enter a valid mainnet Bitcoin address."
        exception.message?.contains("already being tracked") == true -> "This address is already being tracked."
        else -> "Enter a valid mainnet key or supported Taproot descriptor."
}

