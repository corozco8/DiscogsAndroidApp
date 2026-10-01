package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplaceResponseDiagnosticsTest {
    @Test fun includesCaseInsensitiveSupportHeadersButExcludesCredentials() {
        val details = marketplaceErrorDetails("https://www.discogs.com/sell/release/123", 429,
            mapOf("CF-Ray" to "example-ray", "Retry-After" to "3600", "Server" to "cloudflare",
                "Set-Cookie" to "secret-cookie", "Authorization" to "secret-token"), "2026-10-01T12:00:00Z")
        assertTrue(details.contains("HTTP status: 429"))
        assertTrue(details.contains("cf-ray: example-ray"))
        assertTrue(details.contains("retry-after: 3600"))
        assertTrue(details.contains("2026-10-01T12:00:00Z"))
        assertFalse(details.contains("secret"))
    }
}
