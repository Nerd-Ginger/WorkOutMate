package com.nerdginger.workoutmate.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
    assertTrue(
        abs(expected - actual) < tolerance,
        "expected $expected but was $actual (tolerance $tolerance)",
    )
}

class UnitsTest {

    @Test
    fun `kg is stored unchanged`() {
        assertClose(100.0, toKg(100.0, WeightUnit.KG))
        assertClose(100.0, fromKg(100.0, WeightUnit.KG))
    }

    @Test
    fun `pounds convert to kilograms`() {
        assertClose(45.359237, toKg(100.0, WeightUnit.LB))
    }

    // The v1 smoke checklist asserts 100 kg reads as 220.5 lb and converts back
    // without drift. Keeping that exact case so the behaviour is pinned.
    @Test
    fun `100 kg displays as 220 point 5 pounds`() {
        assertEquals(220.5, displayWeight(100.0, WeightUnit.LB))
    }

    @Test
    fun `round trips through pounds without drift`() {
        for (kg in listOf(2.5, 20.0, 60.0, 102.5, 227.5)) {
            assertClose(kg, toKg(fromKg(kg, WeightUnit.LB), WeightUnit.LB))
        }
    }

    // v1: Math.round(v * 2) / 2. There is no 0.46 lb plate, so a load is shown
    // at the smallest increment a gym can actually put on the bar.
    @Test
    fun `loads display at the nearest half unit`() {
        assertEquals(5.5, roundForDisplay(5.51155))
        assertEquals(2.5, roundForDisplay(2.5))
        assertEquals(102.5, roundForDisplay(102.4))
        assertEquals(102.0, roundForDisplay(102.2))
    }

    // Half-unit rounding must not reach a field the user is about to edit:
    // opening a 102.3 kg row and saving it must not rewrite it to 102.5.
    @Test
    fun `an editable weight keeps two decimals so editing cannot quantise it`() {
        assertEquals(102.3, roundForInput(102.3))
        assertEquals(5.51, roundForInput(5.51155))
        assertEquals(102.3, displayWeightForInput(102.3, WeightUnit.KG))
    }

    // A barbell increment is wrong for a bodyweight series whose entire job is
    // showing small changes: 82.3 kg must not render as 82.5.
    @Test
    fun `bodyweight and measurements keep one decimal`() {
        assertEquals(82.3, roundMeasurementForDisplay(82.3))
        assertEquals(82.3, roundMeasurementForDisplay(82.34))
        assertEquals(36.5, roundMeasurementForDisplay(36.47))
    }

    @Test
    fun `negative zero is normalised`() {
        // -0.0 formats as "-0" and reads as a bug to the user.
        assertEquals(0.0, roundForDisplay(-0.0))
        assertTrue(1.0 / roundForDisplay(-0.0) > 0, "should be positive zero")
        assertEquals(0.0, roundForInput(-0.0))
        assertTrue(1.0 / roundForInput(-0.0) > 0, "should be positive zero")
        assertEquals(0.0, roundMeasurementForDisplay(-0.0))
        assertTrue(1.0 / roundMeasurementForDisplay(-0.0) > 0, "should be positive zero")
    }

    @Test
    fun `unknown unit ids fall back to the default rather than throwing`() {
        // These values arrive from stored settings and imported backups. One
        // bad string must not make the app unopenable.
        assertEquals(WeightUnit.DEFAULT, WeightUnit.fromId(null))
        assertEquals(WeightUnit.DEFAULT, WeightUnit.fromId("stones"))
        assertEquals(WeightUnit.KG, WeightUnit.fromId("kg"))
        assertEquals(WeightUnit.LB, WeightUnit.fromId("lb"))
    }

    @Test
    fun `default is pounds for display but storage stays metric`() {
        assertEquals(WeightUnit.LB, WeightUnit.DEFAULT)
        assertClose(45.359237, toKg(100.0, WeightUnit.DEFAULT))
    }
}
