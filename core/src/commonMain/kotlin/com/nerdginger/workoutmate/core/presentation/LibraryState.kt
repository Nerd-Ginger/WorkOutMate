package com.nerdginger.workoutmate.core.presentation

/** One row of the exercise library. */
data class ExerciseRow(
    val id: String,
    val name: String,
    /** e.g. "Barbell · Quads, Glutes". Composed here, not in the composable. */
    val detail: String,
    /** The user's best set, or null if never performed. */
    val best: String?,
    val isCustom: Boolean,
)

/** A muscle-group heading with its exercises beneath it. */
data class ExerciseGroup(val title: String, val rows: List<ExerciseRow>)

sealed interface LibraryState {
    data object Loading : LibraryState

    data class Loaded(
        val query: String,
        val groups: List<ExerciseGroup>,
        /** Rendered when a search matches nothing, so the screen never goes blank. */
        val emptyMessage: String?,
        val totalCount: Int,
    ) : LibraryState
}

sealed interface LibraryEvent {
    data class Search(val query: String) : LibraryEvent
    data class OpenExercise(val id: String) : LibraryEvent
    data object AddCustom : LibraryEvent
}
