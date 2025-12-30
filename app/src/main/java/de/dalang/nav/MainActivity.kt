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
import kotlinx.coroutines.launch
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
import de.dalang.nav.ui.components.OfflineMapsDialog
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
    val errorMessage by viewModel.errorMessage.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Fehler als Snackbar anzeigen
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !navigationState.isNavigating,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp)
            ) {
                DrawerContent(
                    onCloseDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
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

        // Suchleiste oben mit Menu-Button
        if (!navigationState.isNavigating) {
            SearchBar(
                query = searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                results = searchResults,
                isSearching = isSearching,
                onResultClick = viewModel::selectDestination,
                onClear = viewModel::clearSearch,
                onMenuClick = { scope.launch { drawerState.open() } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
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
        val clickedAddress by viewModel.clickedLocationAddress.collectAsState()
        if (clickedLocation != null) {
            MapClickDialog(
                address = clickedAddress,
                onNavigate = { viewModel.navigateToClickedLocation() },
                onDismiss = { viewModel.dismissMapClick() }
            )
        }

    }
    }
    }
}

@Composable
fun MapClickDialog(
    address: String?,
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = address ?: "Lade Adresse...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
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

@Suppress("UNUSED_PARAMETER")
@Composable
fun DrawerContent(
    onCloseDrawer: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showOfflineMaps by remember { mutableStateOf(false) }
    var showDebugLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "DaLang",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Navigation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // Einstellungen
        NavigationDrawerItem(
            label = { Text("Einstellungen") },
            selected = false,
            onClick = {
                showSettings = true
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Offline Karten
        NavigationDrawerItem(
            label = { Text("Offline Karten") },
            selected = false,
            onClick = {
                CrashLogger.log("DrawerContent: Offline Karten clicked")
                showOfflineMaps = true
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Debug Log
        NavigationDrawerItem(
            label = { Text("Debug Log") },
            selected = false,
            onClick = {
                CrashLogger.log("DrawerContent: Debug Log clicked")
                showDebugLog = true
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        // Über
        NavigationDrawerItem(
            label = { Text("Über") },
            selected = false,
            onClick = {
                showAbout = true
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Version
        Text(
            text = "Version 1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
    }

    // Settings Dialog
    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onSave = { }
        )
    }

    // Offline Maps Dialog
    if (showOfflineMaps) {
        OfflineMapsDialog(
            onDismiss = { showOfflineMaps = false }
        )
    }

    // Debug Log Dialog
    if (showDebugLog) {
        DebugLogDialog(
            onDismiss = { showDebugLog = false }
        )
    }

    // Über Dialog
    if (showAbout) {
        Dialog(onDismissRequest = { showAbout = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Text(
                    text = "Über DaLang",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "DaLang ist eine minimalistische Navigations-App.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Verwendete Dienste:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "• MapLibre GL - Kartenansicht\n• OpenFreeMap - Kartendaten\n• OpenStreetMap - Kartendaten\n• Photon - Adresssuche\n• OSRM - Routing\n• HERE - Verkehrsdaten (optional)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { showAbout = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Schließen")
                }
            }
        }
    }
}

@Composable
fun DebugLogDialog(
    onDismiss: () -> Unit
) {
    val debugLog = remember { CrashLogger.getLastCrashLog() }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
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
                text = "Vollständiges Protokoll aller App-Ereignisse:",
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
                    text = debugLog ?: "Keine Logs vorhanden",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
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
                    onClick = {
                        debugLog?.let {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                            copied = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (copied) "Kopiert!" else "Log kopieren")
                }

                OutlinedButton(
                    onClick = {
                        CrashLogger.clearLog()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Log leeren")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schließen")
            }
        }
    }
}
