package com.example.discogsandroidapp
import kotlinx.serialization.Serializable

@Serializable
data class AiSearchRequest(
    val query: String
)

@Serializable
data class AiSearchResponse(
    val query: String,
    val summary: String? = null,
    val resultType: String,
    val results: List<AiInventoryResult>,
    val totalMatches: Int? = null,
    val truncated: Boolean = false,
    val metadataComplete: Boolean = true,
    val missingMetadata: Int = 0
)

@Serializable
data class AiInventoryResult(
    val comments: String = "",
    val listingId: Long? = null,
    val releaseId: Long,
    val artist: String,
    val title: String,
    val year: Int? = null,
    val label: String? = null,
    val catalogNumber: String? = null,
    val format: String? = null,
    val condition: String? = null,
    val sleeveCondition: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val marketValue: Double? = null,
    val recommendedPrice: Double? = null,
    val thumbnail: String? = null,
    val reason: String? = null
)