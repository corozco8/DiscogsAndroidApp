package com.example.discogsandroidapp

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType  // <-- This will now resolve perfectly!
import retrofit2.Retrofit

object RetrofitClient {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    val apiService: DiscogsApiService by lazy { create(false) }
    // Separate dispatcher keeps waiting sync requests out of the interactive call pool.
    val backgroundApiService: DiscogsApiService by lazy { create(true) }

    private fun create(background: Boolean): DiscogsApiService {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().tag(
                    DiscogsRequestPriority::class.java,
                    if (background) DiscogsRequestPriority.BACKGROUND else DiscogsRequestPriority.FOREGROUND
                ).build())
            }
            .addInterceptor(DiscogsRequestPacing())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.discogs.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(DiscogsApiService::class.java)
    }
}
