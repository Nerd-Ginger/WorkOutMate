package com.nerdginger.workoutmate.core.domain

import kotlin.math.abs
import kotlin.math.round

/**
 * Weight units.
 *
 * Everything in the database is stored in kilograms, always. Pounds are a
 * display concern and nothing else. Storing whichever unit the user happened to
 * be using at the time would make every chart, every total and every personal
 * best depend on a preference they can change, and would silently corrupt
 * history the first time they switched.
 */
enum class WeightUnit(val id: String, val label: String) {
    KG("kg", "Kilograms"),
    LB("lb", "Pounds"),
    ;

    companion object {
        /** Falls back to the default rather than throwing: this value arrives
         *  from stored settings and from imported backups, and a single bad
         *  string should not make the app unopenable. */
        fun fromId(id: String?): WeightUnit = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Pounds by owner preference. Storage is unaffected — still kilograms. */
        val DEFAULT: WeightUnit = LB
    }
}

/** Exact, by definition of the international pound. */
const val KG_PER_LB: Double = 0.45359237

fun toKg(value: Double, unit: WeightUnit): Double =
    if (unit == WeightUnit.KG) value else value * KG_PER_LB

fun fromKg(kg: Double, unit: WeightUnit): Double =
    if (unit == WeightUnit.KG) kg else kg / KG_PER_LB

/** Normalises -0.0, which prints as "-0" and reads as a bug to the user. */
private fun zeroNormalised(value: Double): Double =
    if (abs(value) < 1e-9) 0.0 else value

/**
 * Rounds a *load* for display, to the nearest half unit — the smallest
 * increment most gyms can actually load.
 *
 * This is v1's behaviour verbatim (`Math.round(v * 2) / 2`) and
 * `docs/SMOKE_CHECKLIST.md:106` pins the visible consequence: 100 kg reads as
 * 220.5 lb. Showing 220.46 lb is arithmetically truer and practically useless —
 * there is no 0.46 lb plate, and a number nobody can load is noise on a screen
 * you read between sets.
 *
 * Deliberately *not* used for the editable weight field or for measurements;
 * see [roundForInput] and [roundMeasurementForDisplay].
 */
fun roundForDisplay(value: Double): Double = zeroNormalised(round(value * 2.0) / 2.0)

/**
 * Rounds for an editable field, to two decimals.
 *
 * Half-unit rounding must not reach an input the user is about to edit: a
 * stored 102.3 kg would render as 102.5, and simply opening the row and saving
 * would silently rewrite history to a weight that was never lifted. v1 kept
 * this separate for the same reason (`train.js` formatted inputs with
 * `toFixed(2)` while displays used the half-unit rounding).
 */
fun roundForInput(value: Double): Double = zeroNormalised(round(value * 100.0) / 100.0)

/**
 * Rounds a bodyweight or body measurement for display, to one decimal.
 *
 * Half-unit rounding is right for barbells and wrong here: it would render
 * 82.3 kg of bodyweight as 82.5, inventing a 200 g swing in a series whose
 * whole purpose is showing small changes over time. Scales and tape measures
 * both read to 0.1, which is what v1 used (`formatNumber(v, 1)`, input
 * `step: 0.1`).
 */
fun roundMeasurementForDisplay(value: Double): Double = zeroNormalised(round(value * 10.0) / 10.0)

/**
 * Converts a stored load into the display unit, rounded to a loadable step.
 * For a value that is about to be edited use [displayWeightForInput] instead.
 */
fun displayWeight(kg: Double, unit: WeightUnit): Double = roundForDisplay(fromKg(kg, unit))

/**
 * Converts a stored load into the display unit for an editable field.
 * The inverse of [toKg] for round-tripping user input without quantising it.
 */
fun displayWeightForInput(kg: Double, unit: WeightUnit): Double = roundForInput(fromKg(kg, unit))
