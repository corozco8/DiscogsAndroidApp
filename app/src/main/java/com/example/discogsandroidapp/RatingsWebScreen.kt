package com.example.discogsandroidapp

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun RatingsWebScreen(
    username: String,
    ratingType: String,
    onBackClick: () -> Unit // We keep this parameter so MainActivity doesn't complain, even if the global header handles the click!
) {
    // Discogs separates seller and buyer feedback into two different URLs
    val url = if (ratingType == "seller") {
        "https://www.discogs.com/sell/seller_feedback/$username"
    } else {
        "https://www.discogs.com/sell/buyer_feedback/$username"
    }

    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(url) {
        onDispose {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webView = this
                settings.javaScriptEnabled = true

                // 1. Enable DOM Storage
                settings.domStorageEnabled = true

                // 2. Disguise the WebView as a normal Chrome mobile browser
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                // 3. Enable Cookies so Cloudflare remembers you passed the check!
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { view ->
            webView = view
            if (view.url != url) {
                view.loadUrl(url)
            }
        }
    )
}