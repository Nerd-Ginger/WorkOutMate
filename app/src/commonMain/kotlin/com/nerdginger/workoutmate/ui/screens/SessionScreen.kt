package com.nerdginger.workoutmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerdginger.workoutmate.core.presentation.RestTimer
import com.nerdginger.workoutmate.core.presentation.SessionEvent
import com.nerdginger.workoutmate.core.presentation.SessionExercise
import com.nerdginger.workoutmate.core.presentation.SessionState
import com.nerdginger.workoutmate.core.presentation.SetRow
import com.nerdginger.workoutmate.ui.theme.WomTheme

/**
 * The active session, rendered from the design prototype.
 *
 * The whole routine is scrollable with the current set highlighted in place,
 * rather than a one-set-at-a-time wizard. That is the prototype's choice and it
 * is the right one: mid-session you need to see what is coming and what you
 * just did, and a wizard hides both.
 */
@Composable
fun SessionScreen(state: SessionState, onEvent: (SessionEvent) -> Unit) {
    when (state) {
        is SessionState.Loading -> CentreNote("Loading…")
        is SessionState.NoActiveSession -> CentreNote("No session in progress.")
        is SessionState.Active -> ActiveSession(state, onEvent)
    }
}

@Composable
private fun ActiveSession(state: SessionState.Active, onEvent: (SessionEvent) -> Unit) {
    Column(Modifier.fillMaxSize().background(WomTheme.colors.background)) {
        SessionHeader(state.routineName, state.progressLabel, onEvent)

        LazyColumn(Modifier.weight(1f)) {
            items(state.exercises, key = { it.itemId }) { ex ->
                ExerciseCard(ex, onEvent)
            }
            item { Box(Modifier.height(22.dp)) }
        }

        state.rest?.let { RestBar(it, onEvent) }
    }
}

/**
 * Sticky header.
 *
 * Finish is a ghost button rather than a solid one on purpose — the loud
 * affordance on this screen is "Log set", and a session should not end by
 * accident with a bar in your hands.
 */
@Composable
private fun SessionHeader(routineName: String, progressLabel: String, onEvent: (SessionEvent) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WomTheme.colors.background)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(routineName, style = WomTheme.type.title, color = WomTheme.colors.textPrimary)
            Box(Modifier.height(2.dp))
            Text(progressLabel, style = WomTheme.type.mono, color = WomTheme.colors.textDim)
        }
        Text(
            "Finish",
            style = WomTheme.type.label,
            color = WomTheme.colors.accent,
            modifier = Modifier
                .border(1.dp, WomTheme.colors.accentBorder, WomTheme.shapes.button)
                .clickable { onEvent(SessionEvent.Finish) }
                .padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(WomTheme.colors.border))
}

@Composable
private fun ExerciseCard(ex: SessionExercise, onEvent: (SessionEvent) -> Unit) {
    Column(
        Modifier
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .fillMaxWidth()
            .background(WomTheme.colors.surface, WomTheme.shapes.cardSmall)
            .border(1.dp, WomTheme.colors.border, WomTheme.shapes.cardSmall),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                ex.name,
                style = WomTheme.type.cardTitle,
                color = WomTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ex.supersetLabel?.let {
                Text(
                    it,
                    style = WomTheme.type.tab,
                    color = WomTheme.colors.accent,
                    modifier = Modifier
                        .border(1.dp, WomTheme.colors.accentBorder, WomTheme.shapes.badge)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        // What you did last time, immediately above what you are about to do.
        ex.previous?.let {
            Row(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp)
                    .fillMaxWidth()
                    .background(WomTheme.colors.surfaceSunken, WomTheme.shapes.inset)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("PREV", style = WomTheme.type.monoTiny, color = WomTheme.colors.textFaint)
                Text(it, style = WomTheme.type.mono, color = WomTheme.colors.textData)
            }
        }

        Text(
            "TARGET ${ex.target}",
            style = WomTheme.type.mono,
            color = WomTheme.colors.textFaint,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Column(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ex.sets.forEach { row ->
                when (row) {
                    is SetRow.Done -> DoneRow(row, ex.itemId, onEvent)
                    is SetRow.Current -> CurrentSetEditor(row, onEvent)
                    is SetRow.Pending -> PendingRow(row)
                }
            }
        }
    }
}

@Composable
private fun DoneRow(row: SetRow.Done, itemId: String, onEvent: (SessionEvent) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(WomTheme.spacing.setRowHeight)
            .background(WomTheme.colors.surfaceSunken, WomTheme.shapes.button)
            .clickable { onEvent(SessionEvent.EditSet(itemId, row.tag)) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(row.tag, style = WomTheme.type.mono, color = WomTheme.colors.textFaint, modifier = Modifier.width(22.dp))
        Text(
            row.result,
            style = WomTheme.type.monoSet,
            color = WomTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        row.rpe?.let {
            Text(
                "RPE $it",
                style = WomTheme.type.mono,
                color = WomTheme.colors.textDim,
                modifier = Modifier
                    .background(WomTheme.colors.surfaceChip, WomTheme.shapes.badge)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.size(16.dp).background(WomTheme.colors.accent, CircleShape))
    }
}

@Composable
private fun PendingRow(row: SetRow.Pending) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .border(1.dp, WomTheme.colors.surfaceChip, WomTheme.shapes.button)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(row.tag, style = WomTheme.type.mono, color = WomTheme.colors.iconInactive, modifier = Modifier.width(22.dp))
        Text(row.targetShort, style = WomTheme.type.mono, color = WomTheme.colors.textFaint)
    }
}

/**
 * The set being entered.
 *
 * Steppers rather than a keyboard: this is operated with one thumb, often with
 * chalk on your hands, and the increment is whatever that exercise can actually
 * be loaded by — which core decides, not this screen.
 */
@Composable
private fun CurrentSetEditor(row: SetRow.Current, onEvent: (SessionEvent) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WomTheme.colors.accentSurfaceWarm, WomTheme.shapes.tile)
            .border(1.5.dp, WomTheme.colors.accent, WomTheme.shapes.tile)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SET ${row.tag}",
                style = WomTheme.type.monoTiny,
                color = WomTheme.colors.accent,
                modifier = Modifier.weight(1f),
            )
            Text("target ${row.targetShort}", style = WomTheme.type.mono, color = WomTheme.colors.textDim)
        }

        Box(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stepper(
                label = "WEIGHT ${row.weightUnit.uppercase()}",
                value = row.weight,
                onDecrement = { onEvent(SessionEvent.DecrementWeight) },
                onIncrement = { onEvent(SessionEvent.IncrementWeight) },
            )
            Stepper(
                label = "REPS",
                value = row.reps,
                onDecrement = { onEvent(SessionEvent.DecrementReps) },
                onIncrement = { onEvent(SessionEvent.IncrementReps) },
            )
        }

        if (row.rpeOptions.isNotEmpty()) {
            Box(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RPE", style = WomTheme.type.monoTiny, color = WomTheme.colors.textFaint)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.rpeOptions.forEach { option ->
                        val on = option == row.selectedRpe
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(
                                    if (on) WomTheme.colors.accent else WomTheme.colors.surfaceChip,
                                    WomTheme.shapes.inset,
                                )
                                .clickable { onEvent(SessionEvent.PickRpe(option)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option,
                                style = WomTheme.type.monoSet,
                                color = if (on) WomTheme.colors.onAccent else WomTheme.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }

        // The one-line justification for the prefilled numbers.
        row.suggestionReason?.let {
            Box(Modifier.height(9.dp))
            Text(it, style = WomTheme.type.caption, color = WomTheme.colors.textData)
        }

        Box(Modifier.height(11.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(WomTheme.colors.accent, RoundedCornerShape(11.dp))
                .clickable { onEvent(SessionEvent.LogSet) },
            contentAlignment = Alignment.Center,
        ) {
            Text("Log set", style = WomTheme.type.button, color = WomTheme.colors.onAccent)
        }
    }
}

@Composable
private fun RowScope.Stepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .background(WomTheme.colors.background, RoundedCornerShape(11.dp))
            .border(1.dp, WomTheme.colors.border, RoundedCornerShape(11.dp))
            .padding(horizontal = 7.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = WomTheme.type.monoTiny,
            color = WomTheme.colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepperButton("−", onDecrement)
            // softWrap is off deliberately. The prototype was drawn on a 412px
            // frame; a 393dp phone is ~5% narrower, which is enough to make
            // "185" wrap to "18 / 5" — a value that reads as a different number
            // entirely. Overflowing is recoverable, wrapping is not, so the
            // number is pinned to one line and the type shrinks to fit around
            // the buttons instead.
            Text(
                value,
                style = WomTheme.type.monoStat.copy(fontSize = 21.sp),
                color = WomTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            StepperButton("+", onIncrement)
        }
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    // 42dp rather than the prototype's 46: still a comfortable thumb target,
    // and the 8dp reclaimed across both buttons is what lets a five-character
    // weight like "102.5" sit on one line on a 393dp screen.
    Box(
        Modifier
            .size(42.dp)
            .background(WomTheme.colors.surfaceChip, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = WomTheme.type.display.copy(fontSize = 22.sp), color = WomTheme.colors.textSecondary)
    }
}

/** The rest countdown, docked above the tab bar. */
@Composable
private fun RestBar(rest: RestTimer, onEvent: (SessionEvent) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(WomTheme.colors.accentBorder))
    Row(
        Modifier
            .fillMaxWidth()
            .background(WomTheme.colors.accentSurfaceWarm)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            rest.remaining,
            style = WomTheme.type.monoStat,
            color = WomTheme.colors.accent,
            modifier = Modifier.width(66.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(rest.nextLabel, style = WomTheme.type.caption, color = WomTheme.colors.textData)
            Box(Modifier.height(7.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(WomTheme.colors.border, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(rest.progress)
                        .height(4.dp)
                        .background(WomTheme.colors.accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(
            "Skip",
            style = WomTheme.type.label,
            color = WomTheme.colors.accent,
            modifier = Modifier.clickable { onEvent(SessionEvent.SkipRest) }.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun CentreNote(text: String) {
    Box(
        Modifier.fillMaxSize().background(WomTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WomTheme.type.body, color = WomTheme.colors.textDim)
    }
}
