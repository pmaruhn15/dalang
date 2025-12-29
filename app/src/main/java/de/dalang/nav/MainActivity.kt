package de.dalang.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.ui.components.MapViewComposable
import de.dalang.nav.ui.components.NavigationPanel
import de.dalang.nav.ui.components.SearchBar
import de.dalang.nav.ui.components.SettingsDialog
import de.dalang.nav.ui.theme.DaLangTheme
import de.dalang.nav.util.CrashLogger

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        CrashLogger.log("MainActivity: Location permission result received")
        try {
            when {
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                    CrashLogger.log("MainActivity: Fine location granted")
                    viewModel.startLocationUpdates()
                }
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                    CrashLogger.log("MainActivity: Coarse location granted")
                    viewModel.startLocationUpdates()
                }
                else -> {
                    CrashLogger.log("MainActivity: Location permission denied")
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "Permission callback failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.log("MainActivity: onCreate")

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "setDecorFitsSystemWindows failed", e)
        }

        try {
            checkLocationPermission()
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "checkLocationPermission failed", e)
        }

        setContent {
            DaLangTheme {
                var showCrashLog by remember { mutableStateOf(CrashLogger.hasRecentCrash()) }

                Box {
                    DaLangApp(viewModel)

                    // Crash Log Dialog anzeigen wenn es Probleme gab
                    if (showCrashLog) {
                        CrashLogDialog(
                            onDismiss = {
                                showCrashLog = false
                            },
                            onClear = {
                                CrashLogger.clearLog()
                                showCrashLog = false
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkLocationPermission() {
        CrashLogger.log("MainActivity: checkLocationPermission")
        try {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    CrashLogger.log("MainActivity: Already have fine location permission")
                    viewModel.startLocationUpdates()
                }
                else -> {
                    CrashLogger.log("MainActivity: Requesting location permission")
                    locationPermissionRequest.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "checkLocationPermission failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        CrashLogger.log("MainActivity: onResume")
        try {
            if (hasLocationPermission()) {
                viewModel.startLocationUpdates()
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "onResume failed", e)
        }
    }

    override fun onPause() {
        super.onPause()
        CrashLogger.log("MainActivity: onPause")
        try {
            // Location updates weiterlaufen lassen wenn Navigation aktiv
            if (!viewModel.navigationState.value.isNavigating) {
                viewModel.stopLocationUpdates()
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "onPause failed", e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            CrashLogger.logError("MainActivity", "hasLocationPermission check failed", e)
            false
        }
    }
}

@Composable
fun CrashLogDialog(
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val crashLog = remember { CrashLogger.getLastCrashLog() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Text(
                text = "Debug Log",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Hier siehst du Fehler und Ereignisse der App:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                Text(
                    text = crashLog ?: "Keine Logs vorhanden",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Log leeren")
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Schließen")
                }
            }
        }
    }
}

@Composable
fun DaLangApp(viewModel: MainViewModel) {
    val currentLocation by viewModel.currentLocation.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()
    val clickedLocation by viewModel.clickedLocation.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Karte im Hintergrund
        MapViewComposable(
            currentLocation = currentLocation,
            destination = navigationState.destination,
            route = navigationState.route,
            isNavigating = navigationState.isNavigating,
            onMapClick = { location ->
                // Nur reagieren wenn keine Navigation aktiv und keine Route geplant
                if (!navigationState.isNavigating && navigationState.route == null) {
                    viewModel.onMapClicked(location)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Suchleiste oben
        if (!navigationState.isNavigating) {
            SearchBar(
                query = searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                results = searchResults,
                isSearching = isSearching,
                onResultClick = viewModel::selectDestination,
                onClear = viewModel::clearSearch,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }

        // Einstellungs-Button oben rechts (nur wenn keine Navigation aktiv)
        if (!navigationState.isNavigating) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 72.dp, end = 16.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showSettings = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Navigationspanel unten
        NavigationPanel(
            state = navigationState,
            onStartNavigation = viewModel::startNavigation,
            onStopNavigation = viewModel::stopNavigation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // Map-Klick Dialog
        if (clickedLocation != null) {
            MapClickDialog(
                onNavigate = { viewModel.navigateToClickedLocation() },
                onDismiss = { viewModel.dismissMapClick() }
            )
        }

        // Settings Dialog
        if (showSettings) {
            SettingsDialog(
                onDismiss = { showSettings = false },
                onSave = { }
            )
        }
    }
}

@Composable
fun MapClickDialog(
    onNavigate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Hierhin navigieren?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = onNavigate,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Route berechnen")
                }
            }
        }
    }
}
