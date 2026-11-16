package com.airferry.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the shutter-speed model.
 *
 * These run without a device or the native libraries (`./gradlew :app:testDebugUnitTest`),
 * which matters because the interesting cases — clamping against odd sensor ranges
 * and an unsupported preset — cannot be reproduced on demand with real hardware.
 */
class CameraExposureTest {

    // ---- ShutterSpeed presets ----

    @Test
    fun autoIsNotManualAndHasSentinelExposure() {
        assertFalse(ShutterSpeed.AUTO.isManual)
        assertEquals(ShutterSpeed.AUTO_NANOS, ShutterSpeed.AUTO.nanos)
    }

    @Test
    fun presetsAreManualAndOrderedSlowestToFastest() {
        val manual = ShutterSpeed.selectable.filter { it.isManual }
        assertTrue("expected several presets", manual.size >= 3)
        // The UI lists 1/60 -> 1/500, i.e. exposure time *decreases* monotonically.
        // Assert the real guarantee: labels read fastest-last and nanos descend.
        val nanos = manual.map { it.nanos }
        assertEquals(nanos.sortedDescending(), nanos)
        assertTrue(manual.all { it.nanos > 0 })
        // AUTO is offered first so the default is the first radio button.
        assertEquals(ShutterSpeed.AUTO, ShutterSpeed.selectable.first())
    }

    @Test
    fun labelsMatchExposureTimes() {
        // Guards against a label/value drift that would make the UI lie.
        for (s in ShutterSpeed.selectable.filter { it.isManual }) {
            val denom = s.label.removePrefix("1/").toLong()
            val expected = 1_000_000_000L / denom
            // Allow the rounding baked into the enum constants (e.g. 1/60 -> 16666666).
            val tolerance = 2L
            assertTrue(
                "${s.label}: label implies ${expected}ns but preset is ${s.nanos}ns",
                kotlin.math.abs(s.nanos - expected) <= tolerance
            )
        }
    }

    @Test
    fun fromPrefFallsBackToAutoOnGarbage() {
        assertEquals(ShutterSpeed.AUTO, ShutterSpeed.fromPref(null))
        assertEquals(ShutterSpeed.AUTO, ShutterSpeed.fromPref(""))
        assertEquals(ShutterSpeed.AUTO, ShutterSpeed.fromPref("NOT_A_PRESET"))
        assertEquals(ShutterSpeed.AUTO, ShutterSpeed.fromPref("auto")) // case-sensitive names
    }

    @Test
    fun fromPrefRoundTripsEveryPreset() {
        for (s in ShutterSpeed.selectable) {
            assertEquals(s, ShutterSpeed.fromPref(s.name))
        }
    }

    // ---- availableFor (capability filtering) ----

    @Test
    fun availableForKeepsEverythingWhenRangeUnknown() {
        assertEquals(ShutterSpeed.selectable, ShutterSpeed.availableFor(0, 0))
        assertEquals(ShutterSpeed.selectable, ShutterSpeed.availableFor(-1, 100))
        assertEquals(ShutterSpeed.selectable, ShutterSpeed.availableFor(100, 0))
    }

    @Test
    fun availableForHidesPresetsOutsideSensorRange() {
        // A sensor that cannot expose faster than 1/100s: 1/120, 1/240 and 1/500
        // must disappear, AUTO and 1/60 must remain.
        val nanosFor100 = 10_000_000L
        val got = ShutterSpeed.availableFor(nanosFor100, 300_000_000L)
        assertTrue(got.contains(ShutterSpeed.AUTO))
        assertTrue(got.contains(ShutterSpeed.S60))
        assertFalse(got.contains(ShutterSpeed.S120))
        assertFalse(got.contains(ShutterSpeed.S240))
        assertFalse(got.contains(ShutterSpeed.S500))
    }

    @Test
    fun availableForAlwaysKeepsAuto() {
        // Even an absurd range that matches no preset must leave AUTO selectable,
        // otherwise the settings screen would have no valid choice at all.
        val got = ShutterSpeed.availableFor(1L, 2L)
        assertEquals(listOf(ShutterSpeed.AUTO), got)
    }

    @Test
    fun availableForReferenceDeviceKeepsAllPresets() {
        // Values measured on the reference device (Xiaomi 21091116AC, camera id 0):
        // SENSOR_INFO_EXPOSURE_TIME_RANGE = 100000..300000000 ns.
        val got = ShutterSpeed.availableFor(100_000L, 300_000_000L)
        assertEquals(ShutterSpeed.selectable, got)
    }

    // ---- planManualExposure ----

    @Test
    fun planRejectsAuto() {
        val plan = planManualExposure(ShutterSpeed.AUTO, 100_000, 300_000_000, true)
        assertFalse(plan.ok)
    }

    @Test
    fun planRejectsWhenSensorLacksManualCapability() {
        val plan = planManualExposure(ShutterSpeed.S60, 100_000, 300_000_000, supportsManualSensor = false)
        assertFalse(plan.ok)
        assertTrue(plan.problem!!.contains("MANUAL_SENSOR"))
    }

    @Test
    fun planPassesThroughWhenRangeUnknown() {
        val plan = planManualExposure(ShutterSpeed.S60, 0, 0, true)
        assertTrue(plan.ok)
        assertFalse(plan.clamped)
        assertEquals(ShutterSpeed.S60.nanos, plan.exposureNanos)
    }

    @Test
    fun planAcceptsInRangePresetUnchanged() {
        val plan = planManualExposure(ShutterSpeed.S60, 100_000, 300_000_000, true)
        assertTrue(plan.ok)
        assertFalse(plan.clamped)
        assertEquals(ShutterSpeed.S60.nanos, plan.exposureNanos)
    }

    @Test
    fun planClampsBelowSensorMinimum() {
        // Sensor cannot go faster than 1/100s (10ms); asking for 1/500s clamps up.
        val plan = planManualExposure(ShutterSpeed.S500, 10_000_000, 300_000_000, true)
        assertTrue(plan.ok)
        assertTrue(plan.clamped)
        assertEquals(10_000_000L, plan.exposureNanos)
    }

    @Test
    fun planClampsAboveSensorMaximum() {
        // Sensor tops out at 5ms; 1/60s clamps down to it.
        val plan = planManualExposure(ShutterSpeed.S60, 100_000, 5_000_000, true)
        assertTrue(plan.ok)
        assertTrue(plan.clamped)
        assertEquals(5_000_000L, plan.exposureNanos)
    }

    @Test
    fun planNeverExceedsRangeOnReferenceDevice() {
        for (s in ShutterSpeed.selectable.filter { it.isManual }) {
            val plan = planManualExposure(s, 100_000, 300_000_000, true)
            assertTrue(plan.ok)
            assertFalse("${s.label} should need no clamping", plan.clamped)
            assertTrue(plan.exposureNanos in 100_000L..300_000_000L)
        }
    }

    // ---- nextAutoIso ----

    @Test
    fun autoIsoHoldsSteadyAtTarget() {
        // Feeding the controller its own target must produce no change.
        assertEquals(400, nextAutoIso(AUTO_ISO_TARGET_LUMA, 400, 50, 6400))
    }

    @Test
    fun autoIsoTargetIsBelowMidGrey() {
        // Guards the over-exposure fix: a mid-grey (128) target drives gain up
        // until the self-emissive sender screen clips to white.
        assertTrue("target must stay well below 128", AUTO_ISO_TARGET_LUMA < 100.0)
    }

    @Test
    fun autoIsoBacksOffFasterThanItRaises() {
        // Symmetric damping would let a clipped frame linger for many ticks.
        // Lowering must move further than raising for the same absolute error.
        val error = 20.0
        val raised = nextAutoIso(AUTO_ISO_TARGET_LUMA - error, 400, 50, 6400) - 400
        val lowered = 400 - nextAutoIso(AUTO_ISO_TARGET_LUMA + error, 400, 50, 6400)
        assertTrue("lowering ($lowered) should exceed raising ($raised)", lowered > raised)
    }

    @Test
    fun autoIsoRaisesIsoOnDarkFrame() {
        val next = nextAutoIso(64.0, 400, 50, 6400)
        assertTrue("expected a raise, got $next", next > 400)
    }

    @Test
    fun autoIsoLowersIsoOnBrightFrame() {
        val next = nextAutoIso(200.0, 400, 50, 6400)
        assertTrue("expected a drop, got $next", next < 400)
    }

    @Test
    fun autoIsoRespectsSensorBounds() {
        // Fully dark frame would demand a huge ISO; must clamp to the ceiling.
        assertEquals(6400, nextAutoIso(1.5, 6400, 50, 6400))
        // Blown-out frame would demand ~0; must clamp to the floor.
        assertEquals(50, nextAutoIso(255.0, 50, 50, 6400))
    }

    @Test
    fun autoIsoIgnoresBlackFrameInsteadOfRailing() {
        // A covered lens (mean 0) must not push ISO to the ceiling — that would
        // leave the camera blind for a while after the obstruction is removed.
        assertEquals(400, nextAutoIso(0.0, 400, 50, 6400))
        assertEquals(400, nextAutoIso(0.5, 400, 50, 6400))
    }

    @Test
    fun autoIsoIsDamped() {
        // A 2-stop darkening must NOT jump the full 4x in one step.
        val next = nextAutoIso(32.0, 400, 50, 6400)
        assertTrue("jumped too far: $next", next < 1600)
        assertTrue("did not move: $next", next > 400)
    }

    @Test
    fun autoIsoIgnoresInvalidSensorRange() {
        // Degenerate ranges must leave the ISO untouched rather than guess.
        assertEquals(400, nextAutoIso(AUTO_ISO_TARGET_LUMA, 400, 0, 6400))
        assertEquals(400, nextAutoIso(AUTO_ISO_TARGET_LUMA, 400, 6400, 50))
        assertEquals(0, nextAutoIso(AUTO_ISO_TARGET_LUMA, 0, 50, 6400))
    }
}
