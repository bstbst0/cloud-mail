@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.cloudmail.app

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.WebKit.*
import platform.darwin.NSObject

@Composable actual fun HtmlMessage(html: String, loadImages: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    fun Color.cssColor() = "#" + (toArgb() and 0x00ffffff).toString(16).padStart(6, '0')
    val foreground = colors.onSurface.cssColor()
    val background = colors.surface.cssColor()
    val link = colors.primary.cssColor()
    val delegate = remember { object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString
            decisionHandler(if (url == "about:blank" && decidePolicyForNavigationAction.navigationType == WKNavigationType.WKNavigationTypeOther) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    } }
    var previous by remember { mutableStateOf("") }
    UIKitView(modifier = modifier, factory = {
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            preferences.javaScriptEnabled = false
            preferences.javaScriptCanOpenWindowsAutomatically = false
            defaultWebpagePreferences.allowsContentJavaScript = false
            allowsInlineMediaPlayback = false
        }
        WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply { navigationDelegate = delegate; opaque = false }
    }, update = { view ->
        val images = if (loadImages) "data: https:" else "data:"
        val csp = "default-src 'none'; script-src 'none'; img-src $images; style-src 'unsafe-inline'; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'; media-src 'none'"
        val content = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"$csp\"><style>body{font-family:-apple-system,sans-serif;overflow-wrap:anywhere;margin:8px;color:$foreground;background-color:$background}a{color:$link}img{max-width:100%;height:auto}table{max-width:100%}</style></head><body>$html</body></html>"
        if (content != previous) { previous = content; view.loadHTMLString(content, baseURL = null) }
    }, onRelease = { it.stopLoading(); it.navigationDelegate = null; it.loadHTMLString("", baseURL = null) })
}
