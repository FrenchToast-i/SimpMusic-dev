package com.maxrave.simpmusic.expect

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl)
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    copyToClipboard(title, url)
}
