package com.nerdginger.workoutmate.core.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Training maths.
 *
 * This file is a port of v1's `stats.js`, and the port is deliberately literal.
 * Its behaviour — including the parts that look like quirks — is the only
 * precise specification this app has ever had, and every rule below is pinned
 * by a test recovered from v1. Where something reads as odd, the comment says
 * why it is that way rather than tidying it into something merely reasonable.
 *
 * Weights are kilograms throughout. Display units never reach this file.
 */

/** A logged set, reduced to exactly the fields the maths reads. */
data class LoggedSet(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val kind: SetKind = SetKind.WORKING,
    val completed: Boolean = true,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val performedAt: Instant,
    val deletedAt: Instant? = null,
)

/** A finished (or unfinished) session, as the frequency chart reads it. */
data class LoggedSession(
    val id: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val deletedAt: Instant? = null,
)

/**
 * Epley's estimate degrades badly past about 12 reps, where it starts
 * predicting maxes nobody could lift. High-rep sets are excluded from e1RM
 * rather than being allowed to skew the chart upward.
 */
const val E1RM_MAX_REPS: Int = 12

/**
 * Epley estimated one-rep max: `w × (1 + reps/30)`.
 *
 * Three behaviours are load-bearing and pinned by v1's tests:
 * - a single returns the weight **verbatim**, not `w × (1 + 1/30)`;
 * - anything above [E1RM_MAX_REPS] returns 0, meaning "no opinion", not "zero strength";
 * - non-positive or absent input returns 0 rather than throwing or producing a number.
 */
fun epley1RM(weightKg: Double?, reps: Int?): Double {
    val w = weightKg ?: return 0.0
    val r = reps ?: return 0.0
    if (w <= 0.0 || r <= 0) return 0.0
    if (r == 1) return w
    if (r > E1RM_MAX_REPS) return 0.0
    return w * (1.0 + r / 30.0)
}

/**
 * Whether a set counts toward volume, records and progression.
 *
 * v1 tested `completed === true` by identity rather than truthiness. Kotlin's
 * type system makes that distinction for free, but the intent is worth keeping
 * visible: a set that was never explicitly marked done does not count, and
 * warm-ups never count no matter how heavy.
 */
fun isCountedSet(set: LoggedSet?): Boolean =
    set != null && set.deletedAt == null && set.completed && set.kind != SetKind.WARMUP

/** Tonnage for one set: weight × reps. */
fun setVolume(set: LoggedSet?): Double {
    if (!isCountedSet(set)) return 0.0
    val w = set!!.weightKg ?: return 0.0
    val r = set.reps ?: return 0.0
    if (w <= 0.0 || r <= 0) return 0.0
    return w * r
}

fun sessionVolume(sets: List<LoggedSet>?): Double =
    sets.orEmpty().sumOf { setVolume(it) }

// ---- Date bucketing ------------------------------------------------------
//
// All bucketing goes through a local calendar, never fixed-millisecond
// arithmetic. A 9pm Sunday session belongs to the day the lifter thinks they
// trained, and a week that contains a DST change is still seven days long.
// v1 used `Math.round` purely to absorb DST; a real calendar API removes the
// need for the trick rather than reproducing it.

/** Local calendar day, e.g. "2026-07-30". Sorts correctly as a string. */
fun localDayKey(at: Instant, zone: TimeZone): String =
    at.toLocalDateTime(zone).date.toString()

/** The Monday of the ISO week containing [at], in local time. */
fun startOfIsoWeek(at: Instant, zone: TimeZone): LocalDate {
    val date = at.toLocalDateTime(zone).date
    return date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))
}

/**
 * ISO-8601 week label, e.g. "2026-W31". Sorts correctly as a string.
 *
 * The ISO year is the year containing that week's **Thursday**, which is what
 * makes the year boundary come out right: 2027-01-01 is a Friday and therefore
 * belongs to 2026-W53, not 2027-W01.
 */
fun isoWeekKey(at: Instant, zone: TimeZone): String {
    val monday = startOfIsoWeek(at, zone)
    val thursday = monday.plus(DatePeriod(days = 3))
    val isoYear = thursday.year

    // Week 1 is the week containing 4 January, by definition.
    val jan4 = LocalDate(isoYear, 1, 4)
    val firstMonday = jan4.minus(DatePeriod(days = jan4.dayOfWeek.isoDayNumber - 1))
    val week = ((monday.toEpochDays() - firstMonday.toEpochDays()) / 7) + 1

    return "$isoYear-W${week.toString().padStart(2, '0')}"
}

// ---- Aggregations --------------------------------------------------------
//
// Every bucket map below is a LinkedHashMap. Insertion order is observable in
// v1's tests, so it is preserved rather than left to chance.

/** Total tonnage per ISO week, ascending. */
data class WeeklyVolume(val week: String, val weekStart: LocalDate, val volumeKg: Double)

fun weeklyVolume(sets: List<LoggedSet>?, zone: TimeZone): List<WeeklyVolume> {
    val buckets = LinkedHashMap<String, WeeklyVolume>()
    for (set in sets.orEmpty()) {
        val volume = setVolume(set)
        if (volume <= 0.0) continue
        val key = isoWeekKey(set.performedAt, zone)
        val existing = buckets[key]
        buckets[key] = WeeklyVolume(
            week = key,
            weekStart = existing?.weekStart ?: startOfIsoWeek(set.performedAt, zone),
            volumeKg = (existing?.volumeKg ?: 0.0) + volume,
        )
    }
    return buckets.values.sortedBy { it.week }
}

/** Counts of working sets per muscle group, per ISO week. */
data class WeeklyGroups(
    val week: String,
    val weekStart: LocalDate,
    val groups: Map<MuscleGroup, Int>,
)

/**
 * A set targeting several groups counts once for **each**.
 *
 * The question being answered is "did I train legs this week", not "how do I
 * divide credit between quads and glutes". Splitting credit fractionally would
 * make the chart unreadable and the totals meaningless.
 *
 * Note the two independent routes to [MuscleGroup.OTHER]: an exercise missing
 * from the library entirely, *and* a known exercise that lists no groups. v1
 * pins both, because dropping either would silently under-count real work.
 */
fun setsPerMuscleGroup(
    sets: List<LoggedSet>?,
    groupsByExerciseId: Map<String, List<MuscleGroup>>,
    zone: TimeZone,
): List<WeeklyGroups> {
    val buckets = LinkedHashMap<String, WeeklyGroups>()
    for (set in sets.orEmpty()) {
        if (!isCountedSet(set)) continue
        val key = isoWeekKey(set.performedAt, zone)
        val groups = groupsByExerciseId[set.exerciseId]
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(MuscleGroup.OTHER)

        val existing = buckets[key]
        val counts = LinkedHashMap(existing?.groups ?: emptyMap())
        for (group in groups) counts[group] = (counts[group] ?: 0) + 1

        buckets[key] = WeeklyGroups(
            week = key,
            weekStart = existing?.weekStart ?: startOfIsoWeek(set.performedAt, zone),
            groups = counts,
        )
    }
    return buckets.values.sortedBy { it.week }
}

/** One charted point per session. */
data class SeriesPoint(
    val at: Instant,
    val value: Double,
    val weightKg: Double? = null,
    val reps: Int? = null,
)

/** Best estimated 1RM per session — the headline "am I getting stronger" series. */
fun e1rmSeries(sets: List<LoggedSet>?, exerciseId: String): List<SeriesPoint> {
    val bySession = LinkedHashMap<String, SeriesPoint>()
    for (set in sets.orEmpty()) {
        if (!isCountedSet(set) || set.exerciseId != exerciseId) continue
        val value = epley1RM(set.weightKg, set.reps)
        if (value <= 0.0) continue
        val existing = bySession[set.sessionId]
        // Strict >: within a session, a tie leaves the earlier set in place.
        if (existing == null || value > existing.value) {
            bySession[set.sessionId] = SeriesPoint(set.performedAt, value, set.weightKg, set.reps)
        }
    }
    return bySession.values.sortedBy { it.at }
}

/** Heaviest working set per session, regardless of reps. */
fun topSetSeries(sets: List<LoggedSet>?, exerciseId: String): List<SeriesPoint> {
    val bySession = LinkedHashMap<String, SeriesPoint>()
    for (set in sets.orEmpty()) {
        if (!isCountedSet(set) || set.exerciseId != exerciseId) continue
        val weight = set.weightKg ?: continue
        if (weight <= 0.0) continue
        val existing = bySession[set.sessionId]
        if (existing == null || weight > existing.value) {
            bySession[set.sessionId] = SeriesPoint(set.performedAt, weight, weight, set.reps)
        }
    }
    return bySession.values.sortedBy { it.at }
}

/**
 * Per-day session counts for the heatmap.
 *
 * Only **finished** sessions count. An abandoned session is not a workout, and
 * counting it would let the streak be gamed by opening the app.
 */
fun workoutFrequency(sessions: List<LoggedSession>?, zone: TimeZone): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    for (session in sessions.orEmpty()) {
        if (session.deletedAt != null || session.finishedAt == null) continue
        val key = localDayKey(session.startedAt, zone)
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts
}

// ---- Personal records ----------------------------------------------------

fun prKey(exerciseId: String, kind: PrKind): String = "$exerciseId:${kind.id}"

/** A record beaten in the session being examined. */
data class DetectedPr(
    val id: String,
    val exerciseId: String,
    val kind: PrKind,
    val value: Double,
    val weightKg: Double?,
    val reps: Int?,
    val setId: String?,
    val sessionId: String,
    val achievedAt: Instant,
)

/** The current best for an exercise and kind, as stored. */
data class StoredBest(val value: Double)

/**
 * Compares a session's sets against the stored bests and returns what was beaten.
 *
 * Two behaviours here are easy to get wrong and are pinned by v1's tests:
 *
 * 1. **Ties never count.** In-session comparisons use strict `>`, and the check
 *    against a stored best *rejects* on `>=`. You have to actually beat it.
 * 2. **`sessionId` comes from the first qualifying set** of that exercise, not
 *    from the set that actually set the record. That is v1's behaviour; it is
 *    harmless because every candidate set is from the same session, and
 *    changing it would silently alter existing data on re-import.
 */
fun detectPrs(
    sets: List<LoggedSet>?,
    currentBests: Map<String, StoredBest>,
): List<DetectedPr> {
    class Candidate(val exerciseId: String, val sessionId: String) {
        var e1rm: DetectedPr? = null
        var weight: DetectedPr? = null
        var volume: Double = 0.0
        var volumeAt: Instant? = null
        var volumeSetId: String? = null
    }

    val candidates = LinkedHashMap<String, Candidate>()

    for (set in sets.orEmpty()) {
        if (!isCountedSet(set)) continue
        val weight = set.weightKg ?: continue
        val reps = set.reps ?: continue
        if (weight <= 0.0 || reps <= 0) continue

        val entry = candidates.getOrPut(set.exerciseId) {
            Candidate(set.exerciseId, set.sessionId).also {
                it.volumeAt = set.performedAt
                it.volumeSetId = set.id
            }
        }

        val e1rm = epley1RM(weight, reps)
        if (e1rm > 0.0 && (entry.e1rm == null || e1rm > entry.e1rm!!.value)) {
            entry.e1rm = DetectedPr(
                id = prKey(set.exerciseId, PrKind.E1RM),
                exerciseId = set.exerciseId,
                kind = PrKind.E1RM,
                value = e1rm,
                weightKg = weight,
                reps = reps,
                setId = set.id,
                sessionId = entry.sessionId,
                achievedAt = set.performedAt,
            )
        }
        if (entry.weight == null || weight > entry.weight!!.value) {
            entry.weight = DetectedPr(
                id = prKey(set.exerciseId, PrKind.WEIGHT),
                exerciseId = set.exerciseId,
                kind = PrKind.WEIGHT,
                value = weight,
                weightKg = weight,
                reps = reps,
                setId = set.id,
                sessionId = entry.sessionId,
                achievedAt = set.performedAt,
            )
        }
        entry.volume += weight * reps
    }

    val records = mutableListOf<DetectedPr>()
    for (entry in candidates.values) {
        fun consider(candidate: DetectedPr?) {
            if (candidate == null || candidate.value <= 0.0) return
            val previous = currentBests[prKey(entry.exerciseId, candidate.kind)]
            // >= rejects: a tie is not a record.
            if (previous != null && previous.value >= candidate.value) return
            records += candidate
        }

        consider(entry.e1rm)
        consider(entry.weight)
        consider(
            entry.volumeAt?.let { at ->
                DetectedPr(
                    id = prKey(entry.exerciseId, PrKind.VOLUME),
                    exerciseId = entry.exerciseId,
                    kind = PrKind.VOLUME,
                    value = entry.volume,
                    weightKg = null,
                    reps = null,
                    setId = entry.volumeSetId,
                    sessionId = entry.sessionId,
                    achievedAt = at,
                )
            },
        )
    }
    return records
}

/** What you did for this exercise last time. */
data class LastPerformance(
    val sessionId: String,
    val performedAt: Instant,
    val sets: List<Pair<Double?, Int?>>,
    val bestE1rm: Double,
)

/**
 * The reference line shown while logging.
 *
 * [excludeSessionId] is what stops the session in progress from being its own
 * "last time" — without it the reference would update as you logged, which is
 * useless and quietly misleading.
 */
fun lastPerformance(
    sets: List<LoggedSet>?,
    exerciseId: String,
    excludeSessionId: String?,
): LastPerformance? {
    var latest: LoggedSet? = null
    for (set in sets.orEmpty()) {
        if (!isCountedSet(set) || set.exerciseId != exerciseId) continue
        if (excludeSessionId != null && set.sessionId == excludeSessionId) continue
        if (latest == null || set.performedAt > latest.performedAt) latest = set
    }
    val found = latest ?: return null

    val sameSession = sets.orEmpty().filter {
        isCountedSet(it) && it.exerciseId == exerciseId && it.sessionId == found.sessionId
    }
    return LastPerformance(
        sessionId = found.sessionId,
        performedAt = found.performedAt,
        sets = sameSession.map { it.weightKg to it.reps },
        bestE1rm = sameSession.maxOfOrNull { epley1RM(it.weightKg, it.reps) } ?: 0.0,
    )
}
