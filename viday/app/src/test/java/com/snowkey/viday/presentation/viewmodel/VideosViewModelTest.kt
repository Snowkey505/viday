package com.snowkey.viday.presentation.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.snowkey.viday.R
import com.snowkey.viday.model.Video
import com.snowkey.viday.model.VideoError
import com.snowkey.viday.model.VideosResult
import com.snowkey.viday.presentation.videosScreen.VideosUiState
import com.snowkey.viday.presentation.videosScreen.VideosViewModel
import com.snowkey.viday.usecase.playlist.AddContentToPlaylistUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistByIdUseCase
import com.snowkey.viday.usecase.playlist.GetPlaylistsUseCase
import com.snowkey.viday.usecase.playlist.RemoveContentFromPlaylistUseCase
import com.snowkey.viday.usecase.video.GetVideosUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM unit-тесты мобильного приложения (viday): бизнес-логика экрана видео.
 *
 * Используются только моки (MockK) — классический «Лондонский» стиль, никакой
 * сети и устройств: всё выполняется на JVM (./gradlew :app:testDebugUnitTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideosViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val getVideosUseCase = mockk<GetVideosUseCase>()
    private val getPlaylistsUseCase = mockk<GetPlaylistsUseCase>(relaxed = true)
    private val addContentToPlaylistUseCase = mockk<AddContentToPlaylistUseCase>(relaxed = true)
    private val getPlaylistByIdUseCase = mockk<GetPlaylistByIdUseCase>(relaxed = true)
    private val removeContentFromPlaylistUseCase = mockk<RemoveContentFromPlaylistUseCase>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): VideosViewModel =
        VideosViewModel(
            getVideosUseCase = getVideosUseCase,
            getPlaylistsUseCase = getPlaylistsUseCase,
            addContentToPlaylistUseCase = addContentToPlaylistUseCase,
            getPlaylistByIdUseCase = getPlaylistByIdUseCase,
            removeContentFromPlaylistUseCase = removeContentFromPlaylistUseCase,
            savedStateHandle = savedStateHandle,
        )

    @Test
    fun `getVideos maps domain videos to ui and sets Done state`() {
        val video = Video(
            id = 1L,
            name = "MVP clip",
            description = "demo",
            source = "https://cdn.local/videos/1.mp4",
            preview = "https://cdn.local/previews/1.jpg",
            ownerId = 7L,
        )
        coEvery { getVideosUseCase.invoke(null, null) } returns
            VideosResult(error = VideoError.OK, videos = listOf(video))

        val vm = viewModel()
        vm.getVideos(userId = null, playlistId = null)

        assertEquals(VideosUiState.Done, vm.uiState.value)
        assertEquals(listOf(1L), vm.videos.value.map { it.id })
        assertEquals("MVP clip", vm.videos.value.single().name)
    }

    @Test
    fun `getVideos network error maps to network_error resource`() {
        coEvery { getVideosUseCase.invoke(null, null) } returns
            VideosResult(error = VideoError.NETWORK_ERROR, videos = null)

        val vm = viewModel()
        vm.getVideos(userId = null, playlistId = null)

        assertTrue(vm.uiState.value is VideosUiState.Error)
        assertEquals(R.string.network_error, (vm.uiState.value as VideosUiState.Error).messageResId)
    }

    @Test
    fun `clear resets videos and playlists`() {
        coEvery { getVideosUseCase.invoke(null, null) } returns
            VideosResult(error = VideoError.OK, videos = emptyList())
        val vm = viewModel()
        vm.getVideos(userId = null, playlistId = null)
        assertEquals(VideosUiState.Done, vm.uiState.value)

        vm.clear()

        assertTrue(vm.videos.value.isEmpty())
        assertTrue(vm.playlists.value.isEmpty())
        assertEquals(null, vm.playlistInfo.value)
    }
}
