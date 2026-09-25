package com.example.discogsandroidapp

import kotlinx.coroutines.CancellationException
import retrofit2.Response

internal sealed interface ListingCreationResult {
    data class Verified(val listing: InventoryListing, val cached: Boolean) : ListingCreationResult
    data class Unconfirmed(val message: String) : ListingCreationResult
    data class Rejected(val message: String) : ListingCreationResult
}

/** Exactly one POST. A failed follow-up GET/cache write must never trigger another POST. */
internal suspend fun createAndVerifyListing(
    request: CreateListingRequest,
    create: suspend (CreateListingRequest) -> Response<CreateListingResponse>,
    fetch: suspend (Long) -> InventoryListing,
    cache: suspend (InventoryListing) -> Unit
): ListingCreationResult {
    val response = try { create(request) } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        return ListingCreationResult.Unconfirmed("Could not confirm whether Discogs created this listing. Check your website inventory (including Drafts) before retrying to avoid a duplicate.")
    }
    if (!response.isSuccessful) {
        val details = response.errorBody()?.string()?.take(400).orEmpty()
        return if (response.code() >= 500) ListingCreationResult.Unconfirmed(
            "Discogs returned HTTP ${response.code()}. Creation is uncertain; check your website inventory before retrying. $details"
        ) else ListingCreationResult.Rejected("Discogs rejected this listing (HTTP ${response.code()}). $details")
    }
    val id = (response.body()?.listingId ?: response.body()?.id)?.takeIf { it > 0 }
        ?: return ListingCreationResult.Unconfirmed("Discogs accepted the request but returned no listing ID. Check your website inventory (including Drafts) before retrying.")
    val listing = try { fetch(id) } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        return ListingCreationResult.Unconfirmed("Discogs created listing #$id, but its status could not be checked. Check that listing on the website before retrying.")
    }
    if (listing.id != id || listing.release.id != request.release_id.toLong()) {
        return ListingCreationResult.Unconfirmed("Discogs returned unexpected details for listing #$id. Check your website inventory before retrying.")
    }
    if (!listing.status.equals("For Sale", true)) {
        return ListingCreationResult.Unconfirmed("Listing #$id exists, but Discogs reports '${listing.status}', not For Sale. Check it in your website inventory; do not create another copy.")
    }
    val cached = try { cache(listing); true } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }
    return ListingCreationResult.Verified(listing, cached)
}

internal fun ListingCreationResult.userMessage(): String = when (this) {
    is ListingCreationResult.Verified -> "Listed #${listing.id} — verified For Sale." +
        if (cached) "" else " The statistics cache could not update; sync seller data later."
    is ListingCreationResult.Unconfirmed -> message
    is ListingCreationResult.Rejected -> message
}
