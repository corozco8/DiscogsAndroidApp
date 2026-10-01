package com.example.discogsandroidapp.pricing

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Main-thread cache with fixed expiry. Reading an entry never extends its life. */
internal class RecentMarketplacePages<T>(
    private val now: () -> Long,
    private val destroy: (T) -> Unit,
    private val capacity: Int = 2
) {
    private data class Entry<T>(val page: T, val expiresAt: Long)
    private val entries = linkedMapOf<Long, Entry<T>>()
    fun take(id: Long): T? {
        evictExpired()
        return entries.remove(id)?.page
    }
    fun put(id: Long, page: T, remainingMillis: Long) {
        evictExpired()
        entries.remove(id)?.let { destroy(it.page) }
        if (remainingMillis <= 0) { destroy(page); return }
        entries[id] = Entry(page, now() + remainingMillis)
        while (entries.size > capacity) destroy(entries.remove(entries.keys.first())!!.page)
    }
    fun evictExpired() {
        val expired = entries.filterValues { it.expiresAt <= now() }.keys.toList()
        expired.forEach { destroy(entries.remove(it)!!.page) }
    }
    fun clear() {
        entries.values.forEach { destroy(it.page) }
        entries.clear()
    }
}

internal data class CachedMarketplacePage(val view: WebView, val snapshot: MarketplacePriceSnapshot)

/** Keep at most two successful pages. Parked pages cannot issue background requests. */
internal object MarketplacePageCache {
    private val handler = Handler(Looper.getMainLooper())
    private val pages = RecentMarketplacePages<CachedMarketplacePage>(SystemClock::elapsedRealtime,
        { it.view.stopLoading(); it.view.destroy() })
    fun take(id: Long) = pages.take(id)
    fun put(id: Long, view: WebView, snapshot: MarketplacePriceSnapshot) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        view.settings.javaScriptEnabled = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        view.onPause()
        val remaining = MARKETPLACE_LIVE_PRICE_TTL_MS - (System.currentTimeMillis() - snapshot.updatedAtMillis)
        pages.put(id, CachedMarketplacePage(view, snapshot), remaining)
        handler.postDelayed({ pages.evictExpired() }, remaining.coerceAtLeast(0))
    }
    fun clear() = pages.clear()
}
