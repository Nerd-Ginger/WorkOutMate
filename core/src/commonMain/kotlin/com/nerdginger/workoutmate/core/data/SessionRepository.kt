package com.nerdginger.workoutmate.core.data

import com.nerdginger.workoutmate.core.domain.LoggedSession
import com.nerdginger.workoutmate.core.domain.LoggedSet
import com.nerdginger.workoutmate.core.domain.PrKind
import com.nerdginger.workoutmate.core.domain.SetKind
import com.nerdginger.workoutmate.core.domain.StoredBest
import com.nerdginger.workoutmate.core.domain.SuggestionSource
import com.nerdginger.workoutmate.core.domain.detectPrs
import com.nerdginger.workoutmate.core.domain.prKey
import com.nerdginger.workoutmate.db.WorkoutDb
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Sessions and the sets logged in them.
 *
 * This is the source of truth for the entire app. Rotation position, streaks,
 * consistency, progression suggestions and personal records are all *derived*
 * from these two tables — none of them is stored as mutable state, because any
 * stored copy desynchronises the moment a backup is merged, and merging is a
 * first-class operation here.
 */
class SessionRepository(
    private val db: WorkoutDb,
    private val now: () -> Long,
    private val zone: () -> TimeZone,
    private val newId: () -> String,
) {

    // ---- Sessions --------------------------------------------------------

    /**
     * Starts a session.
     *
     * `local_date` and `tz_id` are computed and stored at start time rather
     * than derived later from the timestamp. Bucketing a session by day from a
     * UTC millisecond is wrong the moment someone trains abroad or crosses a
     * DST boundary, and history should say what the lifter thinks happened.
     */
    fun start(routineId: String?, routineName: String, programId: String?, slotId: String?): String {
        val id = newId()
        val timestamp = now()
        val tz = zone()
        db.sessionQueries.upsertSession(
            id = id,
            routine_id = routineId,
            routine_name = routineName,
            program_id = programId,
            program_slot_id = slotId,
            started_at = timestamp,
            finished_at = null,
            local_date = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(tz).date.toString(),
            tz_id = tz.id,
            notes = "",
            bodyweight_kg = null,
            created_at = timestamp,
            updated_at = timestamp,
            deleted_at = null,
        )
        return id
    }

    /** The interrupted-workout check on launch. There is at most one. */
    fun openSession(): SessionRow? =
        db.sessionQueries.selectActiveSession().executeAsOneOrNull()?.let(::toSessionRow)

    fun session(id: String): SessionRow? =
        db.sessionQueries.selectSessionById(id).executeAsOneOrNull()?.let(::toSessionRow)

    /**
     * Filters and limits in Kotlin rather than SQL.
     *
     * The `finished_at IS NOT NULL` queries make SQLDelight narrow that column
     * to non-null, which is correct and useful — but it generates a
     * query-specific row type per query, each needing its own mapper to keep in
     * step with this one. Sessions number a few hundred after years of
     * training, so one mapper over the live query is the better trade.
     */
    fun recentSessions(limit: Int): List<SessionRow> =
        db.sessionQueries.selectLiveSessions().executeAsList()
            .filter { it.finished_at != null }
            .sortedByDescending { it.started_at }
            .take(limit)
            .map(::toSessionRow)

    /**
     * Finishes a session and returns the records it set.
     *
     * Detection happens here, at the one moment the full session is known and
     * complete. Doing it per-set would award a record mid-session that a later
     * set could beat, and the volume record cannot be known until the last set
     * is in.
     */
    fun finish(sessionId: String): List<PrKind> {
        val timestamp = now()
        val sets = setsForSession(sessionId)
        val records = detectPrs(sets, currentBests())

        db.transaction {
            db.sessionQueries.finishSession(timestamp, timestamp, sessionId)
            for (record in records) {
                db.prQueries.upsert(
                    id = record.id,
                    exercise_id = record.exerciseId,
                    kind = record.kind.id,
                    value_ = record.value,
                    weight_kg = record.weightKg,
                    reps = record.reps?.toLong(),
                    set_id = record.setId,
                    session_id = record.sessionId,
                    achieved_at = record.achievedAt.toEpochMilliseconds(),
                    updated_at = timestamp,
                    deleted_at = null,
                )
            }
        }
        return records.map { it.kind }
    }

    // ---- Sets ------------------------------------------------------------

    /**
     * Logs a set.
     *
     * The plan snapshot (`target_reps_min/max`, `target_rpe`) and the
     * suggestion that was offered are both written onto the row. Without the
     * snapshot, editing a routine rewrites what history claims you were aiming
     * for; without the suggestion, whether you accepted it becomes unknowable
     * rather than derivable.
     */
    fun logSet(
        sessionId: String,
        exerciseId: String,
        routineItemId: String?,
        blockId: String? = null,
        indexInItem: Int = 0,
        orderInSession: Int = 0,
        kind: SetKind = SetKind.WORKING,
        weightKg: Double?,
        reps: Int?,
        rpe: Double? = null,
        displayUnit: String = "kg",
        targetRepsMin: Int? = null,
        targetRepsMax: Int? = null,
        targetRpe: Double? = null,
        suggestedWeightKg: Double? = null,
        suggestedReps: Int? = null,
        suggestionSource: SuggestionSource? = null,
        notes: String = "",
    ): String {
        val id = newId()
        val timestamp = now()
        db.sessionQueries.upsertSet(
            id = id,
            session_id = sessionId,
            exercise_id = exerciseId,
            block_id = blockId,
            routine_item_id = routineItemId,
            order_in_session = orderInSession.toLong(),
            index_in_item = indexInItem.toLong(),
            kind = kind.id,
            weight_kg = weightKg,
            reps = reps?.toLong(),
            rpe = rpe,
            display_unit = displayUnit,
            completed = 1L,
            is_pr = null,
            target_reps_min = targetRepsMin?.toLong(),
            target_reps_max = targetRepsMax?.toLong(),
            target_rpe = targetRpe,
            suggested_weight_kg = suggestedWeightKg,
            suggested_reps = suggestedReps?.toLong(),
            suggestion_source = suggestionSource?.id,
            notes = notes,
            performed_at = timestamp,
            created_at = timestamp,
            updated_at = timestamp,
            deleted_at = null,
        )
        return id
    }

    fun setsForSession(sessionId: String): List<LoggedSet> =
        db.sessionQueries.selectSetsForSession(sessionId).executeAsList().map(::toLoggedSet)

    fun allSets(): List<LoggedSet> =
        db.sessionQueries.selectAllSets().executeAsList()
            .map(::toLoggedSet)
            .filter { it.deletedAt == null }

    fun allSessions(): List<LoggedSession> =
        db.sessionQueries.selectAllSessions().executeAsList().map {
            LoggedSession(
                id = it.id,
                startedAt = Instant.fromEpochMilliseconds(it.started_at),
                finishedAt = it.finished_at?.let(Instant::fromEpochMilliseconds),
                deletedAt = it.deleted_at?.let(Instant::fromEpochMilliseconds),
            )
        }

    /** Soft delete, so restoring an older backup cannot resurrect it. */
    fun deleteSet(setId: String) {
        val timestamp = now()
        db.sessionQueries.softDeleteSet(timestamp, timestamp, setId)
    }

    // ---- Personal records ------------------------------------------------

    fun currentBests(): Map<String, StoredBest> =
        db.prQueries.selectLive().executeAsList().associate { it.id to StoredBest(it.value_) }

    /**
     * Rebuilds the `pr` table from the session log.
     *
     * `pr` is a cache, not a source of truth, and this is the function that
     * makes that claim true. A merge-import can bring in *older* sets, which
     * cannot beat a stored best and so would leave the cache silently
     * disagreeing with history — a real latent bug in v1. Replaying every
     * session in chronological order reproduces the records the log actually
     * implies.
     */
    fun rebuildPrs(): Int {
        val sets = allSets()
        val bySession = sets.groupBy { it.sessionId }
        val order = bySession.keys.sortedBy { key ->
            bySession.getValue(key).minOf { it.performedAt }
        }

        val bests = mutableMapOf<String, StoredBest>()
        val winners = LinkedHashMap<String, com.nerdginger.workoutmate.core.domain.DetectedPr>()

        for (sessionId in order) {
            for (record in detectPrs(bySession.getValue(sessionId), bests)) {
                bests[prKey(record.exerciseId, record.kind)] = StoredBest(record.value)
                winners[record.id] = record
            }
        }

        val timestamp = now()
        db.transaction {
            db.prQueries.deleteAll()
            for (record in winners.values) {
                db.prQueries.upsert(
                    id = record.id,
                    exercise_id = record.exerciseId,
                    kind = record.kind.id,
                    value_ = record.value,
                    weight_kg = record.weightKg,
                    reps = record.reps?.toLong(),
                    set_id = record.setId,
                    session_id = record.sessionId,
                    achieved_at = record.achievedAt.toEpochMilliseconds(),
                    updated_at = timestamp,
                    deleted_at = null,
                )
            }
        }
        return winners.size
    }

    // ---- Mapping ---------------------------------------------------------

    private fun toLoggedSet(row: com.nerdginger.workoutmate.db.Exercise_set) = LoggedSet(
        id = row.id,
        sessionId = row.session_id,
        exerciseId = row.exercise_id,
        kind = SetKind.fromId(row.kind),
        completed = row.completed != 0L,
        weightKg = row.weight_kg,
        reps = row.reps?.toInt(),
        rpe = row.rpe,
        performedAt = Instant.fromEpochMilliseconds(row.performed_at),
        deletedAt = row.deleted_at?.let(Instant::fromEpochMilliseconds),
    )

    private fun toSessionRow(row: com.nerdginger.workoutmate.db.Session) = SessionRow(
        id = row.id,
        routineId = row.routine_id,
        routineName = row.routine_name,
        programId = row.program_id,
        programSlotId = row.program_slot_id,
        startedAt = Instant.fromEpochMilliseconds(row.started_at),
        finishedAt = row.finished_at?.let(Instant::fromEpochMilliseconds),
        localDate = row.local_date,
        notes = row.notes,
    )
}

/** A session as the app reads it. */
data class SessionRow(
    val id: String,
    val routineId: String?,
    val routineName: String,
    val programId: String?,
    val programSlotId: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val localDate: String,
    val notes: String,
)
