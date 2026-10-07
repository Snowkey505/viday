package com.snowkey.viday

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snowkey.viday.data.PreferencesLocalDataSource
import com.snowkey.viday.model.Navigation
import com.snowkey.viday.presentation.navigationBar.MainScreen
import com.snowkey.viday.presentation.loginScreen.LoginScreen
import com.snowkey.viday.presentation.loginScreen.LoginViewModel
import com.snowkey.viday.presentation.playlistScreen.PlaylistScreen
import com.snowkey.viday.presentation.profileScreen.ProfileScreen
import com.snowkey.viday.presentation.signUpScreen.SignUpScreen
import com.snowkey.viday.presentation.videoScreen.VideoScreen
import com.snowkey.viday.presentation.videoScreen.VideoViewModel
import com.snowkey.viday.presentation.videosScreen.VideosScreen
import com.snowkey.viday.presentation.videosScreen.VideosViewModel
import com.snowkey.viday.ui.theme.VidayTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import kotlin.getValue

class MainActivity : ComponentActivity() {
    private val loginViewModel: LoginViewModel by viewModel<LoginViewModel>()
    private val prefsDataSource: PreferencesLocalDataSource by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val authData = prefsDataSource.getAuthData()
            Log.d("AUTH", authData.toString())
            val userId = if (authData != null) {
                loginViewModel.loginBackground(authData.login, authData.password)
            } else null
            setContent {
                VidayTheme {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = if (userId != null) {
                            Navigation.createVideosRoute(
                                userId = userId,
                                playlistId = null
                            )
                        } else Navigation.LOGIN.route
                    ) {
                        composable(Navigation.LOGIN.route) {
                            LoginScreen(
                                vm = koinViewModel(),
                                navController = navController

                            )
                        }

                        composable(Navigation.SIGNUP.route) {
                            SignUpScreen(
                                vm = koinViewModel(),
                                navController = navController
                            )
                        }

                        composable(
                            route = Navigation.VIDEOS.route,
                            arguments = listOf(
                                navArgument("userId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                },
                                navArgument("playlistId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                })
                        ) { backStackEntry ->
                            val userId =
                                backStackEntry.arguments?.getLong("userId")?.takeIf { it != -1L }
                            val playlistId =
                                backStackEntry.arguments?.getLong("playlistId")
                                    ?.takeIf { it != -1L }

                            val vm: VideosViewModel = koinViewModel(
                                viewModelStoreOwner = backStackEntry,
                                parameters = { parametersOf(userId, playlistId) }
                            )

                            MainScreen(
                                navController = navController,
                                userId = userId,
                                playlistId = playlistId
                            ) {
                                VideosScreen(
                                    vm = vm,
                                    navController = navController
                                )
                            }
                        }

                        composable(
                            route = Navigation.VIDEO.route,
                            arguments = listOf(
                                navArgument("userId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                },
                                navArgument("videoId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                },
                                navArgument("playlistId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                }
                            )
                        ) { backStackEntry ->
                            val userId = backStackEntry.arguments?.getLong("userId")?.takeIf { it != -1L }
                            val videoId = backStackEntry.arguments?.getLong("videoId")?.takeIf { it != -1L }
                            val playlistId = backStackEntry.arguments?.getLong("playlistId")?.takeIf { it != -1L }

                            val vm: VideoViewModel = koinViewModel(
                                viewModelStoreOwner = backStackEntry,
                                parameters = { parametersOf(userId, videoId, playlistId) }
                            )

                            MainScreen(
                                navController = navController,
                                userId = userId,
                                playlistId = playlistId
                            ) {
                                VideoScreen(
                                    vm = vm,
                                    navController = navController
                                )
                            }
                        }

                        composable(
                            route = Navigation.PLAYLISTS.route,
                            arguments = listOf(
                                navArgument("userId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                    nullable = false
                                }
                            )
                        ) { backStackEntry ->
                            val userId = backStackEntry.arguments?.getLong("userId")?.takeIf { it != -1L }

                            MainScreen(
                                navController = navController,
                                userId = userId
                            ) {
                                PlaylistScreen(
                                    vm = koinViewModel(
                                        viewModelStoreOwner = backStackEntry,
                                        parameters = { parametersOf(userId) }
                                    ),
                                    navController = navController,
                                )
                            }
                        }

                        composable(Navigation.PROFILE.route) {
                            MainScreen(
                                navController = navController,
                                userId = userId
                            ) {
                                ProfileScreen(
                                    vm = koinViewModel(),
                                    navController = navController
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
