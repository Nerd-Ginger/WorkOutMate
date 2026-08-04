package com.nerdginger.workoutmate.core.presentation

/**
 * What the Today screen shows, and what it can be told.
 *
 * The governing rule for every state contract in this package: **a screen may
 * render a field, never decide one.** Anything a composable would otherwise
 * work out — which variant to draw, whether a banner belongs, what a button
 * says — is resolved here, in a module that compiles and unit-tests in seconds.
 *
 * That is not tidiness. The Compose layer has no local verification worth the
 * name, so a bug that lives there costs a full build-install-tap cycle to see
 * and a screenshot to diagnose. Pushing every branch down here converts those
 * into ordinary test failures, and leaves the only bugs that can survive in the
 * UI the ones a screenshot actually shows: layout.
 */

/** One line of a session's logged work, as the Today card summarises it. */
data class SetLine(
    val exercise: String,
    /** Pre-rendered, e.g. "185×8 · 185×8 · 180×6". Formatting is a core concern. */
    val results: String,
)

/** A summary of a session already logged. */
data class SessionSummary(
    val sessionId: String,
    val routineName: String,
    /** e.g. "Mon, 48 min". */
    val whenLabel: String,
    val lines: List<SetLine>,
    /**
     * The record line under the divider, or null for no records.
     * Pre-composed here so the screen never concatenates a sentence.
     */
    val highlight: String? = null,
)

/** A scheduled routine, as Today previews it before you start. */
data class SlotView(
    val slotId: String,
    val routineId: String?,
    /** e.g. "Day B — Pull". */
    val title: String,
    /** e.g. "Pull — 6 exercises". */
    val subtitle: String,
    /** e.g. "~52 min", or null when there is no basis to estimate. */
    val estimate: String?,
    /**
     * The exercise chips. Already truncated, and already carrying the
     * "+4 more" chip as its last entry when there are more than fit.
     */
    val chips: List<String>,
)

/** The three tiles under the primary card. */
data class ConsistencyView(
    val sessionsThisWeek: Int,
    /** e.g. "41.2k". Abbreviation is a formatting decision, so it is made here. */
    val volumeLabel: String,
    val volumeUnit: String,
    val recordsThisWeek: Int,
    /** Target sessions per week, or null when the programme sets no target. */
    val weeklyTarget: Int? = null,
)

/**
 * The stale-backup notice.
 *
 * Present or absent — the screen does not compute staleness, and does not own
 * the threshold. Everything on this phone is one wipe from gone, which is why
 * this is a banner on the main screen rather than a line in settings.
 */
data class BackupNag(
    /** e.g. "Last backup was 9 days ago. Everything lives on this phone." */
    val message: String,
)

/**
 * Every variant Today can be in.
 *
 * These are sealed subtypes rather than nullable fields on one class so that
 * "due" and "rest day" cannot both be half-true at once — a state that has no
 * meaning but that a bag of optionals would happily represent.
 */
sealed interface TodayState {

    /** The backup nag rides along with every loaded variant, so it lives here. */
    val nag: BackupNag? get() = null

    data object Loading : TodayState

    /**
     * No programme yet. Today becomes the front door rather than an empty
     * logger, because finding a programme is the app's first job.
     */
    data class NoProgramme(
        val recent: List<SessionSummary>,
        override val nag: BackupNag? = null,
    ) : TodayState

    /** A session is scheduled for today and has not been started. */
    data class Due(
        val dateLabel: String,
        val slot: SlotView,
        val programme: String,
        val consistency: ConsistencyView,
        val lastSession: SessionSummary?,
        override val nag: BackupNag? = null,
    ) : TodayState

    /** Scheduled rest. Says when the next session lands so the screen still answers the question. */
    data class RestDay(
        val dateLabel: String,
        val nextOnLabel: String,
        val nextSlot: SlotView?,
        val consistency: ConsistencyView,
        val lastSession: SessionSummary?,
        override val nag: BackupNag? = null,
    ) : TodayState

    /**
     * Sessions were scheduled earlier this week and not logged.
     *
     * Missed slots are reported, never silently rolled forward: a plan that
     * quietly rewrites itself stops being a plan you can be behind on.
     */
    data class Missed(
        val dateLabel: String,
        val missed: List<SlotView>,
        val alsoDueToday: SlotView?,
        val consistency: ConsistencyView,
        val lastSession: SessionSummary?,
        override val nag: BackupNag? = null,
    ) : TodayState

    /** A flexible programme with a weekly target that is not on pace. */
    data class BehindTarget(
        val dateLabel: String,
        val done: Int,
        val target: Int,
        val suggestion: SlotView?,
        val consistency: ConsistencyView,
        val lastSession: SessionSummary?,
        override val nag: BackupNag? = null,
    ) : TodayState

    /** A session is open. Resuming beats starting a second one. */
    data class InProgress(
        val sessionId: String,
        val slot: SlotView,
        /** e.g. "24:10 elapsed · 7 sets logged". */
        val progressLabel: String,
        override val nag: BackupNag? = null,
    ) : TodayState
}

/** Everything the Today screen can ask for. */
sealed interface TodayEvent {
    data object StartSession : TodayEvent
    data class ResumeSession(val sessionId: String) : TodayEvent
    data class StartSpecificSlot(val slotId: String) : TodayEvent
    /** From the Missed variant: log a skipped session now. */
    data class DoMissedNow(val slotId: String) : TodayEvent
    /** From the Missed variant: acknowledge and move on. */
    data class SkipMissed(val slotId: String) : TodayEvent
    data object OpenBackup : TodayEvent
    data object DismissNag : TodayEvent
    data object FindProgramme : TodayEvent
    data class OpenSession(val sessionId: String) : TodayEvent
    data object Refresh : TodayEvent
}
