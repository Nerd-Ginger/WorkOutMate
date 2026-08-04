package com.nerdginger.workoutmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.nerdginger.workoutmate.core.presentation.LibraryPresenter
import com.nerdginger.workoutmate.ui.App
import com.nerdginger.workoutmate.ui.theme.rememberAppFonts

/**
 * The single Activity hosting the whole app.
 *
 * A plain [ComponentActivity] rather than `AppCompatActivity`: Compose needs
 * nothing from appcompat, and dropping it removes a dependency along with the
 * theme constraints that come with it.
 *
 * This class stays deliberately thin. Everything it could plausibly do —
 * navigation, screen state, back handling — belongs in `core`, where it can be
 * unit-tested without a device. Whatever genuinely needs Android lives behind
 * the ports in `core`, implemented by adapters in this source set.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as WorkOutMateApp).graph
        // Seeding is done here rather than in Application.onCreate: it is 82
        // inserts, and paying that on the main thread before any window exists
        // slows every launch to benefit only the first.
        graph.onStart()

        setContent {
            App(
                fonts = rememberAppFonts(),
                libraryPresenter = remember { LibraryPresenter(graph.exercises) },
            )
        }
    }
}
