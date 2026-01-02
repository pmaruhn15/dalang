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
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import de.dalang.nav.destinations.DestinationsRepository
import de.dalang.nav.destinations.FavoriteType
import de.dalang.nav.destinations.SavedDestination
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Poi
import de.dalang.nav.navigation.PoiRepository
import de.dalang.nav.navigation.PoiType
import de.dalang.nav.navigation.WaypointType
import de.dalang.nav.search.SearchResult
import de.dalang.nav.settings.FuelType
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.ui.components.FavoriteAddressDialog
import de.dalang.nav.ui.components.HereSettingsDialog
import de.dalang.nav.ui.components.MapViewComposable
import de.dalang.nav.ui.components.NavigationPanel
import de.dalang.nav.ui.components.OfflineMapsDialog
import de.dalang.nav.ui.components.PoiSelectionDialog
import de.dalang.nav.ui.components.RecentDestinationsDropdown
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
    val isRecalculatingRoute by viewModel.isRecalculatingRoute.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Settings
    val settingsRepository = remember { SettingsRepository(context) }
    var preferredFuelType by remember { mutableStateOf(settingsRepository.preferredFuelType) }
    var vehicleRangeKm by remember { mutableStateOf(settingsRepository.vehicleRangeKm) }

    // Destinations (Favoriten & letzte Ziele)
    val destinationsRepository = remember { DestinationsRepository(context) }
    var homeAddress by remember { mutableStateOf(destinationsRepository.getFavorite(FavoriteType.HOME)) }
    var workAddress by remember { mutableStateOf(destinationsRepository.getFavorite(FavoriteType.WORK)) }
    var recentDestinations by remember { mutableStateOf(destinationsRepository.getRecentDestinations()) }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var editingFavoriteType by remember { mutableStateOf<FavoriteType?>(null) }

    // Derived state: Dropdown zeigen wenn Suchfeld fokussiert UND leer UND nicht navigierend
    val showRecentDestinations by remember {
        derivedStateOf {
            isSearchFieldFocused && searchQuery.isEmpty() && !navigationState.isNavigating && !showFavoriteDialog
        }
    }

    // Callback wenn ein gespeichertes Ziel ausgewählt wird
    fun onSavedDestinationSelected(destination: SavedDestination) {
        isSearchFieldFocused = false
        keyboardController?.hide()
        // Als SearchResult behandeln und Route berechnen
        viewModel.selectDestination(
            SearchResult(
                displayName = destination.name,
                lat = destination.lat,
                lon = destination.lng,
                type = "saved"
            )
        )
    }

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
    // Außerdem: Ziel als letztes Ziel speichern
    LaunchedEffect(navigationState.isNavigating, navigationState.destination, navigationState.destinationName) {
        if (navigationState.isNavigating && navigationState.destination != null) {
            selectedPoiType = null
            poiResults = emptyList()
            showMcDonaldsOverview = false
            isSearchFieldFocused = false

            // Ziel als letztes Ziel speichern
            val destName = navigationState.destinationName
            if (!destName.isNullOrBlank()) {
                val savedDest = SavedDestination(
                    name = destName,
                    lat = navigationState.destination!!.lat,
                    lng = navigationState.destination!!.lng
                )
                destinationsRepository.addRecentDestination(savedDest)
                recentDestinations = destinationsRepository.getRecentDestinations()
                CrashLogger.log("MainActivity: Saved destination to recent: $destName")
            }
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
                // Wenn Dropdown offen: nur schließen, nicht navigieren
                if (showRecentDestinations) {
                    isSearchFieldFocused = false
                } else if (!navigationState.isNavigating && navigationState.route == null) {
                    // Nur reagieren wenn keine Navigation aktiv und keine Route geplant
                    viewModel.onMapClicked(location)
                }
            },
            onPoiClick = { poi ->
                // Bei Klick auf POI-Marker: Als Zwischenziel setzen
                val waypointType = when (selectedPoiType) {
                    PoiType.MCDONALDS -> WaypointType.MCDONALDS
                    PoiType.GAS_STATION -> WaypointType.GAS_STATION
                    else -> WaypointType.GAS_STATION
                }
                viewModel.addWaypoint(
                    waypointLocation = LatLng(poi.lat, poi.lng),
                    waypointName = poi.name,
                    waypointType = waypointType
                )
                // POIs von Karte entfernen
                showMcDonaldsOverview = false
                selectedPoiType = null
                poiResults = emptyList()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Recalculating Banner - Slide von oben
        AnimatedVisibility(
            visible = isRecalculatingRoute,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Route wird neu berechnet...",
                        color = MaterialTheme.colorScheme.surface,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Waypoint Indicator - oben rechts (nur während Navigation mit aktivem Zwischenziel)
        AnimatedVisibility(
            visible = navigationState.isNavigating && navigationState.waypoint != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
        ) {
            WaypointIndicator(
                waypointType = navigationState.waypointType,
                waypointName = navigationState.waypointName,
                distanceKm = navigationState.distanceToWaypoint / 1000.0,
                timeMinutes = (navigationState.timeToWaypoint / 60.0).toInt(),
                onClearWaypoint = { viewModel.clearWaypoint() }
            )
        }

        // Suchleiste oben mit Menu-Button
        if (!navigationState.isNavigating) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            ) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { query ->
                        viewModel.updateSearchQuery(query)
                        // showRecentDestinations wird automatisch durch derivedState aktualisiert
                    },
                    results = searchResults,
                    isSearching = isSearching,
                    onResultClick = viewModel::selectDestination,
                    onClear = {
                        viewModel.clearSearch()
                        // showRecentDestinations wird automatisch durch derivedState aktualisiert
                    },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onFocusChanged = { focused ->
                        isSearchFieldFocused = focused
                    },
                    modifier = Modifier
                )

                // Recent Destinations Dropdown
                RecentDestinationsDropdown(
                    isVisible = showRecentDestinations,
                    homeAddress = homeAddress,
                    workAddress = workAddress,
                    recentDestinations = recentDestinations,
                    onDestinationClick = { destination ->
                        onSavedDestinationSelected(destination)
                    },
                    onEditFavorite = { type ->
                        editingFavoriteType = type
                        showFavoriteDialog = true
                        // showRecentDestinations wird automatisch false durch showFavoriteDialog
                    },
                    onDeleteRecent = { destination ->
                        destinationsRepository.removeRecentDestination(destination)
                        recentDestinations = destinationsRepository.getRecentDestinations()
                    }
                )
            }
        }

        // Favorite Address Dialog
        if (showFavoriteDialog && editingFavoriteType != null) {
            FavoriteAddressDialog(
                favoriteType = editingFavoriteType!!,
                currentAddress = when (editingFavoriteType) {
                    FavoriteType.HOME -> homeAddress
                    FavoriteType.WORK -> workAddress
                    else -> null
                },
                currentLocation = currentLocation,
                onSave = { destination ->
                    destinationsRepository.setFavorite(editingFavoriteType!!, destination)
                    when (editingFavoriteType) {
                        FavoriteType.HOME -> homeAddress = destination
                        FavoriteType.WORK -> workAddress = destination
                        else -> {}
                    }
                },
                onDelete = {
                    destinationsRepository.setFavorite(editingFavoriteType!!, null)
                    when (editingFavoriteType) {
                        FavoriteType.HOME -> homeAddress = null
                        FavoriteType.WORK -> workAddress = null
                        else -> {}
                    }
                },
                onDismiss = {
                    showFavoriteDialog = false
                    editingFavoriteType = null
                    // showRecentDestinations wird automatisch durch derivedState aktualisiert
                }
            )
        }

        // Navigationspanel unten (inkl. POI-Buttons)
        NavigationPanel(
            state = navigationState,
            onStartNavigation = viewModel::startNavigation,
            onStopNavigation = {
                viewModel.stopNavigation()
                // POIs zurücksetzen wenn Navigation beendet wird
                selectedPoiType = null
                poiResults = emptyList()
                showMcDonaldsOverview = false
            },
            onMcDonaldsClick = { toggleMcDonalds() },
            onGasStationClick = { searchPoi(PoiType.GAS_STATION) },
            isMcDonaldsLoading = isSearchingPoi && selectedPoiType == PoiType.MCDONALDS,
            showMcDonaldsOverview = showMcDonaldsOverview,
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
                    // POI als Zwischenziel zur Route hinzufügen
                    val waypointType = when (selectedPoiType) {
                        PoiType.MCDONALDS -> WaypointType.MCDONALDS
                        PoiType.GAS_STATION -> WaypointType.GAS_STATION
                        else -> WaypointType.GAS_STATION
                    }
                    viewModel.addWaypoint(
                        waypointLocation = LatLng(poi.lat, poi.lng),
                        waypointName = poi.name,
                        waypointType = waypointType
                    )
                    showPoiDialog = false
                    // POIs von Karte entfernen nach Auswahl
                    selectedPoiType = null
                    poiResults = emptyList()
                    showMcDonaldsOverview = false
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
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = onNavigate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
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
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
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

            val buttonContainerColor = MaterialTheme.colorScheme.onSurface
            val buttonContentColor = MaterialTheme.colorScheme.surface
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
                        containerColor = buttonContainerColor,
                        contentColor = buttonContentColor
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
                        containerColor = buttonContainerColor,
                        contentColor = buttonContentColor
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
                    containerColor = buttonContainerColor,
                    contentColor = buttonContentColor
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schließen")
            }
        }
    }
}

@Composable
fun WaypointIndicator(
    waypointType: WaypointType?,
    waypointName: String?,
    distanceKm: Double,
    timeMinutes: Int,
    onClearWaypoint: () -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.surface
    val fgColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClearWaypoint() }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon je nach Typ
            when (waypointType) {
                WaypointType.MCDONALDS -> {
                    Text(
                        text = "🍟",
                        fontSize = 20.sp
                    )
                }
                WaypointType.GAS_STATION -> {
                    Text(
                        text = "⛽",
                        fontSize = 20.sp
                    )
                }
                else -> {}
            }

            Column {
                // Distanz und Zeit
                Text(
                    text = if (distanceKm >= 1.0) {
                        String.format("%.1f km", distanceKm)
                    } else {
                        String.format("%d m", (distanceKm * 1000).toInt())
                    },
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = fgColor
                )
                Text(
                    text = if (timeMinutes >= 60) {
                        "${timeMinutes / 60} Std. ${timeMinutes % 60} Min."
                    } else {
                        "$timeMinutes Min."
                    },
                    fontSize = 12.sp,
                    color = fgColor.copy(alpha = 0.7f)
                )
            }

            // X zum Schließen
            Text(
                text = "✕",
                fontSize = 14.sp,
                color = fgColor.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
