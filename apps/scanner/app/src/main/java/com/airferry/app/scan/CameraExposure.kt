package com.airferry.app.scan

/**
 * Shutter-speed (exposure-time) model for the Android receiver.
 *
 * ## Why this exists
 *
 * Through v1.2.9 the scanner only pinned `CONTROL_AE_TARGET_FPS_RANGE` and left
 * `CONTROL_AE_MODE` at `ON`, so the user had no way to see or choose the actual
 * shutter speed. On devices whose camera2 advertises a 30fps ceiling the `[60,60]`
 * request is silently ignored (the HAL clamps it; `bindToLifecycle` still
 * succeeds), AE then runs at a 33.3ms frame period and — on a dim scene — pushes
 * exposure all the way to the frame-period limit, i.e. a ~1/30s shutter. That is
 * the slowest possible setting and the exact opposite of what a QR stream wants.
 *
 * Selecting a fixed speed turns AE off (`CONTROL_AE_MODE = OFF`) and drives
 * `SENSOR_EXPOSURE_TIME` directly. Because AE is then gone, brightness must be
 * maintained by [ShutterSpeed.isManual] consumers adjusting `SENSOR_SENSITIVITY`
 * — see `QrStreamAnalyzer`'s auto-ISO loop.
 *
 * Everything in this file is **pure Kotlin** (no Android framework types) so it
 * can be unit-tested on the JVM without a device.
 */

/**
 * A selectable shutter speed.
 *
 * [nanos] is the exposure time in nanoseconds, matching the unit of
 * `CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE` and
 * `CaptureRequest.SENSOR_EXPOSURE_TIME`. [AUTO] uses [AUTO_NANOS] as sentinel:
 * it means "leave AE in charge", not "expose for zero time".
 *
 * The range deliberately stops at 1/500. On the reference device the sensor's
 * `SENSOR_INFO_SENSITIVITY_RANGE` tops out at ISO 6400 while a typical indoor
 * scene needs ~ISO 460 at 1/30s; extrapolating, 1/1000s would demand ~ISO 13890
 * and under-expose by more than a stop, so it is offered only if a device has
 * the headroom — see [availableFor].
 */
enum class ShutterSpeed(val label: String, val nanos: Long) {
    /** Camera-controlled exposure (the behavior of every release through v1.2.9). */
    AUTO("自动", AUTO_NANOS),

    /** Locked 1/60s — fast enough to suppress hand-shake smear on a 30fps sensor. */
    S60("1/60", 16_666_666L),

    /** Locked 1/120s. */
    S120("1/120", 8_333_333L),

    /** Locked 1/240s. */
    S240("1/240", 4_166_666L),

    /** Locked 1/500s — the fastest preset that still fits a typical ISO ceiling. */
    S500("1/500", 2_000_000L);

    /** True when this preset drives SENSOR_EXPOSURE_TIME manually (AE is off). */
    val isManual: Boolean get() = this != AUTO

    companion object {
        /** Sentinel value stored for [AUTO]; never sent to the camera as an exposure time. */
        const val AUTO_NANOS = 0L

        /** SharedPreferences key holding the user's choice (`name` of the enum). */
        const val PREF_KEY = "camera_shutter"

        /**
         * Sensor exposure-time range (ns), cached by the scanner when it binds the
         * camera so the settings screen can hide presets this device cannot honor.
         * 0 = not yet known.
         */
        const val PREF_EXPOSURE_MIN = "camera_exposure_min_ns"
        const val PREF_EXPOSURE_MAX = "camera_exposure_max_ns"

        /** Stable order used by the settings UI (AUTO first, then fastest→slowest). */
        val selectable: List<ShutterSpeed> = listOf(AUTO, S60, S120, S240, S500)

        /**
         * Parse a persisted value, tolerating garbage.
         *
         * Anything unrecognized (older builds, corrupted prefs, a hand-edited
         * file) falls back to [AUTO] rather than throwing — a bad pref must not
         * brick the scanner.
         */
        fun fromPref(raw: String?): ShutterSpeed =
            selectable.firstOrNull { it.name == raw } ?: AUTO

        /**
         * Filter [selectable] down to the presets this camera can actually honor.
         *
         * [minNanos]/[maxNanos] come from `SENSOR_INFO_EXPOSURE_TIME_RANGE`. A
         * preset whose exposure time falls outside that range would be silently
         * clamped or rejected by the HAL, which shows up as "the setting did
         * nothing" — so it is removed from the UI instead. [AUTO] is always kept.
         *
         * When the range is unavailable ([minNanos] <= 0 or [maxNanos] <= 0) we
         * keep every preset: without capability data, hiding options would be
         * more misleading than offering them.
         */
        fun availableFor(minNanos: Long, maxNanos: Long): List<ShutterSpeed> {
            if (minNanos <= 0L || maxNanos <= 0L) return selectable
            return selectable.filter { it == AUTO || (it.nanos in minNanos..maxNanos) }
        }
    }
}

/**
 * Result of preparing a manual exposure request for a given device.
 *
 * @property exposureNanos the exposure time actually requested (already clamped)
 * @property clamped true when [requestedNanos] had to be adjusted
 * @property problem non-null when the request cannot be honored at all (e.g. the
 *   sensor does not support manual exposure); callers should then stay on [ShutterSpeed.AUTO]
 */
data class ManualExposurePlan(
    val exposureNanos: Long,
    val clamped: Boolean,
    val problem: String? = null,
) {
    val ok: Boolean get() = problem == null
}

/**
 * Clamp a requested exposure time into the sensor's supported window.
 *
 * Mirrors what the HAL does internally, but does it up front so we can log the
 * correction. A request outside the range otherwise results in a silently
 * different exposure than the UI claims — the exact failure mode that made
 * `[60,60]` invisible in v1.2.9.
 */
fun planManualExposure(
    speed: ShutterSpeed,
    minNanos: Long,
    maxNanos: Long,
    supportsManualSensor: Boolean,
): ManualExposurePlan {
    if (!speed.isManual) {
        return ManualExposurePlan(ShutterSpeed.AUTO_NANOS, clamped = false,
            problem = "AUTO does not request a manual exposure")
    }
    if (!supportsManualSensor) {
        // LEGACY/LIMITED devices reject CONTROL_AE_MODE=OFF; claiming a fixed
        // shutter would be a lie, so report it instead.
        return ManualExposurePlan(ShutterSpeed.AUTO_NANOS, clamped = false,
            problem = "device lacks MANUAL_SENSOR capability")
    }
    if (minNanos <= 0L || maxNanos <= 0L) {
        // No capability data: pass the request through untouched and let the
        // HAL decide, rather than inventing a range.
        return ManualExposurePlan(speed.nanos, clamped = false)
    }
    val clamped = speed.nanos.coerceIn(minNanos, maxNanos)
    return ManualExposurePlan(clamped, clamped != speed.nanos)
}

/**
 * Next ISO value for the auto-ISO loop that keeps a *fixed* shutter exposed.
 *
 * With AE off, brightness is the product of exposure time and gain, so holding
 * the shutter constant means gain must absorb every change in scene
 * brightness. This is a plain proportional controller on the mean luma of the
 * analysis frame:
 *
 * ```
 * next = current * (target / mean)
 * ```
 *
 * Damping (moving only part of the way) plus a gain cap keep it from oscillating
 * when the frame contains a large white border (QR payloads are ~50% white) or a
 * sudden specular highlight. `mean <= 0` (a fully black frame — lens covered, or
 * the first frame before the sensor settles) is ignored rather than driving ISO
 * to its ceiling.
 *
 * @param meanLuma average luma of the current frame, 0..255
 * @param currentIso ISO currently applied
 * @param minIso/maxIso sensor limits from `SENSOR_INFO_SENSITIVITY_RANGE`
 * @param targetLuma desired mean luma (default mid-grey); lower it if the scan
 *   area is dominated by the bright sender screen and ISO keeps railing low.
 * @return the next ISO to request, or [currentIso] when no change is warranted
 */
fun nextAutoIso(
    meanLuma: Double,
    currentIso: Int,
    minIso: Int,
    maxIso: Int,
    targetLuma: Double = 128.0,
): Int {
    if (meanLuma <= 1.0 || currentIso <= 0 || minIso <= 0 || maxIso < minIso) return currentIso
    val desired = currentIso * (targetLuma / meanLuma)
    // Damp: move at most 25% of the gap per frame so a bright QR border entering
    // the frame cannot slam ISO across its whole range in a single step.
    val damped = currentIso + (desired - currentIso) * AUTO_ISO_DAMPING
    return damped.toInt().coerceIn(minIso, maxIso)
}

/** Fraction of the correction applied per analysis frame (see [nextAutoIso]). */
const val AUTO_ISO_DAMPING = 0.25
