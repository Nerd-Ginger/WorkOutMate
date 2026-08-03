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

/**
 * Rounds for display to two decimals, then drops a trailing `.0` case by
 * returning a value that formats cleanly.
 *
 * Two decimals rather than one because a 2.5 kg plate is 5.51 lb, and rounding
 * that to 5.5 accumulates visible drift once it is summed into weekly tonnage.
 */
fun roundForDisplay(value: Double): Double {
    val scaled = round(value * 100.0) / 100.0
    // Normalise -0.0, which prints as "-0" and looks like a bug.
    return if (abs(scaled) < 1e-9) 0.0 else scaled
}

/**
 * Converts a stored weight into the display unit, rounded.
 * The inverse of [toKg] for round-tripping user input.
 */
fun displayWeight(kg: Double, unit: WeightUnit): Double = roundForDisplay(fromKg(kg, unit))
