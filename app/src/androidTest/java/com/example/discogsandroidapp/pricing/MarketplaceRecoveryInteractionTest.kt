package com.example.discogsandroidapp.pricing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketplaceRecoveryInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var pricing: MarketplacePricingController

    @Test fun expiryEnablesAnExplicitRetryWithoutClaimingServerAccessHasRecovered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MarketplaceTraffic.initialize(context)
        MarketplaceTraffic.policy.restore(System.currentTimeMillis() + 180_000, 1)
        try {
            compose.setContent {
                MaterialTheme {
                    pricing = rememberMarketplacePricing(987654321)
                    if (pricing.ready) MarketplacePricingUnavailable(pricing)
                }
            }
            compose.waitUntil(5_000) { ::pricing.isInitialized && pricing.ready }
            compose.onNode(hasText("Retry in", substring = true)).assertIsNotEnabled()
            compose.runOnIdle {
                MarketplaceTraffic.policy.restore(0, 1)
                pricing.updateCooldown()
            }
            compose.onNodeWithText("Marketplace pause ended. Tap Refresh to check access again.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Open listings").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(MarketplaceUiPriceStatus.LOADING, pricing.status)
                assertTrue(pricing.needsLoad)
                assertEquals(1, pricing.attempt)
            }
            // No WebView is hosted here: retry scheduling itself must not make a website request.
            compose.onNodeWithText("Open listings").assertIsNotEnabled()
        } finally {
            MarketplaceTraffic.policy.restore(0, 0)
        }
    }
}
