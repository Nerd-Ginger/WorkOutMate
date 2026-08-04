package com.nerdginger.workoutmate.core.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from v1's `tests/stats.test.mjs`, case for case.
 *
 * These assertions are the specification. Where one of them looks like it is
 * pinning a quirk — ties not counting, a record's session id coming from the
 * wrong set — it is pinning deliberate behaviour, and the comment says so. Any
 * test here that has to change is a product decision, not a refactor.
 *
 * v1 ran in the browser's local zone. These run in a fixed zone so the suite
 * cannot pass or fail depending on where the machine is.
 */
private val ZONE = TimeZone.of("Europe/London")

private fun at(iso: String): Instant = LocalDateTime.parse(iso).toInstant(ZONE)

private fun set(
    id: String = "s",
    sessionId: String = "s1",
    exerciseId: String = "bench",
    kind: SetKind = SetKind.WORKING,
    completed: Boolean = true,
    weightKg: Double? = 100.0,
    reps: Int? = 5,
    performedAt: Instant = at("2026-07-29T10:00:00"),
    deletedAt: Instant? = null,
) = LoggedSet(id, sessionId, exerciseId, kind, completed, weightKg, reps, null, performedAt, deletedAt)

private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.001) {
    assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
}

class TrainingTest {

    // ---- epley1RM --------------------------------------------------------

    @Test
    fun `epley1RM returns the weight itself for a single`() {
        assertEquals(100.0, epley1RM(100.0, 1))
    }

    @Test
    fun `epley1RM applies w x one plus reps over thirty`() {
        assertClose(116.6667, epley1RM(100.0, 5))
        assertClose(80.0, epley1RM(60.0, 10))
    }

    @Test
    fun `epley1RM refuses rep counts where the formula stops being meaningful`() {
        assertTrue(epley1RM(50.0, E1RM_MAX_REPS) > 0.0)
        assertEquals(0.0, epley1RM(50.0, E1RM_MAX_REPS + 1))
    }

    @Test
    fun `epley1RM rejects nonsense input rather than producing a number`() {
        assertEquals(0.0, epley1RM(0.0, 5))
        assertEquals(0.0, epley1RM(100.0, 0))
        assertEquals(0.0, epley1RM(-100.0, 5))
        // v1 was handed the string 'heavy' here; the Kotlin equivalent of
        // "not a usable number" is absence.
        assertEquals(0.0, epley1RM(null, 5))
        assertEquals(0.0, epley1RM(null, null))
    }

    // ---- volume ----------------------------------------------------------

    @Test
    fun `warm-up and incomplete sets contribute no volume`() {
        assertEquals(500.0, setVolume(set(weightKg = 100.0, reps = 5)))
        assertEquals(0.0, setVolume(set(kind = SetKind.WARMUP)))
        assertEquals(0.0, setVolume(set(completed = false)))
        assertEquals(0.0, setVolume(set(deletedAt = at("2026-07-30T10:00:00"))))
    }

    @Test
    fun `sessionVolume sums only the counted sets`() {
        val sets = listOf(
            set(weightKg = 100.0, reps = 5),
            set(weightKg = 60.0, reps = 10),
            set(weightKg = 40.0, reps = 10, kind = SetKind.WARMUP),
        )
        assertEquals(500.0 + 600.0, sessionVolume(sets))
    }

    // ---- date bucketing --------------------------------------------------

    @Test
    fun `isoWeekKey handles the year boundary the ISO way`() {
        // 2027-01-01 is a Friday, so it belongs to ISO week 53 of 2026.
        assertEquals("2026-W53", isoWeekKey(at("2027-01-01T12:00:00"), ZONE))
        // 2026-01-01 is a Thursday, so it is week 1 of 2026.
        assertEquals("2026-W01", isoWeekKey(at("2026-01-01T12:00:00"), ZONE))
    }

    @Test
    fun `isoWeekKey groups a Monday and the following Sunday together`() {
        assertEquals(
            isoWeekKey(at("2026-07-27T06:00:00"), ZONE),
            isoWeekKey(at("2026-08-02T22:00:00"), ZONE),
        )
    }

    @Test
    fun `startOfIsoWeek snaps back to Monday`() {
        val start = startOfIsoWeek(at("2026-07-30T17:45:00"), ZONE)
        assertEquals(1, start.dayOfWeek.isoDayNumber)
        assertEquals("2026-07-27", start.toString())
    }

    @Test
    fun `localDayKey uses local time, not UTC`() {
        // A late-evening session belongs to the day the lifter trained.
        assertEquals("2026-07-30", localDayKey(at("2026-07-30T23:30:00"), ZONE))
    }

    /**
     * Not a v1 test. v1 used millisecond arithmetic with a `Math.round` to
     * absorb DST; this implementation uses a calendar, so the case it was
     * papering over is worth pinning directly.
     */
    @Test
    fun `a week containing a DST change still buckets as one week`() {
        // Europe/London springs forward on 2026-03-29.
        val before = isoWeekKey(at("2026-03-23T09:00:00"), ZONE)
        val after = isoWeekKey(at("2026-03-29T09:00:00"), ZONE)
        assertEquals(before, after)
    }

    // ---- aggregations ----------------------------------------------------

    @Test
    fun `weeklyVolume buckets and sorts by week`() {
        val result = weeklyVolume(
            listOf(
                set(performedAt = at("2026-07-27T10:00:00"), weightKg = 100.0, reps = 5),
                set(performedAt = at("2026-08-02T10:00:00"), weightKg = 100.0, reps = 5),
                set(performedAt = at("2026-08-04T10:00:00"), weightKg = 50.0, reps = 10),
            ),
            ZONE,
        )
        assertEquals(2, result.size)
        assertEquals(1000.0, result[0].volumeKg)
        assertEquals(500.0, result[1].volumeKg)
        assertTrue(result[0].week < result[1].week)
    }

    @Test
    fun `setsPerMuscleGroup credits every group a movement trains`() {
        val groups = mapOf(
            "bench" to listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS),
            "squat" to listOf(MuscleGroup.QUADS),
        )
        val result = setsPerMuscleGroup(
            listOf(
                set(exerciseId = "bench"),
                set(exerciseId = "bench"),
                set(exerciseId = "squat"),
                set(exerciseId = "squat", kind = SetKind.WARMUP),
            ),
            groups,
            ZONE,
        )
        assertEquals(1, result.size)
        assertEquals(
            mapOf(MuscleGroup.CHEST to 2, MuscleGroup.TRICEPS to 2, MuscleGroup.QUADS to 1),
            result[0].groups,
        )
    }

    @Test
    fun `setsPerMuscleGroup files an unknown exercise under other`() {
        val result = setsPerMuscleGroup(listOf(set(exerciseId = "mystery")), emptyMap(), ZONE)
        assertEquals(mapOf(MuscleGroup.OTHER to 1), result[0].groups)
    }

    @Test
    fun `setsPerMuscleGroup files a known exercise with no groups under other`() {
        // The second, independent route to `other`. Dropping this one would
        // silently discard real work rather than mis-filing it.
        val result = setsPerMuscleGroup(
            listOf(set(exerciseId = "bench")),
            mapOf("bench" to emptyList()),
            ZONE,
        )
        assertEquals(mapOf(MuscleGroup.OTHER to 1), result[0].groups)
    }

    @Test
    fun `e1rmSeries keeps the best set per session, ordered by time`() {
        val series = e1rmSeries(
            listOf(
                set(sessionId = "a", weightKg = 100.0, reps = 5, performedAt = at("2026-07-27T10:00:00")),
                set(sessionId = "a", weightKg = 110.0, reps = 3, performedAt = at("2026-07-27T10:30:00")),
                set(sessionId = "b", weightKg = 105.0, reps = 5, performedAt = at("2026-07-29T10:00:00")),
            ),
            "bench",
        )
        assertEquals(2, series.size)
        assertEquals(110.0, series[0].weightKg)
        assertTrue(series[0].at < series[1].at)
    }

    @Test
    fun `workoutFrequency counts only finished sessions`() {
        val counts = workoutFrequency(
            listOf(
                LoggedSession("a", at("2026-07-30T10:00:00"), at("2026-07-30T11:00:00")),
                LoggedSession("b", at("2026-07-30T18:00:00"), at("2026-07-30T19:00:00")),
                LoggedSession("c", at("2026-07-31T10:00:00"), finishedAt = null),
            ),
            ZONE,
        )
        assertEquals(2, counts["2026-07-30"])
        assertEquals(false, counts.containsKey("2026-07-31"))
    }

    // ---- personal records ------------------------------------------------

    @Test
    fun `detectPrs reports a first-ever session as records across all kinds`() {
        val records = detectPrs(listOf(set(weightKg = 100.0, reps = 5)), emptyMap())
        assertEquals(
            listOf(PrKind.E1RM, PrKind.VOLUME, PrKind.WEIGHT),
            records.map { it.kind }.sortedBy { it.id },
        )
    }

    @Test
    fun `detectPrs does not award a record for matching a previous best`() {
        // The tie rule. You have to actually beat it.
        val bests = mapOf(
            prKey("bench", PrKind.WEIGHT) to StoredBest(100.0),
            prKey("bench", PrKind.E1RM) to StoredBest(116.67),
            prKey("bench", PrKind.VOLUME) to StoredBest(500.0),
        )
        assertEquals(0, detectPrs(listOf(set(weightKg = 100.0, reps = 5)), bests).size)
    }

    @Test
    fun `detectPrs awards a weight record when the bar goes up`() {
        val bests = mapOf(
            prKey("bench", PrKind.WEIGHT) to StoredBest(100.0),
            prKey("bench", PrKind.E1RM) to StoredBest(999.0),
            prKey("bench", PrKind.VOLUME) to StoredBest(999999.0),
        )
        val records = detectPrs(listOf(set(weightKg = 102.5, reps = 3)), bests)
        assertEquals(1, records.size)
        assertEquals(PrKind.WEIGHT, records[0].kind)
        assertEquals(102.5, records[0].value)
    }

    @Test
    fun `detectPrs ignores warm-ups when deciding records`() {
        val bests = mapOf(
            prKey("bench", PrKind.WEIGHT) to StoredBest(100.0),
            prKey("bench", PrKind.E1RM) to StoredBest(999.0),
            prKey("bench", PrKind.VOLUME) to StoredBest(999999.0),
        )
        // A 200 kg single must not set a weight record if it was a warm-up.
        val records = detectPrs(
            listOf(set(weightKg = 200.0, reps = 1, kind = SetKind.WARMUP)),
            bests,
        )
        assertEquals(0, records.size)
    }

    @Test
    fun `detectPrs sums session volume across sets of the same exercise`() {
        val bests = mapOf(
            prKey("bench", PrKind.WEIGHT) to StoredBest(999.0),
            prKey("bench", PrKind.E1RM) to StoredBest(999.0),
            prKey("bench", PrKind.VOLUME) to StoredBest(900.0),
        )
        val records = detectPrs(
            listOf(set(weightKg = 100.0, reps = 5), set(weightKg = 100.0, reps = 5)),
            bests,
        )
        assertEquals(1, records.size)
        assertEquals(PrKind.VOLUME, records[0].kind)
        assertEquals(1000.0, records[0].value)
    }

    @Test
    fun `detectPrs stamps the session id from the first qualifying set`() {
        // Not an accident. Every candidate set is from the same session, so it
        // is harmless — but it is v1's behaviour and changing it would alter
        // existing rows on re-import.
        val records = detectPrs(
            listOf(
                set(id = "first", sessionId = "sess-1", weightKg = 100.0, reps = 5),
                set(id = "best", sessionId = "sess-1", weightKg = 140.0, reps = 3),
            ),
            emptyMap(),
        )
        assertTrue(records.all { it.sessionId == "sess-1" })
        assertEquals("best", records.first { it.kind == PrKind.WEIGHT }.setId)
    }

    // ---- last performance ------------------------------------------------

    @Test
    fun `lastPerformance skips the session in progress`() {
        val sets = listOf(
            set(id = "old", sessionId = "prev", weightKg = 95.0, reps = 5, performedAt = at("2026-07-27T10:00:00")),
            set(id = "new", sessionId = "current", weightKg = 100.0, reps = 5, performedAt = at("2026-07-29T10:00:00")),
        )
        val previous = lastPerformance(sets, "bench", "current")
        assertEquals("prev", previous?.sessionId)
        assertEquals(95.0, previous?.sets?.first()?.first)
    }

    @Test
    fun `lastPerformance returns null the first time an exercise is performed`() {
        assertNull(lastPerformance(emptyList(), "bench", "current"))
    }
}
