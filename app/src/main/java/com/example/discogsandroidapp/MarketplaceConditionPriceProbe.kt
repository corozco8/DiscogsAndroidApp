package com.example.discogsandroidapp

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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
private const val MARKETPLACE_READY_SCAN_DEADLINE_MS = 7_000L
private const val MARKETPLACE_READY_SCAN_INTERVAL_MS = 300L
private const val MARKETPLACE_MIN_SETTLE_MS = 900L
private const val MARKETPLACE_STABLE_SAMPLES_REQUIRED = 2

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
            prices.mediaSleeveLowest,
        activeMediaListingCounts = prices.mediaListingCounts,
        activeMediaSleeveListingCounts = prices.mediaSleeveListingCounts
    )
}

fun marketplaceUrlBelongsToRelease(
    url: String?,
    releaseId: Long
): Boolean {
    if (url.isNullOrBlank()) return false

    val uri = runCatching { Uri.parse(url) }.getOrNull()
        ?: return false

    val host = uri.host?.lowercase()
    if (uri.scheme != "https" || host !in setOf("discogs.com", "www.discogs.com")) {
        return false
    }

    val path = uri.path?.trimEnd('/') ?: return false

    return when {
        path == "/sell/list" ->
            uri.getQueryParameter("release_id") == releaseId.toString()

        path == "/sell/release/$releaseId" -> true

        else -> false
    }
}

fun marketplaceReleaseListingsUrl(
    releaseId: Long,
    page: Int = 1,
    limit: Int = 250,
    condition: String? = null
): String {
    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceIn(25, 250)
    val conditionQuery =
        condition
            ?.takeIf { it.isNotBlank() }
            ?.let {
                "&condition=" +
                        URLEncoder.encode(it, "UTF-8")
            }
            .orEmpty()

    return "https://www.discogs.com/sell/list" +
            "?release_id=$releaseId" +
            conditionQuery +
            "&sort=price%2Casc" +
            "&limit=$safeLimit" +
            "&page=$safePage"
}

data class MarketplacePriceSample(
    val prices: ActiveMarketplaceConditionPrices,
    val rowCount: Int,
    val emptyConfirmed: Boolean,
    val blocked: Boolean = false
)

fun evaluateMarketplaceConditionPriceSample(
    webView: WebView,
    acceptResult: () -> Boolean = { true },
    excludedListingIds: Set<Long> = emptySet(),
    onResult: (MarketplacePriceSample) -> Unit
) {
    MarketplaceExchangeRates.prepare(webView.context) { rates ->
        if (acceptResult()) evaluateMarketplaceSampleWithRates(webView, acceptResult, excludedListingIds, rates, onResult)
    }
}

private fun evaluateMarketplaceSampleWithRates(
    webView: WebView,
    acceptResult: () -> Boolean,
    excludedListingIds: Set<Long>,
    usdRates: Map<String, Double>,
    onResult: (MarketplacePriceSample) -> Unit
) {
    val script = """
        (function() {
            const USD_RATES = ${JSONObject(usdRates).toString()};
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

            function text(el) {
                return el ? (el.textContent || '').trim() : '';
            }

            function parseUsdPrice(raw, currencyHint) {
                if (!raw) return null;
                let valueText = String(raw).replace(/[\u00a0\u202f]/g, ' ').trim();
                let currency = null;
                const aliases = [
                    ['US$', 'USD'], ['CA$', 'CAD'], ['C$', 'CAD'],
                    ['AU$', 'AUD'], ['A$', 'AUD'], ['NZ$', 'NZD'],
                    ['HK$', 'HKD'], ['SG$', 'SGD'], ['S$', 'SGD'],
                    ['MX$', 'MXN'], ['R$', 'BRL'], ['JP¥', 'JPY'],
                    ['€', 'EUR'], ['£', 'GBP']
                ];
                const code = valueText.match(/^([A-Z]{3})\s*/i) || valueText.match(/\s*([A-Z]{3})$/i);
                if (code) {
                    currency = code[1].toUpperCase();
                    valueText = valueText.replace(code[0], '').trim();
                } else {
                    for (const pair of aliases) {
                        if (valueText.startsWith(pair[0])) {
                            currency = pair[1]; valueText = valueText.slice(pair[0].length).trim(); break;
                        }
                        if (valueText.endsWith(pair[0])) {
                            currency = pair[1]; valueText = valueText.slice(0, -pair[0].length).trim(); break;
                        }
                    }
                }
                const hint = String(currencyHint || '').toUpperCase();
                if (currency && hint && currency !== hint) return null;
                if (!currency && /^[A-Z]{3}$/.test(hint)) {
                    currency = hint;
                    // Bare symbols require explicit row currency metadata.
                    if (valueText.startsWith('$') && /^(USD|CAD|AUD|NZD|HKD|SGD|MXN)$/.test(currency)) valueText = valueText.slice(1).trim();
                    if (valueText.startsWith('¥') && /^(JPY|CNY)$/.test(currency)) valueText = valueText.slice(1).trim();
                }
                if (!currency || !Number.isFinite(USD_RATES[currency]) || USD_RATES[currency] <= 0) return null;

                // Validate the entire amount; accept grouped and localized decimals.
                if (/^(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?$/.test(valueText)) {
                    valueText = valueText.replace(/,/g, '');
                } else if (/^(?:\d{1,3}(?:\.\d{3})+|\d+),\d{1,2}$/.test(valueText)) {
                    valueText = valueText.replace(/\./g, '').replace(',', '.');
                } else if (/^\d{1,3}(?: \d{3})+(?:[.,]\d{1,2})?$/.test(valueText)) {
                    valueText = valueText.replace(/ /g, '').replace(',', '.');
                } else return null;
                const amount = Number(valueText);
                const usd = amount * USD_RATES[currency];
                return Number.isFinite(usd) && usd > 0 ? usd : null;
            }

            function canonicalGrade(raw, grades) {
                if (!raw) return '';
                let bestGrade = '';
                let bestIndex = Number.MAX_SAFE_INTEGER;

                for (let i = 0; i < grades.length; i++) {
                    const index = raw.indexOf(grades[i]);
                    if (index !== -1 && index < bestIndex) {
                        bestIndex = index;
                        bestGrade = grades[i];
                    }
                }

                return bestGrade;
            }

            function labelledValue(all, label) {
                const pattern = new RegExp(
                    label + '(?:\\s+Condition)?\\s*:\\s*([^\\n]+)',
                    'i'
                );
                const match = all.match(pattern);
                return match ? match[1].trim() : '';
            }

            function getMediaCondition(row) {
                const all = row.innerText || row.textContent || '';
                const labelled = labelledValue(all, 'Media');
                const fromLabel = canonicalGrade(labelled, MEDIA_GRADES);
                if (fromLabel) return fromLabel;

                const explicit = row.querySelector(
                    '[data-testid="media-condition"], ' +
                    '[data-testid*="media-condition"], ' +
                    '.item_condition'
                );

                const explicitText = text(explicit);
                const explicitLabel = labelledValue(explicitText, 'Media');
                const fromExplicitLabel = canonicalGrade(explicitLabel, MEDIA_GRADES);
                if (fromExplicitLabel) return fromExplicitLabel;
                return canonicalGrade(explicitText, MEDIA_GRADES);
            }

            function getSleeveCondition(row) {
                const all = row.innerText || row.textContent || '';
                const labelled = labelledValue(all, 'Sleeve');
                const fromLabel = canonicalGrade(labelled, SLEEVE_GRADES);
                if (fromLabel) return fromLabel;

                const explicit = row.querySelector(
                    '[data-testid="sleeve-condition"], ' +
                    '[data-testid*="sleeve-condition"], ' +
                    '.item_sleeve_condition'
                );
                return canonicalGrade(text(explicit), SLEEVE_GRADES);
            }

            function getPrice(row) {
                const explicit = row.querySelector(
                    '.price, ' +
                    '.item_price, ' +
                    '[data-testid="price"], ' +
                    '[data-testid*="price"]'
                );

                const currencyElement = row.querySelector('[itemprop="priceCurrency"], [data-currency]');
                const currencyHint = (explicit && explicit.getAttribute('data-currency')) ||
                    row.getAttribute('data-currency') ||
                    (currencyElement && (currencyElement.getAttribute('content') || currencyElement.getAttribute('data-currency'))) || '';
                const explicitText = text(explicit);
                if (explicitText && !/(?:shipping|postage|tax|subtotal|total)/i.test(explicitText)) {
                    const explicitPrice = parseUsdPrice(explicitText, currencyHint);
                    if (explicitPrice !== null) return explicitPrice;
                }

                // Fallback only to complete row lines that are themselves a currency
                // amount. Never scrape a numeric substring from shipping/total text.
                const all = row.innerText || row.textContent || '';
                const lines = all.split(/\n+/)
                    .map(function(line) { return line.trim(); })
                    .filter(Boolean);

                for (let i = 0; i < lines.length; i++) {
                    const line = lines[i];
                    if (/(?:shipping|postage|tax|subtotal|total)/i.test(line)) continue;
                    const price = parseUsdPrice(line, currencyHint);
                    if (price !== null) return price;
                }

                return null;
            }

            function setMin(target, key, price) {
                if (!key) return;
                if (target[key] === undefined || price < target[key]) {
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
                document.querySelectorAll(selector).forEach(function(row) {
                    if (!seen.has(row)) {
                        seen.add(row);
                        rows.push(row);
                    }
                });
            });

            const result = {
                media: {},
                pairs: {},
                mediaCounts: {},
                pairCounts: {},
                rowCount: rows.length,
                emptyConfirmed: false
            };

            const excludedListingIds = new Set(${excludedListingIds.joinToString(prefix = "[", postfix = "]")}.map(String));
            const countedListings = new Set();
            rows.forEach(function(row) {
                const media = getMediaCondition(row);
                const sleeve = getSleeveCondition(row);
                const price = getPrice(row);
                if (!media || price === null) return;

                // Responsive layouts may expose the same listing more than once.
                const link = row.querySelector('a[href*="/sell/item/"]');
                const match = link && (link.getAttribute('href') || '').match(/\/sell\/item\/(\d+)/);
                const explicitId = row.getAttribute('data-listing-id') || row.getAttribute('data-item-id');
                const listingId = match ? match[1] : (/^\d+$/.test(explicitId || '') ? explicitId : null);
                // When excluding a seller inventory, unidentified rows cannot
                // safely be treated as competitors.
                if (excludedListingIds.size > 0 && (!listingId || excludedListingIds.has(listingId))) return;
                if (listingId) {
                    if (countedListings.has(listingId)) return;
                    countedListings.add(listingId);
                }

                setMin(result.media, media, price);
                result.mediaCounts[media] = (result.mediaCounts[media] || 0) + 1;
                if (sleeve) {
                    setMin(result.pairs, media + '||' + sleeve, price);
                    const pair = media + '||' + sleeve;
                    result.pairCounts[pair] = (result.pairCounts[pair] || 0) + 1;
                }
            });

            if (rows.length === 0) {
                const bodyText = (document.body && document.body.innerText || '').toLowerCase();
                result.blocked = /verify you are human|checking your browser|access denied|just a moment|enable javascript and cookies to continue/.test(bodyText);
                result.emptyConfirmed =
                    /no (?:items|copies|listings).*for sale/.test(bodyText) ||
                    /0 copies for sale/.test(bodyText);
            }

            return JSON.stringify(result);
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { rawResult ->
        if (!acceptResult()) return@evaluateJavascript

        try {
            if (rawResult.isNullOrBlank() || rawResult == "null") {
                onResult(MarketplacePriceSample(ActiveMarketplaceConditionPrices(), 0, false))
                return@evaluateJavascript
            }

            val decoded = JSONArray("[$rawResult]").getString(0)
            val json = JSONObject(decoded)

            fun readPriceMap(objectName: String): Map<String, Double> {
                val source = json.optJSONObject(objectName) ?: return emptyMap()
                return buildMap {
                    val keys = source.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = source.optDouble(key, Double.NaN)
                        if (value.isFinite() && value > 0.0) {
                            put(key, value)
                        }
                    }
                }
            }

            fun readCountMap(objectName: String): Map<String, Int> {
                val source = json.optJSONObject(objectName) ?: return emptyMap()
                return buildMap {
                    val keys = source.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val count = source.optInt(key, 0)
                        if (count > 0) put(key, count)
                    }
                }
            }

            if (!acceptResult()) return@evaluateJavascript

            onResult(
                MarketplacePriceSample(
                    prices = ActiveMarketplaceConditionPrices(
                        mediaLowest = readPriceMap("media"),
                        mediaSleeveLowest = readPriceMap("pairs"),
                        mediaListingCounts = readCountMap("mediaCounts"),
                        mediaSleeveListingCounts = readCountMap("pairCounts")
                    ),
                    rowCount = json.optInt("rowCount", 0),
                    emptyConfirmed = json.optBoolean("emptyConfirmed", false),
                    blocked = json.optBoolean("blocked", false)
                )
            )
        } catch (_: Exception) {
            onResult(MarketplacePriceSample(ActiveMarketplaceConditionPrices(), 0, false))
        }
    }
}

fun evaluateMarketplaceConditionPrices(
    webView: WebView,
    onPrices: (ActiveMarketplaceConditionPrices) -> Unit,
    onRowCount: ((Int) -> Unit)? = null,
    acceptResult: () -> Boolean = { true }
) {
    evaluateMarketplaceConditionPriceSample(
        webView = webView,
        acceptResult = acceptResult
    ) { sample ->
        if (
            sample.prices.mediaLowest.isNotEmpty() ||
            sample.prices.mediaSleeveLowest.isNotEmpty()
        ) {
            onPrices(sample.prices)
        }
        onRowCount?.invoke(sample.rowCount)
    }
}

/**
 * Read the marketplace until the DOM has stopped changing, instead of probing
 * at a fixed list of times. Fast pages finish quickly; slow pages may continue
 * until the bounded deadline. Results are revalidated inside the JavaScript
 * callback so a late result from an old release/navigation is discarded.
 */
fun beginMarketplacePriceScan(
    webView: WebView,
    releaseId: Long,
    page: Int = 1,
    isCurrent: () -> Boolean,
    onPrices: (ActiveMarketplaceConditionPrices) -> Unit,
    onStatus: (MarketplaceUiPriceStatus) -> Unit = {}
) {
    val startedAt = SystemClock.elapsedRealtime()
    var lastSignature: String? = null
    var stableSamples = 0
    var lastGoodPrices: ActiveMarketplaceConditionPrices? = null
    var finished = false

    fun finish(status: MarketplaceUiPriceStatus) {
        if (finished) return
        finished = true
        onStatus(status)
    }

    fun sampleSignature(sample: MarketplacePriceSample): String {
        return buildString {
            append(sample.rowCount)
            append('|')
            sample.prices.mediaLowest.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(';')
            }
            append('|')
            sample.prices.mediaSleeveLowest.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(';')
            }
            append('|').append(sample.emptyConfirmed)
            append('|').append(sample.prices.mediaListingCounts.toSortedMap())
            append('|').append(sample.prices.mediaSleeveListingCounts.toSortedMap())
        }
    }

    fun scheduleNext(scan: () -> Unit) {
        if (!finished) {
            webView.postDelayed(scan, MARKETPLACE_READY_SCAN_INTERVAL_MS)
        }
    }

    lateinit var scan: () -> Unit
    scan = {
        if (finished || !isCurrent()) {
            finished = true
        } else if (!marketplaceUrlBelongsToRelease(webView.url, releaseId)) {
            finish(MarketplaceUiPriceStatus.UNREADABLE)
        } else {
            evaluateMarketplaceConditionPriceSample(
                webView = webView,
                acceptResult = {
                    !finished &&
                        isCurrent() &&
                        marketplaceUrlBelongsToRelease(webView.url, releaseId)
                }
            ) { sample ->
                if (finished || !isCurrent()) return@evaluateMarketplaceConditionPriceSample

                val signature = sampleSignature(sample)
                stableSamples =
                    if (signature == lastSignature) stableSamples + 1 else 0
                lastSignature = signature

                if (
                    sample.prices.mediaLowest.isNotEmpty() ||
                    sample.prices.mediaSleeveLowest.isNotEmpty()
                ) {
                    lastGoodPrices = sample.prices
                    onPrices(sample.prices)
                }

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val stableEnough =
                    sample.rowCount > 0 &&
                    elapsed >= MARKETPLACE_MIN_SETTLE_MS &&
                    stableSamples >= MARKETPLACE_STABLE_SAMPLES_REQUIRED

                when {
                    sample.blocked -> finish(MarketplaceUiPriceStatus.BLOCKED)
                    sample.emptyConfirmed && stableSamples >= 1 -> {
                        cacheMarketplaceConditionPrices(releaseId, ActiveMarketplaceConditionPrices())
                        finish(MarketplaceUiPriceStatus.NO_MATCH)
                    }

                    stableEnough && lastGoodPrices != null -> {
                        lastGoodPrices?.let { prices ->
                            cacheMarketplaceConditionPrices(
                                releaseId = releaseId,
                                prices = prices,
                                currency = "USD",
                                pagesChecked = setOf(page),
                                complete = true
                            )
                        }
                        finish(MarketplaceUiPriceStatus.FRESH)
                    }

                    elapsed >= MARKETPLACE_READY_SCAN_DEADLINE_MS -> {
                        finish(
                            if (lastGoodPrices != null) {
                                MarketplaceUiPriceStatus.PARTIAL
                            } else {
                                MarketplaceUiPriceStatus.UNREADABLE
                            }
                        )
                    }

                    else -> scheduleNext(scan)
                }
            }
        }
    }

    onStatus(MarketplaceUiPriceStatus.LOADING)
    scan()
}
