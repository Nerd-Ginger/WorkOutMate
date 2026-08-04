package com.nerdginger.workoutmate.core.presentation

/**
 * The active session — the screen the whole app exists to serve.
 *
 * Same rule as [TodayState]: the screen renders fields, it does not compute
 * them. That matters more here than anywhere else, because this is the screen
 * used one-handed, mid-set, with a bar waiting. Every string below is composed
 * in `core` where a test can pin it.
 */

/**
 * One row in an exercise's set list.
 *
 * Done / Current / Pending are sealed subtypes rather than booleans on one row
 * type because they carry genuinely different data — a completed set has a
 * result and no editor, a pending set has a target and neither. Flags would let
 * "done and current" exist, which is not a state this screen has.
 */
sealed interface SetRow {
    /** 1-based position within the exercise, rendered as the row's tag. */
    val tag: String

    /** Already logged. */
    data class Done(
        override val tag: String,
        /** Pre-formatted, e.g. "185 × 8". */
        val result: String,
        /** null hides the chip entirely — RPE is optional per user setting. */
        val rpe: String? = null,
        val isPr: Boolean = false,
    ) : SetRow

    /**
     * The set being entered. Exactly one row across the whole session is
     * Current, which is what makes the screen answer "what do I do next"
     * without the user having to decide.
     */
    data class Current(
        override val tag: String,
        /** e.g. "8 @ 185". */
        val targetShort: String,
        /** Already converted to the display unit and formatted. */
        val weight: String,
        val weightUnit: String,
        val reps: String,
        /** Empty disables the RPE row. */
        val rpeOptions: List<String> = emptyList(),
        val selectedRpe: String? = null,
        /**
         * Why the fields are prefilled as they are — "+2.5 kg — you hit 12 on
         * every set last time". Null when there is nothing to justify.
         *
         * Composed in core deliberately: a suggestion the user cannot
         * interrogate is one they will stop trusting, and a rationale built by
         * string concatenation in a composable is one no test ever sees.
         */
        val suggestionReason: String? = null,
    ) : SetRow

    /** Not yet reached. Shows the target so the work ahead is visible. */
    data class Pending(
        override val tag: String,
        val targetShort: String,
    ) : SetRow
}

/** One exercise block within the session. */
data class SessionExercise(
    val itemId: String,
    val name: String,
    /** e.g. "SUPERSET A", or null. */
    val supersetLabel: String? = null,
    /** Last time's work, pre-formatted. Null the first time this is performed. */
    val previous: String? = null,
    /** e.g. "4 × 8 @ 185 lb". */
    val target: String,
    val sets: List<SetRow>,
)

/** The rest countdown, when one is running. */
data class RestTimer(
    /** e.g. "1:24". */
    val remaining: String,
    /** 0f..1f, already clamped. */
    val progress: Float,
    /** What the rest is *for* — "next set of Barbell Row". */
    val nextLabel: String,
)

sealed interface SessionState {
    data object Loading : SessionState

    data class Active(
        val sessionId: String,
        val routineName: String,
        /** e.g. "24:10 elapsed · 7 sets logged". */
        val progressLabel: String,
        val exercises: List<SessionExercise>,
        val rest: RestTimer? = null,
    ) : SessionState

    /** Nothing open. The screen should not have been reachable; say so rather than crash. */
    data object NoActiveSession : SessionState
}

sealed interface SessionEvent {
    /** Steppers move by the exercise's loadable increment, which core owns. */
    data object IncrementWeight : SessionEvent
    data object DecrementWeight : SessionEvent
    data object IncrementReps : SessionEvent
    data object DecrementReps : SessionEvent
    data class PickRpe(val value: String) : SessionEvent
    data object LogSet : SessionEvent
    data object SkipRest : SessionEvent
    data object Finish : SessionEvent
    /** Tapping a completed row to correct it. */
    data class EditSet(val itemId: String, val tag: String) : SessionEvent
    data class AddSet(val itemId: String) : SessionEvent
    data object AddExercise : SessionEvent
}
