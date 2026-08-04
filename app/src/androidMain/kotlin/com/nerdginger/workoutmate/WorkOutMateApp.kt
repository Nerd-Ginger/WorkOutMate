package com.nerdginger.workoutmate

import android.app.Application

/**
 * Owns the app-scoped graph.
 *
 * The graph lives here rather than in the Activity because presenters are held
 * across configuration changes and process-death restores, and an
 * Activity-scoped graph would rebuild the database connection every rotation.
 *
 * Seeding is deliberately *not* done here. `Application.onCreate` runs on the
 * main thread before any window exists, and 82 inserts there is a measurable
 * cold-start cost paid on every launch to benefit only the first one.
 */
class WorkOutMateApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }
}
