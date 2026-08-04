package com.nerdginger.workoutmate.core.data

import com.nerdginger.workoutmate.core.domain.Equipment
import com.nerdginger.workoutmate.core.domain.MuscleGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loading the bundled content: the exercise library and, later, the starter
 * programmes.
 *
 * [ContentSource] is an interface rather than a direct file read so that
 * `commonMain` owns the seeding logic — which is the part worth testing — while
 * the platform supplies only the bytes. The JVM implementation reads from the
 * classpath, and because jar resources are packaged into the APK, that same
 * implementation serves Android. iOS would need its own; nothing else moves.
 */
fun interface ContentSource {
    /** Returns the named content file, or null if it is absent. */
    fun read(name: String): String?
}

/** One row of `exercises.seed.json`. */
@Serializable
data class SeedExercise(
    val name: String,
    @SerialName("muscleGroups") val muscleGroups: List<String> = emptyList(),
    val equipment: String = "other",
    val notes: String = "",
)

@Serializable
private data class SeedFile(
    val version: Int = 1,
    val exercises: List<SeedExercise> = emptyList(),
)

/** An exercise ready to be written to the library. */
data class SeededExercise(
    val id: String,
    val name: String,
    val nameKey: String,
    val muscleGroups: List<MuscleGroup>,
    val equipment: Equipment,
    val notes: String,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Normalises a name for matching: lowercased, punctuation stripped, runs of
 * whitespace collapsed.
 *
 * This is what lets the routine builder's output be matched against the
 * library, so "Barbell Bench Press", "barbell bench-press" and "Barbell  Bench
 * Press" all resolve to the same exercise instead of silently creating three.
 * It is also the seed's identity, which is why it must stay stable: changing it
 * would orphan every custom exercise a user has matched against.
 */
fun normaliseExerciseName(name: String): String =
    name.lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .split(" ", "\t", "\n")
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * Reads and validates the seed library.
 *
 * The id is derived from the normalised name rather than randomly generated:
 * seeding must be idempotent, so that re-running it after an app update updates
 * the existing rows instead of duplicating all 82 of them.
 */
fun loadExerciseSeed(source: ContentSource): List<SeededExercise> {
    val raw = source.read("exercises.seed.json") ?: return emptyList()
    val parsed = json.decodeFromString<SeedFile>(raw)
    return parsed.exercises.mapNotNull { entry ->
        val key = normaliseExerciseName(entry.name)
        if (key.isEmpty()) return@mapNotNull null
        SeededExercise(
            id = "seed:$key",
            name = entry.name,
            nameKey = key,
            muscleGroups = MuscleGroup.fromIds(entry.muscleGroups),
            equipment = Equipment.fromId(entry.equipment),
            notes = entry.notes,
        )
    }
}
