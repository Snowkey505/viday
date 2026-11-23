package com.snowkey.viday.presentation.navigationBar

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.compose.rememberNavController
import com.snowkey.viday.model.Navigation
import com.snowkey.viday.ui.theme.VidayTheme

@Composable
fun VidayBottomNavigationBar(
    navController: NavController,
    currentDestination: NavDestination?,
    userId: Long? = null,
    playlistId: Long? = null
) {
    val items = listOf(
        Navigation.VIDEOS to "videos",
        Navigation.PLAYLISTS to "playlists",
        Navigation.PROFILE to "profile"
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier,
        tonalElevation = 0.dp
    ) {
        items.forEach { (destination, title) ->
            val isSelected = when (destination) {
                Navigation.VIDEOS -> currentDestination?.route?.startsWith("videos/") == true
                else -> currentDestination?.route == destination.route
            }

            NavigationBarItem(
                modifier = Modifier.height(40.dp),
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        when (destination) {
                            Navigation.VIDEOS -> {
                                val route = Navigation.createVideosRoute(
                                    userId = userId,
                                    playlistId = playlistId
                                )
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            Navigation.PLAYLISTS -> {
                                navController.navigate(Navigation.createPlaylistsRoute(userId))
                            }
                            else -> {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            Navigation.PLAYLISTS -> Icons.AutoMirrored.Filled.PlaylistPlay
                            Navigation.PROFILE -> Icons.Default.AccountCircle
                            else -> Icons.Default.Home
                        },
                        modifier = Modifier.size(30.dp),
                        contentDescription = title,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent
                ),
                interactionSource = remember { MutableInteractionSource() },
                alwaysShowLabel = true
            )
        }
    }
}

@Preview
@Composable
fun VidayBottomNavigationBarPreview() {
    VidayTheme(darkTheme = false) {
        VidayBottomNavigationBar(
            navController = rememberNavController(),
            currentDestination = null
        )
    }
}

@Preview
@Composable
fun VidayBottomNavigationBarPreviewDark() {
    VidayTheme(darkTheme = true) {
        VidayBottomNavigationBar(
            navController = rememberNavController(),
            currentDestination = null
        )
    }
}
