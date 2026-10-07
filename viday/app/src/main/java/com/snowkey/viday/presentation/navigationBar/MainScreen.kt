package com.snowkey.viday.presentation.navigationBar

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.snowkey.viday.presentation.videosScreen.VideosScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen(
    navController: NavController,
    userId: Long? = null,
    playlistId: Long? = null,
    content: @Composable () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        bottomBar = {
            if (!isLandscape) {
                VidayBottomNavigationBar(
                    navController = navController,
                    currentDestination = currentDestination,
                    userId = userId,
                    playlistId = playlistId
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content()
        }
    }
}

@Preview
@Composable
fun MainScreenPreview(){
    MainScreen(
        navController = rememberNavController()
    ) {
        VideosScreen(
            vm = koinViewModel(),
            navController = rememberNavController()
        )
    }
}
