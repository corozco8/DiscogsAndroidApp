package com.example.discogsandroidapp

import retrofit2.http.Body
import retrofit2.http.POST

interface AiSearchApiService {

    @POST("api/ai-search") // Retrofit will eventually call something like: https://your-backend.com/api/ai-search
    suspend fun search(
        @Body request: AiSearchRequest //takes AiSearchRequest and converts to JSON
    ): AiSearchResponse // Android expects the backend's JSON response to match the models
}