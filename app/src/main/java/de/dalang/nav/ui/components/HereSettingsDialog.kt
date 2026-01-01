package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.config.HereConfig
import de.dalang.nav.settings.ApiUsageInfo
import de.dalang.nav.settings.FuelType
import de.dalang.nav.settings.PeriodType
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.settings.UsageStatus
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

@Composable
fun HereSettingsDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var hereApiKey by remember { mutableStateOf(HereConfig.getApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var monthlyLimit by remember { mutableStateOf(settingsRepository.hereMonthlyLimit.toString()) }
    var selectedFuelType by remember { mutableStateOf(settingsRepository.preferredFuelType) }
    var vehicleRangeKm by remember { mutableStateOf(settingsRepository.vehicleRangeKm.let { if (it == 0) "" else it.toString() }) }

    // Fuel Prices API Test State
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var usageInfos by remember { mutableStateOf(settingsRepository.getAllApiUsageInfos()) }
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.GERMANY) }

    // Funktion zum Testen der Fuel Prices API
    fun testFuelPricesApi() {
        if (hereApiKey.isBlank()) {
            testResult = "Kein API Key eingegeben"
            testSuccess = false
            return
        }

        if (!settingsRepository.canMakeFuelPricesRequest()) {
            testResult = "Monatslimit erreicht (${settingsRepository.fuelPricesMonthlyLimit})"
            testSuccess = false
            return
        }

        isTesting = true
        testResult = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Test mit München Koordinaten
                    val url = "https://fuel-v2.cc.api.here.com/fuel/stations.json" +
                            "?prox=48.1351,11.5820,5000" +
                            "&apiKey=${hereApiKey.trim()}"

                    CrashLogger.log("HereSettings: Testing Fuel Prices API...")
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val response = connection.getInputStream().bufferedReader().readText()

                    // Counter erhöhen
                    settingsRepository.incrementFuelPricesUsage()

                    // Prüfen ob Stationen gefunden wurden
                    if (response.contains("\"stations\"")) {
                        val stationCount = Regex("\"id\"\\s*:").findAll(response).count()
                        "OK! $stationCount Tankstellen gefunden"
                    } else {
                        "API antwortet, aber keine Daten"
                    }
                }
                testResult = result
                testSuccess = true
                usageInfos = settingsRepository.getAllApiUsageInfos()
                CrashLogger.log("HereSettings: Fuel Prices API test successful: $result")
            } catch (e: java.io.FileNotFoundException) {
                testResult = "404 - API nicht aktiviert"
                testSuccess = false
                CrashLogger.logError("HereSettings", "Fuel Prices API 404", e)
            } catch (e: Exception) {
                testResult = "Fehler: ${e.message?.take(50)}"
                testSuccess = false
                CrashLogger.logError("HereSettings", "Fuel Prices API test failed", e)
            } finally {
                isTesting = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "HERE API",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Für Routing mit Verkehrsdaten und Spritpreise",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "API Key",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (hereApiKey.isEmpty()) {
                            Text(
                                text = "API Key eingeben...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        BasicTextField(
                            value = hereApiKey,
                            onValueChange = { hereApiKey = it },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    TextButton(
                        onClick = { showApiKey = !showApiKey },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = if (showApiKey) "Verbergen" else "Zeigen",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "platform.here.com → Projects → API Keys",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Fuel Prices API Test
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { testFuelPricesApi() },
                    enabled = !isTesting && hereApiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Teste...")
                    } else {
                        Text("Fuel Prices API testen")
                    }
                }
            }

            // Test-Ergebnis anzeigen
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (testSuccess) Color(0xFF4CAF50).copy(alpha = 0.2f)
                            else Color(0xFFE53935).copy(alpha = 0.2f)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = result,
                        fontSize = 13.sp,
                        color = if (testSuccess) Color(0xFF4CAF50) else Color(0xFFE53935),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Kraftstoff-Einstellungen
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Tankstellen",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Kraftstofftyp",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FuelType.entries.forEach { fuelType ->
                    FilterChip(
                        selected = selectedFuelType == fuelType,
                        onClick = { selectedFuelType = fuelType },
                        label = { Text(fuelType.displayName) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Wird auf der Karte und im Dialog angezeigt",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Reichweite (km)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                if (vehicleRangeKm.isEmpty()) {
                    Text(
                        text = "z.B. 500",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }

                BasicTextField(
                    value = vehicleRangeKm,
                    onValueChange = { vehicleRangeKm = it.filter { c -> c.isDigit() } },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Leer lassen für unbegrenzt",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // API Usage Stats (wenn konfiguriert)
            if (usageInfos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Nutzung",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                usageInfos.forEach { info ->
                    ApiUsageCard(
                        info = info,
                        numberFormat = numberFormat
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Limit Settings
                Text(
                    text = "Monatslimit",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = monthlyLimit,
                        onValueChange = { monthlyLimit = it.filter { c -> c.isDigit() } },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Free Tier: 250.000/Monat",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bei Limit: Fallback auf OSRM (ohne Verkehrsdaten)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (hereApiKey.isBlank()) {
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "Kein API Key konfiguriert",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Routing erfolgt über OSRM ohne Verkehrsdaten",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Buttons
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
                    onClick = {
                        HereConfig.setApiKey(hereApiKey.trim())
                        monthlyLimit.toIntOrNull()?.let {
                            settingsRepository.hereMonthlyLimit = it
                        }
                        settingsRepository.preferredFuelType = selectedFuelType
                        settingsRepository.vehicleRangeKm = vehicleRangeKm.toIntOrNull() ?: 0
                        onSave()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Speichern")
                }
            }
        }
    }
}

@Composable
private fun ApiUsageCard(
    info: ApiUsageInfo,
    numberFormat: NumberFormat
) {
    val barColor = when (info.status) {
        UsageStatus.BLOCKED -> Color(0xFFE53935)
        UsageStatus.WARNING -> Color(0xFFFF9800)
        UsageStatus.OK -> Color(0xFF4CAF50)
    }

    val statusText = when (info.status) {
        UsageStatus.BLOCKED -> "Limit erreicht!"
        UsageStatus.WARNING -> "Warnung"
        UsageStatus.OK -> "OK"
    }

    val periodText = when (info.periodType) {
        PeriodType.MONTHLY -> info.periodStart
        PeriodType.DAILY -> "Heute"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${numberFormat.format(info.used)} / ${numberFormat.format(info.limit)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = barColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (info.percentage / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${numberFormat.format(info.remaining)} verbleibend",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = periodText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (info.status == UsageStatus.BLOCKED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠ OSRM Fallback aktiv",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = barColor
            )
        }
    }
}
