package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.destinations.FavoriteType
import de.dalang.nav.destinations.SavedDestination
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.search.SearchRepository
import de.dalang.nav.search.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FavoriteAddressDialog(
    favoriteType: FavoriteType,
    currentAddress: SavedDestination?,
    currentLocation: LatLng?,
    onSave: (SavedDestination) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val searchRepository = remember { SearchRepository() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val houseNumberFocusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var houseNumber by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedResult by remember { mutableStateOf<SearchResult?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var showHouseNumberInput by remember { mutableStateOf(false) }
    var streetName by remember { mutableStateOf("") }

    // Bei vorhandener Adresse diese als "ausgewählt" markieren
    LaunchedEffect(currentAddress) {
        if (currentAddress != null) {
            selectedResult = SearchResult(
                displayName = currentAddress.name,
                lat = currentAddress.lat,
                lon = currentAddress.lng,
                type = "saved"
            )
        }
    }

    // Focus house number field when shown
    LaunchedEffect(showHouseNumberInput) {
        if (showHouseNumberInput) {
            delay(100)
            houseNumberFocusRequester.requestFocus()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = when (favoriteType) {
                            FavoriteType.HOME -> Icons.Outlined.Home
                            FavoriteType.WORK -> Icons.Outlined.Work
                        },
                        contentDescription = favoriteType.displayName,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${favoriteType.displayName} festlegen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Hausnummer-Eingabe (wenn Straße erkannt)
                if (showHouseNumberInput) {
                    Text(
                        text = streetName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (houseNumber.isEmpty()) {
                                    Text(
                                        text = "Hausnummer eingeben…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                BasicTextField(
                                    value = houseNumber,
                                    onValueChange = { houseNumber = it },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Search
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            if (houseNumber.isNotEmpty()) {
                                                // Suche mit Straße + Hausnummer
                                                searchJob?.cancel()
                                                searchJob = scope.launch {
                                                    isSearching = true
                                                    searchResults = searchRepository.search(
                                                        query = "$streetName $houseNumber",
                                                        currentLat = currentLocation?.lat,
                                                        currentLon = currentLocation?.lng
                                                    )
                                                    isSearching = false
                                                    showHouseNumberInput = false
                                                    keyboardController?.hide()
                                                }
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(houseNumberFocusRequester)
                                )
                            }

                            // Zurück-Button
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Zurück",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(20.dp)
                                    .clickable {
                                        showHouseNumberInput = false
                                        houseNumber = ""
                                    }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Suchen-Button für Hausnummer
                    Button(
                        onClick = {
                            if (houseNumber.isNotEmpty()) {
                                searchJob?.cancel()
                                searchJob = scope.launch {
                                    isSearching = true
                                    searchResults = searchRepository.search(
                                        query = "$streetName $houseNumber",
                                        currentLat = currentLocation?.lat,
                                        currentLon = currentLocation?.lng
                                    )
                                    isSearching = false
                                    showHouseNumberInput = false
                                    keyboardController?.hide()
                                }
                            }
                        },
                        enabled = houseNumber.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            disabledContentColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Suchen")
                    }
                } else {
                    // Standard-Suchfeld
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Adresse suchen…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = { newQuery ->
                                        query = newQuery
                                        selectedResult = null

                                        // Debounced search
                                        searchJob?.cancel()
                                        if (newQuery.length >= 3) {
                                            searchJob = scope.launch {
                                                delay(300)
                                                isSearching = true
                                                searchResults = searchRepository.search(
                                                    query = newQuery,
                                                    currentLat = currentLocation?.lat,
                                                    currentLon = currentLocation?.lng
                                                )
                                                isSearching = false
                                            }
                                        } else {
                                            searchResults = emptyList()
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (query.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Löschen",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(20.dp)
                                        .clickable {
                                            query = ""
                                            searchResults = emptyList()
                                            selectedResult = null
                                        }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Suchergebnisse oder ausgewählte Adresse
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 100.dp, max = 250.dp)
                ) {
                    when {
                        isSearching -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        selectedResult != null -> {
                            // Ausgewählte Adresse anzeigen
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Ausgewählt:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = selectedResult!!.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        searchResults.isNotEmpty() && !showHouseNumberInput -> {
                            LazyColumn {
                                itemsIndexed(
                                    items = searchResults,
                                    key = { index, result -> "fav_search_${index}_${result.lat}_${result.lon}" }
                                ) { _, result ->
                                    val isStreet = result.type == "highway" ||
                                                   result.type == "road" ||
                                                   result.type == "street" ||
                                                   result.type == "residential"

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isStreet) {
                                                    // Bei Straße: Hausnummer-Eingabe zeigen
                                                    streetName = result.displayName.split(",").first().trim()
                                                    showHouseNumberInput = true
                                                    searchResults = emptyList()
                                                    query = ""
                                                } else {
                                                    // Normale Adresse: direkt auswählen
                                                    selectedResult = result
                                                    query = ""
                                                    searchResults = emptyList()
                                                }
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.LocationOn,
                                            contentDescription = "Ort",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            val parts = result.displayName.split(",")
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = parts.first().trim(),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isStreet) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "+ Nr.",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            if (parts.size > 1) {
                                                Text(
                                                    text = parts.drop(1).take(2).joinToString(",").trim(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                        query.isEmpty() && currentAddress == null && !showHouseNumberInput -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Gib eine Adresse ein",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Löschen-Button (nur wenn bereits gesetzt)
                    if (currentAddress != null) {
                        OutlinedButton(
                            onClick = {
                                onDelete()
                                onDismiss()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Löschen")
                        }
                    }

                    // Abbrechen
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Abbrechen")
                    }

                    // Speichern
                    Button(
                        onClick = {
                            selectedResult?.let { result ->
                                onSave(
                                    SavedDestination(
                                        name = result.displayName,
                                        lat = result.lat,
                                        lng = result.lon
                                    )
                                )
                            }
                            onDismiss()
                        },
                        enabled = selectedResult != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            disabledContentColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Speichern")
                    }
                }
            }
        }
    }
}
