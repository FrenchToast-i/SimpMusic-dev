package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String = ""

        override fun removeAllCookies() {
            // Cookie clearing is handled by the embedded web view session
        }
    }

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(
    state: MutableState<WebViewState>,
    initUrl: String,
    aboveContent: @Composable (BoxScope.() -> Unit),
    onPageFinished: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        UIKitView(
            factory = {
                val configuration = WKWebViewConfiguration()
                val webView = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
                webView.navigationDelegate =
                    object : NSObject(), WKNavigationDelegateProtocol {
                        override fun webView(
                            webView: WKWebView,
                            didFinishNavigation: WKNavigation?,
                        ) {
                            onPageFinished(webView.URL?.absoluteString ?: initUrl)
                        }
                    }
                val request = NSURLRequest.requestWithURL(NSURL.URLWithString(initUrl)!!)
                webView.loadRequest(request)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
        aboveContent()
    }
}

@Composable
actual fun DiscordWebView(
    state: MutableState<WebViewState>,
    aboveContent: @Composable (BoxScope.() -> Unit),
    onLoginDone: (String) -> Unit,
) {
    PlatformWebView(
        state = state,
        initUrl = "https://discord.com/login",
        aboveContent = aboveContent,
        onPageFinished = { url ->
            if (url.contains("/app")) {
                onLoginDone("")
            }
        },
    )
}
