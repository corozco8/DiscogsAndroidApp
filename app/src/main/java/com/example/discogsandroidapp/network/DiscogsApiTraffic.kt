package com.example.discogsandroidapp.network

import android.os.SystemClock
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response

/** Local network attempts in a rolling minute, shared by foreground and background API clients. */
internal class ApiRequestWindow(private val now: () -> Long) {
    private val sentAt = ArrayDeque<Long>()
    private val mutableCount = MutableStateFlow(0)
    val count = mutableCount.asStateFlow()

    @Synchronized fun record() {
        val timestamp = now()
        prune(timestamp)
        sentAt.addLast(timestamp)
        mutableCount.value = sentAt.size
    }

    @Synchronized fun tick() {
        prune(now())
        mutableCount.value = sentAt.size
    }

    private fun prune(timestamp: Long) {
        while (sentAt.isNotEmpty() && timestamp - sentAt.peekFirst() >= 60_000L) {
            sentAt.removeFirst()
        }
    }
}

internal object DiscogsApiTraffic {
    val requests = ApiRequestWindow(SystemClock::elapsedRealtime)
}

/** Runs after pacing, for real network exchanges, including error responses and redirects. */
internal class DiscogsApiTrafficInterceptor(
    private val requests: ApiRequestWindow = DiscogsApiTraffic.requests
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host == "api.discogs.com") requests.record()
        return chain.proceed(chain.request())
    }
}
