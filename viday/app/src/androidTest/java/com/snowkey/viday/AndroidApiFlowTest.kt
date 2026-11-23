package com.snowkey.viday

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.requests.CreatePlaylistRequest
import com.snowkey.viday.api.requests.LoginRequest
import com.snowkey.viday.api.requests.SignUpRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Тест, выполняемый НА УСТРОЙСТВЕ (Требование 7 ЛР2): запускается на Android-
 * эмуляторе / физическом устройстве через connectedDebugAndroidTest и работает
 * с РЕАЛЬНЫМ API тестового стенда.
 *
 * Как подключить API стенда к устройству:
 *   adb reverse tcp:8080 tcp:8080    (локальный стенд, порт 8080)
 *
 * Если API недоступен (например, в CI без стенда) — тест не падает, а
 * помечается как SKIPPED (assumeTrue), т.к. проверка контура на устройстве
 * требует развёрнутого окружения ЛР2.
 */
@RunWith(AndroidJUnit4::class)
class AndroidApiFlowTest {

    private val baseUrl = "http://127.0.0.1:8080/"

    private val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    @Test
    fun `register_login_and_create_playlist_from_a_real_device_against_the_LR2_stand`() {
        assumeTrue(
            "API стенда недоступен на 127.0.0.1:8080 — нужен 'adb reverse tcp:8080 tcp:8080' " +
                "и запущенный стенд ЛР2 (docker compose -f docker/docker-compose.test.yml up -d)",
            apiReachable(),
        )

        runBlocking {
            val stamp = System.currentTimeMillis()
            val username = "device_$stamp"
            val password = "Secret$stamp!"

            // 1. Регистрация нового пользователя на реальном стенде
            val registered = api.register(SignUpRequest(username, password))
            assertTrue("registration must return a positive id", registered.id > 0)

            // 2. Логин -> JWT
            val login = api.login(LoginRequest(username, password))
            assertTrue("token must not be blank", login.token.isNotBlank())
            val auth = "Bearer ${login.token}"

            // 3. Создание плейлиста через API стенда
            val name = "Device playlist $stamp"
            val created = api.createPlaylist(auth, CreatePlaylistRequest(name = name, accessType = "PUBLIC"))
            assertEquals(name, created.name)

            // 4. Проверка видимости созданного плейлиста
            val mine = api.getPlaylists(auth)
            assertTrue("created playlist must be visible in my playlists", mine.items.any { it.name == name })
        }
    }

    /** Быстрая проверка доступности API стенда (GET /api/videos разрешён без токена). */
    private fun apiReachable(): Boolean = try {
        val conn = URL(baseUrl + "api/videos").openConnection() as HttpURLConnection
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