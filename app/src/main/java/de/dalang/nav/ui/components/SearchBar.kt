package de.dalang.nav.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dalang.nav.search.SearchResult
import de.dalang.nav.util.DistanceFormatter

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    isSearching: Boolean,
    onResultClick: (SearchResult) -> Unit,
    onClear: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var isExpanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .padding(16.dp)
    ) {
        // Suchfeld mit Menu-Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Menu Button links
            if (onMenuClick != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onMenuClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "☰",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Suchfeld
            Box(
                modifier = Modifier
                    .weight(1f)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        focusRequester.requestFocus()
                        // Direkt onFocusChanged aufrufen für sofortige Reaktion
                        onFocusChanged?.invoke(true)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Ziel eingeben…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                        }

                        BasicTextField(
                            value = query,
                            onValueChange = {
                                onQueryChange(it)
                                isExpanded = it.isNotEmpty()
                            },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    val wasFocused = isFocused
                                    isFocused = focusState.isFocused
                                    if (isFocused != wasFocused) {
                                        onFocusChanged?.invoke(isFocused)
                                    }
                                }
                        )
                    }

                    // Löschen-Button
                    if (query.isNotEmpty()) {
                        Text(
                            text = "X",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable {
                                    onClear()
                                    isExpanded = false
                                    keyboardController?.hide()
                                }
                        )
                    }
                }
            }
        }

        // Suchergebnisse
        AnimatedVisibility(visible = isExpanded && (results.isNotEmpty() || isSearching)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .heightIn(max = 300.dp)
            ) {
                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    val select: (SearchResult) -> Unit = { selectedResult ->
                        // WICHTIG: Erst Fokus und Keyboard entfernen BEVOR UI geändert wird
                        // Das verhindert den LayoutCoordinate Crash
                        focusManager.clearFocus()
                        keyboardController?.hide()

                        // Dann UI aktualisieren
                        isExpanded = false
                        onClear()

                        // Zuletzt Ziel auswählen (kann UI-Änderungen auslösen)
                        onResultClick(selectedResult)
                    }
                    val firstFar = results.indexOfFirst { it.isFar }
                    LazyColumn {
                        itemsIndexed(
                            items = results,
                            key = { index, result -> "search_${index}_${result.lat}_${result.lon}" }
                        ) { index, result ->
                            if (index == firstFar) {
                                Text(
                                    text = "Weiter entfernt",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                )
                            }
                            SearchResultItem(result = result, onClick = select)
                        }
                    }
                }
            }
        }
    }
}

/** Name / „Typ · Adresse“ / Entfernung, darunter auswählbare Teilziele (Parkplatz, Bahnhof). */
@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: (SearchResult) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(result) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val details = listOf(result.typeLabel, result.address).filter { it.isNotBlank() }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            DistanceText(result.distance)
        }
        result.related.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(sub) }
                    .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↳ ${sub.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DistanceText(sub.distance)
            }
        }
    }
}

@Composable
private fun DistanceText(km: Double) {
    if (km > 0) {
        Text(
            text = DistanceFormatter.formatKilometersSimple(km),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
