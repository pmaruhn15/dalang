package de.dalang.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import de.dalang.nav.ui.components.MapViewComposable
import de.dalang.nav.ui.components.NavigationPanel
import de.dalang.nav.ui.components.SearchBar
import de.dalang.nav.ui.theme.DaLangTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                viewModel.startLocationUpdates()
            }
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                viewModel.startLocationUpdates()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        checkLocationPermission()

        setContent {
            DaLangTheme {
                DaLangApp(viewModel)
            }
        }
    }

    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.startLocationUpdates()
            }
            else -> {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            viewModel.startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        // Location updates weiterlaufen lassen wenn Navigation aktiv
        if (!viewModel.navigationState.value.isNavigating) {
            viewModel.stopLocationUpdates()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun DaLangApp(viewModel: MainViewModel) {
    val currentLocation by viewModel.currentLocation.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Karte im Hintergrund
        MapViewComposable(
            currentLocation = currentLocation,
            destination = navigationState.destination,
            route = navigationState.route,
            isNavigating = navigationState.isNavigating,
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

        // Navigationspanel unten
        NavigationPanel(
            state = navigationState,
            onStartNavigation = viewModel::startNavigation,
            onStopNavigation = viewModel::stopNavigation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}
