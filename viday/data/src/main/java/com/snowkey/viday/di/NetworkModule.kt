package com.snowkey.viday.di

import com.google.gson.GsonBuilder
import com.snowkey.viday.api.ApiService
import com.snowkey.viday.api.TokenManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// USB-connected physical device: adb reverse tcp:8080 tcp:8080 maps the phone's
// localhost:8080 to the host where vidayapi listens (Wi-Fi may be off).
const val BASE_URL = "http://127.0.0.1:8080/"

val networkModule = module {
    single {
        GsonBuilder().create()
    }

    single {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(get())
            .addConverterFactory(GsonConverterFactory.create(get()))
            .build()
    }

    single {
        get<Retrofit>().create(ApiService::class.java)
    }

    single<TokenManager> {
        TokenManager(androidContext())
    }
}
