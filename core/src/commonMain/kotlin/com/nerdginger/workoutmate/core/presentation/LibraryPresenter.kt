package com.nerdginger.workoutmate.core.presentation

import com.nerdginger.workoutmate.core.data.Exercise
import com.nerdginger.workoutmate.core.data.ExerciseRepository
import com.nerdginger.workoutmate.core.data.normaliseExerciseName
import com.nerdginger.workoutmate.core.domain.MuscleGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The exercise library.
 *
 * A presenter is a plain class holding a `StateFlow` — no `ViewModel`, no
 * lifecycle, nothing from androidx. That is what keeps it here in `core` where
 * it compiles and tests in seconds, and it is why the whole grouping, search
 * and formatting story below is covered by ordinary unit tests rather than
 * being blind-zone code.
 */
class LibraryPresenter(
    private val exercises: ExerciseRepository,
) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var query: String = ""

    fun load() {
        render()
    }

    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Search -> {
                query = event.query
                render()
            }
            // Navigation and creation are the host's business, not the
            // presenter's; they are listed on the event type so the screen has
            // one place to send everything.
            is LibraryEvent.OpenExercise -> Unit
            is LibraryEvent.AddCustom -> Unit
        }
    }

    private fun render() {
        val all = exercises.all()
        val matches = filter(all, query)

        _state.value = LibraryState.Loaded(
            query = query,
            groups = group(matches),
            emptyMessage = when {
                all.isEmpty() -> "No exercises yet."
                matches.isEmpty() -> "Nothing matches “$query”."
                else -> null
            },
            totalCount = all.size,
        )
    }

    /**
     * Search runs over the *normalised* name, so "bench-press" and "Bench
     * Press" both find the same movement — the same rule the routine builder
     * uses to match generated names against the library.
     */
    private fun filter(all: List<Exercise>, query: String): List<Exercise> {
        val needle = normaliseExerciseName(query)
        if (needle.isEmpty()) return all
        return all.filter { needle in it.nameKey || needle in normaliseExerciseName(it.equipment.label) }
    }

    /**
     * Grouped by primary muscle group — the first one listed.
     *
     * Filing a movement under every group it trains would put the bench press
     * in three places, which makes the list longer than the flat one it was
     * meant to improve on. Credit is shared in the *charts*, where the question
     * is "did I train this"; here the question is "where do I find it".
     */
    private fun group(matches: List<Exercise>): List<ExerciseGroup> =
        matches
            .groupBy { it.muscleGroups.firstOrNull() ?: MuscleGroup.OTHER }
            .toList()
            .sortedBy { (group, _) -> MuscleGroup.entries.indexOf(group) }
            .map { (group, rows) ->
                ExerciseGroup(
                    title = group.label,
                    rows = rows.sortedBy { it.name.lowercase() }.map(::toRow),
                )
            }

    private fun toRow(exercise: Exercise) = ExerciseRow(
        id = exercise.id,
        name = exercise.name,
        detail = buildString {
            append(exercise.equipment.label)
            val groups = exercise.muscleGroups.joinToString(", ") { it.label }
            if (groups.isNotEmpty()) append(" · ").append(groups)
        },
        // Wired once the set log is readable; the field exists now so the
        // screen is not restructured later to make room for it.
        best = null,
        isCustom = exercise.isCustom,
    )
}
