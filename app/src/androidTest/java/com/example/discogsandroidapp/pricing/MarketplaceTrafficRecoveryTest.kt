package com.example.discogsandroidapp.pricing

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class MarketplaceTrafficRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val grade = "Very Good Plus (VG+)"

    private fun initialized(id: Long, policy: MarketplaceLoadPolicy) =
        MarketplacePricingController(id, policy).also { controller ->
            runBlocking { controller.initialize(context) }
        }

    private fun prepare(controller: MarketplacePricingController) = runBlocking {
        withContext(Dispatchers.Main) { controller.prepareLoad() }
    }

    @Test fun blockStopsAutomaticChecksAcrossRecordsButManualRetryStaysEnabled() {
        val policy = MarketplaceLoadPolicy()
        val first = initialized(987654340, policy)
        val next = initialized(987654341, policy)
        try {
            compose.runOnIdle {
                first.onPageError(MarketplaceUiPriceStatus.RATE_LIMITED)
                next.requestSellPrices()
                assertEquals(MarketplaceUiPriceStatus.AUTOMATIC_PAUSED, next.status)
                assertFalse(next.needsLoad)
                assertFalse(next.canShowPage)
                assertEquals(0, next.attempt)
            }
            compose.setContent {
                MaterialTheme {
                    ListingPricingNotice(next.listingInfo, true, null, next::refresh)
                }
            }
            compose.onNodeWithText("Check live price").assertIsEnabled().performClick()
            prepare(next)
            compose.runOnIdle {
                assertTrue(next.canShowPage)
                assertTrue(policy.automaticChecksSuspended)
                next.onPageError(MarketplaceUiPriceStatus.BLOCKED)
                assertTrue(policy.automaticChecksSuspended)
            }
        } finally { compose.runOnIdle { first.dispose(); next.dispose() } }
    }

    @Test fun freshCachedPricesStillWorkWhileAutomaticChecksAreSuspended() {
        val policy = MarketplaceLoadPolicy().apply { recordBlock() }
        val id = 987654342L
        MarketplacePricingCache.put(MarketplacePriceSnapshot(id,
            prices = ActiveMarketplaceConditionPrices(mediaLowest = mapOf(grade to 10.0),
                mediaListingCounts = mapOf(grade to 2)), updatedAtMillis = System.currentTimeMillis()))
        val controller = initialized(id, policy)
        try {
            compose.runOnIdle {
                controller.requestSellPrices()
                assertEquals(MarketplaceUiPriceStatus.CACHED, controller.status)
                assertFalse(controller.needsLoad)
                assertEquals(10.0, controller.prices!!.mediaLowest[grade]!!, 0.0)
                assertTrue(policy.automaticChecksSuspended)
            }
        } finally { compose.runOnIdle { controller.dispose() } }
    }

    @Test fun automaticSellWaitsForSpacingAndManualCheckingCanBypassIt() {
        val clock = AtomicLong(1_000_000L)
        val policy = MarketplaceLoadPolicy(clock::get)
        assertTrue(policy.reserveLoad(explicit = true))
        val controller = initialized(987654343, policy)
        try {
            compose.runOnIdle { controller.requestSellPrices() }
            runBlocking {
                withContext(Dispatchers.Main) {
                    assertNull(withTimeoutOrNull(750) { controller.prepareLoad(); true })
                }
            }
            compose.runOnIdle {
                assertFalse(controller.canShowPage)
                controller.requestVisiblePage()
            }
            prepare(controller)
            compose.runOnIdle { assertTrue(controller.canShowPage) }
        } finally { compose.runOnIdle { controller.dispose() } }
    }

    @Test fun closingSellCancelsAQueuedAutomaticCheck() {
        val policy = MarketplaceLoadPolicy()
        val controller = initialized(987654344, policy)
        try {
            compose.runOnIdle {
                controller.requestSellPrices()
                controller.cancelAutomaticCheck()
            }
            prepare(controller)
            compose.runOnIdle {
                assertEquals(MarketplaceUiPriceStatus.IDLE, controller.status)
                assertFalse(controller.needsLoad)
                assertFalse(controller.canShowPage)
            }
        } finally { compose.runOnIdle { controller.dispose() } }
    }

    private fun loadFixture(controller: MarketplacePricingController, id: Long): WebView {
        lateinit var page: WebView
        compose.setContent {
            AndroidView(factory = { hostContext ->
                FrameLayout(hostContext).apply {
                    // The scanner schedules work through View.postDelayed, which needs an attached host.
                    page = controller.createWebView(hostContext, hidden = true)
                    addView(page, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    page.stopLoading()
                    val rows = listOf(10.0, 15.0).mapIndexed { index, amount ->
                        """<tr><td class="item_condition">$grade</td>
                            <td class="item_sleeve_condition">$grade</td><td class="price">US${'$'} $amount</td>
                            <td><a href="/sell/item/${index + 100}">View listing</a></td></tr>"""
                    }.joinToString("")
                    val url = marketplaceReleaseListingsUrl(id)
                    page.loadDataWithBaseURL(url,
                        "<html><body><table class='table_block'><tbody>$rows</tbody></table></body></html>",
                        "text/html", "UTF-8", url)
                }
            })
        }
        compose.waitUntil(15_000) { controller.status == MarketplaceUiPriceStatus.FRESH }
        return page
    }

    @Test fun hiddenPageStopsNetworkWorkAndManualSuccessResumesAutomaticChecks() {
        val policy = MarketplaceLoadPolicy().apply { recordBlock() }
        val id = 987654345L
        val controller = initialized(id, policy)
        try {
            compose.runOnIdle { controller.refresh() }
            prepare(controller)
            val page = loadFixture(controller, id)
            compose.runOnIdle {
                assertFalse(policy.automaticChecksSuspended)
                assertFalse(controller.needsLoad)
                assertFalse(page.settings.javaScriptEnabled)
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("https://www.discogs.com/local-test-resource.js")
                    override fun isForMainFrame() = false
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                assertNotNull(page.webViewClient.shouldInterceptRequest(page, request))
                val attempt = controller.attempt
                policy.recordBlock()
                controller.requestVisiblePage()
                assertSame(page, controller.createWebView(context, hidden = false))
                assertTrue(page.settings.javaScriptEnabled)
                assertNull(page.webViewClient.shouldInterceptRequest(page, request))
                assertEquals(attempt, controller.attempt)
                assertTrue("Viewing a cached page does not prove server recovery", policy.automaticChecksSuspended)
                (page.parent as? ViewGroup)?.removeView(page)
                controller.onPageDetached(page)
                assertFalse(page.settings.javaScriptEnabled)
            }
        } finally { compose.runOnIdle { controller.dispose(); MarketplacePageCache.clear() } }
    }

    @Test fun anAutomaticCheckFinishingAfterABlockDoesNotResumeAutomaticChecks() {
        val policy = MarketplaceLoadPolicy()
        val id = 987654346L
        val controller = initialized(id, policy)
        try {
            compose.runOnIdle { controller.requestSellPrices() }
            prepare(controller)
            policy.recordBlock()
            loadFixture(controller, id)
            compose.runOnIdle { assertTrue(policy.automaticChecksSuspended) }
        } finally { compose.runOnIdle { controller.dispose(); MarketplacePageCache.clear() } }
    }
}
