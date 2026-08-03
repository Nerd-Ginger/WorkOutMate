package com.nerdginger.workoutmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerdginger.workoutmate.core.domain.WeightUnit
import com.nerdginger.workoutmate.core.domain.displayWeight

/**
 * The app's single root composable.
 *
 * Lives in `commonMain`, not `androidMain`, and must stay free of `android.*`
 * imports and generated `R` references. That is what makes enabling an iOS
 * target later an additive change rather than a port.
 *
 * Phase 0 scope: this exists to prove the build wiring end to end — that the
 * Compose Multiplatform plugin, the Kotlin compose compiler and the composite
 * dependency on `core` all agree with each other. It renders a value computed
 * by `core` precisely so the dependency cannot be optimised away or silently
 * unresolved.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("WorkOutMate", style = MaterialTheme.typography.headlineMedium)
                Text(
                    // Computed in `core`. If the composite substitution ever
                    // stops working this line stops compiling, which is the
                    // point of putting it here.
                    "100 kg reads as ${displayWeight(100.0, WeightUnit.LB)} lb",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
