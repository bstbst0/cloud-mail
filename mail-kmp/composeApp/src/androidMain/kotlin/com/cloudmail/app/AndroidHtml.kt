package com.cloudmail.app

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/** Isolated email renderer: no script, bridges, file access, navigation, forms or mixed content. */
@SuppressLint("SetJavaScriptEnabled")
@Composable actual fun HtmlMessage(html: String, loadImages: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    fun Color.cssColor() = "#" + (toArgb() and 0x00ffffff).toString(16).padStart(6, '0')
    val foreground = colors.onSurface.cssColor()
    val background = colors.surface.cssColor()
    val link = colors.primary.cssColor()
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = false; domStorageEnabled = false; databaseEnabled = false
                allowFileAccess = false; allowContentAccess = false; setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = true; displayZoomControls = false; loadsImagesAutomatically = true
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        }
    }, update = { view ->
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            @Deprecated("Legacy navigation hook") override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return blocked()
                if (url.scheme == "data") return null
                if (loadImages && url.scheme == "https" && !request.isForMainFrame && !privateHost(url.host.orEmpty())) return null
                return blocked()
            }
            private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        val images = if (loadImages) "data: https:" else "data:"
        val csp = "default-src 'none'; script-src 'none'; img-src $images; style-src 'unsafe-inline'; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'; media-src 'none'"
        // CSP precedes attacker-controlled markup. JavaScript is additionally disabled at engine level.
        val content = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"$csp\"><style>body{font-family:system-ui,sans-serif;overflow-wrap:anywhere;margin:8px;color:$foreground;background-color:$background}a{color:$link}img{max-width:100%;height:auto}table{max-width:100%}</style></head><body>$html</body></html>"
        if (view.tag != content) { view.tag = content; view.loadDataWithBaseURL("https://email.invalid/", content, "text/html", "UTF-8", null) }
    }, onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() })
}
private fun privateHost(host: String): Boolean {
    val value = host.lowercase().trimEnd('.')
    return value == "localhost" || value.endsWith(".localhost") || value.endsWith(".local") || value == "0.0.0.0" || value == "::1" || value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.") || value.startsWith("169.254.") || Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(value) || value.startsWith("fc", true) && value.contains(':') || value.startsWith("fd", true) && value.contains(':')
}
