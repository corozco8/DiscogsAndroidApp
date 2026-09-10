package com.example.discogsandroidapp

import kotlinx.serialization.Serializable

import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class RemoveInventoryCacheRequest(
    val listingIds: List<Long>
)

@Serializable
data class RemoveInventoryCacheResponse(
    val status: String,
    val removed: Int,
    val cachedItems: Int
)

interface AiSearchApiService {

    @POST("api/ai-search")
    suspend fun search(
        @Body request: AiSearchRequest
    ): AiSearchResponse

    @POST("api/inventory-cache/remove")
    suspend fun removeFromInventoryCache(
        @Body request: RemoveInventoryCacheRequest
    ): RemoveInventoryCacheResponse
}
