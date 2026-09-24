package com.example.discogsandroidapp

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal fun discogsRetryDelay(header: String?, now: Long): Long {
    val seconds = header?.trim()?.toLongOrNull()
    val millis = if (seconds != null) seconds.coerceIn(0, 86_400) * 1000 else {
        runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(header ?: "")!!.time - now
        }.getOrDefault(60_000L)
    }
    return millis.coerceIn(60_000, 86_400_000)
}

/** Shared by API reads and writes. Never automatically retries a store mutation. */
internal class DiscogsRequestPacing : Interceptor {
    companion object {
        private val lock = Any()
        private val turns = RequestTurnQueue()
        private var nextRequestAt = 0L
        private var blockedUntil = 0L
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val ticket = synchronized(lock) { turns.add(chain.request().tag(DiscogsRequestPriority::class.java) == DiscogsRequestPriority.BACKGROUND) }
        try {
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val wait = synchronized(lock) {
                val now = System.currentTimeMillis()
                val remaining = if (!turns.isNext(ticket)) 100L else maxOf(nextRequestAt, blockedUntil) - now
                if (remaining <= 0) {
                    turns.take(ticket)
                    nextRequestAt = now + 1_500
                }
                remaining
            }
            if (wait <= 0) break
            try { Thread.sleep(minOf(wait, 200)) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("Interrupted while waiting for Discogs", e)
            }
        }
        } finally { synchronized(lock) { turns.remove(ticket) } }
        val response = chain.proceed(chain.request())
        if (response.code == 429 || response.header("X-Discogs-Ratelimit-Remaining")?.toIntOrNull() == 0) {
            val now = System.currentTimeMillis()
            synchronized(lock) { blockedUntil = maxOf(blockedUntil, now + discogsRetryDelay(response.header("Retry-After"), now)) }
        }
        return response
    }
}
