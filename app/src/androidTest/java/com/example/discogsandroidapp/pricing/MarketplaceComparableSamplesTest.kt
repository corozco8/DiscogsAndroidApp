package com.example.discogsandroidapp.pricing

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discogsandroidapp.data.ReleasePriceSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MarketplaceComparableSamplesTest {
    @Test fun actualPageParserPreservesComparablesAndExcludesOurOwnListing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        // Supply a fresh local FX fixture so this test needs no external service.
        val prefs = context.getSharedPreferences("marketplace-fx-v1", 0)
        val oldRates = prefs.getString("rates", null)
        val oldFetched = prefs.getLong("fetchedAt", 0L)
        prefs.edit().putString("rates", """[{"date":"$date","base":"USD","quote":"CAD","rate":1.5}]""")
            .putLong("fetchedAt", System.currentTimeMillis()).commit()
        val loaded = CountDownLatch(1)
        val parsed = CountDownLatch(1)
        val result = AtomicReference<MarketplacePriceSample>()
        var page: WebView? = null
        val vgp = "Very Good Plus (VG+)"
        val rows = listOf(1.0, 18.0, 20.0, 22.0, 100.0, 0.01).mapIndexed { index, price ->
            """<tr><td class="item_condition">$vgp</td>
                <td class="item_sleeve_condition">$vgp</td><td class="price">US${'$'} $price</td>
                <td><a href="/sell/item/${index + 1}">View listing</a></td></tr>"""
        }.joinToString("")
        try {
            instrumentation.runOnMainSync {
                val fixture = WebView(context)
                page = fixture
                fixture.settings.javaScriptEnabled = true
                fixture.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                }
                val url = marketplaceReleaseListingsUrl(987654330)
                fixture.loadDataWithBaseURL(url, "<html><body><table class='table_block'><tbody>$rows</tbody></table></body></html>",
                    "text/html", "UTF-8", url)
            }
            assertTrue("Local page must load", loaded.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                evaluateMarketplaceConditionPriceSample(requireNotNull(page), excludedListingIds = setOf(6L)) {
                    result.set(it)
                    parsed.countDown()
                }
            }
            assertTrue("Price parser must complete", parsed.await(10, TimeUnit.SECONDS))
            val prices = result.get().prices
            assertEquals(listOf(1.0, 18.0, 20.0, 22.0, 100.0), prices.mediaPriceSamples[vgp])
            assertEquals(prices.mediaPriceSamples[vgp], prices.mediaSleevePriceSamples["$vgp||$vgp"])
            assertEquals(5, prices.mediaListingCounts[vgp])
            assertEquals(1.0, ReleasePriceSummary().withActiveMarketplacePrices(prices)
                .recommendedPriceFor(vgp, vgp)!!, 0.0)
        } finally {
            instrumentation.runOnMainSync { page?.destroy() }
            prefs.edit().putString("rates", oldRates).putLong("fetchedAt", oldFetched).commit()
        }
    }
}
