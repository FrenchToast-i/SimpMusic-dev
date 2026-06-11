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
import platform.Foundation.NSHTTPCookie
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

// In-memory cache to support synchronous getCookie() calls on KMP threads
private val cookieCache = mutableMapOf<String, String>()

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String {
            // Exact URL match
            cookieCache[url]?.let { return it }
            // Host domain match
            val host = NSURL.URLWithString(url)?.host
            if (host != null) {
                cookieCache[host]?.let { return it }
            }
            return ""
        }

        override fun removeAllCookies() {
            cookieCache.clear()
            val dataStore = WKWebsiteDataStore.defaultDataStore()
            val dataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
            dataStore.fetchDataRecordsOfTypes(dataTypes) { records ->
                if (records != null) {
                    dataStore.removeDataOfTypes(dataTypes, records, completionHandler = {})
                }
            }
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
                            val currentUrl = webView.URL?.absoluteString ?: initUrl
                            
                            // Capture HTTP cookies asynchronously and update synchronous cache
                            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
                                val cookieList = cookies?.mapNotNull { it as? NSHTTPCookie } ?: emptyList()
                                val cookieString = cookieList.joinToString("; ") { "${it.name}=${it.value}" }
                                if (cookieString.isNotEmpty()) {
                                    cookieCache[currentUrl] = cookieString
                                    webView.URL?.host?.let { host ->
                                        cookieCache[host] = cookieString
                                    }
                                }
                            }
                            
                            onPageFinished(currentUrl)
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

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun DiscordWebView(
    state: MutableState<WebViewState>,
    aboveContent: @Composable (BoxScope.() -> Unit),
    onLoginDone: (String) -> Unit,
) {
    val initUrl = "https://discord.com/login"
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
                            val currentUrl = webView.URL?.absoluteString ?: ""
                            if (currentUrl.contains("/app")) {
                                // Inject JS snippet to extract user token directly from localStorage
                                val js = "(function(){var i=document.createElement('iframe');document.body.appendChild(i);return i.contentWindow.localStorage.token.slice(1,-1)})()"
                                webView.evaluateJavaScript(js) { result, error ->
                                    if (error == null && result is String) {
                                        onLoginDone(result)
                                    } else {
                                        onLoginDone("")
                                    }
                                }
                            }
                        }
                    }
                
                // Override user agent to bypass WebView detection blocks
                webView.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/605.1"
                
                val request = NSURLRequest.requestWithURL(NSURL.URLWithString(initUrl)!!)
                webView.loadRequest(request)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
        aboveContent()
    }
}
