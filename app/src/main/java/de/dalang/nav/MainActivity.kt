package de.dalang.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Poi
import de.dalang.nav.navigation.PoiRepository
import de.dalang.nav.navigation.PoiType
import de.dalang.nav.settings.FuelType
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.ui.components.HereSettingsDialog
import de.dalang.nav.ui.components.MapViewComposable
import de.dalang.nav.ui.components.NavigationPanel
import de.dalang.nav.ui.components.OfflineMapsDialog
import de.dalang.nav.ui.components.PoiSelectionDialog
import de.dalang.nav.ui.components.SearchBar
import de.dalang.nav.ui.components.UpdateDialog
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
                DaLangApp(viewModel)
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
fun DaLangApp(viewModel: MainViewModel) {
    val currentLocation by viewModel.currentLocation.collectAsState()
    val heading by viewModel.heading.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val bearing by viewModel.bearing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()
    val clickedLocation by viewModel.clickedLocation.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Settings
    val settingsRepository = remember { SettingsRepository(context) }
    var preferredFuelType by remember { mutableStateOf(settingsRepository.preferredFuelType) }
    var vehicleRangeKm by remember { mutableStateOf(settingsRepository.vehicleRangeKm) }

    // POI Search State
    val poiRepository = remember { PoiRepository() }
    var showPoiDialog by remember { mutableStateOf(false) }
    var selectedPoiType by remember { mutableStateOf<PoiType?>(null) }
    var poiResults by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var isSearchingPoi by remember { mutableStateOf(false) }
    var showMcDonaldsOverview by remember { mutableStateOf(false) }

    // McDonald's Toggle - Zeigt/versteckt McDonald's auf der Route
    fun toggleMcDonalds() {
        if (showMcDonaldsOverview) {
            // Ausschalten - zurück zur normalen Ansicht
            showMcDonaldsOverview = false
            selectedPoiType = null
            poiResults = emptyList()
        } else {
            // Einschalten - McDonald's laden und anzeigen
            val location = currentLocation ?: return
            val route = navigationState.route
            if (route != null && route.geometry.isNotEmpty()) {
                selectedPoiType = PoiType.MCDONALDS
                isSearchingPoi = true
                showMcDonaldsOverview = true
                scope.launch {
                    poiResults = poiRepository.searchAlongRoute(PoiType.MCDONALDS, route.geometry, location)
                    isSearchingPoi = false
                }
            }
        }
    }

    // POI Suche starten - entlang der Route wenn vorhanden (für Tankstellen)
    fun searchPoi(type: PoiType) {
        val location = currentLocation ?: return
        selectedPoiType = type
        showPoiDialog = true
        isSearchingPoi = true
        poiResults = emptyList()
        showMcDonaldsOverview = false  // McDonald's Übersicht ausschalten

        scope.launch {
            val route = navigationState.route
            poiResults = if (route != null && route.geometry.isNotEmpty()) {
                // Suche entlang der Route
                poiRepository.searchAlongRoute(type, route.geometry, location)
            } else {
                // Fallback: Suche in der Nähe
                poiRepository.searchNearby(type, location)
            }
            isSearchingPoi = false
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // Screen aktiv halten während Navigation
    val view = LocalView.current
    DisposableEffect(navigationState.isNavigating) {
        if (navigationState.isNavigating) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    // POI-Marker und McDonald's-Übersicht ausblenden wenn Navigation startet
    LaunchedEffect(navigationState.isNavigating) {
        if (navigationState.isNavigating) {
            selectedPoiType = null
            poiResults = emptyList()
            showMcDonaldsOverview = false
        }
    }

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

    // Info-Meldung kurz anzeigen (auto-dismiss)
    LaunchedEffect(infoMessage) {
        infoMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearInfoMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,  // Allow close gestures when open, but don't open by swipe
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp)
            ) {
                DrawerContent(
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    onSettingsChanged = {
                        // Settings aktualisieren
                        preferredFuelType = settingsRepository.preferredFuelType
                        vehicleRangeKm = settingsRepository.vehicleRangeKm
                    }
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
            heading = heading,
            speed = speed,
            bearing = bearing,
            distanceToNextManeuver = navigationState.distanceToNextStep,
            destination = navigationState.destination,
            route = navigationState.route,
            isNavigating = navigationState.isNavigating,
            pois = poiResults,
            selectedPoiType = selectedPoiType,
            preferredFuelType = preferredFuelType,
            vehicleRangeKm = vehicleRangeKm,
            showPoiOverview = showMcDonaldsOverview,
            onMapClick = { location ->
                // Nur reagieren wenn keine Navigation aktiv und keine Route geplant
                if (!navigationState.isNavigating && navigationState.route == null) {
                    viewModel.onMapClicked(location)
                }
            },
            onPoiClick = { poi ->
                // Bei Klick auf McDonald's: Dorthin navigieren
                if (showMcDonaldsOverview) {
                    viewModel.addWaypoint(LatLng(poi.lat, poi.lng))
                    showMcDonaldsOverview = false
                    selectedPoiType = null
                    poiResults = emptyList()
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

        // Navigationspanel unten (inkl. POI-Buttons)
        NavigationPanel(
            state = navigationState,
            onStartNavigation = viewModel::startNavigation,
            onStopNavigation = viewModel::stopNavigation,
            onMcDonaldsClick = { toggleMcDonalds() },
            onGasStationClick = { searchPoi(PoiType.GAS_STATION) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
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

        // POI Auswahl Dialog
        if (showPoiDialog && selectedPoiType != null) {
            PoiSelectionDialog(
                poiType = selectedPoiType!!,
                pois = poiResults,
                isLoading = isSearchingPoi,
                isAlongRoute = navigationState.route != null,
                preferredFuelType = preferredFuelType,
                onSelect = { poi ->
                    // POI als Zwischenstopp zur Route hinzufügen
                    viewModel.addWaypoint(LatLng(poi.lat, poi.lng))
                    showPoiDialog = false
                    // selectedPoiType bleibt erhalten, damit Marker auf der Karte bleiben
                },
                onDismiss = {
                    showPoiDialog = false
                    // selectedPoiType bleibt erhalten, damit Marker auf der Karte bleiben
                }
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
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = onNavigate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Route")
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun DrawerContent(
    onCloseDrawer: () -> Unit,
    onSettingsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    var showHereSettings by remember { mutableStateOf(false) }
    var showOfflineMaps by remember { mutableStateOf(false) }
    var showDebugLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

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

        // HERE API
        NavigationDrawerItem(
            label = { Text("HERE API") },
            selected = false,
            onClick = {
                showHereSettings = true
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

        // App aktualisieren
        NavigationDrawerItem(
            label = { Text("App aktualisieren") },
            selected = false,
            onClick = {
                CrashLogger.log("DrawerContent: App aktualisieren clicked")
                showUpdate = true
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
            text = "Version $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
    }

    // HERE Settings Dialog
    if (showHereSettings) {
        HereSettingsDialog(
            onDismiss = { showHereSettings = false },
            onSave = { onSettingsChanged() }
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Schließen")
                }
            }
        }
    }

    // Update Dialog
    if (showUpdate) {
        UpdateDialog(
            onDismiss = { showUpdate = false }
        )
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
                Button(
                    onClick = {
                        debugLog?.let {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                            copied = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (copied) "Kopiert!" else "Log kopieren")
                }

                Button(
                    onClick = {
                        CrashLogger.clearLog()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Log leeren")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schließen")
            }
        }
    }
}
