package com.nerdginger.workoutmate.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.nerdginger.workoutmate.core.domain.PrKind
import com.nerdginger.workoutmate.core.domain.SetKind
import com.nerdginger.workoutmate.core.domain.prKey
import com.nerdginger.workoutmate.db.WorkoutDb
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session log, against real SQLite.
 *
 * The most important test here is the last one. `pr` is a cache, and
 * `rebuildPrs` is the function that makes that claim true rather than
 * aspirational.
 */
class SessionRepositoryTest {

    private var clock = 1_700_000_000_000L
    private var ids = 0

    private fun repository(db: WorkoutDb) = SessionRepository(
        db = db,
        now = { clock },
        zone = { TimeZone.of("Europe/London") },
        newId = { "id-${ids++}" },
    )

    private fun database(): WorkoutDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorkoutDb.Schema.create(driver)
        return WorkoutDb(driver)
    }

    @Test
    fun `a started session is the open session until it is finished`() {
        val repo = repository(database())
        val id = repo.start(routineId = null, routineName = "Pull", programId = null, slotId = null)

        assertEquals(id, repo.openSession()?.id)
        repo.finish(id)
        assertNull(repo.openSession(), "a finished session must not still be open")
    }

    @Test
    fun `the local date is stored at start time, not derived later`() {
        // Bucketing by day from a UTC millisecond is wrong the moment someone
        // trains abroad or crosses a DST boundary.
        val repo = repository(database())
        val id = repo.start(null, "Pull", null, null)

        val session = repo.session(id)
        assertNotNull(session)
        assertEquals("2023-11-14", session.localDate)
    }

    @Test
    fun `finishing a session awards the records it set`() {
        val repo = repository(database())
        val id = repo.start(null, "Push", null, null)
        repo.logSet(id, "bench", null, weightKg = 100.0, reps = 5)

        val kinds = repo.finish(id).sortedBy { it.id }
        assertEquals(listOf(PrKind.E1RM, PrKind.VOLUME, PrKind.WEIGHT), kinds)
    }

    @Test
    fun `a warm-up cannot take a record`() {
        val repo = repository(database())
        val id = repo.start(null, "Push", null, null)
        repo.logSet(id, "bench", null, weightKg = 200.0, reps = 1, kind = SetKind.WARMUP)

        assertEquals(emptyList(), repo.finish(id))
    }

    @Test
    fun `finishing does not disturb when the session started`() {
        // finishSession is a targeted UPDATE precisely so closing a session
        // cannot rewrite started_at, local_date or tz_id.
        val repo = repository(database())
        val id = repo.start(null, "Pull", null, null)
        val started = repo.session(id)!!.startedAt

        clock += 3_600_000
        repo.finish(id)

        val after = repo.session(id)!!
        assertEquals(started, after.startedAt)
        assertEquals("2023-11-14", after.localDate)
        assertNotNull(after.finishedAt)
    }

    @Test
    fun `a deleted set stops counting`() {
        val repo = repository(database())
        val id = repo.start(null, "Push", null, null)
        val setId = repo.logSet(id, "bench", null, weightKg = 100.0, reps = 5)

        repo.deleteSet(setId)
        assertEquals(emptyList(), repo.allSets())
    }

    /**
     * The whole reason `pr` is a cache, and a real latent bug in v1.
     *
     * Detection runs when a session is *finished*. A merge-import does not
     * finish anything — it writes rows straight into the tables — so every set
     * it brings in is invisible to the personal-record table no matter how
     * heavy it was. The cache silently disagrees with the log, and nothing
     * surfaces it.
     *
     * The insert below therefore bypasses [SessionRepository.finish]
     * deliberately: going through it would exercise detection and prove
     * nothing.
     */
    @Test
    fun `rebuildPrs recovers records that a merge-import wrote in behind it`() {
        val db = database()
        val repo = repository(db)

        // A normal session, detected the normal way.
        val recent = repo.start(null, "Push", null, null)
        repo.logSet(recent, "bench", null, weightKg = 100.0, reps = 5)
        repo.finish(recent)
        assertEquals(100.0, repo.currentBests()[prKey("bench", PrKind.WEIGHT)]?.value)

        // Now an imported session: a heavier day, written directly.
        val importedAt = clock - 30L * 24 * 3_600_000
        db.sessionQueries.upsertSession(
            id = "imported", routine_id = null, routine_name = "Push",
            program_id = null, program_slot_id = null,
            started_at = importedAt, finished_at = importedAt + 3_600_000,
            local_date = "2023-10-15", tz_id = "Europe/London",
            notes = "", bodyweight_kg = null,
            created_at = importedAt, updated_at = importedAt, deleted_at = null,
        )
        db.sessionQueries.upsertSet(
            id = "imported-set", session_id = "imported", exercise_id = "bench",
            block_id = null, routine_item_id = null,
            order_in_session = 0, index_in_item = 0, kind = SetKind.WORKING.id,
            weight_kg = 140.0, reps = 3, rpe = null, display_unit = "kg",
            completed = 1, is_pr = null,
            target_reps_min = null, target_reps_max = null, target_rpe = null,
            suggested_weight_kg = null, suggested_reps = null, suggestion_source = null,
            notes = "", performed_at = importedAt,
            created_at = importedAt, updated_at = importedAt, deleted_at = null,
        )

        // The 140 kg set is in the log and absent from the records.
        val stale = repo.currentBests()[prKey("bench", PrKind.WEIGHT)]?.value
        assertEquals(100.0, stale, "detection never ran for the imported session")

        repo.rebuildPrs()

        val rebuilt = repo.currentBests()[prKey("bench", PrKind.WEIGHT)]?.value
        assertEquals(140.0, rebuilt, "the heaviest set ever lifted is the weight record")
        assertTrue(stale != rebuilt, "this test is pointless if the rebuild changed nothing")
    }

    @Test
    fun `rebuildPrs is idempotent`() {
        val repo = repository(database())
        val id = repo.start(null, "Push", null, null)
        repo.logSet(id, "bench", null, weightKg = 100.0, reps = 5)
        repo.finish(id)

        val first = repo.rebuildPrs()
        val second = repo.rebuildPrs()
        assertEquals(first, second)
        assertEquals(100.0, repo.currentBests()[prKey("bench", PrKind.WEIGHT)]?.value)
    }

    @Test
    fun `the suggestion offered is recorded so acceptance stays derivable`() {
        // Storing "did they accept it" as a flag would desynchronise on merge;
        // storing what was offered lets acceptance be computed from the row.
        val repo = repository(database())
        val id = repo.start(null, "Push", null, null)
        repo.logSet(
            sessionId = id,
            exerciseId = "bench",
            routineItemId = null,
            weightKg = 102.5,
            reps = 5,
            suggestedWeightKg = 102.5,
            suggestedReps = 5,
        )

        val sets = repo.setsForSession(id)
        assertEquals(1, sets.size)
        assertEquals(102.5, sets.first().weightKg)
    }
}
