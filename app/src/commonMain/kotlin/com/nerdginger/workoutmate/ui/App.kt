package com.nerdginger.workoutmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerdginger.workoutmate.core.presentation.BackupNag
import com.nerdginger.workoutmate.core.presentation.ConsistencyView
import com.nerdginger.workoutmate.core.presentation.SessionSummary
import com.nerdginger.workoutmate.core.presentation.SetLine
import com.nerdginger.workoutmate.core.presentation.RestTimer
import com.nerdginger.workoutmate.core.presentation.SessionEvent
import com.nerdginger.workoutmate.core.presentation.SessionExercise
import com.nerdginger.workoutmate.core.presentation.SessionState
import com.nerdginger.workoutmate.core.presentation.SetRow
import com.nerdginger.workoutmate.core.presentation.SlotView
import com.nerdginger.workoutmate.core.presentation.TodayEvent
import com.nerdginger.workoutmate.core.presentation.TodayState
import com.nerdginger.workoutmate.core.presentation.LibraryPresenter
import com.nerdginger.workoutmate.ui.screens.LibraryScreen
import com.nerdginger.workoutmate.ui.screens.SessionScreen
import com.nerdginger.workoutmate.ui.screens.TodayScreen
import com.nerdginger.workoutmate.ui.theme.WomFonts
import com.nerdginger.workoutmate.ui.theme.WomTheme
import com.nerdginger.workoutmate.ui.theme.WorkOutMateTheme

/**
 * The app's single root composable.
 *
 * Lives in `commonMain`, not `androidMain`, and must stay free of `android.*`
 * imports and generated `R` references. That is what makes enabling an iOS
 * target later an additive change rather than a port.
 *
 * The six tabs are the ones in the design prototype. That differs from the
 * five in `docs/PLAN.md` — see the note on [Tab].
 */
@Composable
fun App(
    fonts: WomFonts = WomFonts(),
    /**
     * Null renders the library from no data at all, which is what a preview or
     * a future iOS target would do before its own graph exists. The Android
     * host passes a real presenter.
     */
    libraryPresenter: LibraryPresenter? = null,
) {
    WorkOutMateTheme(fonts = fonts) {
        Surface(Modifier.fillMaxSize(), color = WomTheme.colors.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                var tab by remember { mutableStateOf(Tab.Today) }
                // Stands in for the Navigator until it exists. Deliberately the
                // smallest thing that lets the session screen be reached and
                // left, so the shell can be tapped through end to end.
                var inSession by remember { mutableStateOf(false) }

                Box(Modifier.weight(1f)) {
                    when {
                        inSession -> SessionScreen(
                            state = previewSession,
                            onEvent = { if (it is SessionEvent.Finish) inSession = false },
                        )
                        tab == Tab.Today -> TodayScreen(
                            state = previewToday,
                            onEvent = { if (it is TodayEvent.StartSession) inSession = true },
                        )
                        tab == Tab.Exercises && libraryPresenter != null -> {
                            val libraryState by libraryPresenter.state.collectAsState()
                            LaunchedEffect(libraryPresenter) { libraryPresenter.load() }
                            LibraryScreen(libraryState, libraryPresenter::onEvent)
                        }
                        else -> Placeholder(tab)
                    }
                }

                TabBar(current = tab, onSelect = { tab = it })
            }
        }
    }
}

/**
 * The bottom tabs.
 *
 * The prototype ships six — Exercises is top-level and Data replaces Settings —
 * where `docs/PLAN.md` specifies five (Today · Plan · Progress · Body ·
 * Settings). The prototype is the newer artefact and is what the owner asked to
 * build against, so it wins here; the divergence is flagged rather than
 * silently reconciled, because which one is right is a product call.
 */
enum class Tab(val label: String) {
    Today("Today"),
    Routines("Routines"),
    Charts("Charts"),
    Exercises("Exercises"),
    Body("Body"),
    Data("Data"),
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WomTheme.colors.surfaceSunken)
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // The design uses a filled square for the active tab and a
                // hollow one otherwise — placeholder glyphs in the prototype,
                // kept as-is until real icons exist.
                if (on) {
                    Box(Modifier.size(20.dp).background(WomTheme.colors.accent, WomTheme.shapes.badge))
                } else {
                    Box(
                        Modifier
                            .size(20.dp)
                            .border(1.5.dp, WomTheme.colors.iconInactive, WomTheme.shapes.badge),
                    )
                }
                Text(
                    t.label,
                    style = WomTheme.type.tab,
                    color = if (on) WomTheme.colors.accent else WomTheme.colors.textInactive,
                )
            }
        }
    }
}

@Composable
private fun Placeholder(tab: Tab) {
    Box(
        Modifier.fillMaxSize().background(WomTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Text("${tab.label} — not built yet", style = WomTheme.type.body, color = WomTheme.colors.textDim)
    }
}

/**
 * Stub state so the screen can be seen on a device before repositories exist.
 *
 * Deliberately the *Due* variant with a stale backup: it is the busiest arrangement
 * Today has, so it exercises the most layout. Values match the prototype's so
 * the build can be compared against the design side by side.
 */
private val previewToday = TodayState.Due(
    dateLabel = "Wednesday 5 Aug",
    programme = "Push / Pull / Legs",
    slot = SlotView(
        slotId = "slot-b",
        routineId = "routine-pull",
        title = "Day B — Pull",
        subtitle = "Pull — 6 exercises",
        estimate = "~52 min",
        chips = listOf("Barbell Row 4×8", "Lat Pulldown 3×10", "+4 more"),
    ),
    consistency = ConsistencyView(
        sessionsThisWeek = 3,
        volumeLabel = "41.2k",
        volumeUnit = "lb",
        recordsThisWeek = 2,
        weeklyTarget = 4,
    ),
    lastSession = SessionSummary(
        sessionId = "s-prev",
        routineName = "Day A — Push",
        whenLabel = "Mon, 48 min",
        lines = listOf(
            SetLine("Bench Press", "185×8 · 185×8 · 180×6"),
            SetLine("Overhead Press", "110×8 · 110×7 · 105×7"),
            SetLine("Incline DB Press", "65×10 · 65×9 · 60×9"),
        ),
        highlight = "New best e1RM — Bench Press 231 lb",
    ),
    nag = BackupNag("Last backup was 9 days ago. Everything lives on this phone."),
)

/**
 * Stub session state, again matching the prototype's own sample data.
 *
 * Arranged so all three set-row states are on screen at once — two logged, one
 * being entered, one still to come — because that is the arrangement most
 * likely to expose a layout mistake.
 */
private val previewSession = SessionState.Active(
    sessionId = "s-live",
    routineName = "Day B — Pull",
    progressLabel = "24:10 elapsed · 7 sets logged",
    rest = RestTimer(remaining = "1:24", progress = 0.55f, nextLabel = "Rest — next set of Barbell Row"),
    exercises = listOf(
        SessionExercise(
            itemId = "i1",
            name = "Barbell Row",
            previous = "185×8 · 185×8 · 175×7 · 175×7",
            target = "4 × 8 @ 185 lb",
            sets = listOf(
                SetRow.Done(tag = "1", result = "185 × 8", rpe = "8"),
                SetRow.Done(tag = "2", result = "185 × 8", rpe = "8.5"),
                SetRow.Current(
                    tag = "3",
                    targetShort = "8 @ 185",
                    weight = "185",
                    weightUnit = "lb",
                    reps = "8",
                    rpeOptions = listOf("6", "7", "8", "9", "10"),
                    selectedRpe = "8",
                    suggestionReason = "Same weight — you were a rep short last time.",
                ),
                SetRow.Pending(tag = "4", targetShort = "8 @ 185"),
            ),
        ),
        SessionExercise(
            itemId = "i2",
            name = "Face Pull",
            supersetLabel = "SUPERSET A",
            previous = "40×15 · 40×15 · 40×13",
            target = "3 × 15 @ 40 lb",
            sets = listOf(
                SetRow.Pending(tag = "1", targetShort = "15 @ 40"),
                SetRow.Pending(tag = "2", targetShort = "15 @ 40"),
                SetRow.Pending(tag = "3", targetShort = "15 @ 40"),
            ),
        ),
    ),
)
