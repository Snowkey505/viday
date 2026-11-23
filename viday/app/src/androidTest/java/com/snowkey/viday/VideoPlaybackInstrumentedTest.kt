package com.snowkey.viday

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.requests.SignUpRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FeedInstrumentedTest {

    private companion object {
        const val BASE_URL = "http://127.0.0.1:8080/"
        const val UI_TIMEOUT_MS = 30_000L
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
    fun `feed_opens_after_login_and_shows_first_video`() {
        assumeTrue(
            "API стенда недоступен на 127.0.0.1:8080 — нужны 'adb reverse tcp:8080 tcp:8080' " +
                    "и запущенный стенд (docker compose -f docker/docker-compose.test.yml up -d)",
            apiReachable(),
        )

        val firstVideo = runBlocking {
            runCatching { api.getVideos(null, null, null).items.firstOrNull() }.getOrNull()
        }
        assumeTrue(
            "публичная лента пуста — нечего проверять; загрузите видео через E2E-сценарий " +
                    "(make e2e-traffic) и повторите",
            firstVideo != null,
        )
        val videoName = firstVideo!!.name

        val stamp = System.currentTimeMillis()
        val username = "feed_$stamp"
        val password = "Secret$stamp!"
        runBlocking { runCatching { api.register(SignUpRequest(username, password)) } }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(username)
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput(password)
        composeRule.onNodeWithText("Войти").performClick()

        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(videoName).fetchSemanticsNodes().isNotEmpty()
        }
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