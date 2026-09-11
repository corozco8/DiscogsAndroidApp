package com.example.discogsandroidapp

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object BackendRetrofitClient {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val apiService: AiSearchApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(
                json.asConverterFactory(
                    "application/json".toMediaType()
                )
            )
            .build()
            .create(AiSearchApiService::class.java)
    }
}