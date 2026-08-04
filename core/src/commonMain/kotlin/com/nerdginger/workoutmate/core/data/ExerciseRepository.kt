package com.nerdginger.workoutmate.core.data

import com.nerdginger.workoutmate.core.domain.Equipment
import com.nerdginger.workoutmate.core.domain.MuscleGroup
import com.nerdginger.workoutmate.db.WorkoutDb
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** An exercise as the app uses it. */
data class Exercise(
    val id: String,
    val name: String,
    val nameKey: String,
    val muscleGroups: List<MuscleGroup>,
    val equipment: Equipment,
    val isCustom: Boolean,
    val notes: String,
    val defaultIncrementKg: Double?,
) {
    /**
     * The smallest change that can actually be loaded for this movement:
     * the exercise's own override first, then the equipment default.
     *
     * Null means there is no weight to add — bodyweight and bands progress by
     * reps. That is distinct from zero, which would make a stalled lift suggest
     * the same number forever and look broken.
     */
    val incrementKg: Double? get() = defaultIncrementKg ?: equipment.defaultIncrementKg
}

private val json = Json { ignoreUnknownKeys = true }
private val stringList = ListSerializer(String.serializer())

/**
 * The exercise library.
 *
 * Muscle groups are stored as a JSON array in a TEXT column rather than a join
 * table. That is a deliberate trade: the list is short, always read whole, and
 * never queried across — and keeping it inline means an exercise remains a
 * single atomic row for backup merge, which is the operation that actually
 * shapes this schema.
 */
class ExerciseRepository(
    private val db: WorkoutDb,
    private val now: () -> Long,
) {

    fun all(): List<Exercise> = db.exerciseQueries.selectLive().executeAsList().map(::toDomain)

    fun byId(id: String): Exercise? =
        db.exerciseQueries.selectById(id).executeAsOneOrNull()?.let(::toDomain)

    fun count(): Long = db.exerciseQueries.countAll().executeAsOne()

    /**
     * Writes the bundled library, idempotently.
     *
     * Seeded rows keep a stable id derived from the normalised name, so running
     * this after every app update refreshes the built-in library in place
     * rather than duplicating it. A user's own edits to a seeded exercise are
     * therefore overwritten by design — customisation belongs on a custom
     * exercise, which this never touches.
     *
     * @return how many rows were written.
     */
    fun seed(source: ContentSource): Int {
        val seeded = loadExerciseSeed(source)
        if (seeded.isEmpty()) return 0

        val timestamp = now()
        db.transaction {
            for (exercise in seeded) {
                db.exerciseQueries.upsert(
                    id = exercise.id,
                    name = exercise.name,
                    name_key = exercise.nameKey,
                    muscle_groups = json.encodeToString(stringList, exercise.muscleGroups.map { it.id }),
                    equipment = exercise.equipment.id,
                    is_custom = 0L,
                    notes = exercise.notes,
                    default_increment_kg = null,
                    created_at = timestamp,
                    updated_at = timestamp,
                    deleted_at = null,
                )
            }
        }
        return seeded.size
    }

    /** Seeds only if the library is empty, so a user's deletions are not resurrected. */
    fun seedIfEmpty(source: ContentSource): Int =
        if (count() == 0L) seed(source) else 0

    private fun toDomain(row: com.nerdginger.workoutmate.db.Exercise) = Exercise(
        id = row.id,
        name = row.name,
        nameKey = row.name_key,
        // A malformed array must not make the library unopenable; an exercise
        // with no groups is recoverable, a crash on launch is not.
        muscleGroups = runCatching {
            MuscleGroup.fromIds(json.decodeFromString(stringList, row.muscle_groups))
        }.getOrDefault(emptyList()),
        equipment = Equipment.fromId(row.equipment),
        isCustom = row.is_custom != 0L,
        notes = row.notes,
        defaultIncrementKg = row.default_increment_kg,
    )
}
