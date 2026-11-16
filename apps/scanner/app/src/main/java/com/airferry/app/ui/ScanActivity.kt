package com.airferry.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.airferry.app.nativelib.NativeBridge
import com.airferry.app.scan.BundleParser
import com.airferry.app.scan.QrDecodePool
import com.airferry.app.scan.QrStreamAnalyzer
import com.airferry.app.scan.ReceiverSessionManager
import com.airferry.app.scan.ShutterSpeed
import com.airferry.app.scan.TextParser
import com.airferry.app.scan.nextAutoIso
import com.airferry.app.scan.planManualExposure
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Design tokens
private val BgDark = Color(0xFF0F172A)
private val CardBg = Color(0xCC1E293B)
private val Accent = Color(0xFF3B82F6)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val Success = Color(0xFF22C55E)

class ScanActivity : ComponentActivity() {

    private var session = ReceiverSessionManager()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    /** Dedicated single-thread executor for the post-recovery heavy work
     *  (JNI assemble, CRC, disk writes, bundle unpacking) so it never blocks
     *  the main thread. The work runs under the decode pool's ingest lock. */
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var cameraStarted = false
    private var previewView: PreviewView? = null

    /** Parallel QR decode pool (capture → queue → N workers → serialized ingest). */
    private var decodePool: QrDecodePool? = null

    // ---- Fixed-shutter / exposure state ----

    /** Camera bound by [startCameraWithView]; needed to push new exposure options. */
    private var boundCamera: androidx.camera.core.Camera? = null

    /** Analyzer bound to the active camera; told whether the shutter is locked so
     *  it can skip the luma sampling that feeds the auto-ISO loop. */
    private var boundAnalyzer: QrStreamAnalyzer? = null

    /** Range of exposure times this sensor supports (from CameraCharacteristics). */
    private var exposureRange: Range<Long>? = null
    /** Range of ISO values this sensor supports. */
    private var sensitivityRange: Range<Int>? = null
    /** Whether the device advertises MANUAL_SENSOR (required for AE_MODE=OFF). */
    private var supportsManualSensor = false

    /** Shutter chosen by the user (persisted in Settings); AUTO by default. */
    private var currentShutter: ShutterSpeed = ShutterSpeed.AUTO
    /** Shutter actually applied to the session — differs from [currentShutter] when
     *  the request was clamped or rejected (then this is AUTO). */
    private var appliedShutter: ShutterSpeed = ShutterSpeed.AUTO
    /** True while a fixed shutter is driving exposure (AE is off). */
    @Volatile
    private var shutterLocked = false
    /** ISO currently requested by the auto-ISO loop (0 = not seeded yet). */
    @Volatile
    private var currentIso: Int = 0

    /** Exposure time reported by the last CaptureResult (0 = not yet known). */
    @Volatile
    private var actualExposureNanos: Long = 0L
    /** ISO reported by the last CaptureResult (0 = not yet known). */
    @Volatile
    private var actualIso: Int = 0

    /** Drives the 1Hz refresh of the exposure read-out while idle. */
    private val exposureTick = mutableIntStateOf(0)
    private val exposureTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var exposureTicker: Runnable? = null

    /**
     * Read-back of the resolved exposure settings.
     *
     * CameraX offers no way to observe them: `ImageProxy` carries no exposure
     * metadata and `Camera2CameraControl` is write-only. This callback is the only
     * source, and it is what lets the UI show the *real* shutter — including the
     * AE-chosen value when the user leaves it on AUTO.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            try {
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (exp != null) actualExposureNanos = exp
                if (iso != null) actualIso = iso
            } catch (_: Exception) {
                // A malformed/incomplete result must never disturb the pipeline.
            }
        }
    }

    /** Disk-backed assembler for a descriptor-v5 large transfer (null = none in progress). */
    private var segAssembler: com.airferry.app.scan.SegmentAssembler? = null
    /** Optional task selected from history; unrelated roots are ignored. */
    private var resumeRootId: String? = null

    /**
     * Sliding-window rate samples for the UI.
     * Prefer the last [RATE_WINDOW_MS] over whole-session averages so the user
     * sees near-instant throughput when the stream speeds up or stalls.
     *
     * Store symbol *counts* (not wire bytes) so a late-arriving real
     * [symbolSize] does not create a fake throughput spike from a discontinuous
     * byte counter.
     */
    private data class RateSample(val tMs: Long, val decoded: Long, val receivedSymbols: Long)
    private val rateSamples = ArrayDeque<RateSample>()
    private var decodePerSec = 0
    /** Recent wire throughput (bytes/s) over the sliding window. */
    private var recentWireBps = 0L

    // Reactive state observed by Compose
    private val uiState = mutableStateOf(UiState())

    data class UiState(
        val statusText: String = "正在初始化…",
        val progressPct: Int = 0,
        val receivedSymbols: Int = 0,
        val totalSymbols: Int = 0,
        val decodedBlocks: Int = 0,
        val totalBlocks: Int = 0,
        val lossPct: Int = 0,
        val framesSeen: Long = 0,
        val decodePerSec: Int = 0,
        val framesDropped: Long = 0,
        val fileName: String = "",
        val fileSize: Long = 0,
        /** Real compressed payload size (descriptor `compressed_size`); for
         *  segmented transfers this is the whole compressed-stream size. */
        val compressedSize: Long = 0,
        /** Zero-based current segment index when the transfer is segmented (0 otherwise). */
        val segmentIndex: Int = 0,
        /** Total segment count when the transfer is segmented (1 otherwise). */
        val segmentCount: Int = 1,
        val complete: Boolean = false,
        val jniReady: Boolean = false,
        /** Elapsed transfer time in ms (0 = not started yet). */
        val transferElapsedMs: Long = 0,
        /** RaptorQ symbol size in bytes (from the sender's config). */
        val symbolSize: Int = 0,
        /**
         * Recent wire throughput (bytes/s) over ~[RATE_WINDOW_MS], not the
         * whole-session average. 0 when the window is still empty.
         */
        val recentWireBps: Long = 0,
    )

    private val recoveryStage = mutableStateOf<String?>(null)
    /** Wall-clock ms when the transfer first started (totalSymbols > 0). */
    private var transferStartMs = 0L

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraStarted = false
                startCamera()
            } else {
                updateUi { it.copy(statusText = "需要相机权限") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeRootId = intent.getStringExtra("RESUME_ROOT_ID")
            ?.lowercase()
            ?.takeIf { id -> id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' } }

        // Keep the screen on for the whole scan session. Transfers can run for
        // many minutes; without this the system timeout dims/locks the screen,
        // stops the camera preview, and aborts an in-progress receive.
        // FLAG_KEEP_SCREEN_ON only applies while this window is visible — no
        // WAKE_LOCK permission needed, and leaving the activity restores normal
        // timeout automatically.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Shutter speed chosen in Settings (AUTO unless the user opted in). Read
        // once here: the settings screen is a separate Activity, so the value is
        // re-read in onResume when the user comes back from it.
        currentShutter = ShutterSpeed.fromPref(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(ShutterSpeed.PREF_KEY, null)
        )

        // JNI self-test + native ABI version handshake.
        // The version check must come first: a stale `.so` from an older APK
        // lacks the v5 segmented-receive symbol, so `nativeAbiVersion()` throws
        // `UnsatisfiedLinkError` while the (older) `receiverCreate` still works —
        // the old library would otherwise pass the create/destroy self-test and
        // then stall forever at "正在同步" on >32 MiB segmented transfers.
        var abiVersion = -1
        val abiOk = try {
            abiVersion = NativeBridge.nativeAbiVersion()
            abiVersion >= NativeBridge.NATIVE_ABI_VERSION
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI ABI version symbol missing (stale native lib)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "JNI ABI version query FAILED", e)
            false
        }

        val jniOk = if (!abiOk) {
            false
        } else {
            try {
                val h = NativeBridge.receiverCreate(0L, 1L, 1, 100, 1024)
                NativeBridge.receiverDestroy(h)
                true
            } catch (e: Exception) {
                Log.e(TAG, "JNI self-test FAILED", e); false
            }
        }
        updateUi {
            it.copy(
                jniReady = jniOk,
                statusText = if (!jniOk) "JNI 加载失败" else idleStatus(),
            )
        }
        if (!jniOk) {
            setContent {
                ErrorScreen(
                    if (abiOk) {
                        "原生库加载失败，请重新安装应用。"
                    } else {
                        "原生库版本过旧（ABI v$abiVersion，需要 v${NativeBridge.NATIVE_ABI_VERSION}），" +
                            "请卸载后重新安装最新版应用。"
                    }
                )
            }
            return
        }

        setContent { ScanScreen() }

        // The exposure read-out has to stay live even while idle (the decode-event
        // driven UI refresh only ticks during a transfer). A 1Hz ticker that only
        // touches the exposure state is cheaper than refreshing the whole card and
        // keeps the numbers the user is watching from going stale.
        exposureTicker = object : Runnable {
            override fun run() {
                exposureTick.value = exposureTick.value + 1
                exposureTickHandler.postDelayed(this, EXPOSURE_TICK_MS)
            }
        }.also { exposureTickHandler.postDelayed(it, EXPOSURE_TICK_MS) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @Composable
    private fun ScanScreen() {
        val state by uiState
        val recovery by recoveryStage

        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(BgDark)) {

            // Camera preview (full screen) — CameraX PreviewView + ImageAnalysis.
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { pv -> bindCameraIfNeeded(pv) }
            )

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // Circular progress indicator
                CircularProgress(
                    progress = state.progressPct / 100f,
                    label = "${state.progressPct}%",
                    sublabel = if (state.fileName.isNotEmpty()) state.fileName else "等待扫描…"
                )

                Spacer(modifier = Modifier.weight(1f))

                // Bottom info card
                if (state.totalSymbols > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // 文件标题行（大号字体，仅文件名）
                            if (state.fileName.isNotEmpty()) {
                                Text(
                                    state.fileName,
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            // 大小行（原大小~压缩后大小）。压缩后大小来自描述符
                            // （分段时为整条压缩流大小），不是线上含冗余的符号字节。
                            val showOrig = state.fileSize > 0
                            val showCompressed = state.compressedSize > 0
                            if (showOrig || showCompressed) {
                                val sizeStr = buildString {
                                    if (showOrig) {
                                        append(formatSize(state.fileSize))
                                        if (showCompressed) append("~压缩后 ")
                                    }
                                    if (showCompressed) append(formatSize(state.compressedSize))
                                }
                                InfoRow("大小", sizeStr)
                            }
                            // 分段传输：明确当前收的是第几段。
                            if (state.segmentCount > 1) {
                                InfoRow("分段", "${state.segmentIndex + 1} / ${state.segmentCount}")
                            }
                            InfoRow("已识别符号", "${state.receivedSymbols} / ${state.totalSymbols}")
                            InfoRow("解码速率", "${state.decodePerSec} 符号/秒")
                            // 传输用时 + 近几秒滑动窗口速度（非全程平均）
                            if (state.transferElapsedMs > 0) {
                                val elapsedStr = formatDuration(state.transferElapsedMs)
                                // 线上吞吐 = 最近 RATE_WINDOW_MS 内 Δ(符号×symbolSize)/Δt
                                val speedStr = if (state.recentWireBps > 0)
                                    formatSize(state.recentWireBps) + "/s" else ""
                                InfoRow("用时", if (speedStr.isNotEmpty()) "$elapsedStr @ $speedStr" else elapsedStr)
                            }
                            LinearProgressIndicator(
                                progress = { state.progressPct / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                color = Accent,
                                trackColor = Color(0xFF334155)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Status text. A live recovery stage (assemble/CRC/save) takes
                // precedence over the "✓ 文件恢复完成" snapshot, so the user sees
                // the post-scan pipeline advancing instead of a frozen 100%.
                Text(
                    text = recovery ?: state.statusText,
                    color = if (recovery != null) Accent
                            else if (state.complete) Success else TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Exposure read-out. Shows the *actual* shutter reported by the
                // camera, so the user can verify what the settings screen did
                // rather than trusting a label. `appliedShutter` reflects what was
                // requested; the value after "·" is what the sensor resolved (they
                // differ when AE is in charge, or if a request was clamped).
                ExposureChip(
                    locked = shutterLocked,
                    requested = appliedShutter,
                    actualNanos = actualExposureNanos,
                    actualIso = actualIso,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionButton(Icons.Default.Folder, "文件") {
                        startActivity(Intent(this@ScanActivity, FileListActivity::class.java))
                    }
                    ActionButton(Icons.Default.Settings, "设置") {
                        startActivity(Intent(this@ScanActivity, SettingsActivity::class.java))
                    }
                    if (state.totalSymbols > 0 || state.complete) {
                        ActionButton(Icons.Default.Refresh, "重扫") { resetSession() }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    /**
     * Live exposure read-out.
     *
     * `locked` = a fixed shutter is driving the sensor (AE off). The left side
     * shows what we asked for, the right side what the camera actually resolved —
     * the second number is the ground truth and comes from `CaptureResult`, which
     * is why it can show a real value even on AUTO.
     */
    @Composable
    private fun ExposureChip(
        locked: Boolean,
        requested: ShutterSpeed,
        actualNanos: Long,
        actualIso: Int,
    ) {
        // Observe the ticker so these volatile readings recompose ~1Hz even when no
        // frames are being decoded.
        exposureTick.value
        val actualText = if (actualNanos > 0) formatShutter(actualNanos) else "—"
        val isoText = if (actualIso > 0) "ISO $actualIso" else ""
        val label = if (locked) "快门 ${requested.label} · 实测 $actualText"
                    else "快门 自动 · 实测 $actualText"
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xCC1E293B),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (locked) Accent else TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isoText.isEmpty()) label else "$label · $isoText",
                    color = if (locked) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }

    @Composable
    private fun CircularProgress(progress: Float, label: String, sublabel: String) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = CardBg,
                modifier = Modifier.size(160.dp)
            ) {}
            // Progress ring
            androidx.compose.foundation.Canvas(modifier = Modifier.size(160.dp)) {
                val stroke = 8.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                val arc = androidx.compose.ui.geometry.Size(diameter, diameter)
                drawArc(
                    color = Color(0xFF334155),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
                drawArc(
                    color = Accent,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = topLeft, size = arc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    sublabel.take(20),
                    color = TextSecondary, fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        ) {
            Text(label, color = TextSecondary, fontSize = 13.sp)
            Text(
                value, color = TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
                Icon(icon, contentDescription = label, tint = Accent)
            }
            Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun ErrorScreen(msg: String) {
        Box(
            modifier = Modifier.fillMaxSize().background(BgDark),
            contentAlignment = Alignment.Center
        ) {
            Text(msg, color = TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        }
    }

    // ===== Camera + session logic =====

    private fun bindCameraIfNeeded(previewView: PreviewView) {
        this.previewView = previewView
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (cameraStarted) return
        cameraStarted = true
        startCameraWithView(previewView)
    }

    private fun startCamera() {
        val view = previewView ?: return
        bindCameraIfNeeded(view)
    }

    /** Lazily create + start the shared parallel decode pool. */
    private fun ensurePool(): QrDecodePool {
        var p = decodePool
        if (p == null) {
            // Multi-QR mode is always on: the pool decodes every code on screen per
            // frame (not just the first), so a sender tiling N codes yields ~N×
            // throughput. Single-code senders decode just as well (the multi path
            // returns one result), so there's no need for a user-facing toggle — it
            // worked regardless of the switch position, and only added confusion.
            p = QrDecodePool(
                onDecoded = { payload, _ -> handleFrameAsync(payload) },
                multiMode = true,
            ).also { it.start() }
            decodePool = p
        }
        return p
    }

    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
    )
    private fun startCameraWithView(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Get/create the parallel decode pool. Each decoded payload is fed
                // to the native receiver via handleFrameAsync, serialized by the
                // pool's ingest lock so the non-thread-safe JNI handle is only ever
                // touched by one thread at a time.
                val pool = ensurePool()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Request a 1080p analysis stream so each QR module has more camera pixels,
                // improving ZXing decode reliability — especially important with the
                // reduced quiet zone on multi-QR. CameraX may pick the closest
                // supported size.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // The analyzer owns the auto-ISO loop and needs to know whether a
                // fixed shutter is active before it bothers sampling luma.
                val analyzer = QrStreamAnalyzer(pool)
                analyzer.onMeanLuma = { mean -> onAnalysisLuma(mean) }
                boundAnalyzer = analyzer

                fun buildAnalysis(fpsRange: Range<Int>): ImageAnalysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolutionSelector)
                        .also { builder ->
                            // Pin the sensor frame-rate via Camera2Interop (CameraX
                            // 1.3.x has no public ImageAnalysis#setTargetFrameRate).
                            Camera2Interop.Extender(builder).setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange
                            )
                        }
                        .build()
                        .also {
                            attachExposureReadback(it)
                            it.setAnalyzer(cameraExecutor, analyzer)
                        }

                // Camera2 view of the selected camera — source of the exposure and
                // frame-rate capabilities used below (and by the settings screen).
                val cam2Info = Camera2CameraInfo.from(
                    cameraProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                )
                readCameraCapabilities(cam2Info)
                val fpsRange = chooseFpsRange(cam2Info)

                cameraProvider.unbindAll()
                val camera = try {
                    cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, buildAnalysis(fpsRange)
                    )
                } catch (e: Exception) {
                    // Last-resort fallback: if even the capability-derived range is
                    // rejected, bind with no fps constraint at all rather than
                    // failing to open the camera entirely.
                    Log.w(TAG, "bind with fps=$fpsRange failed; rebinding unconstrained", e)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, buildAnalysis(DEFAULT_FPS_RANGE)
                    )
                }

                boundCamera = camera
                // Apply the shutter chosen in Settings (defaults to AUTO).
                applyShutter(camera)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                cameraStarted = false
                updateUi { it.copy(statusText = "相机启动失败") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Read the exposure/frame-rate capabilities of the active camera.
     *
     * Logged at `Log.i` on purpose: release builds strip only `Log.d`/`Log.v`
     * (see `proguard-rules.pro`), and these values are the first thing needed when
     * a device behaves unexpectedly. Through v1.2.9 the scanner requested
     * `[60,60]` on cameras that only advertise a 30fps ceiling; the HAL accepted
     * the bind but clamped the range, AE ran at a 33ms frame period and settled on
     * a ~1/30s exposure with no warning anywhere. Printing what the device
     * actually supports makes that class of failure visible instead of silent.
     */
    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
    )
    private fun readCameraCapabilities(info: Camera2CameraInfo) {
        try {
            val ranges = info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            exposureRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            sensitivityRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val caps = info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            supportsManualSensor = caps?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true
            val hwLevel = info.getCameraCharacteristic(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
            )
            Log.i(
                TAG,
                "camera caps: fpsRanges=${ranges?.joinToString(" ") { "[${it.lower} ${it.upper}]" }} " +
                    "exposureRange=${exposureRange?.lower}..${exposureRange?.upper}ns " +
                    "isoRange=${sensitivityRange?.lower}..${sensitivityRange?.upper} " +
                    "manualSensor=$supportsManualSensor hwLevel=$hwLevel"
            )
            // Cache the exposure range so SettingsActivity can hide presets this
            // device cannot honor. Written here because this is the only place with
            // an open camera to read the characteristic from.
            exposureRange?.let { r ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong(ShutterSpeed.PREF_EXPOSURE_MIN, r.lower)
                    .putLong(ShutterSpeed.PREF_EXPOSURE_MAX, r.upper)
                    .apply()
            }
        } catch (e: Exception) {
            // Capability queries must never take the camera down: without them the
            // scanner still works, it just cannot offer a fixed shutter.
            Log.w(TAG, "camera capability query failed", e)
        }
    }

    /**
     * Pick a frame-rate range the device actually advertises.
     *
     * This replaces the previous hard-coded `Range(60, 60)` with its `[30,60]`
     * fallback. Both values are absent from many devices' capability lists (the
     * reference device only offers up to `[30,30]`), and an unsupported range does
     * **not** make `bindToLifecycle` throw — the HAL merely ignores it. So the old
     * catch-based fallback never ran, the app believed it had pinned 60fps, and the
     * exposure could silently stretch to the real 33ms frame period.
     *
     * Preferring the highest `upper` bound pins the frame period as short as the
     * device allows, which is both a higher symbol rate and a hard ceiling on how
     * long AE can stretch the exposure while the shutter is "automatic".
     */
    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
    )
    private fun chooseFpsRange(info: Camera2CameraInfo): Range<Int> {
        val ranges = try {
            info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )?.toList()
        } catch (e: Exception) {
            Log.w(TAG, "fps range query failed", e)
            null
        }
        if (ranges.isNullOrEmpty()) {
            Log.w(TAG, "no fps ranges advertised; using unconstrained")
            return DEFAULT_FPS_RANGE
        }
        // Highest upper bound wins; among equals prefer the wider (lower) range so
        // a dim scene can still trade frame rate for exposure when on AUTO.
        val best = ranges.maxWithOrNull(compareBy({ it.upper }, { -it.lower })) ?: DEFAULT_FPS_RANGE
        Log.i(TAG, "fps range chosen=[${best.lower} ${best.upper}] (from ${ranges.size} advertised)")
        return best
    }

    /**
     * Install the per-frame exposure read-back callback.
     *
     * Registered on the ImageAnalysis use case (the stream we actually care about)
     * rather than the camera as a whole, because CameraX surfaces CaptureResults
     * per use case. Values land in [actualExposureNanos]/[actualIso] and are picked
     * up by the throttled UI refresh.
     */
    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
    )
    private fun attachExposureReadback(analysis: ImageAnalysis) {
        try {
            Camera2Interop.Extender(analysis).setSessionCaptureCallback(captureCallback)
        } catch (e: Exception) {
            Log.w(TAG, "exposure read-back not installed", e)
        }
    }

    /**
     * Apply the shutter speed chosen in Settings.
     *
     * Uses **session-level** [Camera2CameraControl] rather than a per-UseCase
     * extender. AE mode is device-global state: if only ImageAnalysis requested
     * `CONTROL_AE_MODE = OFF` while Preview kept `ON`, the two alternating
     * repeating requests would fight, producing visible preview flicker and an
     * exposure that jumps between the locked and automatic values. Setting it once
     * at session scope applies to every use case, and — unlike rebinding — takes
     * effect immediately without tearing down the camera.
     */
    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
    )
    private fun applyShutter(camera: Camera) {
        val speed = currentShutter
        val control = try {
            Camera2CameraControl.from(camera.cameraControl)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2CameraControl unavailable; staying on AUTO", e)
            return
        }
        currentIso = 0 // force the loop to re-seed from the controller
        if (!speed.isManual) {
            control.clearCaptureRequestOptions()
            setShutterLocked(false)
            appliedShutter = ShutterSpeed.AUTO
            Log.i(TAG, "shutter=AUTO (AE in charge)")
            return
        }
        val expMin = exposureRange?.lower?.toLong() ?: 0L
        val expMax = exposureRange?.upper?.toLong() ?: 0L
        val plan = planManualExposure(speed, expMin, expMax, supportsManualSensor)
        if (!plan.ok) {
            // Never pretend: fall back to AUTO and say why.
            control.clearCaptureRequestOptions()
            setShutterLocked(false)
            appliedShutter = ShutterSpeed.AUTO
            Log.w(TAG, "shutter ${speed.label} unsupported: ${plan.problem}; staying on AUTO")
            updateUi { it.copy(statusText = "本机不支持固定快门") }
            return
        }
        if (plan.clamped) {
            Log.w(TAG, "shutter ${speed.label} clamped to ${plan.exposureNanos}ns by sensor range")
        }
        val iso = seedIso()
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNanos)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .build()
        // `setCaptureRequestOptions` replaces the bundle outright; `add` would merge
        // with whatever was applied before (e.g. a previous ISO from the auto loop),
        // leaving stale keys behind when switching presets.
        control.setCaptureRequestOptions(opts)
        setShutterLocked(true)
        appliedShutter = speed
        currentIso = iso
        Log.i(TAG, "shutter applied: ${speed.label} (${plan.exposureNanos}ns) iso=$iso")
    }

    /**
     * Single place that flips the "shutter locked" state.
     *
     * Two consumers must agree: this Activity (for the UI read-out) and the
     * analyzer (which skips luma sampling unless a fixed shutter is active).
     * Updating them through one setter prevents the two from drifting apart.
     */
    private fun setShutterLocked(locked: Boolean) {
        shutterLocked = locked
        boundAnalyzer?.shutterLocked = locked
    }

    /** First ISO guess for a fixed shutter: mid-range, then corrected by the loop. */
    private fun seedIso(): Int {
        val r = sensitivityRange
        if (r == null) return 400
        val min = r.lower
        val max = r.upper
        if (max < min || min <= 0) return 400
        // Start at half the range: a locked 1/60s on a typical indoor screen lands
        // near there, so the controller converges in a couple of frames either way.
        return (min + (max - min) / 2).coerceIn(min, max)
    }

    /** Auto-ISO feedback, called (throttled) from the analysis thread. */
    private fun onAnalysisLuma(mean: Double) {
        val camera = boundCamera ?: return
        if (!shutterLocked) return
        val r = sensitivityRange ?: return
        val current = currentIso
        if (current <= 0) return
        val next = nextAutoIso(mean, current, r.lower, r.upper)
        if (next == current) return
        currentIso = next
        try {
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, appliedShutter.nanos)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, next)
                .build()
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(opts)
        } catch (e: Exception) {
            Log.w(TAG, "auto-iso update failed", e)
        }
    }


    private var lastUiUpdate = 0L
    /** Written on the main thread AND from ioExecutor (recovery failure /
     *  re-archive paths), read on the main thread (onResume) → must be volatile. */
    @Volatile
    private var completedHandled = false
    /** Once recovery completes, stop feeding the native receiver so the
     *  main-thread assemble() (a `&` borrow) can't race a worker ingest (`&mut`). */
    private val ingestStopped = AtomicBoolean(false)

    /**
     * Pure-data snapshot produced on a decode-worker thread and handed to the
     * main thread. Keeping every JNI / JSON / RaptorQ step off the main thread is
     * what lets the receiver keep up with the camera — the heavy ingest chain
     * runs on the [QrDecodePool]'s serialized ingest path, and only the throttled
     * UI snapshot is posted to the main thread.
     */
    private data class FrameSnapshot(
        val progress: ReceiverSessionManager.Progress,
        val fileName: String,
        val fileSize: Long,
        /** Real compressed size (whole compressed stream for segmented). */
        val compressedSize: Long,
        /** Zero-based current segment index (0 when not segmented). */
        val segmentIndex: Int,
        /** Total segment count (1 when not segmented). */
        val segmentCount: Int
    )

    /** Ingest-thread entry (serialized by the pool): heavy work here, post a snapshot. */
    private fun handleFrameAsync(payload: ByteArray) {
        // After completion, drop further frames: the main thread is (or will be)
        // calling assemble() on the receiver, which must not run concurrently
        // with another ingest. This runs under the pool's ingest lock, so the
        // check+ingest+stop sequence is atomic w.r.t. other workers.
        if (ingestStopped.get()) return
        // ingest() returns a lightweight status (no JSON) so the per-frame path
        // stays cheap; the full progress is fetched only on the throttled UI tick.
        val status = session.ingest(payload) ?: return

        // Duplicate-segment fast path — evaluated on DESCRIPTOR frames only
        // (the sender re-sends the descriptor every 17 frames, so this still
        // fires promptly). The old implementation ran per data symbol, doing a
        // disk-ledger read + JSON parse (+ a full ~32 MiB SHA-256 in
        // hasStoredSegment) and an unconditional Log.w for EVERY ingested
        // symbol. Once the descriptor confirms the segment metadata, if this
        // segment is already stored there is no point receiving the whole
        // (~32 MiB) segment again — skip straight to the next one. Runs here
        // (ingest lock) so the re-scan of a completed segment is rejected on
        // the descriptor frame itself, before the UI even shows "receiving".
        if (!status.complete && session.isInitialized && session.isSegmented() &&
            payload.size > 3 &&
            (payload[3].toInt() and 0xFF and ReceiverSessionManager.FLAG_DESCRIPTOR) != 0
        ) {
            val idx = session.segmentIndex()
            val cnt = session.segmentCount()
            val lo = session.rootSessionIdLo()
            val hi = session.rootSessionIdHi()
            // In-memory ledger for the ongoing transfer; fall back to the
            // durable disk ledger (e.g. after the app restarted / resume) so a
            // re-scanned already-completed segment is still rejected.
            val asm = segAssembler
            val inMem = asm != null && asm.rootSessionIdLo() == lo && asm.rootSessionIdHi() == hi
            val dup = if (inMem) asm!!.hasSegment(idx)
                else com.airferry.app.scan.SegmentAssembler.hasStoredSegment(com.airferry.app.scan.ContentStore.root(this), lo, hi, idx)
            if (dup) {
                val rootHex = rootSessionIdHex(lo, hi)
                // H2: before skipping, check whether the ledger is ALREADY
                // complete. If the promotion into ContentStore was interrupted
                // (asm.finish() failed on a full disk, or the process died
                // between finish() and putFile), blindly swapping to the next
                // segment makes the crash-recovery archive branch in
                // handleSegmentedTransfer unreachable — the data would stay
                // locked in .partial forever. A complete ledger must re-run the
                // idempotent archive instead of skipping.
                val ledgerComplete = if (inMem) asm!!.isComplete()
                else com.airferry.app.scan.SegmentAssembler.listTasks(
                    com.airferry.app.scan.ContentStore.root(this)
                ).any { it.rootSessionIdHex == rootHex && it.receivedCount >= it.segmentCount }
                if (ledgerComplete && (resumeRootId == null || resumeRootId == rootHex)) {
                    enqueueSegmentedReArchive(if (inMem) asm else null)
                    return
                }
                Log.i(TAG, "dupSeg: segIdx=$idx cnt=$cnt inMem=$inMem => skip to next segment")
                val dupText = "第 ${idx + 1}/$cnt 段已接收过，自动跳过"
                runOnUiThread { updateUi { it.copy(statusText = dupText) } }
                swapReceiverForNextSegment()
                return
            }
        }

        // UI refresh throttle: ~7 Hz is plenty for a progress bar, and keeps the
        // main thread free. Always let the final "complete" frame through.
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 150 && !status.complete) return
        lastUiUpdate = now

        // On the UI tick (or completion), pull the full progress snapshot. This
        // is the only place the JSON is parsed — not every frame.
        val progress = session.progress() ?: return

        // Read file metadata from session (JNI) — keep on this background thread.
        val fn = if (session.isInitialized) session.fileName() else ""
        val fs = if (session.isInitialized) session.fileSize() else 0L
        // Real compressed size. For a segmented transfer each child session only
        // reports THIS segment's compressed length; the user-facing "压缩后" is
        // the whole compressed stream (root_original_size). For a single-object
        // transfer compressed_size is exactly the payload size.
        val segmented = session.isInitialized && session.isSegmented()
        val cs = if (session.isInitialized) {
            if (segmented) session.rootOriginalSize() else session.compressedSize()
        } else {
            0L
        }
        val segIdx = if (segmented) session.segmentIndex() else 0
        val segCount = if (segmented) session.segmentCount() else 1

        val snapshot = FrameSnapshot(progress, fn, fs, cs, segIdx, segCount)
        if (status.complete) {
            // Block any further ingest before the completion path (assemble +
            // file I/O + Activity start) runs on the main thread.
            ingestStopped.set(true)
            runOnUiThread { applySnapshot(snapshot, handleCompletion = true) }
        } else {
            runOnUiThread { applySnapshot(snapshot, handleCompletion = false) }
        }
    }

    /** Main-thread only: apply the precomputed snapshot to Compose state. */
    private fun applySnapshot(s: FrameSnapshot, handleCompletion: Boolean) {
        val progress = s.progress
        // Progress bar tracks *received (de-duplicated) symbols*, not decoded
        // symbols. RaptorQ decodes a whole source block at once when it has
        // collected enough independent symbols, so a "decoded fraction" bar sits
        // flat near 0% for a long time and then jumps in steps — it reads as
        // "stuck". The received-symbol count, by contrast, increments by one
        // for every new symbol the receiver accepts, so the bar climbs ~linearly
        // and matches what the user sees on screen. Fountain repair symbols can
        // push receivedSymbols above totalSymbols K, so clamp to 100.
        val pct = when {
            progress.complete -> 100
            progress.metaConfirmed || progress.totalSymbols > 0 -> {
                if (progress.totalSymbols > 0) {
                    (progress.receivedSymbols * 100 / progress.totalSymbols).coerceIn(0, 100)
                } else {
                    0
                }
            }
            // Cache mode: no confirmed total yet. Estimate from the first frame's
            // total_symbols (advisory only) and cap at 15% — the descriptor may
            // later reveal a larger total, so don't over-promise early.
            progress.receivedSymbols > 0 -> {
                val estimated = session.getEstimatedTotalSymbols()
                if (estimated > 0) {
                    (progress.receivedSymbols * 100 / estimated).coerceIn(0, 15)
                } else {
                    0
                }
            }
            else -> 0
        }
        val statusMsg = when {
            progress.complete -> "✓ 文件恢复完成"
            !progress.metaConfirmed && progress.receivedSymbols > 0 ->
                "⏳ 正在同步… 已缓存 ${progress.receivedSymbols} 符号 (~$pct%)"
            progress.totalSymbols == 0 -> "等待二维码…"
            progress.receivedSymbols > 0 && progress.decodedBlocks == 0 ->
                "接收中… ${progress.receivedSymbols}/${progress.totalSymbols} 符号 (等待解码)"
            else -> "恢复中… $pct%"
        }
        // Sliding-window rates: decode symbols/s + wire bytes/s over RATE_WINDOW_MS.
        // UI ticks are already throttled (~7 Hz), so each sample is a fresh point;
        // prune anything older than the window and derive Δcount/Δt.
        val pool = decodePool
        val nowMs = System.currentTimeMillis()
        // Rate math uses ≥1 so early pre-descriptor ticks don't div0; samples
        // store symbol counts so a late real symbolSize never rewrites history.
        val symbolSize = session.symbolSizeBytes().coerceAtLeast(1)
        val receivedNow = progress.receivedSymbols.toLong().coerceAtLeast(0)
        val decodedNow = pool?.decodedCount() ?: 0L
        if (progress.complete) {
            // Freeze at 0 once done — final tick would otherwise show the last
            // non-zero window forever on the completed card.
            decodePerSec = 0
            recentWireBps = 0L
            rateSamples.clear()
        } else if (receivedNow > 0L || decodedNow > 0L) {
            rateSamples.addLast(RateSample(nowMs, decodedNow, receivedNow))
            while (rateSamples.size > 1 && nowMs - rateSamples.first().tMs > RATE_WINDOW_MS) {
                rateSamples.removeFirst()
            }
            if (rateSamples.size >= 2) {
                val oldest = rateSamples.first()
                val newest = rateSamples.last()
                val dt = newest.tMs - oldest.tMs
                // Need a short baseline so a single tick doesn't explode the rate.
                if (dt >= RATE_MIN_DT_MS) {
                    decodePerSec = (((newest.decoded - oldest.decoded) * 1000L) / dt)
                        .toInt().coerceAtLeast(0)
                    val dSym = (newest.receivedSymbols - oldest.receivedSymbols).coerceAtLeast(0L)
                    recentWireBps = ((dSym * symbolSize * 1000L) / dt).coerceAtLeast(0L)
                }
            } else {
                // Window collapsed (e.g. long stall then one fresh tick) — don't
                // keep showing a stale non-zero rate from before the gap.
                decodePerSec = 0
                recentWireBps = 0L
            }
        }
        val droppedTotal = pool?.droppedCount() ?: 0L

        // Start the transfer timer on first symbol receipt.
        if (progress.totalSymbols > 0 && transferStartMs == 0L) {
            transferStartMs = nowMs
        }
        val elapsedMs = if (transferStartMs > 0) nowMs - transferStartMs else 0L

        updateUi {
            it.copy(
                progressPct = pct,
                receivedSymbols = progress.receivedSymbols,
                totalSymbols = progress.totalSymbols,
                decodedBlocks = progress.decodedBlocks,
                totalBlocks = progress.totalBlocks,
                lossPct = (progress.lossRatio * 100).toInt(),
                framesSeen = progress.framesSeen,
                decodePerSec = decodePerSec,
                framesDropped = droppedTotal,
                fileName = s.fileName,
                fileSize = s.fileSize,
                compressedSize = s.compressedSize,
                segmentIndex = s.segmentIndex,
                segmentCount = s.segmentCount,
                statusText = statusMsg,
                complete = progress.complete,
                transferElapsedMs = elapsedMs,
                symbolSize = symbolSize,
                recentWireBps = recentWireBps,
            )
        }

        if (handleCompletion && progress.complete && !completedHandled) {
            completedHandled = true
            // Move the heavy recovery work (JNI assemble, CRC over the full
            // payload, disk writes, bundle unpacking) off the main thread — it
            // previously ran here synchronously and ANR'd on multi-MB transfers.
            // ingestStopped (set on the completing worker) already guarantees no
            // further ingest touches the native session, and we wrap the JNI
            // access in runExclusive so it cannot race a straggler or destroy().
            val snapshotFileName = s.fileName
            // Capture the pool at enqueue time and use THIS captured ref inside
            // the task — never re-read the `decodePool` field. onDestroy (main
            // thread) nulls `decodePool` and captures `session` to a local for its
            // own background destroy via the SAME pool instance. If this task
            // re-read the field it could (a) take the lock-less `?: work()`
            // branch once onDestroy has nulled the field, and (b) race destroy()
            // on the native handle (isInitialized is only a TOCTOU hint, not a
            // real guard). By pinning the pool here, the recovery and onDestroy's
            // destroy are GUARANTEED to serialize on the same pool.runExclusive
            // (ingestLock) — recovery holds the lock while assemble() runs,
            // destroy blocks on the lock until recovery returns, no
            // use-after-free, no TOCTOU. recoverAndStage reads the `session`
            // field, but onDestroy never reassigns it (only destroys in place),
            // so after destroy the field's isInitialized==false and the guarded
            // getters no-op → recoverAndStage returns null harmlessly.
            val poolAtEnqueue = decodePool
            ioExecutor.execute {
                try {
                    var intent: Intent? = null
                    val work = fun() {
                        intent = recoverAndStage(snapshotFileName)
                    }
                    // Always serialize via the captured pool. If the pool was
                    // already null at enqueue (shouldn't happen mid-scan, but be
                    // defensive), skip recovery entirely — calling recoverAndStage
                    // without the lock would race destroy() on the native handle.
                    poolAtEnqueue?.runExclusive(work)
                    intent?.let { runOnUiThread { startActivity(it) } }
                } catch (e: Exception) {
                    clearRecoveryStage()
                    resetReceiverAfterRecoveryFailure()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            e.message ?: "保存接收内容失败",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (e: OutOfMemoryError) {
                    // A large recovered payload (e.g. multi-MB text decoded to a
                    // ~2x String) can transiently exceed the default heap. Do not
                    // crash the whole scanner — drop to a graceful message. The
                    // bytes are typically already persisted by this point, so the
                    // user can reopen the file from the list.
                    android.util.Log.e("ScanActivity", "recoverAndStage OOM", e)
                    clearRecoveryStage()
                    resetReceiverAfterRecoveryFailure()
                    runOnUiThread {
                        Toast.makeText(this, "文件过大，接收内存不足", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Assemble the recovered bytes, verify CRC, and stage the file(s) to disk.
     * Returns the [Intent] to launch the detail/bundle screen, or null if there
     * was nothing to recover. Runs on a background thread under the decode pool's
     * ingest lock (so it can't race an in-flight ingest or a destroy()).
     */
    private fun recoverAndStage(displayName: String): Intent? {
        updateRecoveryStage("正在组装数据…")
        if (session.isSegmented()) {
            // Descriptor-v5 large transfer: recover this segment's **compressed**
            // bytes (no per-segment decompression — the whole stream is
            // decompressed once after every segment arrives) and store it.
            val compressed = session.assembleRawBytes() ?: run {
                clearRecoveryStage()
                if (session.isComplete()) {
                    runOnUiThread {
                        Toast.makeText(this, "恢复失败: 分段组装失败", Toast.LENGTH_LONG).show()
                    }
                    swapReceiverForNextSegment()
                }
                return null
            }
            return handleSegmentedTransfer(displayName, compressed)
        }
        val fileBytes = session.assemble() ?: run {
            clearRecoveryStage()
            if (session.isComplete()) {
                val detail = session.lastAssembleError().ifEmpty { "数据组装或解压失败" }
                runOnUiThread {
                    Toast.makeText(this, "恢复失败: $detail", Toast.LENGTH_LONG).show()
                }
                // A completed-but-unassemblable child must not leave the
                // scanner permanently stopped. Keep durable earlier segments
                // and accept a fresh scan immediately.
                swapReceiverForNextSegment()
            }
            return null
        }
        if (resumeRootId != null) {
            clearRecoveryStage()
            runOnUiThread {
                updateUi { it.copy(statusText = "已忽略其他传输，继续等待选中的恢复任务…") }
            }
            swapReceiverForNextSegment()
            return null
        }
        val originalSize = session.fileSize()
        // Truncate RaptorQ zero-padding back to the original size. originalSize
        // is a Long (up to 2^63); clamp to the bytes we actually recovered and
        // never let a bogus/large value overflow Int (the old `originalSize.toInt()`
        // would wrap for >2GB and throw IndexOutOfBounds in copyOfRange).
        val truncLen = when {
            originalSize > 0 && originalSize <= fileBytes.size -> originalSize.toInt()
            else -> fileBytes.size
        }
        // v3 assemble normally already returns exactly originalSize. Reuse that
        // array instead of allocating a second full-size ByteArray; only legacy
        // padded results need an actual truncation copy.
        val truncBytes = if (truncLen == fileBytes.size) {
            fileBytes
        } else {
            fileBytes.copyOfRange(0, truncLen)
        }

        updateRecoveryStage("正在校验完整性…")
        val expectedCrc = session.crc32()
        val crcKnown = session.crc32Known()
        val receivedCrc = crc32OfBytes(truncBytes)

        // Content-addressed store: one blob per unique content; detail/share/list
        // all use the blob path (no recovered_* + received/ double-write).
        val store = com.airferry.app.scan.ContentStore

        // Text payload → ETTEXTv1. Detected BEFORE the bundle check.
        // Prefer the descriptor filename (sender select-page name); fall back
        // to the default only when the descriptor never supplied one.
        //
        // The 8-byte wire magic is stripped up front: BOTH the in-memory text
        // path and the oversized→file fallback below must see the message
        // text — the fallback previously saved the raw wire bytes, putting the
        // literal "ETTEXTv1" protocol header at the start of the user's file.
        if (TextParser.isText(truncBytes)) {
            val messageBytes = TextParser.payloadWithoutMagic(truncBytes)
            // Prefer descriptor name; ensure a .txt-ish save label for pure text.
            // (Sender already normalizes to *.txt; this is a receive-side belt.)
            val textName = when {
                displayName.isEmpty() -> TEXT_RECEIVED_NAME
                displayName.contains('.') -> displayName
                else -> "$displayName.txt"
            }
            // Size guard: only decode into the in-memory text UI when it fits
            // the text cap — decoding a multi-MB message into a ~2x UTF-16
            // String plus a re-encode balloons the JVM heap on low-end devices.
            val text = if (com.airferry.app.scan.TextLike.fitsTextUi(messageBytes.size))
                TextParser.parse(truncBytes)
            else
                null
            if (text != null) {
                updateRecoveryStage("正在保存文字…")
                val contentBytes = text.toByteArray(Charsets.UTF_8)
                val contentCrc = crc32OfBytes(contentBytes)
                val crcHex = java.lang.Long.toHexString(contentCrc)
                val put = store.putBytes(
                    this, textName, contentBytes,
                    crcHex = crcHex, crcUnknown = false, kind = "text",
                )
                clearRecoveryStage()
                return Intent(this, ReceiveTextActivity::class.java).apply {
                    putExtra("FILE_PATH", put.path.absolutePath)
                    // Prefer the user-facing name (descriptor) over store sanitization.
                    putExtra("FILE_NAME", textName)
                    putExtra("ENTRY_ID", put.entry.id)
                    putExtra("CRC32", expectedCrc)
                    putExtra("CRC32_RECEIVED", receivedCrc)
                    putExtra("CRC32_UNKNOWN", !crcKnown)
                }
            }
            // Oversized (over the text cap) or invalid UTF-8 → ordinary .txt
            // FILE, magic stripped.
            updateRecoveryStage("正在保存文件…")
            val contentCrc = crc32OfBytes(messageBytes)
            val put = store.putBytes(
                this, textName, messageBytes,
                crcHex = java.lang.Long.toHexString(contentCrc),
                crcUnknown = false, kind = "file",
            )
            clearRecoveryStage()
            return Intent(this, ReceiveDetailActivity::class.java).apply {
                putExtra("FILE_PATH", put.path.absolutePath)
                putExtra("FILE_SIZE", messageBytes.size.toLong())
                putExtra("FILE_NAME", textName)
                putExtra("ENTRY_ID", put.entry.id)
                putExtra("CRC32", expectedCrc)
                putExtra("CRC32_RECEIVED", receivedCrc)
                putExtra("CRC32_UNKNOWN", !crcKnown)
                // Already archived into ContentStore — do not copy again.
                putExtra("RESAVE", true)
            }
        }

        // Multi-file bundle → one ContentStore entry per member, shared bundleId.
        if (BundleParser.isBundle(truncBytes)) {
            val bundle = BundleParser.parse(truncBytes)
            if (bundle != null && bundle.files.isNotEmpty()) {
                val totalFiles = bundle.files.size
                val paths = ArrayList<String>()
                val names = ArrayList<String>()
                val sizes = ArrayList<String>()
                val entryIds = ArrayList<String>()
                val ts = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val bundleId = java.util.UUID.randomUUID().toString()
                val bundleTitle = "发送_$ts"
                updateRecoveryStage("正在保存 $totalFiles 个文件…")
                val puts = store.putBytesBatch(
                    this,
                    bundle.files.map { f ->
                        com.airferry.app.scan.ContentStore.PutBytesRequest(
                            f.name, f.data,
                        crcHex = "unknown", crcUnknown = true, kind = "file",
                        bundleId = bundleId, bundleTitle = bundleTitle,
                        )
                    },
                )
                for ((f, put) in bundle.files.zip(puts)) {
                    paths.add(put.path.absolutePath)
                    names.add(f.name)
                    sizes.add(f.data.size.toString())
                    entryIds.add(put.entry.id)
                }
                clearRecoveryStage()
                return Intent(this, ReceiveBundleActivity::class.java).apply {
                    putStringArrayListExtra("FILE_PATHS", paths)
                    putStringArrayListExtra("FILE_NAMES", names)
                    putStringArrayListExtra("FILE_SIZES", sizes)
                    putStringArrayListExtra("ENTRY_IDS", entryIds)
                    putExtra("CRC32", expectedCrc)
                    putExtra("CRC32_RECEIVED", receivedCrc)
                    putExtra("CRC32_UNKNOWN", !crcKnown)
                }
            }
        }

        // Single-file path (or text-like). Canonical store path only.
        updateRecoveryStage("正在保存文件…")
        val finalName = if (displayName.isNotEmpty()) displayName else "received_file"
        val contentCrc = crc32OfBytes(truncBytes)
        val crcHex = if (crcKnown) java.lang.Long.toHexString(expectedCrc) else java.lang.Long.toHexString(contentCrc)
        val crcUnknown = !crcKnown

        if (com.airferry.app.scan.TextLike.isTextLikeName(finalName) &&
            com.airferry.app.scan.TextLike.fitsTextUi(truncBytes.size)
        ) {
            val text = com.airferry.app.scan.TextLike.decodeUtf8Strict(truncBytes)
            if (text != null) {
                val archiveLabel =
                    if (finalName.contains('.')) finalName else TEXT_RECEIVED_NAME
                val put = store.putBytes(
                    this, archiveLabel, truncBytes,
                    crcHex = java.lang.Long.toHexString(contentCrc),
                    crcUnknown = false,
                    kind = "text",
                )
                clearRecoveryStage()
                return Intent(this, ReceiveTextActivity::class.java).apply {
                    putExtra("FILE_PATH", put.path.absolutePath)
                    putExtra("FILE_NAME", finalName)
                    putExtra("ENTRY_ID", put.entry.id)
                    putExtra("CRC32", if (crcKnown) expectedCrc else contentCrc)
                    putExtra("CRC32_RECEIVED", contentCrc)
                    putExtra("CRC32_UNKNOWN", !crcKnown)
                }
            }
        }

        val put = store.putBytes(
            this, finalName, truncBytes,
            crcHex = crcHex, crcUnknown = crcUnknown, kind = "file",
        )
        clearRecoveryStage()
        return Intent(this, ReceiveDetailActivity::class.java).apply {
            putExtra("FILE_PATH", put.path.absolutePath)
            putExtra("FILE_SIZE", if (originalSize > 0) originalSize else truncBytes.size.toLong())
            putExtra("FILE_NAME", finalName)
            putExtra("ENTRY_ID", put.entry.id)
            putExtra("CRC32", expectedCrc)
            putExtra("CRC32_RECEIVED", receivedCrc)
            putExtra("CRC32_UNKNOWN", !crcKnown)
            // Already archived into ContentStore — do not copy again.
            putExtra("RESAVE", true)
        }
    }

    /**
     * Store one recovered descriptor-v5 segment into the disk-backed assembler.
     *
     * Returns an Intent (navigates to the detail page) only once every segment
     * of the root transfer has arrived and been merged; otherwise null (the
     * receiver keeps scanning for the next segment).
     */
    private fun handleSegmentedTransfer(displayName: String, compressedBytes: ByteArray): Intent? {
        val index = session.segmentIndex()
        val count = session.segmentCount()
        // rootSize = whole **compressed** stream size (descriptor root_original_size).
        val rootSize = session.rootOriginalSize()
        val lo = session.rootSessionIdLo()
        val hi = session.rootSessionIdHi()
        // segSize = this segment's **compressed** length (descriptor compressed_size).
        val segSize = session.compressedSize()
        val originalOffset = session.originalOffset()
        val compression = session.compression()
        val decompressedSize = session.originalSize()
        val crc32 = session.crc32()
        val crc32Known = session.crc32Known()
        val expectedSha256 = requireNotNull(session.rawSha256()) {
            "分段描述符缺少 SHA-256"
        }
        val rootSha256 = requireNotNull(session.rootSha256()) {
            "分段描述符缺少整文件 SHA-256"
        }

        require(count in 1..com.airferry.app.scan.SegmentAssembler.MAX_SEGMENT_COUNT) {
            "分段数量超出安全上限"
        }
        require(rootSize > 0 && originalOffset == index.toLong() *
            com.airferry.app.scan.SegmentAssembler.SEGMENT_RAW_BYTES) {
            "分段偏移或根文件大小无效"
        }
        require(segSize in 1..com.airferry.app.scan.SegmentAssembler.SEGMENT_RAW_BYTES) {
            "分段长度无效"
        }
        require(compressedBytes.size.toLong() == segSize) {
            "分段实际长度 ${compressedBytes.size} 与描述符 $segSize 不一致"
        }
        require(expectedSha256.size == 32) { "分段描述符 SHA-256 长度无效" }
        require(rootSha256.size == 32) { "整文件 SHA-256 长度无效" }
        val expectedCount = (rootSize - 1) /
            com.airferry.app.scan.SegmentAssembler.SEGMENT_RAW_BYTES + 1
        val expectedLength = minOf(
            com.airferry.app.scan.SegmentAssembler.SEGMENT_RAW_BYTES,
            rootSize - originalOffset,
        )
        require(index in 0 until count && count.toLong() == expectedCount && segSize == expectedLength) {
            "分段数量或本段长度与根文件不一致"
        }

        val actualRootId = rootSessionIdHex(lo, hi)
        val targetRootId = resumeRootId
        if (targetRootId != null && actualRootId != targetRootId) {
            clearRecoveryStage()
            runOnUiThread {
                Toast.makeText(this, "已忽略其他大文件任务", Toast.LENGTH_SHORT).show()
                updateUi { it.copy(statusText = "继续等待选中任务的下一段…") }
            }
            swapReceiverForNextSegment()
            return null
        }

        val root = com.airferry.app.scan.ContentStore.root(this)
        // Reuse the active root so a long, sequential transfer does not reopen
        // the ledger and re-hash every earlier ~32 MiB segment for each child.
        // Interleaved roots still open their own identity-bound assembler.
        val active = segAssembler
        val asm = if (
            active != null &&
            active.matches(lo, hi, count, rootSize, rootSha256, displayName)
        ) {
            active
        } else {
            com.airferry.app.scan.SegmentAssembler.open(
                root, lo, hi, count, rootSize, decompressedSize, compression,
                crc32, crc32Known, rootSha256, displayName
            ).also { segAssembler = it }
        }

        // Crash recovery: all segments may already be durable while promotion
        // into ContentStore was interrupted. Re-run the idempotent promotion.
        if (asm.isComplete()) {
            return archiveSegmentedTransfer(asm, displayName, decompressedSize)
        }

        try {
            val stored = asm.storeSegment(index, compressedBytes, expectedSha256)
            if (!stored) {
                updateSegmentedProgress(asm)
                clearRecoveryStage()
                swapReceiverForNextSegment()
                return null
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "分段写入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            clearRecoveryStage()
            // Keep already-verified segments. A bad/current segment can simply
            // be scanned again; deleting the entire task would make resume lie.
            swapReceiverForNextSegment()
            return null
        }

        if (!asm.isComplete()) {
            updateSegmentedProgress(asm)
            clearRecoveryStage()
            // Keep scanning for the remaining segments.
            swapReceiverForNextSegment()
            return null
        }

        return archiveSegmentedTransfer(asm, displayName, decompressedSize)
    }

    /**
     * Re-run the (idempotent) promotion of a fully-received segmented transfer
     * into ContentStore. Triggered from the duplicate-segment fast path when
     * the ledger turns out to be COMPLETE — i.e. every segment is durable but
     * the original archive was interrupted (disk-full during `asm.finish()`,
     * or the process dying between `finish()` and `putFile`). Skipping to the
     * next segment instead would strand the data in `.partial` forever, because
     * the archive branch inside [handleSegmentedTransfer] is only reachable
     * via a segment's normal completion, which a dup-swap never reaches.
     *
     * Scheduling mirrors the completion path in [applySnapshot]: the heavy work
     * (open ledger → stream-decompress → putFile) is posted to [ioExecutor]
     * and runs under the *captured* pool's ingest lock — never on the decode
     * worker that detected it (which already holds the lock), never on the
     * main thread.
     *
     * @param active the in-memory assembler when it already matches this root
     *        and is complete; null → the durable ledger is re-opened from disk
     *        using the descriptor snapshot (which re-verifies every stored
     *        segment's SHA-256 before declaring completeness).
     */
    private fun enqueueSegmentedReArchive(active: com.airferry.app.scan.SegmentAssembler?) {
        // Snapshot every descriptor field the re-open needs BEFORE leaving the
        // ingest lock — the session may be reset/destroyed by the time the
        // task runs, and reading it then would be a use-after-free.
        val lo = session.rootSessionIdLo()
        val hi = session.rootSessionIdHi()
        val count = session.segmentCount()
        val compressedSize = session.rootOriginalSize()
        val decompressedSize = session.originalSize()
        val compression = session.compression()
        val crc32Val = session.crc32()
        val crc32Known = session.crc32Known()
        val rootSha256 = session.rootSha256()
        val fileName = session.fileName()
        // Block any further ingest: this segment is already durable so the
        // remaining symbols are useless, and stragglers in the same batched
        // flush must not re-run the dup check and enqueue a second archive.
        ingestStopped.set(true)
        updateRecoveryStage("检测到已完成的分段任务，正在入库…")
        val poolAtEnqueue = decodePool
        ioExecutor.execute {
            try {
                var intent: Intent? = null
                val work = fun() {
                    val sha = requireNotNull(rootSha256) { "分段描述符缺少整文件 SHA-256" }
                    require(sha.size == 32) { "分段描述符 SHA-256 长度无效" }
                    val asm = active ?: com.airferry.app.scan.SegmentAssembler.open(
                        com.airferry.app.scan.ContentStore.root(this),
                        lo, hi, count, compressedSize, decompressedSize, compression,
                        crc32Val, crc32Known, sha, fileName,
                    ).also { segAssembler = it }
                    if (!asm.isComplete()) {
                        // The cheap ledger check was a false positive (open()'s
                        // per-segment re-verification rejected bitmap entries —
                        // e.g. .partial corrupted in place). Keep the verified
                        // segments and go back to scanning the missing ones.
                        Log.i(TAG, "reArchive: ledger not actually complete — resume scanning")
                        swapReceiverForNextSegment()
                        clearRecoveryStage()
                        return
                    }
                    intent = archiveSegmentedTransfer(asm, fileName, decompressedSize)
                }
                // Always serialize via the captured pool (same reasoning as the
                // completion path in applySnapshot — see the long comment there).
                poolAtEnqueue?.runExclusive(work)
                intent?.let {
                    runOnUiThread {
                        completedHandled = true
                        startActivity(it)
                    }
                }
            } catch (e: Exception) {
                clearRecoveryStage()
                resetReceiverAfterRecoveryFailure()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "保存接收内容失败",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "segmented re-archive OOM", e)
                clearRecoveryStage()
                resetReceiverAfterRecoveryFailure()
                runOnUiThread {
                    Toast.makeText(this, "文件过大，接收内存不足", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun archiveSegmentedTransfer(
        asm: com.airferry.app.scan.SegmentAssembler,
        displayName: String,
        rootSize: Long,
    ): Intent? {
        // Concatenate the compressed segments and stream-decompress exactly once
        // to a temp file. The native call already verified the decompressed
        // length + CRC32 (when known) + root SHA-256 over the decompressed bytes.
        val decompressedFile = asm.finish()
            ?: throw IllegalStateException("分段账本已完成，但解压或完整性校验失败")
        updateRecoveryStage("正在校验完整性…")
        val crcKnown = asm.crc32Known()
        val expectedCrc = asm.crc32()
        val store = com.airferry.app.scan.ContentStore

        // Text / bundle detection needs the bytes in memory. Anything larger
        // than the legacy whole-transfer ceiling is a single file by
        // construction, so skip the in-memory dispatch and stream-copy straight
        // to the content store — this is what lets > 256 MiB files be recovered.
        val small = decompressedFile.length() <= 256L * 1024 * 1024
        val originalBytes = if (small) decompressedFile.readBytes() else ByteArray(0)

        if (small) {
            val receivedCrc = crc32OfBytes(originalBytes)

            // Text payload → ETTEXTv1. Strip the 8-byte wire magic up front
            // (same restructure as recoverAndStage): the oversized→file
            // fallback must stage the message text, never bytes starting with
            // the literal "ETTEXTv1" protocol header.
            if (com.airferry.app.scan.TextParser.isText(originalBytes)) {
                val messageBytes =
                    com.airferry.app.scan.TextParser.payloadWithoutMagic(originalBytes)
                val textName = when {
                    displayName.isEmpty() -> TEXT_RECEIVED_NAME
                    displayName.contains('.') -> displayName
                    else -> "$displayName.txt"
                }
                val text =
                    if (com.airferry.app.scan.TextLike.fitsTextUi(messageBytes.size))
                        com.airferry.app.scan.TextParser.parse(originalBytes)
                    else
                        null
                if (text != null) {
                    updateRecoveryStage("正在保存文字…")
                    val contentBytes = text.toByteArray(Charsets.UTF_8)
                    val contentCrc = crc32OfBytes(contentBytes)
                    val crcHex = java.lang.Long.toHexString(contentCrc)
                    val put = store.putBytes(
                        this, textName, contentBytes,
                        crcHex = crcHex, crcUnknown = false, kind = "text",
                    )
                    asm.commitArchived(); segAssembler = null; resumeRootId = null
                    clearRecoveryStage()
                    return Intent(this, ReceiveTextActivity::class.java).apply {
                        putExtra("FILE_PATH", put.path.absolutePath)
                        putExtra("FILE_NAME", textName)
                        putExtra("ENTRY_ID", put.entry.id)
                        putExtra("CRC32", expectedCrc)
                        putExtra("CRC32_RECEIVED", receivedCrc)
                        putExtra("CRC32_UNKNOWN", !crcKnown)
                    }
                }
                // Oversized (over the text cap) or invalid UTF-8 → ordinary
                // .txt FILE, magic stripped.
                updateRecoveryStage("正在保存文件…")
                val contentCrc = crc32OfBytes(messageBytes)
                val put = store.putBytes(
                    this, textName, messageBytes,
                    crcHex = java.lang.Long.toHexString(contentCrc),
                    crcUnknown = false, kind = "file",
                )
                asm.commitArchived(); segAssembler = null; resumeRootId = null
                clearRecoveryStage()
                return Intent(this, ReceiveDetailActivity::class.java).apply {
                    putExtra("FILE_PATH", put.path.absolutePath)
                    putExtra("FILE_SIZE", messageBytes.size.toLong())
                    putExtra("FILE_NAME", textName)
                    putExtra("ENTRY_ID", put.entry.id)
                    putExtra("CRC32", expectedCrc)
                    putExtra("CRC32_RECEIVED", receivedCrc)
                    putExtra("CRC32_UNKNOWN", !crcKnown)
                    putExtra("RESAVE", true)
                }
            }

            // Multi-file bundle → one ContentStore entry per member.
            if (com.airferry.app.scan.BundleParser.isBundle(originalBytes)) {
                val bundle = com.airferry.app.scan.BundleParser.parse(originalBytes)
                if (bundle != null && bundle.files.isNotEmpty()) {
                    val totalFiles = bundle.files.size
                    val paths = ArrayList<String>()
                    val names = ArrayList<String>()
                    val sizes = ArrayList<String>()
                    val entryIds = ArrayList<String>()
                    val ts = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val bundleId = java.util.UUID.randomUUID().toString()
                    val bundleTitle = "发送_$ts"
                    updateRecoveryStage("正在保存 $totalFiles 个文件…")
                    val puts = store.putBytesBatch(
                        this,
                        bundle.files.map { f ->
                            com.airferry.app.scan.ContentStore.PutBytesRequest(
                                f.name, f.data,
                                crcHex = "unknown", crcUnknown = true, kind = "file",
                                bundleId = bundleId, bundleTitle = bundleTitle,
                            )
                        },
                    )
                    for ((f, put) in bundle.files.zip(puts)) {
                        paths.add(put.path.absolutePath)
                        names.add(f.name)
                        sizes.add(f.data.size.toString())
                        entryIds.add(put.entry.id)
                    }
                    asm.commitArchived(); segAssembler = null; resumeRootId = null
                    clearRecoveryStage()
                    return Intent(this, ReceiveBundleActivity::class.java).apply {
                        putStringArrayListExtra("FILE_PATHS", paths)
                        putStringArrayListExtra("FILE_NAMES", names)
                        putStringArrayListExtra("FILE_SIZES", sizes)
                        putStringArrayListExtra("ENTRY_IDS", entryIds)
                        putExtra("CRC32", expectedCrc)
                        putExtra("CRC32_RECEIVED", receivedCrc)
                        putExtra("CRC32_UNKNOWN", !crcKnown)
                    }
                }
            }
        }

        // Single-file path (works for both small and very large files — for the
        // latter, putFile streams/atomically-moves the on-disk original).
        updateRecoveryStage("正在保存文件…")
        val finalName = if (displayName.isNotEmpty()) displayName else "received_file"
        if (small) {
            if (com.airferry.app.scan.TextLike.isTextLikeName(finalName) &&
                com.airferry.app.scan.TextLike.fitsTextUi(originalBytes.size)
            ) {
                val text = com.airferry.app.scan.TextLike.decodeUtf8Strict(originalBytes)
                if (text != null) {
                    val receivedCrc = crc32OfBytes(originalBytes)
                    val archiveLabel =
                        if (finalName.contains('.')) finalName else TEXT_RECEIVED_NAME
                    val put = store.putBytes(
                        this, archiveLabel, originalBytes,
                        crcHex = java.lang.Long.toHexString(receivedCrc),
                        crcUnknown = false,
                        kind = "text",
                    )
                    asm.commitArchived(); segAssembler = null; resumeRootId = null
                    clearRecoveryStage()
                    return Intent(this, ReceiveTextActivity::class.java).apply {
                        putExtra("FILE_PATH", put.path.absolutePath)
                        putExtra("FILE_NAME", finalName)
                        putExtra("ENTRY_ID", put.entry.id)
                        putExtra("CRC32", if (crcKnown) expectedCrc else receivedCrc)
                        putExtra("CRC32_RECEIVED", receivedCrc)
                        putExtra("CRC32_UNKNOWN", !crcKnown)
                    }
                }
            }
        }

        val put = store.putFile(
            this, finalName, decompressedFile,
            crcHex = if (crcKnown) java.lang.Long.toHexString(expectedCrc) else "unknown",
            crcUnknown = !crcKnown,
            kind = "file",
            expectedSha256Hex = asm.rootSha256Hex(),
            expectedSize = rootSize,
            stableEntryId = "segment-${rootSessionIdHex(asm.rootSessionIdLo(), asm.rootSessionIdHi())}",
        )
        // ContentStore index is now durable; the resumable task can be removed.
        asm.commitArchived()
        segAssembler = null
        resumeRootId = null
        clearRecoveryStage()
        return Intent(this, ReceiveDetailActivity::class.java).apply {
            putExtra("FILE_PATH", put.path.absolutePath)
            putExtra("FILE_SIZE", rootSize)
            putExtra("FILE_NAME", finalName)
            putExtra("ENTRY_ID", put.entry.id)
            putExtra("RESAVE", true)
        }
    }

    /** Update the UI progress with how many segments have been stored. */
    private fun updateSegmentedProgress(asm: com.airferry.app.scan.SegmentAssembler) {
        val received = asm.receivedCount()
        val totalSeg = asm.segmentCount()
        runOnUiThread {
            val s = "分段 $received/$totalSeg 已收，继续扫描下一段…"
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
            updateUi { it.copy(statusText = s) }
        }
    }

    /**
     * Swap the receiver to a fresh session for the *next* descriptor-v5 segment.
     *
     * Called from `recoverAndStage` which runs *inside* the decode pool's ingest
     * lock, so we must NOT re-acquire it (`resetSession()` would deadlock).
     * `ingestStopped` is cleared so the capture loop keeps feeding frames.
     */
    private fun swapReceiverForNextSegment() {
        session.destroy()
        session = ReceiverSessionManager()
        ingestStopped.set(false)
        completedHandled = false
        lastUiUpdate = 0
        rateSamples.clear()
        runOnUiThread {
            updateUi {
                it.copy(
                    complete = false,
                    progressPct = 0,
                    receivedSymbols = 0,
                    totalSymbols = 0,
                    decodedBlocks = 0,
                    totalBlocks = 0,
                )
            }
        }
    }

    /** Recover from any post-decode failure without stranding the scanner in
     * `completedHandled=true` / `ingestStopped=true`. */
    private fun resetReceiverAfterRecoveryFailure() {
        val swap = {
            session.destroy()
            session = ReceiverSessionManager()
            ingestStopped.set(false)
            completedHandled = false
            lastUiUpdate = 0
            rateSamples.clear()
        }
        try {
            decodePool?.runExclusive(swap) ?: swap()
        } catch (resetError: Exception) {
            Log.e(TAG, "failed to reset receiver after recovery error", resetError)
        }
    }

    private fun rootSessionIdHex(lo: Long, hi: Long): String {
        val low = java.lang.Long.toUnsignedString(lo, 16).padStart(16, '0')
        val high = java.lang.Long.toUnsignedString(hi, 16).padStart(16, '0')
        return "$high$low"
    }

    private fun idleStatus(): String = resumeRootId?.let {
        "继续恢复任务 ${it.take(8)}… — 对准对应分段二维码"
    } ?: "就绪 — 对准二维码…"

    /**
     * Reset the native receiver on a background thread, under the pool's ingest
     * lock. Main-thread callers (重扫 button, onResume) must NEVER block on that
     * lock directly: an in-flight archive (recoverAndStage → asm.finish() 解压 +
     * CRC + SHA → putFile, executed on ioExecutor via runExclusive) can hold it
     * for tens of seconds on large transfers — a lock acquire without timeout
     * on the main thread is a guaranteed ANR (H3). The swap is posted to the
     * single-threaded [ioExecutor] so it also stays ordered behind any queued
     * archive work.
     */
    private fun resetReceiverAsync() {
        val poolAtEnqueue = decodePool
        ioExecutor.execute {
            val swap = {
                session.destroy()
                session = ReceiverSessionManager()
                ingestStopped.set(false)
            }
            try {
                poolAtEnqueue?.runExclusive(swap) ?: swap()
            } catch (resetError: Exception) {
                Log.e(TAG, "failed to reset receiver", resetError)
            }
        }
    }

    private fun resetSession() {
        segAssembler = null
        // Swap the receiver under the pool's ingest lock so no worker is mid-ingest
        // while we destroy the old native handle — asynchronously (see
        // [resetReceiverAsync]); the UI-visible counters below reset immediately.
        resetReceiverAsync()
        completedHandled = false
        lastUiUpdate = 0
        rateSamples.clear()
        decodePerSec = 0
        recentWireBps = 0L
        transferStartMs = 0L
        recoveryStage.value = null
        updateUi {
            UiState(jniReady = true, statusText = idleStatus())
        }
    }

    private fun updateUi(block: (UiState) -> UiState) {
        uiState.value = block(uiState.value)
    }

    /** Set the live recovery-stage status text (posted to the main thread).
     *  Called from [ioExecutor] during [recoverAndStage] so the user sees the
     *  post-scan pipeline advancing instead of a frozen "完成". */
    private fun updateRecoveryStage(text: String) {
        runOnUiThread { recoveryStage.value = text }
    }

    /** Clear the recovery-stage status (e.g. right before launching the result
     *  Activity, or on error / reset). */
    private fun clearRecoveryStage() {
        runOnUiThread { recoveryStage.value = null }
    }

    // slotScreenPos 已移除（火花动画已删除）。

    // refreshOverlay / dedupeSparksBySlot 已移除（火花动画已删除）。
    override fun onResume() {
        super.onResume()
        // Re-read the shutter preference: the user may have changed it in
        // SettingsActivity and navigated back. Re-applying is cheap and takes
        // effect without rebinding the camera.
        val saved = ShutterSpeed.fromPref(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(ShutterSpeed.PREF_KEY, null)
        )
        if (saved != currentShutter) {
            currentShutter = saved
            boundCamera?.let { applyShutter(it) }
        }
        // If returning from ReceiveDetailActivity after completion, reset for next scan.
        if (completedHandled) {
            // Never wait on the ingest lock on the main thread — an in-flight
            // archive may hold it for tens of seconds (H3). Post the swap to
            // ioExecutor; reset the UI-visible counters immediately.
            resetReceiverAsync()
            completedHandled = false
            lastUiUpdate = 0
            rateSamples.clear()
            decodePerSec = 0
            recentWireBps = 0L
            transferStartMs = 0L
            recoveryStage.value = null
            updateUi { UiState(jniReady = true, statusText = idleStatus()) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exposureTicker?.let { exposureTickHandler.removeCallbacks(it) }
        exposureTicker = null
        cameraExecutor.shutdown()
        // Snapshot the native-session owner and decode pool to locals, then drop
        // the Activity fields so any post-destroy callback / Activity-recreation
        // cannot reach them. The actual drain + destroy happens on a detached
        // daemon thread (below) — NEVER on the main thread. The previous code
        // called ioExecutor.awaitTermination(30s) here, which for a large
        // segmented transfer (stream-decompress + SHA over hundreds of MiB on
        // ioExecutor) blocked the main thread for up to 30 s → guaranteed ANR on
        // rotation / recents. Mirrors the Windows ScanViewModel 2 s quarantine.
        val pool = decodePool
        decodePool = null
        val sessionRef = session
        // Drain the IO executor BEFORE tearing down the decode pool: the pending
        // recovery task holds the pool's ingest lock and touches the native
        // session, so freeing the handle first would race it. Shutdown lets an
        // in-flight stage finish (bounded; assemble is the slow part and already
        // running under ingestStopped, which halted further ingest). The drain
        // + destroy run on a daemon thread so the main thread is never blocked.
        ioExecutor.shutdown()
        // The correctness anchor: the in-flight recovery job and destroy() both
        // go through pool.runExclusive (ingestLock), so they are mutually
        // exclusive regardless of timing — no use-after-free even if the await
        // below is skipped.
        Thread {
            try {
                ioExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // Stop workers, then destroy the (captured) native receiver UNDER
            // the ingest lock so a straggler that outran shutdown()'s join
            // timeout can't still be mid-ingest (&mut) when destroy() frees the
            // handle (use-after-free). destroy() is idempotent.
            if (pool != null) {
                pool.shutdown()
                pool.runExclusive { sessionRef.destroy() }
            } else {
                sessionRef.destroy()
            }
        }.apply { isDaemon = true; name = "airferry-destroy" }.start()
    }

    companion object {
        private const val TAG = "ScanActivity"

        /** SharedPreferences file shared with SettingsActivity. */
        private const val PREFS_NAME = "airferry"

        /**
         * Last-resort fps range, used only when the device advertises no ranges at
         * all or rejects the capability-derived one. `[15,30]` is the Camera2
         * baseline range every device must support (SENSOR_INFO_MAX_FRAME_DURATION
         * guarantees 30fps at 1080p), so it is far safer than the previous
         * hard-coded `[60,60]`.
         */
        private val DEFAULT_FPS_RANGE = Range(15, 30)
        /**
         * Sliding window for decode rate + wire throughput shown in the info card.
         * ~3s is responsive enough to feel "live" without jittering every tick.
         */
        private const val RATE_WINDOW_MS = 3_000L
        /** Minimum Δt before publishing a rate (avoids 1-tick spikes). */
        private const val RATE_MIN_DT_MS = 300L
        /** Refresh period of the idle exposure read-out. */
        private const val EXPOSURE_TICK_MS = 1000L
        /** Default display/store name when an older TEXT descriptor has no filename. */
        private const val TEXT_RECEIVED_NAME = "文字消息.txt"

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            return "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }

        /** Format milliseconds as a human-readable duration (e.g. "23 秒", "1 分 05 秒"). */
        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            if (totalSec < 60) return "${totalSec} 秒"
            val m = totalSec / 60
            val s = totalSec % 60
            return "${m} 分 ${s.toString().padStart(2, '0')} 秒"
        }

        /**
         * Format an exposure time in nanoseconds as a shutter fraction ("1/60",
         * "1/33"), rounding to the nearest integer denominator so 30.0ms renders
         * as "1/33" and 33.35ms (a full 30fps frame period) as "1/30" — matching
         * how camera apps display it.
         */
        fun formatShutter(nanos: Long): String {
            if (nanos <= 0) return "—"
            val denom = (1_000_000_000.0 / nanos).toInt().coerceAtLeast(1)
            return "1/$denom"
        }

        fun crc32OfBytes(data: ByteArray): Long {
            // Compute CRC32 and return as an unsigned 32-bit value in a Long
            // (0..=0xFFFFFFFF) so it compares correctly with the JNI-supplied
            // expected CRC (also a Long). Using Int would sign-flip high-bit
            // values and break equality.
            // java.util.zip.CRC32 is the table-driven JVM implementation — the
            // previous bit-by-bit software loop was ~50× slower and sat on the
            // recovery hot path (multi-MB payloads) and the file-list open path.
            val crc = java.util.zip.CRC32()
            crc.update(data)
            return crc.value
        }

        /**
         * Streaming CRC32 over a file (64 KiB buffer) — O(1) memory regardless
         * of file size. Replaces the old `crc32OfBytes(file.readBytes())`
         * pattern in the file list, which whole-loaded blobs of hundreds of MiB
         * and OOM-crashed (an Error the surrounding `catch (Exception)` could
         * not intercept). Returns the same unsigned 32-bit Long as
         * [crc32OfBytes]; throws on I/O failure (caller decides the fallback).
         */
        fun crc32OfFile(file: java.io.File): Long {
            val crc = java.util.zip.CRC32()
            java.io.FileInputStream(file).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    crc.update(buf, 0, n)
                }
            }
            return crc.value
        }
    }
}
