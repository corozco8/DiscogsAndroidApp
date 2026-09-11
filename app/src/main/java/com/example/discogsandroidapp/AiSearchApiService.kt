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

@Serializable
data class SyncInventoryResponse(
    val status: String,
    val cachedItems: Int
)

@Serializable
data class UpdateInventoryCacheListingRequest(
    val listingId: Long,
    val price: Double,
    val condition: String,
    val sleeveCondition: String,
    val comments: String
)

@Serializable
data class UpdateInventoryCacheListingResponse(
    val status: String,
    val updated: Boolean,
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

    @POST("api/sync-inventory")
    suspend fun syncInventory(): SyncInventoryResponse

    @POST("api/inventory-cache/update")
    suspend fun updateInventoryCacheListing(
        @Body request: UpdateInventoryCacheListingRequest
    ): UpdateInventoryCacheListingResponse
}
