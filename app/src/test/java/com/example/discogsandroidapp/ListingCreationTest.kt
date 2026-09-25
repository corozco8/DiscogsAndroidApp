package com.example.discogsandroidapp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ListingCreationTest {
    private val request = CreateListingRequest(42, "Very Good (VG)", "Generic", 12.50, "test", "For Sale")
    private val listing = InventoryListing(123, "For Sale", "Very Good (VG)", "Generic", "test",
        price = Price(12.5, "USD"), release = ListingRelease(id = 42, description = "Artist - Title", thumbnail = "", artist = "Artist", title = "Title"))

    @Test fun forSaleIsActuallySentOverTheWire() {
        val body = Json { ignoreUnknownKeys = true }.encodeToString(request)
        assertEquals("For Sale", Json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("12.5", Json.parseToJsonElement(body).jsonObject["price"]?.jsonPrimitive?.content)
    }

    @Test fun recognizesReturnedListingId() {
        assertEquals(123L, Json { ignoreUnknownKeys = true }.decodeFromString<CreateListingResponse>("""{"listing_id":123,"resource_url":"https://api.discogs.com/marketplace/listings/123"}""").listingId)
    }

    @Test fun successRequiresReadBackAndCachesTheVerifiedRow() = runBlocking {
        var posts = 0; var reads = 0; var writes = 0
        val result = createAndVerifyListing(request,
            { posts++; Response.success(201, CreateListingResponse(listingId = 123)) },
            { assertEquals(123L, it); reads++; listing },
            { assertEquals(listing, it); writes++ })
        assertTrue(result is ListingCreationResult.Verified)
        assertEquals(1, posts); assertEquals(1, reads); assertEquals(1, writes)
        assertTrue(result.userMessage().contains("#123"))
    }

    @Test fun draftIsNotReportedAsForSale() = runBlocking {
        val result = createAndVerifyListing(request,
            { Response.success(CreateListingResponse(listingId = 123)) },
            { listing.copy(status = "Draft") }, { fail("Must not cache Draft as active") })
        assertTrue(result is ListingCreationResult.Unconfirmed)
        assertTrue(result.userMessage().contains("Draft"))
    }

    @Test fun rejectionIsNotSuccessAndDoesNotReadOrCache() = runBlocking {
        for (code in listOf(400, 401, 403, 429)) {
            val result = createAndVerifyListing(request,
                { Response.error(code, "Rejected".toResponseBody()) },
                { error("Unexpected read") }, { fail("Unexpected cache") })
            assertTrue(result is ListingCreationResult.Rejected)
            assertTrue(result.userMessage().contains(code.toString()))
        }
    }

    @Test fun failedVerificationNeverResubmits() = runBlocking {
        var posts = 0
        val result = createAndVerifyListing(request,
            { posts++; Response.success(CreateListingResponse(listingId = 123)) },
            { throw java.io.IOException("GET unavailable") }, { fail("Unexpected cache") })
        assertEquals(1, posts)
        assertTrue(result is ListingCreationResult.Unconfirmed)
        assertTrue(result.userMessage().contains("#123"))
    }

    @Test fun missingAcknowledgementIsUncertain() = runBlocking {
        val result = createAndVerifyListing(request,
            { Response.success(CreateListingResponse()) },
            { error("Unexpected read") }, { fail("Unexpected cache") })
        assertTrue(result is ListingCreationResult.Unconfirmed)
    }

    @Test fun timeoutDoesNotInviteBlindRetry() = runBlocking {
        var posts = 0
        val result = createAndVerifyListing(request,
            { posts++; throw java.io.IOException("POST response lost") },
            { error("Unexpected read") }, { fail("Unexpected cache") })
        assertEquals(1, posts)
        assertTrue(result is ListingCreationResult.Unconfirmed)
        assertTrue(result.userMessage().contains("duplicate"))
    }

    @Test fun cacheFailureDoesNotTurnARealCreationIntoAFailedPost() = runBlocking {
        val result = createAndVerifyListing(request,
            { Response.success(CreateListingResponse(listingId = 123)) },
            { listing }, { throw java.io.IOException("Cache unavailable") })
        assertEquals(ListingCreationResult.Verified(listing, false), result)
        assertTrue(result.userMessage().contains("statistics cache"))
    }

    @Test fun wrongReleaseIsNotVerified() = runBlocking {
        val result = createAndVerifyListing(request,
            { Response.success(CreateListingResponse(listingId = 123)) },
            { listing.copy(release = listing.release.copy(id = 99)) }, { fail("Unexpected cache") })
        assertTrue(result is ListingCreationResult.Unconfirmed)
    }
}
