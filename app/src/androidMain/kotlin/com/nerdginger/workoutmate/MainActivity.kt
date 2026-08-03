package com.nerdginger.workoutmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nerdginger.workoutmate.ui.App

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
        setContent { App() }
    }
}
