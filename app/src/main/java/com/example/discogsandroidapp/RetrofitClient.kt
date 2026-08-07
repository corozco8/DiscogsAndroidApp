package com.example.discogsandroidapp

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType  // <-- This will now resolve perfectly!
import retrofit2.Retrofit

object RetrofitClient {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    val apiService: DiscogsApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.discogs.com/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DiscogsApiService::class.java)
    }
}