package com.nerdginger.workoutmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nerdginger.workoutmate.core.presentation.BackupNag
import com.nerdginger.workoutmate.core.presentation.ConsistencyView
import com.nerdginger.workoutmate.core.presentation.SessionSummary
import com.nerdginger.workoutmate.core.presentation.SlotView
import com.nerdginger.workoutmate.core.presentation.TodayEvent
import com.nerdginger.workoutmate.core.presentation.TodayState
import com.nerdginger.workoutmate.ui.theme.WomTheme

/**
 * Today, rendered from `Black and orange palette views.zip`.
 *
 * Note what this file does not contain: no date arithmetic, no staleness
 * threshold, no "if there are more than three, say +N more". Every one of those
 * is resolved in `core` and arrives as a field. The `when` below dispatches on
 * a sealed type — that is rendering a variant, not deciding one.
 */
@Composable
fun TodayScreen(state: TodayState, onEvent: (TodayEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WomTheme.colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        when (state) {
            is TodayState.Loading -> LoadingBody()

            is TodayState.NoProgramme -> {
                Header(overline = "No programme yet", title = "Let's find you one")
                state.nag?.let { NagCard(it, onEvent) }
                FindFrontDoor(onEvent)
                RecentSessions(state.recent, onEvent)
            }

            is TodayState.Due -> {
                Header(overline = state.dateLabel, title = state.slot.title)
                state.nag?.let { NagCard(it, onEvent) }
                DueCard(state.slot, onEvent)
                ConsistencyStrip(state.consistency)
                LastSession(state.lastSession, onEvent)
            }

            is TodayState.RestDay -> {
                Header(overline = state.dateLabel, title = "Rest day")
                state.nag?.let { NagCard(it, onEvent) }
                RestCard(state.nextOnLabel, state.nextSlot)
                ConsistencyStrip(state.consistency)
                LastSession(state.lastSession, onEvent)
            }

            is TodayState.Missed -> {
                Header(overline = state.dateLabel, title = "Missed sessions")
                state.nag?.let { NagCard(it, onEvent) }
                state.missed.forEach { MissedCard(it, onEvent) }
                state.alsoDueToday?.let { DueCard(it, onEvent) }
                ConsistencyStrip(state.consistency)
                LastSession(state.lastSession, onEvent)
            }

            is TodayState.BehindTarget -> {
                Header(overline = state.dateLabel, title = "Behind target")
                state.nag?.let { NagCard(it, onEvent) }
                BehindCard(state.done, state.target, state.suggestion, onEvent)
                ConsistencyStrip(state.consistency)
                LastSession(state.lastSession, onEvent)
            }

            is TodayState.InProgress -> {
                Header(overline = "In progress", title = state.slot.title)
                state.nag?.let { NagCard(it, onEvent) }
                InProgressCard(state, onEvent)
            }
        }
        Spacer(24.dp)
    }
}

// ---- Pieces --------------------------------------------------------------

@Composable
private fun Header(overline: String, title: String) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp)) {
        Text(
            overline.uppercase(),
            style = WomTheme.type.overline,
            color = WomTheme.colors.textDim,
        )
        Spacer(4.dp)
        Text(title, style = WomTheme.type.display, color = WomTheme.colors.textPrimary)
    }
}

/**
 * The stale-backup notice.
 *
 * It appears on Today and nowhere else, by design: everything lives on this
 * phone, so the one screen you cannot avoid is the only honest place to say so.
 */
@Composable
private fun NagCard(nag: BackupNag, onEvent: (TodayEvent) -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, top = 14.dp)
            .fillMaxWidth()
            .background(WomTheme.colors.accentSurface, WomTheme.shapes.tile)
            .border(1.dp, WomTheme.colors.accentBorder, WomTheme.shapes.tile)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A 45°-rotated square: the design's diamond bullet.
        Box(
            Modifier
                .size(8.dp)
                .rotate(45f)
                .background(WomTheme.colors.accent, RoundedCornerShape(2.dp)),
        )
        Text(
            nag.message,
            style = WomTheme.type.body,
            color = WomTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Back up",
            style = WomTheme.type.label,
            color = WomTheme.colors.accent,
            modifier = Modifier
                .clickable { onEvent(TodayEvent.OpenBackup) }
                .padding(horizontal = 2.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DueCard(slot: SlotView, onEvent: (TodayEvent) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                slot.subtitle,
                style = WomTheme.type.cardTitle,
                color = WomTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            slot.estimate?.let {
                Text(it, style = WomTheme.type.mono, color = WomTheme.colors.textDim)
            }
        }
        Spacer(12.dp)
        ChipFlow(slot.chips)
        Spacer(16.dp)
        PrimaryAction("Start session") { onEvent(TodayEvent.StartSession) }
    }
}

@Composable
private fun RestCard(nextOnLabel: String, nextSlot: SlotView?) {
    Card {
        Text("Nothing scheduled today", style = WomTheme.type.cardTitle, color = WomTheme.colors.textPrimary)
        Spacer(8.dp)
        Text(nextOnLabel, style = WomTheme.type.body, color = WomTheme.colors.textDim)
        nextSlot?.let {
            Spacer(12.dp)
            ChipFlow(it.chips)
        }
    }
}

@Composable
private fun MissedCard(slot: SlotView, onEvent: (TodayEvent) -> Unit) {
    Card {
        Text(slot.title, style = WomTheme.type.cardTitle, color = WomTheme.colors.textPrimary)
        Spacer(6.dp)
        Text(slot.subtitle, style = WomTheme.type.body, color = WomTheme.colors.textDim)
        Spacer(16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryAction("Do it now") { onEvent(TodayEvent.DoMissedNow(slot.slotId)) }
            }
            Box(Modifier.weight(1f)) {
                GhostAction("Skip") { onEvent(TodayEvent.SkipMissed(slot.slotId)) }
            }
        }
    }
}

@Composable
private fun BehindCard(done: Int, target: Int, suggestion: SlotView?, onEvent: (TodayEvent) -> Unit) {
    Card {
        Text(
            "$done of $target sessions this week",
            style = WomTheme.type.cardTitle,
            color = WomTheme.colors.textPrimary,
        )
        suggestion?.let {
            Spacer(12.dp)
            ChipFlow(it.chips)
            Spacer(16.dp)
            PrimaryAction("Start session") { onEvent(TodayEvent.StartSession) }
        }
    }
}

@Composable
private fun InProgressCard(state: TodayState.InProgress, onEvent: (TodayEvent) -> Unit) {
    Card {
        Text(state.slot.subtitle, style = WomTheme.type.cardTitle, color = WomTheme.colors.textPrimary)
        Spacer(8.dp)
        Text(state.progressLabel, style = WomTheme.type.mono, color = WomTheme.colors.textDim)
        Spacer(16.dp)
        PrimaryAction("Resume session") { onEvent(TodayEvent.ResumeSession(state.sessionId)) }
    }
}

/** The first-run front door, rendered inline when there is no programme. */
@Composable
private fun FindFrontDoor(onEvent: (TodayEvent) -> Unit) {
    Card {
        Text(
            "Get a programme and the app will tell you what to train each day.",
            style = WomTheme.type.body,
            color = WomTheme.colors.textSecondary,
        )
        Spacer(16.dp)
        PrimaryAction("Find me a programme") { onEvent(TodayEvent.FindProgramme) }
    }
}

@Composable
private fun ConsistencyStrip(c: ConsistencyView) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            value = c.sessionsThisWeek.toString(),
            caption = "sessions\nthis week",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = c.volumeLabel,
            caption = "${c.volumeUnit} volume\n7 days",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = c.recordsThisWeek.toString(),
            caption = "records\nthis week",
            // The design highlights records in the accent — the one number on
            // this screen that means something happened rather than something
            // was counted.
            accent = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Column(
        modifier
            .background(WomTheme.colors.surface, WomTheme.shapes.tile)
            .border(1.dp, WomTheme.colors.border, WomTheme.shapes.tile)
            .padding(13.dp),
    ) {
        Text(
            value,
            style = WomTheme.type.monoStat,
            color = if (accent) WomTheme.colors.accent else WomTheme.colors.textPrimary,
        )
        Spacer(2.dp)
        Text(caption, style = WomTheme.type.caption, color = WomTheme.colors.textDim)
    }
}

@Composable
private fun LastSession(session: SessionSummary?, onEvent: (TodayEvent) -> Unit) {
    session ?: return
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp)) {
        Text("LAST SESSION", style = WomTheme.type.overline, color = WomTheme.colors.textDim)
        Spacer(8.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .background(WomTheme.colors.surface, WomTheme.shapes.cardSmall)
                .border(1.dp, WomTheme.colors.border, WomTheme.shapes.cardSmall)
                .clickable { onEvent(TodayEvent.OpenSession(session.sessionId)) }
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    session.routineName,
                    style = WomTheme.type.label,
                    color = WomTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(session.whenLabel, style = WomTheme.type.mono, color = WomTheme.colors.textDim)
            }
            Spacer(11.dp)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                session.lines.forEach { line ->
                    Row {
                        Text(
                            line.exercise,
                            style = WomTheme.type.mono,
                            color = WomTheme.colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(line.results, style = WomTheme.type.mono, color = WomTheme.colors.textMuted)
                    }
                }
            }
            session.highlight?.let {
                Spacer(12.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(WomTheme.colors.border))
                Spacer(11.dp)
                Text(it, style = WomTheme.type.label, color = WomTheme.colors.accent)
            }
        }
    }
}

@Composable
private fun RecentSessions(recent: List<SessionSummary>, onEvent: (TodayEvent) -> Unit) {
    recent.forEach { LastSession(it, onEvent) }
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", style = WomTheme.type.body, color = WomTheme.colors.textDim)
    }
}

// ---- Shared primitives ---------------------------------------------------

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(14.dp)
            .fillMaxWidth()
            .background(WomTheme.colors.surface, WomTheme.shapes.card)
            .border(1.dp, WomTheme.colors.border, WomTheme.shapes.card)
            .padding(16.dp),
        content = content,
    )
}

/**
 * The design's `flex-wrap` chip row.
 *
 * A fixed number of chips per row would be wrong: these are exercise names of
 * wildly different lengths, so wrapping has to follow measured width rather
 * than a count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(chips: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { Chip(it) }
    }
}

@Composable
private fun Chip(label: String) {
    Text(
        label,
        style = WomTheme.type.mono,
        color = WomTheme.colors.textMuted,
        modifier = Modifier
            .background(WomTheme.colors.surfaceChip, WomTheme.shapes.chip)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(WomTheme.spacing.actionHeight)
            .background(WomTheme.colors.accent, WomTheme.shapes.tile)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = WomTheme.type.button, color = WomTheme.colors.onAccent)
    }
}

@Composable
private fun GhostAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(WomTheme.spacing.actionHeight)
            .border(1.dp, WomTheme.colors.accentBorder, WomTheme.shapes.tile)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = WomTheme.type.button, color = WomTheme.colors.accent, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.height(height))
}
