package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.navigation.FuelPrices
import de.dalang.nav.navigation.Poi
import de.dalang.nav.navigation.PoiType
import de.dalang.nav.settings.FuelType
import de.dalang.nav.util.OpeningHoursParser

@Composable
fun PoiSelectionDialog(
    poiType: PoiType,
    pois: List<Poi>,
    isLoading: Boolean,
    isAlongRoute: Boolean = false,
    preferredFuelType: FuelType = FuelType.DIESEL,
    onSelect: (Poi) -> Unit,
    onDismiss: () -> Unit
) {
    // Günstigste Tankstelle finden
    val cheapestPoi = if (poiType == PoiType.GAS_STATION) {
        pois.filter { poi ->
            poi.fuelPrices?.getPriceForType(preferredFuelType) != null
        }.minByOrNull { poi ->
            poi.fuelPrices?.getPriceForType(preferredFuelType) ?: Double.MAX_VALUE
        }
    } else null
    val locationText = if (isAlongRoute) "entlang der Route" else "in der Nähe"
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "${poiType.displayName} $locationText",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Suche ${poiType.displayName}...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                pois.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Keine ${poiType.displayName} $locationText gefunden",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = pois,
                            key = { index, poi -> "poi_${index}_${poi.lat}_${poi.lng}" }
                        ) { _, poi ->
                            PoiListItem(
                                poi = poi,
                                preferredFuelType = preferredFuelType,
                                isCheapest = poi == cheapestPoi,
                                onClick = { onSelect(poi) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Close Button
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Text("Abbrechen", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PoiListItem(
    poi: Poi,
    preferredFuelType: FuelType = FuelType.DIESEL,
    isCheapest: Boolean = false,
    onClick: () -> Unit
) {
    val backgroundColor = if (isCheapest) {
        Color(0xFF4CAF50).copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = poi.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCheapest) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Günstigste",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF4CAF50).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!poi.address.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = poi.address,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Öffnungszeiten-Status anzeigen
                val openStatus = OpeningHoursParser.checkOpenStatus(
                    poi.openingHours,
                    poi.estimatedArrivalMinutes
                )
                if (openStatus.displayText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val statusColor = when {
                        !openStatus.isOpenNow -> Color(0xFFE53935)  // Rot - geschlossen
                        !openStatus.willBeOpenAtArrival -> Color(0xFFFF9800)  // Orange - schließt bald
                        else -> Color(0xFF4CAF50)  // Grün - offen
                    }
                    Text(
                        text = openStatus.displayText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Distance & Time
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = formatDistance(poi.distanceKm),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${poi.estimatedArrivalMinutes} min",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (poi.detourMinutes > 0) {
                    Text(
                        text = "+${poi.detourMinutes} min Umweg",
                        fontSize = 11.sp,
                        color = Color(0xFFFF9800)  // Orange für Umweg
                    )
                }
            }
        }

        // Kraftstoffpreis anzeigen (nur für Tankstellen, nur ausgewählter Typ)
        poi.fuelPrices?.let { prices ->
            val price = prices.getPriceForType(preferredFuelType)
            if (price != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FuelPriceChip(
                    label = preferredFuelType.displayName,
                    price = price,
                    isCheapest = isCheapest
                )
            }
        }
    }
}

@Composable
private fun FuelPricesRow(prices: FuelPrices) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        prices.diesel?.let { price ->
            FuelPriceChip(label = "Diesel", price = price)
        }
        prices.e5?.let { price ->
            FuelPriceChip(label = "Super", price = price)
        }
    }
}

@Composable
private fun FuelPriceChip(label: String, price: Double, isCheapest: Boolean = false) {
    val chipBackground = if (isCheapest) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.surface
    }
    // Günstigste immer weiß auf grün, sonst theme-aware
    val textColor = if (isCheapest) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(chipBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = textColor.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = String.format("%.2f€", price),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

private fun formatDistance(distanceKm: Double): String {
    return if (distanceKm < 1.0) {
        "${(distanceKm * 1000).toInt()} m"
    } else {
        String.format("%.1f km", distanceKm)
    }
}
