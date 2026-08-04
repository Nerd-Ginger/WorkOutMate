package com.nerdginger.workoutmate.core.domain

/**
 * The closed vocabularies the schema stores as TEXT.
 *
 * Every one of these is persisted as its [id] and read back through `fromId`,
 * which falls back to a default rather than throwing. That is deliberate and
 * applies across the file: these values arrive from stored rows and from
 * imported backups written by older builds, and one unrecognised string must
 * never be able to make the app unopenable. A row that reads back as the
 * default is a visible, recoverable annoyance; a crash on launch is not.
 */

/** Groups a movement trains. A set credits every group its exercise lists. */
enum class MuscleGroup(val id: String, val label: String) {
    CHEST("chest", "Chest"),
    BACK("back", "Back"),
    SHOULDERS("shoulders", "Shoulders"),
    BICEPS("biceps", "Biceps"),
    TRICEPS("triceps", "Triceps"),
    QUADS("quads", "Quads"),
    HAMSTRINGS("hamstrings", "Hamstrings"),
    GLUTES("glutes", "Glutes"),
    CALVES("calves", "Calves"),
    CORE("core", "Core"),
    FOREARMS("forearms", "Forearms"),
    CARDIO("cardio", "Cardio"),

    /**
     * The catch-all, and reachable by two independent routes that v1's tests
     * pin separately: a set whose exercise is not in the library at all, and a
     * set whose exercise is known but lists no groups. Both must land here
     * rather than being dropped, or the per-group chart silently under-counts
     * work that was actually done.
     */
    OTHER("other", "Other"),
    ;

    companion object {
        fun fromId(id: String?): MuscleGroup = entries.firstOrNull { it.id == id } ?: OTHER

        /** Parses the stored JSON array, tolerating junk entries. */
        fun fromIds(ids: List<String>?): List<MuscleGroup> =
            ids.orEmpty().mapNotNull { raw -> entries.firstOrNull { it.id == raw } }
    }
}

/**
 * What the movement is loaded with.
 *
 * This is not cosmetic: it supplies the default loadable increment when neither
 * the routine item nor the exercise overrides it, which is what stops the
 * suggestion engine proposing a weight nobody can actually put on the bar.
 */
enum class Equipment(val id: String, val label: String, val defaultIncrementKg: Double?) {
    /** 2.5 kg — the pair of 1.25 kg plates most gyms own. */
    BARBELL("barbell", "Barbell", 2.5),

    /** 2.0 kg, because dumbbells come in pairs and step in whole numbers. */
    DUMBBELL("dumbbell", "Dumbbell", 2.0),

    /** Stacks are coarse; pretending otherwise produces unreachable numbers. */
    MACHINE("machine", "Machine", 5.0),
    CABLE("cable", "Cable", 5.0),

    /**
     * `null` means "there is no weight to add" rather than "add nothing" — the
     * suggestion engine reads this as *progress the reps instead*. Encoding it
     * as 0.0 would make a stalled bodyweight lift suggest the same weight
     * forever and look broken.
     */
    BODYWEIGHT("bodyweight", "Bodyweight", null),
    BAND("band", "Band", null),
    KETTLEBELL("kettlebell", "Kettlebell", 4.0),
    OTHER("other", "Other", 2.5),
    ;

    companion object {
        fun fromId(id: String?): Equipment = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** What a logged set counts as. */
enum class SetKind(val id: String, val label: String) {
    WORKING("working", "Working"),

    /**
     * Excluded from volume, personal records and progression alike. A 200 kg
     * single done as a warm-up must not be able to set a weight record — v1's
     * tests pin exactly that case.
     */
    WARMUP("warmup", "Warm-up"),
    DROP("drop", "Drop set"),
    FAILURE("failure", "To failure"),
    ;

    /** Warm-ups are the only kind excluded from the training maths. */
    val countsTowardVolume: Boolean get() = this != WARMUP

    companion object {
        fun fromId(id: String?): SetKind = entries.firstOrNull { it.id == id } ?: WORKING
    }
}

/**
 * How a programme decides what you train today.
 *
 * All three are derived from the session log at read time; none of them stores
 * a position. See `whatIsDue`.
 */
enum class ScheduleKind(val id: String, val label: String) {
    /** Routines pinned to days of the week. Missed days are reported, never rolled forward. */
    WEEKDAY("weekday", "Days of the week"),

    /** Ordered A/B/C, advanced by what was last completed, gated by a minimum rest gap. */
    ROTATION("rotation", "Rotation"),

    /** No fixed days — just a target number of sessions per week. */
    FLEXIBLE("flexible", "Sessions per week"),
    ;

    companion object {
        fun fromId(id: String?): ScheduleKind = entries.firstOrNull { it.id == id } ?: FLEXIBLE
    }
}

/** How a planned item's next target is proposed. Per item, so accessories can differ from main lifts. */
enum class ProgressionRule(val id: String, val label: String) {
    /** Reps to the top of the range, then weight up and reps back to the bottom. */
    DOUBLE("double", "Double progression"),

    /** Add weight every session that hit its targets. */
    LINEAR("linear", "Linear"),

    /** Never add weight; add reps. Correct for bodyweight and band work. */
    REPS_ONLY("reps_only", "Reps only"),

    /** Suggest nothing at all for this item. */
    NONE("none", "No suggestions"),
    ;

    companion object {
        fun fromId(id: String?): ProgressionRule = entries.firstOrNull { it.id == id } ?: DOUBLE
    }
}

/**
 * Body metrics, kept apart from training loads.
 *
 * [storedAsWeight] is the load-bearing bit: bodyweight is a mass and is stored
 * in kilograms like every other weight, while a waist measurement is a length
 * and a body-fat reading is a percentage. Converting the latter two by the
 * pound factor would be nonsense, so the unit rules are per type rather than
 * global.
 */
enum class MeasurementType(
    val id: String,
    val label: String,
    val storedAsWeight: Boolean,
    val fixedUnit: String?,
) {
    BODYWEIGHT("bodyweight", "Bodyweight", true, null),
    WAIST("waist", "Waist", false, "cm"),
    CHEST("chest", "Chest", false, "cm"),
    HIPS("hips", "Hips", false, "cm"),
    THIGH("thigh", "Thigh", false, "cm"),
    ARM("arm", "Arm", false, "cm"),
    BODYFAT("bodyfat", "Body fat", false, "%"),
    ;

    companion object {
        fun fromId(id: String?): MeasurementType = entries.firstOrNull { it.id == id } ?: BODYWEIGHT
    }
}

/**
 * The three records the app tracks.
 *
 * Ties never count — see `detectPrs`. You have to actually beat it.
 */
enum class PrKind(val id: String, val label: String) {
    /** Best estimated one-rep max, the headline "am I getting stronger" number. */
    E1RM("e1rm", "Best e1RM"),

    /** Heaviest working set, whatever the reps. */
    WEIGHT("weight", "Heaviest set"),

    /** Most tonnage for that exercise within a single session. */
    VOLUME("volume", "Best session volume"),
    ;

    companion object {
        fun fromId(id: String?): PrKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Why a set was prefilled with the numbers it was.
 *
 * Stored on the set so suggestion acceptance is *derivable* — comparing what
 * was suggested against what was logged — rather than recorded as a flag that
 * a merge-import could desynchronise.
 */
enum class SuggestionSource(val id: String, val label: String) {
    /** No history yet; the routine's own target weight was used. */
    TARGET("target", "Routine target"),

    /** Repeat what was done last time. */
    REPEAT("repeat", "Same as last time"),

    /** Targets met across the board — load went up. */
    ADD_WEIGHT("add_weight", "Added weight"),

    /** In range but not at the top of it — one more rep instead. */
    ADD_REP("add_rep", "Added a rep"),

    /** Two sessions stuck at the same weight. */
    DELOAD("deload", "Deload"),

    /** Overridden downward three times running; the app stopped pushing this lift. */
    QUIET("quiet", "Suggestions paused"),
    ;

    companion object {
        fun fromId(id: String?): SuggestionSource? = entries.firstOrNull { it.id == id }
    }
}

/** Where a programme came from. Shown on the programme screen so adopted starters stay identifiable. */
enum class ProgramSource(val id: String, val label: String) {
    STARTER("starter", "Starter programme"),
    AI("ai", "Built with Claude"),
    MANUAL("manual", "Created by you"),
    IMPORTED("imported", "Imported"),
    ;

    companion object {
        fun fromId(id: String?): ProgramSource = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

/**
 * Whether a block is performed straight through or alternated.
 *
 * Deliberately stored rather than inferred from item count: a superset block
 * that is down to one item mid-edit must stay a superset instead of flickering
 * type under the user's hands.
 */
enum class BlockKind(val id: String, val label: String) {
    SINGLE("single", "Single exercise"),
    SUPERSET("superset", "Superset"),
    ;

    companion object {
        fun fromId(id: String?): BlockKind = entries.firstOrNull { it.id == id } ?: SINGLE
    }
}
