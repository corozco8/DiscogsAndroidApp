package com.example.discogsandroidapp

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscogsWebScreen(
    title: String,
    url: String,
    hideNewOrderNotifications: Boolean = false,
    onBackClick: () -> Unit
) {
    var webView by remember {
        mutableStateOf<WebView?>(null)
    }

    BackHandler {
        val currentWebView = webView

        if (
            currentWebView != null &&
            currentWebView.canGoBack()
        ) {
            currentWebView.goBack()
        } else {
            onBackClick()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title)
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val currentWebView = webView

                            if (
                                currentWebView != null &&
                                currentWebView.canGoBack()
                            ) {
                                currentWebView.goBack()
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context ->
                WebView(context).apply {
                    webView = this

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"

                    CookieManager.getInstance()
                        .setAcceptCookie(true)

                    CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(
                            this,
                            true
                        )

                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView,
                                finishedUrl: String
                            ) {
                                super.onPageFinished(
                                    view,
                                    finishedUrl
                                )

                                if (
                                    hideNewOrderNotifications &&
                                    finishedUrl.contains(
                                        "discogs.com",
                                        ignoreCase = true
                                    )
                                ) {
                                    view.evaluateJavascript(
                                        NEW_ORDER_FILTER_SCRIPT,
                                        null
                                    )
                                }
                            }
                        }

                    loadUrl(url)
                }
            },
            update = { view ->
                webView = view
            }
        )
    }
}

/*
 * Discogs does not expose the private inbox through its public REST API.
 * For the authenticated Discogs web inbox, hide duplicate "New Order"
 * notification rows while keeping the more useful Payment Received event.
 *
 * This is intentionally conservative: it only hides list/table rows when a
 * link or link-like element begins with "New Order".
 */
private val NEW_ORDER_FILTER_SCRIPT =
    """
    (function() {
        function hideNewOrderRows() {
            var nodes = document.querySelectorAll(
                'a, [role="link"], li, tr, [role="listitem"]'
            );

            nodes.forEach(function(node) {
                var text = (node.innerText || node.textContent || '')
                    .replace(/\s+/g, ' ')
                    .trim();

                if (/^New Order(?:\b|\s|:|-)/i.test(text)) {
                    var row = node.closest(
                        'li, tr, [role="listitem"]'
                    );

                    if (row) {
                        row.style.display = 'none';
                    }
                }
            });
        }

        hideNewOrderRows();

        if (!window.__sellerInboxFilterInstalled) {
            window.__sellerInboxFilterInstalled = true;

            new MutationObserver(function() {
                hideNewOrderRows();
            }).observe(
                document.body,
                {
                    childList: true,
                    subtree: true
                }
            );
        }
    })();
    """.trimIndent()
