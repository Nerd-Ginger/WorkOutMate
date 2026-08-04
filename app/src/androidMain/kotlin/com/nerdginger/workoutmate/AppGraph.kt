package com.nerdginger.workoutmate

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.nerdginger.workoutmate.core.data.ClasspathContentSource
import com.nerdginger.workoutmate.core.data.ContentSource
import com.nerdginger.workoutmate.core.data.ExerciseRepository
import com.nerdginger.workoutmate.db.WorkoutDb

/**
 * The whole dependency graph, by hand.
 *
 * There is no DI framework here on purpose. Koin and friends fail at *runtime*
 * on a missing binding, which is the worst possible failure mode in a layer
 * that cannot be exercised locally — you find it by installing the APK and
 * tapping until it crashes. Manual constructor wiring turns every one of those
 * into a compile error instead, and the entire cost is this file.
 *
 * Held for the process lifetime by [WorkOutMateApp]. Nothing here is scoped to
 * an Activity, which is what lets the app lock to portrait and skip
 * `ViewModel` entirely — see `docs/PLAN.md`.
 */
class AppGraph(context: Context) {

    private val driver = AndroidSqliteDriver(
        schema = WorkoutDb.Schema,
        context = context.applicationContext,
        name = "workoutmate.db",
    )

    val database: WorkoutDb = WorkoutDb(driver)

    /**
     * Resources are packaged into the APK by the same mechanism that packages
     * them into a jar, so the JVM implementation from `core` serves Android
     * without an Android-specific variant.
     */
    val content: ContentSource = ClasspathContentSource()

    val exercises: ExerciseRepository = ExerciseRepository(database) { System.currentTimeMillis() }

    /**
     * First-run setup.
     *
     * `seedIfEmpty` rather than `seed`: re-seeding on every launch would
     * resurrect exercises the user had deleted, which is a data-loss bug
     * wearing the costume of a convenience.
     */
    fun onStart() {
        exercises.seedIfEmpty(content)
    }
}
