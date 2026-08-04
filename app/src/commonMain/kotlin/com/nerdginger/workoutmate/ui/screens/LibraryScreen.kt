package com.nerdginger.workoutmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nerdginger.workoutmate.core.presentation.ExerciseRow
import com.nerdginger.workoutmate.core.presentation.LibraryEvent
import com.nerdginger.workoutmate.core.presentation.LibraryState
import com.nerdginger.workoutmate.ui.theme.WomTheme

/**
 * The exercise library, grouped and searchable.
 *
 * Everything shown here comes from the database — this is the first screen in
 * the app that is not stub data.
 */
@Composable
fun LibraryScreen(state: LibraryState, onEvent: (LibraryEvent) -> Unit) {
    Column(Modifier.fillMaxSize().background(WomTheme.colors.background)) {
        when (state) {
            is LibraryState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", style = WomTheme.type.body, color = WomTheme.colors.textDim)
            }

            is LibraryState.Loaded -> {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp)) {
                    Text(
                        "EXERCISES",
                        style = WomTheme.type.overline,
                        color = WomTheme.colors.textDim,
                    )
                    Box(Modifier.height(4.dp))
                    Text(
                        "${state.totalCount} movements",
                        style = WomTheme.type.display,
                        color = WomTheme.colors.textPrimary,
                    )
                }

                SearchField(state.query) { onEvent(LibraryEvent.Search(it)) }

                state.emptyMessage?.let {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text(it, style = WomTheme.type.body, color = WomTheme.colors.textDim)
                    }
                }

                LazyColumn(Modifier.weight(1f)) {
                    state.groups.forEach { group ->
                        item(key = "h-${group.title}") {
                            Text(
                                group.title.uppercase(),
                                style = WomTheme.type.overline,
                                color = WomTheme.colors.textDim,
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
                            )
                        }
                        items(group.rows.size, key = { group.rows[it].id }) { index ->
                            ExerciseRowItem(group.rows[index], onEvent)
                        }
                    }
                    item { Box(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(WomTheme.colors.surface, WomTheme.shapes.tile)
            .border(1.dp, WomTheme.colors.border, WomTheme.shapes.tile)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (query.isEmpty()) {
            Text("Search", style = WomTheme.type.body, color = WomTheme.colors.textFaint)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = WomTheme.type.body.copy(color = WomTheme.colors.textPrimary),
            cursorBrush = SolidColor(WomTheme.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExerciseRowItem(row: ExerciseRow, onEvent: (LibraryEvent) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .fillMaxWidth()
            .background(WomTheme.colors.surface, WomTheme.shapes.button)
            .border(1.dp, WomTheme.colors.border, WomTheme.shapes.button)
            .clickable { onEvent(LibraryEvent.OpenExercise(row.id)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = WomTheme.type.label, color = WomTheme.colors.textPrimary)
            Box(Modifier.height(3.dp))
            Text(row.detail, style = WomTheme.type.caption, color = WomTheme.colors.textDim)
        }
        // The user's best set sits alongside the name so the list is useful on
        // its own rather than only as a way into the detail screen.
        row.best?.let {
            Text(it, style = WomTheme.type.mono, color = WomTheme.colors.textMuted)
        }
    }
}
