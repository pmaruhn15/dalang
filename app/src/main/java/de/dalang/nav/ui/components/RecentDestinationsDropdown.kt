package de.dalang.nav.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dalang.nav.destinations.FavoriteType
import de.dalang.nav.destinations.SavedDestination

@Composable
fun RecentDestinationsDropdown(
    isVisible: Boolean,
    homeAddress: SavedDestination?,
    workAddress: SavedDestination?,
    recentDestinations: List<SavedDestination>,
    onDestinationClick: (SavedDestination) -> Unit,
    onEditFavorite: (FavoriteType) -> Unit,
    onDeleteRecent: (SavedDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut() + slideOutVertically { -it / 4 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(max = 400.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                // ===== Favoriten-Sektion =====
                item(key = "favorites_header") {
                    Text(
                        text = "Favoriten",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                    )
                }

                // Zuhause
                item(key = "home") {
                    FavoriteItem(
                        type = FavoriteType.HOME,
                        destination = homeAddress,
                        onClick = { homeAddress?.let { onDestinationClick(it) } },
                        onEdit = { onEditFavorite(FavoriteType.HOME) }
                    )
                }

                // Arbeit
                item(key = "work") {
                    FavoriteItem(
                        type = FavoriteType.WORK,
                        destination = workAddress,
                        onClick = { workAddress?.let { onDestinationClick(it) } },
                        onEdit = { onEditFavorite(FavoriteType.WORK) }
                    )
                }

                // ===== Letzte Ziele =====
                if (recentDestinations.isNotEmpty()) {
                    item(key = "recent_header") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "Letzte Ziele",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    itemsIndexed(
                        items = recentDestinations,
                        key = { index, dest -> "recent_${index}_${dest.lat}_${dest.lng}" }
                    ) { _, destination ->
                        SwipeToDeleteItem(
                            destination = destination,
                            onClick = { onDestinationClick(destination) },
                            onDelete = { onDeleteRecent(destination) }
                        )
                    }
                }

                // Padding am Ende
                item(key = "bottom_padding") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FavoriteItem(
    type: FavoriteType,
    destination: SavedDestination?,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = destination != null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Text(
            text = type.icon,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name und Adresse
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = type.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (destination != null) {
                Text(
                    text = destination.name.split(",").first().trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            } else {
                Text(
                    text = "Nicht festgelegt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        // Bearbeiten-Button
        Text(
            text = "⚙️",
            fontSize = 18.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickable { onEdit() }
        )
    }
}

@Composable
private fun SwipeToDeleteItem(
    destination: SavedDestination,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        label = "swipe_offset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Hintergrund mit Löschen-Indikator
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (offsetX < -50f) {
                Text(
                    text = "🗑️ Löschen",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }

        // Vordergrund-Item
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = animatedOffset }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -150f) {
                                onDelete()
                            }
                            offsetX = 0f
                        },
                        onDragCancel = {
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-200f, 0f)
                        }
                    )
                }
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📍",
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val parts = destination.name.split(",")
                Text(
                    text = parts.first().trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
    }
}
