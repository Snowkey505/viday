package com.snowkey.viday

import android.view.View
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.requests.SignUpRequest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Интеграционный тест НА ЭМУЛЯТОРЕ (Требование 7 ЛР2): реальное приложение,
 * реальный UI, реальный API тестового стенда.
 *
 * Сценарий:
 *   1. берём ПЕРВОЕ видео из публичной ленты стенда (GET /api/videos);
 *   2. через реальный LoginScreen логинимся в приложение;
 *   3. открываем это видео из ленты — открывается экран плеера (ExoPlayer);
 *   4. ПРОИГРЫВАЕМ видео НЕСКОЛЬКО РАЗ (PLAY_CYCLES = 3): каждый цикл —
 *      seekTo(0) -> play -> ждём STATE_ENDED, проверяя, что позиция реально
 *      двигалась (то есть кадры действительно декодировались).
 *
 * Подключение стенда к эмулятору:
 *   adb reverse tcp:8080  tcp:8080   (API)
 *   adb reverse tcp:59002 tcp:59002  (MinIO — источник video.source)
 *
 * Если стенд недоступен — тест помечается SKIPPED (assumeTrue), как и
 * AndroidApiFlowTest: проверка интеграции требует развёрнутого окружения.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackInstrumentedTest {

    private companion object {
        const val BASE_URL = "http://127.0.0.1:8080/"
        /** Сколько раз проигрываем видео в плеере. */
        const val PLAY_CYCLES = 3
        /** Таймаут одной попытки ожидания состояния плеера, мс. */
        const val PLAYER_TIMEOUT_MS = 30_000L
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    @Test
    fun `first_feed_video_opens_in_player_and_plays_several_times_on_emulator`() {
        assumeTrue(
            "API стенда недоступен на 127.0.0.1:8080 — нужны 'adb reverse tcp:8080 tcp:8080' " +
                "и запущенный стенд ЛР2 (docker compose -f docker/docker-compose.test.yml up -d)",
            apiReachable(),
        )

        // --- 1. Первое видео публичной ленты -----------------------------------
        val firstVideo = runBlocking {
            runCatching { api.getVideos(null, null, null).items.firstOrNull() }.getOrNull()
        }
        assumeTrue(
            "публичная лента пуста — нечего проигрывать; загрузите видео через E2E-сценарий " +
                "(make e2e-traffic) и повторите",
            firstVideo != null,
        )
        val videoName = firstVideo!!.name

        // --- 2. Регистрируем пользователя через API и логинимся через UI -------
        val stamp = System.currentTimeMillis()
        val username = "player_$stamp"
        val password = "Secret$stamp!"
        runBlocking { runCatching { api.register(SignUpRequest(username, password)) } }

        // Поля логина: два editable-узла в порядке разметки — username, password
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(username)
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput(password)
        composeRule.onNodeWithText("Войти").performClick()

        // --- 3. Лента открылась, кликаем по первому видео -----------------------
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(videoName).fetchSemanticsNodes().isNotEmpty()
        }
        // У узла Text нет click-акции — тапаем по его координатам (хит-тест
        // проходит в родительский clickable элемент VideoItem)
        composeRule.onAllNodesWithText(videoName)[0].performTouchInput { down(center); up() }

        // --- 4. Экран плеера: находим PlayerView (AndroidView в Compose) --------
        val playerViewRef = AtomicReference<PlayerView?>()
        waitUntil(PLAYER_TIMEOUT_MS) {
            runOnMain { playerViewRef.set(findPlayerView(composeRule.activity.window.decorView)) }
            playerViewRef.get()?.player != null
        }
        val playerView = playerViewRef.get()
        assertNotNull("PlayerView не найден на экране видео", playerView)
        val player: ExoPlayer = playerView!!.player as ExoPlayer

        // Ждём готовности источника (реальная загрузка с MinIO через API)
        waitUntil(PLAYER_TIMEOUT_MS) {
            player.playbackState == ExoPlayer.STATE_READY || player.playbackState == ExoPlayer.STATE_ENDED
        }
        assertTrue("источник не готов к воспроизведению", player.duration > 0)

        // --- 5. Проигрываем НЕСКОЛЬКО РАЗ ---------------------------------------
        repeat(PLAY_CYCLES) { cycle ->
            var sawProgress = false
            runOnMain {
                player.seekTo(0)
                player.play()
            }
            waitUntil(PLAYER_TIMEOUT_MS) {
                if (player.currentPosition > 0) sawProgress = true
                player.playbackState == ExoPlayer.STATE_ENDED
            }
            assertTrue(
                "цикл ${cycle + 1}/$PLAY_CYCLES: позиция плеера не двигалась — видео не воспроизводилось",
                sawProgress,
            )
        }
    }

    /** Обход иерархии view в поиске PlayerView (обёртка AndroidView в VideoPlayer). */
    private fun findPlayerView(root: View): PlayerView? {
        if (root is PlayerView) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findPlayerView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        // Последняя попытка — иначе fail с понятным сообщением вызывающего кода
        check(condition()) { "условие не выполнилось за $timeoutMs мс" }
    }

    /** Быстрая проверка доступности API стенда (GET /api/videos разрешён без токена). */
    private fun apiReachable(): Boolean = try {
        val conn = URL(BASE_URL + "api/videos").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    } catch (e: Exception) {
        false
    }
}
