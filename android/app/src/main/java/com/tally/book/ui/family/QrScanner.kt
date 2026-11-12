package com.tally.book.ui.family

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tally.book.core.Clock
import com.tally.book.core.t
import com.tally.book.sync.Invite
import com.tally.book.ui.AppNav
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/*
 * Join by scanning: a full-screen camera that reads a Tally family QR. The camera permission is asked here, at this moment;
 * if it is refused the screen offers to paste the code instead. Frames are read on a background thread and the first valid
 * invite stops the scan (a tick, then "Join <name>?"). Other QR codes are ignored with a quiet note. The invite text is only
 * ever held in a local variable on its way to Invite.find: never shown, logged or stored.
 *
 * The overlay is drawn over a live camera picture, so it uses white on dark rather than theme colours.
 */
@Composable
fun ScanScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var allowed by remember { mutableStateOf<Boolean?>(if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) true else null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val asked = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (allowed == null) asked.launch(Manifest.permission.CAMERA) }

    val listening = remember { AtomicBoolean(true) }
    val lastNote = remember { AtomicLong(0) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val notCode = t("scan.notTally", "This isn't a Tally family code")

    fun found(text: String) {
        val invite = Invite.find(text)
        if (invite == null) {
            // a different QR code: say so quietly, and not again for a few seconds
            val now = Clock.millis()
            if (now - lastNote.get() > 3_000) { lastNote.set(now); note = if (Invite.isOld(text)) SyncText.oldCode() else notCode; scope.launch { kotlinx.coroutines.delay(2_500); note = null } }
            return
        }
        if (!listening.compareAndSet(true, false)) return
        haptics.tick()
        scope.launch {
            val joined = FamilyFlow.join(invite)
            if (!joined) listening.set(true)    // cancelled or failed: look again
        }
    }
    val latestFound by rememberUpdatedState(::found)

    DisposableEffect(allowed) {
        if (allowed != true) return@DisposableEffect onDispose { }
        val analysisThread = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get(); provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisThread) { image ->
                    try {
                        if (listening.get()) {
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val text = QrDecoder.decode(bytes, plane.rowStride, image.height, image.width, image.height)
                            if (text != null) mainExecutor.execute { latestFound(text) }
                        }
                    } finally { image.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                cameraFailed = true
            }
        }, mainExecutor)
        onDispose { provider?.unbindAll(); analysisThread.shutdown() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed == true && !cameraFailed) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.align(Alignment.Center).size(260.dp)) {
                drawRoundRect(Color.White.copy(alpha = 0.9f), Offset.Zero, this.size, CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(t("dlg.cancel", "Cancel"), { AppNav.back() }, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            when {
                allowed == false || cameraFailed -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (cameraFailed) t("scan.noCamera", "The camera could not be opened.") else t("scan.denied", "The camera is not allowed, so Tally cannot scan the code."),
                        style = Apple.type.body, color = Color.White, textAlign = TextAlign.Center,
                    )
                    PrimaryButton(t("scan.paste", "Paste the code instead"), { AppNav.back() }, Modifier.fillMaxWidth())
                }
                else -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var shown by remember { mutableStateOf(notCode) }
                    note?.let { shown = it }
                    val noteAlpha by animateFloatAsState(if (note != null) 1f else 0f, tween(200), label = "note")
                    Text(
                        shown, Modifier.heightIn(min = 24.dp).graphicsLayer { alpha = noteAlpha }.semantics { liveRegion = LiveRegionMode.Polite },
                        style = Apple.type.callout, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
                    )
                    Text(t("scan.help", "Point the camera at the code on the other phone."), style = Apple.type.body, color = Color.White, textAlign = TextAlign.Center)
                    PlainButton(t("scan.paste", "Paste the code instead"), { AppNav.back() }, color = Color.White)
                }
            }
        }
    }
}
