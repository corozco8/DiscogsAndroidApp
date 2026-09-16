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
 * Lowest live Discogs Marketplace asking prices discovered from the current
 * release's marketplace listings page.
 *
 * mediaLowest:
 *   "Very Good Plus (VG+)" -> 30.00
 *
 * mediaSleeveLowest:
 *   "Very Good Plus (VG+)||Very Good Plus (VG+)" -> 36.00
 *
 * This is intentionally best-effort. If Discogs changes its marketplace page
 * markup or no matching listing exists, ReleasePriceSummary automatically
 * falls back to the app's existing pricing algorithm.
 */
fun ReleasePriceSummary.withActiveMarketplacePrices(
    prices: ActiveMarketplaceConditionPrices
): ReleasePriceSummary {
    return copy(
        // Keep these populated for compatibility with the older premium-grade
        // pricing code/fallback paths.
        activeNearMintPrice = prices.nearMint,
        activeNearMintPremiumSleevePrice =
            prices.nearMintPremiumSleeve,
        activeMintPrice = prices.mint,
        activeMintSleevePrice = prices.mintSleeve,

        // New generic live-price maps used by every media/sleeve grade.
        activeMediaLowestPrices = prices.mediaLowest,
        activeMediaSleeveLowestPrices =
            prices.mediaSleeveLowest
    )
}

fun marketplaceReleaseListingsUrl(
    releaseId: Long
): String {
    /*
     * Keep the marketplace sorted lowest-price-first. The probe still computes
     * minima itself, but sorting this way means useful pricing data appears as
     * early as possible while the page is loading.
     */
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
    val webViewHolder =
        remember(releaseId) {
            arrayOfNulls<WebView>(1)
        }

    val currentOnPrices =
        rememberUpdatedState(onPrices)

    // Discogs can progressively render/replace marketplace rows. Keep the
    // lowest price seen for every grade across every probe pass so a later,
    // partial DOM update cannot erase a valid lower price found earlier.
    val accumulatedMediaLowest =
        remember(releaseId) {
            mutableMapOf<String, Double>()
        }

    val accumulatedMediaSleeveLowest =
        remember(releaseId) {
            mutableMapOf<String, Double>()
        }

    fun mergeAndEmit(
        prices: ActiveMarketplaceConditionPrices
    ) {
        prices.mediaLowest.forEach { (grade, price) ->
            val oldPrice = accumulatedMediaLowest[grade]
            if (oldPrice == null || price < oldPrice) {
                accumulatedMediaLowest[grade] = price
            }
        }

        prices.mediaSleeveLowest.forEach { (key, price) ->
            val oldPrice = accumulatedMediaSleeveLowest[key]
            if (oldPrice == null || price < oldPrice) {
                accumulatedMediaSleeveLowest[key] = price
            }
        }

        currentOnPrices.value(
            ActiveMarketplaceConditionPrices(
                mediaLowest = accumulatedMediaLowest.toMap(),
                mediaSleeveLowest =
                    accumulatedMediaSleeveLowest.toMap()
            )
        )
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {
                            super.onPageFinished(
                                view,
                                url
                            )

                            /*
                             * Discogs can render marketplace rows after the
                             * initial page-finished callback. Probe repeatedly
                             * so pricing becomes available as soon as possible
                             * and then gets more complete as the page settles.
                             */
                            listOf(
                                350L,
                                800L,
                                1600L,
                                2800L,
                                4500L
                            ).forEach { delayMs ->
                                view.postDelayed(
                                    {
                                        evaluateMarketplaceConditionPrices(
                                            webView = view,
                                            onPrices = ::mergeAndEmit
                                        )
                                    },
                                    delayMs
                                )
                            }
                        }
                    }

                loadUrl(
                    marketplaceReleaseListingsUrl(
                        releaseId
                    )
                )

                webViewHolder[0] = this
            }
        },
        update = { webView ->
            webViewHolder[0] = webView
        }
    )

    DisposableEffect(releaseId) {
        onDispose {
            webViewHolder[0]
                ?.apply {
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
    val queryLiteral =
        JSONObject.quote(
            query.trim().lowercase()
        )

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
                row.style.display =
                    (!q || text.indexOf(q) !== -1)
                        ? ''
                        : 'none';
            });

            return rows.length;
        })();
    """.trimIndent()

    webView.evaluateJavascript(
        script,
        null
    )
}

fun evaluateMarketplaceConditionPrices(
    webView: WebView,
    onPrices: (ActiveMarketplaceConditionPrices) -> Unit
) {
    val script = """
        (function() {
            const MEDIA_GRADES = [
                'Near Mint (NM or M-)',
                'Very Good Plus (VG+)',
                'Very Good (VG)',
                'Good Plus (G+)',
                'Good (G)',
                'Fair (F)',
                'Poor (P)',
                'Mint (M)'
            ];

            const SLEEVE_GRADES = [
                'Near Mint (NM or M-)',
                'Very Good Plus (VG+)',
                'Very Good (VG)',
                'Good Plus (G+)',
                'Good (G)',
                'Fair (F)',
                'Poor (P)',
                'Mint (M)',
                'Not Graded',
                'No Cover',
                'Generic'
            ];

            const VG_OR_HIGHER_MEDIA = new Set([
                'Very Good (VG)',
                'Very Good Plus (VG+)',
                'Near Mint (NM or M-)',
                'Mint (M)'
            ]);

            const REJECTED_SLEEVES_FOR_VG_OR_HIGHER = new Set([
                'Fair (F)',
                'Poor (P)',
                'Not Graded',
                'No Cover'
            ]);

            function text(el) {
                return el
                    ? (el.textContent || '').trim()
                    : '';
            }

            function parsePrice(raw) {
                if (!raw) return null;

                const match = raw.match(
                    /([0-9][0-9,]*(?:\.[0-9]{1,2})?)/
                );

                if (!match) return null;

                const value =
                    parseFloat(
                        match[1].replace(/,/g, '')
                    );

                return Number.isFinite(value) &&
                    value > 0
                        ? value
                        : null;
            }

            function canonicalGrade(raw, grades) {
                if (!raw) return '';

                // Choose the grade that appears first in the source text,
                // not the first grade in our lookup array. This matters for
                // rows such as Media: Good (G) / Sleeve: Good Plus (G+).
                // The old lookup-order behavior could incorrectly classify
                // that row as G+ because G+ was checked before G.
                let bestGrade = '';
                let bestIndex = Number.MAX_SAFE_INTEGER;

                for (
                    let i = 0;
                    i < grades.length;
                    i++
                ) {
                    const index = raw.indexOf(grades[i]);

                    if (
                        index !== -1 &&
                        index < bestIndex
                    ) {
                        bestIndex = index;
                        bestGrade = grades[i];
                    }
                }

                return bestGrade;
            }

            function labelledValue(all, label) {
                const pattern = new RegExp(
                    label +
                    '(?:\\s+Condition)?\\s*:\\s*([^\\n]+)',
                    'i'
                );

                const match = all.match(pattern);
                return match ? match[1].trim() : '';
            }

            function getMediaCondition(row) {
                const all =
                    row.innerText ||
                    row.textContent ||
                    '';

                // Current Discogs rows use "Media:". Older layouts can use
                // "Media Condition:". Parse the labelled media line first so
                // the sleeve grade can never be mistaken for the media grade.
                const labelled =
                    labelledValue(all, 'Media');

                const fromLabel =
                    canonicalGrade(
                        labelled,
                        MEDIA_GRADES
                    );

                if (fromLabel) {
                    return fromLabel;
                }

                const explicit =
                    row.querySelector(
                        '[data-testid="media-condition"], ' +
                        '[data-testid*="media-condition"], ' +
                        '.item_condition'
                    );

                const explicitText = text(explicit);
                const explicitLabel =
                    labelledValue(
                        explicitText,
                        'Media'
                    );

                const fromExplicitLabel =
                    canonicalGrade(
                        explicitLabel,
                        MEDIA_GRADES
                    );

                if (fromExplicitLabel) {
                    return fromExplicitLabel;
                }

                return canonicalGrade(
                    explicitText,
                    MEDIA_GRADES
                );
            }

            function getSleeveCondition(row) {
                const all =
                    row.innerText ||
                    row.textContent ||
                    '';

                const labelled =
                    labelledValue(all, 'Sleeve');

                const fromLabel =
                    canonicalGrade(
                        labelled,
                        SLEEVE_GRADES
                    );

                if (fromLabel) {
                    return fromLabel;
                }

                const explicit =
                    row.querySelector(
                        '[data-testid="sleeve-condition"], ' +
                        '[data-testid*="sleeve-condition"], ' +
                        '.item_sleeve_condition'
                    );

                return canonicalGrade(
                    text(explicit),
                    SLEEVE_GRADES
                );
            }

            function getPrice(row) {
                const explicit =
                    row.querySelector(
                        '.price, ' +
                        '.item_price, ' +
                        '[data-testid="price"], ' +
                        '[data-testid*="price"]'
                    );

                const explicitPrice =
                    parsePrice(
                        text(explicit)
                    );

                if (
                    explicitPrice !== null
                ) {
                    return explicitPrice;
                }

                const all =
                    row.innerText ||
                    row.textContent ||
                    '';

                const usd =
                    all.match(
                        /(?:US\$|\$)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i
                    );

                return usd
                    ? parsePrice(usd[1])
                    : null;
            }

            function setMin(
                target,
                key,
                price
            ) {
                if (!key) return;

                if (
                    target[key] === undefined ||
                    price < target[key]
                ) {
                    target[key] = price;
                }
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
                document.querySelectorAll(selector)
                    .forEach(function(row) {
                        if (!seen.has(row)) {
                            seen.add(row);
                            rows.push(row);
                        }
                    });
            });

            const result = {
                media: {},
                pairs: {}
            };

            rows.forEach(function(row) {
                const media =
                    getMediaCondition(row);

                const sleeve =
                    getSleeveCondition(row);

                const price =
                    getPrice(row);

                if (
                    !media ||
                    price === null
                ) {
                    return;
                }

                // Always keep the exact media+sleeve price. That lets an
                // explicit seller selection of F, P, No Cover, or Not Graded
                // use a true like-for-like live listing.
                if (sleeve) {
                    setMin(
                        result.pairs,
                        media + '||' + sleeve,
                        price
                    );
                }

                // For the normal media-grade recommendation, VG or better
                // ignores severely compromised/missing sleeves. If the seller
                // explicitly picks one of those sleeves, ReleasePriceSummary
                // uses the exact pair preserved above instead.
                if (
                    VG_OR_HIGHER_MEDIA.has(media) &&
                    REJECTED_SLEEVES_FOR_VG_OR_HIGHER.has(sleeve)
                ) {
                    return;
                }

                setMin(
                    result.media,
                    media,
                    price
                );
            });

            return JSON.stringify(result);
        })();
    """.trimIndent()

    webView.evaluateJavascript(
        script
    ) { rawResult ->
        try {
            if (
                rawResult.isNullOrBlank() ||
                rawResult == "null"
            ) {
                return@evaluateJavascript
            }

            /*
             * evaluateJavascript returns a JSON-encoded JavaScript string.
             */
            val decoded =
                JSONArray(
                    "[$rawResult]"
                ).getString(0)

            val json =
                JSONObject(decoded)

            fun readPriceMap(
                objectName: String
            ): Map<String, Double> {
                val source =
                    json.optJSONObject(
                        objectName
                    )
                        ?: return emptyMap()

                return buildMap {
                    val keys =
                        source.keys()

                    while (keys.hasNext()) {
                        val key =
                            keys.next()

                        val value =
                            source.optDouble(
                                key,
                                Double.NaN
                            )

                        if (
                            value.isFinite() &&
                            value > 0.0
                        ) {
                            put(
                                key,
                                value
                            )
                        }
                    }
                }
            }

            val prices =
                ActiveMarketplaceConditionPrices(
                    mediaLowest =
                        readPriceMap(
                            "media"
                        ),
                    mediaSleeveLowest =
                        readPriceMap(
                            "pairs"
                        )
                )

            if (
                prices.mediaLowest.isNotEmpty() ||
                prices.mediaSleeveLowest.isNotEmpty()
            ) {
                onPrices(prices)
            }
        } catch (_: Exception) {
            /*
             * Best-effort live pricing only. The existing pricing algorithm
             * remains available automatically.
             */
        }
    }
}
