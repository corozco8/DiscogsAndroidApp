package com.example.discogsandroidapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@Serializable
internal data class MarketplaceFxRate(val date: String, val base: String, val quote: String, val rate: Double)

/** API quotes are units of foreign currency per USD; invert them for pricing. */
internal fun marketplaceUsdRates(rows: List<MarketplaceFxRate>, now: Long): Map<String, Double> = buildMap {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val today = now / 86_400_000L
    rows.forEach { row ->
        val date = if (row.date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            runCatching { formatter.parse(row.date)?.time?.div(86_400_000L) }.getOrNull() else null
        if (row.base == "USD" && row.quote.matches(Regex("[A-Z]{3}")) &&
            date != null && date <= today && date >= today - 7 &&
            row.rate.isFinite() && row.rate > 0.0) {
            val inverse = 1.0 / row.rate
            if (inverse.isFinite() && inverse > 0.0) put(row.quote, inverse)
        }
    }
    put("USD", 1.0)
}

/** Shared, off-main-thread fetch. No Discogs credentials or customer data are sent. */
internal object MarketplaceExchangeRates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private var rows: List<MarketplaceFxRate>? = null
    private var fetchedAt = 0L
    private var retryAfter = 0L

    fun prepare(context: Context, onReady: (Map<String, Double>) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            val rates = mutex.withLock {
                val prefs = app.getSharedPreferences("marketplace-fx-v1", Context.MODE_PRIVATE)
                if (rows == null) {
                    rows = runCatching { json.decodeFromString<List<MarketplaceFxRate>>(prefs.getString("rates", "[]")!!) }.getOrDefault(emptyList())
                    fetchedAt = prefs.getLong("fetchedAt", 0L)
                }
                val now = System.currentTimeMillis()
                val cached = marketplaceUsdRates(rows.orEmpty(), now)
                if ((now - fetchedAt !in 0 until 86_400_000L || cached.size <= 1) && now >= retryAfter) {
                    retryAfter = now + 300_000L
                    try {
                        client.newCall(Request.Builder().url("https://api.frankfurter.dev/v2/rates?base=USD").build()).execute().use { response ->
                            if (response.isSuccessful) {
                                val parsed = json.decodeFromString<List<MarketplaceFxRate>>(response.body?.string() ?: "[]")
                                if (marketplaceUsdRates(parsed, now).size > 1) {
                                    rows = parsed
                                    fetchedAt = now
                                    prefs.edit().putString("rates", json.encodeToString(parsed)).putLong("fetchedAt", now).apply()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep recent reference rates; otherwise only USD is eligible.
                    }
                }
                marketplaceUsdRates(rows.orEmpty(), now)
            }
            withContext(Dispatchers.Main) { onReady(rates) }
        }
    }
}
