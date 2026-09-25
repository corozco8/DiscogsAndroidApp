package com.example.discogsandroidapp

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import retrofit2.http.Query
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.Response
import retrofit2.Call

// ----------------------------
// Request DTOs (keep if not defined elsewhere)
// ----------------------------
@Serializable
data class CreateListingRequest(
    @SerialName("release_id") val release_id: Int,
    val condition: String,
    @SerialName("sleeve_condition") val sleeve_condition: String,
    val price: Double,
    val comments: String,
    val status: String
)

@Serializable
data class CreateListingResponse(
    @SerialName("listing_id") val listingId: Long? = null,
    val id: Long? = null
)

// If EditListingRequest already exists elsewhere, remove this.
@Serializable
data class EditListingRequest(
    val price: Double,
    val condition: String,
    @SerialName("sleeve_condition") val sleeve_condition: String,
    val status: String = "For Sale",
    val comments: String? = null
)

// IMPORTANT: Do NOT redeclare Price, MarketplaceStatsResponse, or PriceSuggestions here.
// They are already defined elsewhere in your project.

// ----------------------------
// Retrofit API Interface
// ----------------------------
interface DiscogsApiService {
    @GET("releases/{releaseId}")
    suspend fun getRelease(
        @Path("releaseId") releaseId: Long,
        @Header("Authorization") authHeader: String,
        @Header("User-Agent") userAgent: String = "MyDiscogsClone/1.0"
    ): DiscogsRelease

    @GET("database/search")
    suspend fun searchDatabase(
        @Query("q") query: String,
        @Query("type") type: String = "release",
        @Header("Authorization") authHeader: String,
        @Header("User-Agent") userAgent: String = "MyDiscogsClone/1.0"
    ): DiscogsSearchResponse

    @GET("users/{username}")
    suspend fun getUserProfile(
        @Path("username") username: String,
        @Header("Authorization") authHeader: String,
        @Header("User-Agent") userAgent: String = "MyDiscogsClone/1.0"
    ): DiscogsProfile

    @GET("oauth/identity")
    suspend fun getIdentity(
        @Header("Authorization") authHeader: String,
        @Header("User-Agent") userAgent: String = "MyDiscogsClone/1.0"
    ): DiscogsIdentityResponse

    @GET("users/{username}/inventory")
    suspend fun getInventory(
        @Path("username") username: String,
        @Header("Authorization") authHeader: String,
        @Query("status") status: String = "For Sale",
        @Query("string") searchString: String? = null,
        @Query("sort") sort: String = "listed",
        @Query("sort_order") sortOrder: String = "desc",
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50
    ): InventoryResponse

    @GET("marketplace/listings/{listing_id}")
    suspend fun getMarketplaceListing(
        @Path("listing_id") listingId: Long,
        @Header("Authorization") authHeader: String,
        @Query("curr_abbr") currency: String = "USD"
    ): InventoryListing

    @DELETE("marketplace/listings/{listing_id}")
    suspend fun deleteListing(
        @Path("listing_id") listingId: Long,
        @Header("Authorization") authHeader: String
    ): Response<Unit>

    @POST("marketplace/listings/{listing_id}")
    suspend fun editListing(
        @Path("listing_id") listingId: Long,
        @Header("Authorization") authHeader: String,
        @Body request: EditListingRequest
    ): Response<Unit>

    @POST("marketplace/listings")
    suspend fun createListing(
        @Header("Authorization") authHeader: String,
        @Body request: CreateListingRequest
    ): Response<CreateListingResponse>

    @GET("marketplace/orders")
    suspend fun getOrders(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 100,
        @Query("sort") sort: String = "created",
        @Query("sort_order") sortOrder: String = "desc"
    ): DiscogsOrdersResponse


    @GET("marketplace/orders/{order_id}")
    suspend fun getOrder(
        @Path("order_id") orderId: String,
        @Header("Authorization") authHeader: String
    ): DiscogsOrder

    @POST("marketplace/orders/{order_id}")
    suspend fun updateOrderStatus(
        @Path("order_id") orderId: String,
        @Header("Authorization") authHeader: String,
        @Query("status") status: String
    ): Response<okhttp3.ResponseBody>


    @GET("marketplace/orders/{order_id}/messages")
    suspend fun getOrderMessages(
        @Path("order_id") orderId: String,
        @Header("Authorization") authHeader: String,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 100
    ): DiscogsOrderMessagesResponse

    @POST("marketplace/orders/{order_id}/messages")
    suspend fun updateOrderStatusViaMessage(
        @Path("order_id") orderId: String,
        @Header("Authorization") authHeader: String,
        @Body request: AddOrderMessageRequest
    ): Response<okhttp3.ResponseBody>

    @POST("marketplace/orders/{order_id}/messages")
    suspend fun sendOrderMessage(
        @Path("order_id") orderId: String,
        @Header("Authorization") authHeader: String,
        @Body request: AddOrderMessageRequest
    ): DiscogsOrderMessage

    @GET("users/{username}/feedback")
    fun getUserEvaluations(
        @Path("username") username: String,
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50
    ): Call<DiscogsEvaluationsResponse>

    @GET("marketplace/stats/{release_id}")
    suspend fun getMarketplaceStats(
        @Path("release_id") releaseId: Long,
        @Header("Authorization") authHeader: String,
        @Query("curr_abbr") currency: String = "USD"
    ): MarketplaceStatsResponse  // Already defined elsewhere

    @GET("marketplace/price_suggestions/{release_id}")
    suspend fun getPriceSuggestions(
        @Path("release_id") releaseId: Long,
        @Header("Authorization") authHeader: String
    ): PriceSuggestions  // Already defined elsewhere


    @GET("masters/{master_id}/versions")
    suspend fun getMasterVersions(
        @Path("master_id") masterId: Long,
        @Header("Authorization") authHeader: String,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 100,
        @Query("sort") sort: String = "released",
        @Query("sort_order") sortOrder: String = "asc"
    ): MasterVersionsResponse
}
