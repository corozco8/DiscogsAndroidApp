package com.example.discogsandroidapp.pricing

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MarketplaceRecoveryInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var pricing: MarketplacePricingController

    private fun showPricing(releaseId: Long = 987654321) {
        compose.setContent {
            MaterialTheme {
                pricing = rememberMarketplacePricing(releaseId)
                if (pricing.ready) {
                    Column {
                        MarketplacePricingUnavailable(pricing)
                        ListingPricingNotice(pricing.listingInfo, enabled = true,
                            onVerify = null, onRefresh = pricing::refresh)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { ::pricing.isInitialized && pricing.ready }
    }

    @Test fun rateLimitAndTimeoutAllowImmediateExplicitRetryWithoutAutomaticReloads() {
        showPricing()
        listOf(MarketplaceUiPriceStatus.RATE_LIMITED, MarketplaceUiPriceStatus.TIMEOUT,
            MarketplaceUiPriceStatus.RATE_LIMITED).forEachIndexed { index, failure ->
            compose.runOnIdle { pricing.onPageError(failure) }
            compose.onNodeWithText("Open listings").assertIsEnabled()
            compose.onNodeWithText("Refresh prices").assertIsEnabled()
            compose.onNode(hasText("Retry in", substring = true)).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(60_000)
            compose.runOnIdle {
                assertEquals(failure, pricing.status)
                assertFalse(pricing.needsLoad)
                assertEquals(index, pricing.attempt)
            }
            compose.onNodeWithText("Refresh prices").performClick()
            compose.runOnIdle {
                assertEquals(MarketplaceUiPriceStatus.LOADING, pricing.status)
                assertTrue(pricing.needsLoad)
                assertEquals(index + 1, pricing.attempt)
            }
            compose.onNodeWithText("Open listings").assertIsNotEnabled()
        }
        // No WebView is hosted, so none of these retries can make a website request.
    }

    @Test fun browsingDoesNotLoadAndSellRequestsOnlyOnce() {
        showPricing(987654326)
        compose.mainClock.advanceTimeBy(60_000)
        compose.runOnIdle {
            assertEquals(MarketplaceUiPriceStatus.IDLE, pricing.status)
            assertFalse(pricing.needsLoad)
            assertFalse(pricing.canShowPage)
            assertEquals(0, pricing.attempt)
            pricing.requestSellPrices()
            pricing.requestSellPrices()
            assertEquals(1, pricing.attempt)
            assertTrue(pricing.needsLoad)
        }
        compose.waitUntil(2_000) { pricing.canShowPage }
    }

    @Test fun freshCachedPricesDoNotLoadOnSellButVisibleListingsCanLoad() {
        val id = 987654327L
        MarketplacePricingCache.put(MarketplacePriceSnapshot(id,
            prices = ActiveMarketplaceConditionPrices(firstPagePrices = listOf(10.0, 20.0)),
            updatedAtMillis = System.currentTimeMillis()))
        showPricing(id)
        compose.runOnIdle {
            pricing.requestSellPrices()
            assertEquals(MarketplaceUiPriceStatus.CACHED, pricing.status)
            assertFalse(pricing.needsLoad)
            assertFalse(pricing.canShowPage)
            pricing.requestVisiblePage()
            assertEquals(1, pricing.attempt)
        }
        compose.waitUntil(2_000) { pricing.canShowPage }
    }

    @Test fun legacyHourLongPauseCannotBlockOpeningListings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("marketplace_traffic_v1", Context.MODE_PRIVATE)
        preferences.edit().putLong("blocked_until", System.currentTimeMillis() + 3_600_000)
            .putInt("failures", 5).commit()
        try {
            showPricing(987654322)
            compose.runOnIdle {
                assertEquals(MarketplaceUiPriceStatus.IDLE, pricing.status)
                pricing.requestVisiblePage()
            }
            compose.waitUntil(2_000) { pricing.canShowPage }
            compose.runOnIdle {
                assertTrue(pricing.needsLoad)
                assertEquals(1, pricing.attempt)
            }
            compose.onNode(hasText("Retry in", substring = true)).assertDoesNotExist()
        } finally {
            preferences.edit().remove("blocked_until").remove("failures").commit()
        }
    }

    @Test fun openListingsRetriesAnErrorAndChangingReleasesDoesNotCarryAFailureLockout() {
        showPricing(987654323)
        compose.runOnIdle { pricing.onPageError(MarketplaceUiPriceStatus.RATE_LIMITED) }
        compose.onNodeWithText("Open listings").assertIsEnabled().performClick()
        compose.waitUntil(2_000) { pricing.canShowPage }
        compose.runOnIdle {
            assertEquals(MarketplaceUiPriceStatus.LOADING, pricing.status)
            assertEquals(1, pricing.attempt)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val anotherRelease = MarketplacePricingController(987654324)
        kotlinx.coroutines.runBlocking { anotherRelease.initialize(context) }
        compose.runOnIdle {
            assertEquals(MarketplaceUiPriceStatus.IDLE, anotherRelease.status)
            assertFalse(anotherRelease.needsLoad)
            anotherRelease.requestVisiblePage()
        }
        kotlinx.coroutines.runBlocking { anotherRelease.prepareLoad() }
        compose.runOnIdle {
            assertTrue(anotherRelease.canShowPage)
            anotherRelease.dispose()
        }
    }

    @Test fun anHourLongRetryHeaderDoesNotHideOrDestroyTheDiscogsErrorPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = MarketplacePricingController(987654325)
        kotlinx.coroutines.runBlocking { controller.initialize(context) }
        compose.runOnIdle { controller.requestVisiblePage() }
        kotlinx.coroutines.runBlocking { controller.prepareLoad() }
        lateinit var page: WebView
        val url = marketplaceReleaseListingsUrl(987654325)
        val html = "<html><head><title>Discogs error</title></head><body>Discogs: Too many requests</body></html>"
        try {
            compose.runOnIdle {
                page = controller.createWebView(context, hidden = false)
                page.stopLoading()
                // An inline fixture contains no links or subresources; the emulator is offline.
                page.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
            }
            compose.setContent {
                MaterialTheme {
                    if (controller.canShowPage) {
                        MarketplacePricingWebView(controller, hidden = false, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            compose.waitUntil(5_000) { controller.status == MarketplaceUiPriceStatus.RATE_LIMITED }
            compose.runOnIdle {
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse(url)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                val response = WebResourceResponse("text/html", "UTF-8", 429, "Too Many Requests",
                    mapOf("Retry-After" to "3600"), ByteArrayInputStream(html.toByteArray()))
                page.webViewClient.onReceivedHttpError(page, request, response)
            }
            compose.waitForIdle()
            val visibleBody = AtomicReference<String?>()
            compose.runOnIdle {
                assertEquals(MarketplaceUiPriceStatus.RATE_LIMITED, controller.status)
                assertTrue(controller.canShowPage)
                val attempt = controller.attempt
                controller.requestVisiblePage()
                controller.requestSellPrices()
                assertEquals(attempt, controller.attempt)
                assertTrue(controller.errorDetails.orEmpty().contains("retry-after: 3600"))
                assertSame(page, controller.createWebView(context, hidden = false))
                page.evaluateJavascript("document.body.innerText") { visibleBody.set(it) }
            }
            compose.waitUntil(2_000) { visibleBody.get() != null }
            assertTrue(visibleBody.get().orEmpty().contains("Too many requests"))
        } finally {
            compose.runOnIdle { controller.dispose() }
        }
    }

    @Test fun aSuccessfulPageSurvivesLeavingAReleaseAndRefreshBypassesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = 987654328L
        val url = marketplaceReleaseListingsUrl(id)
        val snapshot = MarketplacePriceSnapshot(id,
            prices = ActiveMarketplaceConditionPrices(firstPagePrices = listOf(10.0, 20.0)),
            updatedAtMillis = System.currentTimeMillis())
        lateinit var original: WebView
        val fixtureReady = AtomicReference(false)
        var returned: MarketplacePricingController? = null
        try {
            compose.runOnIdle {
                MarketplacePageCache.clear()
                original = WebView(context.applicationContext)
                original.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        fixtureReady.set(finishedUrl == url)
                    }
                }
                original.loadDataWithBaseURL(url,
                    "<html><body>Cached marketplace fixture</body></html>", "text/html", "UTF-8", url)
            }
            compose.waitUntil(5_000) { fixtureReady.get() }
            compose.runOnIdle {
                MarketplacePricingCache.put(snapshot)
                MarketplacePageCache.put(id, original, snapshot)
                assertFalse(original.settings.javaScriptEnabled)
            }
            showPricing(id)
            compose.runOnIdle { pricing.requestVisiblePage() }
            compose.waitUntil(2_000) { pricing.canShowPage }
            compose.runOnIdle {
                assertSame(original, pricing.createWebView(context, hidden = false))
                assertTrue(original.settings.javaScriptEnabled)
                assertEquals(MarketplaceUiPriceStatus.CACHED, pricing.status)
                pricing.dispose()
            }
            val controller = MarketplacePricingController(id)
            returned = controller
            kotlinx.coroutines.runBlocking { controller.initialize(context) }
            compose.runOnIdle { controller.requestVisiblePage() }
            kotlinx.coroutines.runBlocking { controller.prepareLoad() }
            compose.runOnIdle {
                assertSame(original, controller.createWebView(context, hidden = false))
                controller.refresh()
            }
            kotlinx.coroutines.runBlocking { controller.prepareLoad() }
            compose.runOnIdle {
                val refreshed = controller.createWebView(context, hidden = true)
                assertNotSame(original, refreshed)
                assertTrue(url.contains("limit=50"))
                assertTrue(url.contains("page=1"))
            }
        } finally {
            compose.runOnIdle {
                if (::pricing.isInitialized) pricing.dispose()
                returned?.dispose()
                MarketplacePageCache.clear()
            }
        }
    }
}
