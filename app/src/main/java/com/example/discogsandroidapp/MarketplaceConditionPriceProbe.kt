package com.example.discogsandroidapp

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Condition-specific asking prices read from the public Discogs marketplace
 * page. These values are optional. The seller pricing model automatically
 * falls back to the approved VG+ multipliers if the marketplace page cannot
 * be read or if there are no matching NM/M copies for sale.
 */
data class ActiveMarketplaceConditionPrices(
    val nearMint: Double? = null,
    val nearMintPremiumSleeve: Double? = null,
    val mint: Double? = null,
    val mintSleeve: Double? = null
)

fun ReleasePriceSummary.withActiveMarketplacePrices(
    prices: ActiveMarketplaceConditionPrices
): ReleasePriceSummary {
    return copy(
        activeNearMintPrice = prices.nearMint,
        activeNearMintPremiumSleevePrice = prices.nearMintPremiumSleeve,
        activeMintPrice = prices.mint,
        activeMintSleevePrice = prices.mintSleeve
    )
}

fun marketplaceReleaseListingsUrl(
    releaseId: Long
): String {
    // limit=250 gives the on-device search/probe a useful working set while
    // price,asc keeps the page in the same lowest-price-first order requested.
    return "https://www.discogs.com/sell/list" +
            "?release_id=$releaseId" +
            "&sort=price%2Casc" +
            "&limit=250" +
            "&currency=USD"
}

@Composable
fun MarketplaceConditionPriceProbe(
    releaseId: Long,
    onPrices: (ActiveMarketplaceConditionPrices) -> Unit
) {
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val currentOnPrices = rememberUpdatedState(onPrices)

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String
                    ) {
                        super.onPageFinished(view, url)

                        // Discogs can render parts of the marketplace after the
                        // initial page-finished event, so retry a few times.
                        listOf(700L, 1600L, 3000L).forEach { delayMs ->
                            view.postDelayed(
                                {
                                    evaluateMarketplaceConditionPrices(
                                        webView = view,
                                        onPrices = currentOnPrices.value
                                    )
                                },
                                delayMs
                            )
                        }
                    }
                }
                loadUrl(marketplaceReleaseListingsUrl(releaseId))
                webViewHolder[0] = this
            }
        },
        update = { webView ->
            webViewHolder[0] = webView
        }
    )

    DisposableEffect(releaseId) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                destroy()
            }
            webViewHolder[0] = null
        }
    }
}

fun applyMarketplaceListingFilter(
    webView: WebView,
    query: String
) {
    val queryLiteral = JSONObject.quote(query.trim().lowercase())

    val script = """
        (function() {
            const q = $queryLiteral;
            const selectors = [
                'table.table_block tbody tr',
                'table tbody tr',
                'tr.shortcut_navigable',
                '[data-testid="listing-card"]',
                '[data-testid*="listing-row"]'
            ];

            const rows = [];
            const seen = new Set();
            selectors.forEach(function(selector) {
                document.querySelectorAll(selector).forEach(function(row) {
                    if (!seen.has(row)) {
                        seen.add(row);
                        rows.push(row);
                    }
                });
            });

            rows.forEach(function(row) {
                const text = (row.innerText || '').toLowerCase();
                row.style.display = (!q || text.indexOf(q) !== -1) ? '' : 'none';
            });

            return rows.length;
        })();
    """.trimIndent()

    webView.evaluateJavascript(script, null)
}

private fun evaluateMarketplaceConditionPrices(
    webView: WebView,
    onPrices: (ActiveMarketplaceConditionPrices) -> Unit
) {
    val script = """
        (function() {
            function text(el) {
                return el ? (el.textContent || '').trim() : '';
            }

            function parsePrice(raw) {
                if (!raw) return null;
                const match = raw.match(/([0-9][0-9,]*(?:\.[0-9]{1,2})?)/);
                if (!match) return null;
                const value = parseFloat(match[1].replace(/,/g, ''));
                return Number.isFinite(value) && value > 0 ? value : null;
            }

            function getMediaCondition(row) {
                const explicit = row.querySelector(
                    '.item_condition span:nth-child(3), ' +
                    '[data-testid="media-condition"], ' +
                    '[data-testid*="media-condition"]'
                );
                const value = text(explicit);
                if (value) return value;

                const all = row.innerText || '';
                const labelled = all.match(/Media Condition:?\s*([^\n]+)/i);
                if (labelled) return labelled[1].trim();

                const grade = all.match(
                    /Near Mint \(NM or M-\)|Mint \(M\)|Very Good Plus \(VG\+\)|Very Good \(VG\)|Good Plus \(G\+\)|Good \(G\)|Fair \(F\)|Poor \(P\)/
                );
                return grade ? grade[0] : '';
            }

            function getSleeveCondition(row) {
                const explicit = row.querySelector(
                    '.item_sleeve_condition, ' +
                    '[data-testid="sleeve-condition"], ' +
                    '[data-testid*="sleeve-condition"]'
                );
                const value = text(explicit);
                if (value) return value;

                const all = row.innerText || '';
                const labelled = all.match(/Sleeve Condition:?\s*([^\n]+)/i);
                return labelled ? labelled[1].trim() : '';
            }

            function getPrice(row) {
                const explicit = row.querySelector(
                    '.price, .item_price, [data-testid="price"], [data-testid*="price"]'
                );
                const explicitPrice = parsePrice(text(explicit));
                if (explicitPrice !== null) return explicitPrice;

                const all = row.innerText || '';
                const usd = all.match(/(?:US\$|\$)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i);
                return usd ? parsePrice(usd[1]) : null;
            }

            const selectors = [
                'table.table_block tbody tr',
                'table tbody tr',
                'tr.shortcut_navigable',
                '[data-testid="listing-card"]',
                '[data-testid*="listing-row"]'
            ];

            const rows = [];
            const seen = new Set();
            selectors.forEach(function(selector) {
                document.querySelectorAll(selector).forEach(function(row) {
                    if (!seen.has(row)) {
                        seen.add(row);
                        rows.push(row);
                    }
                });
            });

            const result = {
                nm: [],
                nmPremium: [],
                m: [],
                mSleeve: []
            };

            rows.forEach(function(row) {
                const media = getMediaCondition(row);
                const sleeve = getSleeveCondition(row);
                const price = getPrice(row);
                if (price === null) return;

                if (media.indexOf('Near Mint (NM or M-)') !== -1) {
                    result.nm.push(price);
                    if (
                        sleeve.indexOf('Near Mint (NM or M-)') !== -1 ||
                        sleeve.indexOf('Mint (M)') !== -1
                    ) {
                        result.nmPremium.push(price);
                    }
                } else if (media.indexOf('Mint (M)') !== -1) {
                    result.m.push(price);
                    if (sleeve.indexOf('Mint (M)') !== -1) {
                        result.mSleeve.push(price);
                    }
                }
            });

            return JSON.stringify(result);
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { rawResult ->
        try {
            if (rawResult.isNullOrBlank() || rawResult == "null") {
                return@evaluateJavascript
            }

            // evaluateJavascript returns a JSON-encoded JavaScript string.
            val decoded = JSONArray("[$rawResult]").getString(0)
            val json = JSONObject(decoded)

            fun values(name: String): List<Double> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return buildList {
                    for (index in 0 until array.length()) {
                        val value = array.optDouble(index, Double.NaN)
                        if (value.isFinite() && value > 0.0) add(value)
                    }
                }
            }

            fun median(values: List<Double>): Double? {
                if (values.isEmpty()) return null
                val sorted = values.sorted()
                val middle = sorted.size / 2
                return if (sorted.size % 2 == 1) {
                    sorted[middle]
                } else {
                    (sorted[middle - 1] + sorted[middle]) / 2.0
                }
            }

            val prices = ActiveMarketplaceConditionPrices(
                nearMint = median(values("nm")),
                nearMintPremiumSleeve = median(values("nmPremium")),
                mint = median(values("m")),
                mintSleeve = median(values("mSleeve"))
            )

            if (
                prices.nearMint != null ||
                prices.nearMintPremiumSleeve != null ||
                prices.mint != null ||
                prices.mintSleeve != null
            ) {
                onPrices(prices)
            }
        } catch (_: Exception) {
            // Best-effort only. The approved multiplier model remains active.
        }
    }
}
