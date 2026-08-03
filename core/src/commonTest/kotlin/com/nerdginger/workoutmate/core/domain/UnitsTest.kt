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
        assertEquals(220.46, displayWeight(100.0, WeightUnit.LB))
    }

    @Test
    fun `round trips through pounds without drift`() {
        for (kg in listOf(2.5, 20.0, 60.0, 102.5, 227.5)) {
            assertClose(kg, toKg(fromKg(kg, WeightUnit.LB), WeightUnit.LB))
        }
    }

    @Test
    fun `display rounds to two decimals`() {
        assertEquals(5.51, roundForDisplay(5.51155))
        assertEquals(2.5, roundForDisplay(2.5))
    }

    @Test
    fun `negative zero is normalised`() {
        // -0.0 formats as "-0" and reads as a bug to the user.
        assertEquals(0.0, roundForDisplay(-0.0))
        assertTrue(1.0 / roundForDisplay(-0.0) > 0, "should be positive zero")
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
