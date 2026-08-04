package com.nerdginger.workoutmate.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.nerdginger.workoutmate.core.domain.Equipment
import com.nerdginger.workoutmate.core.domain.MuscleGroup
import com.nerdginger.workoutmate.db.WorkoutDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Seeding, against real SQLite rather than a fake.
 *
 * This is the capability v1 never had: every query and every migration in this
 * module runs against the actual engine, in memory, in milliseconds.
 */
class ExerciseSeedTest {

    private fun database(): WorkoutDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorkoutDb.Schema.create(driver)
        return WorkoutDb(driver)
    }

    private fun repository(db: WorkoutDb) = ExerciseRepository(db) { 1_700_000_000_000 }

    @Test
    fun `the bundled library seeds every exercise`() {
        val repo = repository(database())
        val written = repo.seed(ClasspathContentSource())

        assertEquals(82, written, "the seed file should contain 82 exercises")
        assertEquals(82, repo.all().size)
    }

    @Test
    fun `seeding twice does not duplicate the library`() {
        // Ids derive from the normalised name precisely so that shipping an
        // updated seed refreshes rows in place rather than doubling them.
        val repo = repository(database())
        repo.seed(ClasspathContentSource())
        repo.seed(ClasspathContentSource())
        assertEquals(82, repo.all().size)
    }

    @Test
    fun `seedIfEmpty leaves an existing library alone`() {
        val repo = repository(database())
        repo.seed(ClasspathContentSource())
        assertEquals(0, repo.seedIfEmpty(ClasspathContentSource()))
    }

    @Test
    fun `muscle groups and equipment survive the round trip`() {
        val repo = repository(database())
        repo.seed(ClasspathContentSource())

        val squat = repo.all().firstOrNull { it.name == "Barbell Back Squat" }
        assertNotNull(squat, "the seed should contain Barbell Back Squat")
        assertEquals(Equipment.BARBELL, squat.equipment)
        assertTrue(MuscleGroup.QUADS in squat.muscleGroups)
        assertTrue(MuscleGroup.GLUTES in squat.muscleGroups)
        assertEquals(false, squat.isCustom)
    }

    @Test
    fun `a barbell movement inherits the barbell increment`() {
        // Nothing overrides it, so it must fall through to the equipment
        // default — this is what stops the suggestion engine proposing a
        // weight that cannot be loaded.
        val repo = repository(database())
        repo.seed(ClasspathContentSource())

        val squat = repo.all().first { it.name == "Barbell Back Squat" }
        assertEquals(2.5, squat.incrementKg)
    }

    @Test
    fun `a bodyweight movement has no increment at all`() {
        // Null rather than zero: the engine reads this as "progress the reps".
        val repo = repository(database())
        repo.seed(ClasspathContentSource())

        val bodyweight = repo.all().firstOrNull { it.equipment == Equipment.BODYWEIGHT }
        assertNotNull(bodyweight, "the seed should contain at least one bodyweight movement")
        assertEquals(null, bodyweight.incrementKg)
    }

    @Test
    fun `name normalisation collapses punctuation, case and spacing`() {
        assertEquals("barbell bench press", normaliseExerciseName("Barbell Bench Press"))
        assertEquals("barbell bench press", normaliseExerciseName("barbell bench-press"))
        assertEquals("barbell bench press", normaliseExerciseName("  Barbell   Bench  Press "))
    }

    @Test
    fun `every seeded name normalises to something unique`() {
        // Two exercises colliding on the normalised key would silently
        // overwrite each other during seeding, losing a movement from the
        // library with no error anywhere.
        val repo = repository(database())
        repo.seed(ClasspathContentSource())

        val names = repo.all().map { it.nameKey }
        assertEquals(names.size, names.toSet().size, "normalised names must be unique")
    }
}
