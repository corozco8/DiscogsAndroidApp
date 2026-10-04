package com.example.discogsandroidapp.debug

import android.content.Context
import com.example.discogsandroidapp.data.ReleasePriceSummary
import com.example.discogsandroidapp.pricing.apiOnlyTypicalPrice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PricingDebugReleaseInfo(
    val releaseId: Long,
    val artist: String,
    val title: String,
    val year: Int?,
    val have: Int,
    val want: Int
)

data class PricingDebugSnapshot(
    val comparisonCount: Int,
    val fileSizeBytes: Long,
    val preview: String
)

object PricingDebugRepository {
    private const val FILE_NAME = "pricing_model_comparisons.tsv"
    private const val PREFS_NAME = "pricing_debug_seen"
    private const val PREFS_KEY = "seen_comparisons"
    private const val MAX_PREVIEW_LINES = 20
    private val lock = Any()

    private val header = listOf(
        "timestamp_utc", "release_id", "artist", "title", "year",
        "media_condition", "sleeve_condition",
        "algorithm_price_usd", "live_price_usd",
        "algorithm_minus_live_usd", "algorithm_error_pct",
        "using_saved_live_prices", "live_media_listing_count",
        "live_exact_sleeve_listing_count", "api_current_lowest_ask_usd",
        "api_num_for_sale", "community_have", "community_want",
        "api_guide_low_usd", "api_guide_median_usd", "api_only_typical_usd", "api_guide_high_usd",
        "raw_selected_condition_suggestion_usd", "raw_mint_usd",
        "raw_near_mint_usd", "raw_vg_plus_usd", "raw_vg_usd",
        "raw_g_plus_usd", "raw_g_usd", "raw_fair_usd", "raw_poor_usd",
        "is_album"
    ).joinToString("\t")

    fun recordIfNew(
        context: Context,
        release: PricingDebugReleaseInfo,
        algorithmSummary: ReleasePriceSummary,
        liveSummary: ReleasePriceSummary,
        mediaCondition: String,
        sleeveCondition: String,
        algorithmPrice: Double,
        livePrice: Double,
        usingSavedLivePrices: Boolean
    ): Boolean = synchronized(lock) {
        if (!algorithmPrice.isFinite() || algorithmPrice <= 0.0 ||
            !livePrice.isFinite() || livePrice <= 0.0
        ) return@synchronized false

        val dedupeKey = listOf(
            release.releaseId, mediaCondition, sleeveCondition,
            cents(algorithmPrice), cents(livePrice)
        ).joinToString("|")

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(PREFS_KEY, emptySet()).orEmpty().toMutableSet()
        if (!seen.add(dedupeKey)) return@synchronized false

        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(header + "\n")
        }

        val suggestions = algorithmSummary.priceSuggestions
        val difference = algorithmPrice - livePrice
        val errorPct = difference / livePrice * 100.0
        val exactPairKey = "$mediaCondition||$sleeveCondition"

        val row = listOf(
            timestampUtc(),
            release.releaseId.toString(),
            tsv(release.artist),
            tsv(release.title),
            release.year?.toString().orEmpty(),
            tsv(mediaCondition),
            tsv(sleeveCondition),
            money(algorithmPrice),
            money(livePrice),
            money(difference),
            decimal(errorPct),
            usingSavedLivePrices.toString(),
            (liveSummary.activeMediaListingCounts[mediaCondition] ?: 0).toString(),
            (liveSummary.activeMediaSleeveListingCounts[exactPairKey] ?: 0).toString(),
            nullableMoney(algorithmSummary.lowestAskingPrice),
            algorithmSummary.numForSale.toString(),
            release.have.toString(),
            release.want.toString(),
            nullableMoney(algorithmSummary.low),
            nullableMoney(algorithmSummary.median),
            nullableMoney(apiOnlyTypicalPrice(algorithmSummary, release.have, release.want)),
            nullableMoney(algorithmSummary.high),
            nullableMoney(suggestions?.forCondition(mediaCondition)?.value),
            nullableMoney(suggestions?.mint?.value),
            nullableMoney(suggestions?.nearMint?.value),
            nullableMoney(suggestions?.veryGoodPlus?.value),
            nullableMoney(suggestions?.veryGood?.value),
            nullableMoney(suggestions?.goodPlus?.value),
            nullableMoney(suggestions?.good?.value),
            nullableMoney(suggestions?.fair?.value),
            nullableMoney(suggestions?.poor?.value),
            algorithmSummary.isAlbumRelease.toString()
        ).joinToString("\t")

        try {
            file.appendText(row + "\n")
            prefs.edit().putStringSet(PREFS_KEY, seen).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun snapshot(context: Context): PricingDebugSnapshot = synchronized(lock) {
        val file = logFile(context)
        if (!file.exists()) {
            return@synchronized PricingDebugSnapshot(0, 0L, "No pricing comparisons recorded yet.")
        }

        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val dataLines = lines.drop(1)
        val previewLines = dataLines.takeLast(MAX_PREVIEW_LINES)
        PricingDebugSnapshot(
            comparisonCount = dataLines.size,
            fileSizeBytes = file.length(),
            preview = if (previewLines.isEmpty()) {
                "No pricing comparisons recorded yet."
            } else buildString {
                append("Most recent comparisons:\n\n")
                previewLines.asReversed().forEach { line ->
                    val c = line.split('\t')
                    val artist = c.getOrNull(2).orEmpty()
                    val title = c.getOrNull(3).orEmpty()
                    val media = c.getOrNull(5).orEmpty()
                    val sleeve = c.getOrNull(6).orEmpty()
                    val algorithm = c.getOrNull(7).orEmpty()
                    val live = c.getOrNull(8).orEmpty()
                    append("$artist - $title\n")
                    append("$media / $sleeve   Algorithm $$algorithm   Live $$live\n\n")
                }
            }
        )
    }

    fun exportFile(context: Context): File = synchronized(lock) {
        val source = logFile(context)
        val exportDir = File(context.cacheDir, "debug_exports").apply { mkdirs() }
        val destination = File(exportDir, "pricing_model_comparisons.txt")
        if (source.exists()) source.copyTo(destination, overwrite = true)
        else destination.writeText(header + "\n")
        destination
    }

    fun clear(context: Context) = synchronized(lock) {
        runCatching { logFile(context).delete() }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREFS_KEY).apply()
    }

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)
    private fun cents(value: Double): Long = kotlin.math.round(value * 100.0).toLong()
    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun nullableMoney(value: Double?): String = value?.takeIf { it.isFinite() }?.let(::money).orEmpty()
    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun tsv(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun timestampUtc(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
