package com.airferry.app.scan

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] that runs as a lightweight *producer*: it
 * copies the Y (luminance) plane of each frame into the [QrDecodePool] and
 * returns immediately, so the camera pipeline is never blocked by QR decoding.
 *
 * The actual ZXing decode runs in parallel on the pool's worker threads. This
 * replaces the previous design that decoded synchronously on the single analysis
 * thread — which couldn't keep up at high fps and dropped most frames.
 *
 * Since the fixed-shutter feature, this class also owns the readings the
 * exposure UI depends on:
 *
 *  * [publishedExposureNanos] / [publishedIso] are written from the
 *    `CameraCaptureSession.CaptureCallback` in `ScanActivity`, because
 *    `ImageProxy` carries no exposure metadata. Reading them back is the only way
 *    to show the *actual* shutter when AE is in charge.
 *  * [sampleMeanLuma] feeds the auto-ISO loop that keeps a locked shutter
 *    correctly exposed (with `CONTROL_AE_MODE = OFF`, AE can no longer maintain
 *    brightness).
 */
class QrStreamAnalyzer(
    private val pool: QrDecodePool
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            // Plane 0 is Y / luminance. Copy it into the pool (which reads the
            // buffer now) before we close the ImageProxy below.
            val planes = image.planes
            if (planes.isEmpty()) return
            val plane = planes[0]
            // Log the actual delivered resolution once (diagnostics for the
            // 1080p-vs-720p upgrade). CameraX picks the closest supported size
            // to the requested ResolutionStrategy, so this confirms what the
            // device actually hands us.
            if (!loggedActualResolution) {
                loggedActualResolution = true
                Log.i(TAG, "actual analysis stream: ${image.width}x${image.height}" +
                    " rs=${plane.rowStride} fps-target was set via Camera2Interop")
            }
            // Publish the sensor rotation so the UI overlay can map analysis-stream
            // pixel bboxes back into PreviewView space. Stable for the lifetime of a
            // camera bind (a portrait phone is almost always 90°), so writing it
            // every frame is cheap and race-free (last-write-wins, all equal).
            pool.setAnalysisRotation(image.imageInfo.rotationDegrees)
            pool.submit(plane.buffer, image.width, image.height, plane.rowStride)

            // Auto-ISO input. Sampled before the buffer is handed off (submit()
            // only borrows it transiently), and throttled: the controller is a
            // slow-moving proportional loop, so a few samples per second are
            // plenty and this keeps the per-frame cost off the hot path.
            if (shutterLocked) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastIsoSampleMs >= ISO_SAMPLE_INTERVAL_MS) {
                    lastIsoSampleMs = now
                    val mean = sampleMeanLuma(plane.buffer, image.width, image.height, plane.rowStride)
                    if (mean > 0.0) onMeanLuma?.invoke(mean)
                }
            }
        } catch (e: Exception) {
            // Narrowed from Throwable: an OutOfMemoryError from a large frame
            // allocation should not be swallowed here (it leaves the JVM in a
            // degraded state and continuing to accept frames worsens it). Only
            // ordinary per-frame failures are non-fatal.
            Log.w(TAG, "frame submit failed", e)
        } finally {
            // Always close so CameraX can deliver the next frame.
            image.close()
        }
    }

    /**
     * Mean luma (0..255) of a YUV_420_888 Y plane, sampled on a coarse grid.
     *
     * Only every [LUMA_SAMPLE_STEP]-th pixel in *both* axes is read, so a 1080p
     * frame costs ~13k byte reads instead of 2M — statistically more than enough
     * for a brightness controller, and cheap enough to run on the analysis
     * thread. The plane is read via [stride]: `ImageProxy` rows are padded, so a
     * naive `w*h` walk would drift into padding and skewed content (the same
     * trap documented for `VideoFrame.copyTo(I420)` in AGENTS.md).
     */
    private fun sampleMeanLuma(buf: java.nio.ByteBuffer, width: Int, height: Int, stride: Int): Double {
        val dup = buf.duplicate()
        val base = dup.position()
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val row = base + y * stride
            var x = 0
            while (x < width) {
                sum += (dup.get(row + x).toInt() and 0xFF)
                count++
                x += LUMA_SAMPLE_STEP
            }
            y += LUMA_SAMPLE_STEP
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    /**
     * Called (throttled) with the mean luma of each frame while the shutter is
     * locked. Wired by `ScanActivity` to the auto-ISO controller.
     */
    var onMeanLuma: ((Double) -> Unit)? = null

    /** Set true after the first frame logs the real delivered resolution. */
    private var loggedActualResolution = false

    /**
     * True while a fixed shutter is applied. Written by `ScanActivity` on every
     * shutter change, read on the analysis thread → volatile. When AE owns
     * exposure there is nothing to correct, so luma sampling is skipped.
     *
     * Deliberately an instance field, not a companion (static) one: a static flag
     * would survive Activity recreation and leak "locked" state into a freshly
     * bound camera that may have gone back to AUTO.
     */
    @Volatile
    var shutterLocked: Boolean = false

    /** Sensor-clock time of the previous auto-ISO sample. Analysis-thread only. */
    private var lastIsoSampleMs = 0L

    companion object {
        private const val TAG = "QrStreamAnalyzer"

        /** Sample every 16th row/column when computing mean luma (1080p → ~8k reads). */
        private const val LUMA_SAMPLE_STEP = 16

        /** Minimum gap between auto-ISO samples. */
        private const val ISO_SAMPLE_INTERVAL_MS = 250L
    }
}
