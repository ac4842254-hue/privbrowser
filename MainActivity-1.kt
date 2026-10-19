package com.priv.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Random
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

// Proxy: Orbot (Tor) ka SOCKS port. Agar ye kaam na kare to "http://127.0.0.1:8118" try karo.
// Khali "" rakhoge to proxy nahi lagega (tab phone ka VPN app use karo).
private const val PROXY = ""

private const val UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/124.0.0.0 Mobile Safari/537.36"

private val TRACKERS = setOf(
    "google-analytics.com", "googletagmanager.com", "googletagservices.com",
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adservice.google.com", "facebook.net", "connect.facebook.com", "pixel.facebook.com",
    "hotjar.com", "scorecardresearch.com", "adnxs.com", "criteo.com", "criteo.net",
    "taboola.com", "outbrain.com", "mixpanel.com", "segment.io", "segment.com",
    "amplitude.com", "quantserve.com", "clarity.ms", "fullstory.com", "mouseflow.com",
    "crazyegg.com", "optimizely.com", "newrelic.com", "nr-data.net", "branch.io",
    "adjust.com", "appsflyer.com", "moatads.com", "rubiconproject.com", "pubmatic.com",
    "openx.net", "casalemedia.com", "advertising.com", "bluekai.com", "demdex.net",
    "everesttech.net", "krxd.net", "rlcdn.com", "tapad.com", "bat.bing.com",
    "ads.linkedin.com", "analytics.tiktok.com", "ads.twitter.com", "static.ads-twitter.com",
    "analytics.twitter.com", "snap.licdn.com", "ads.yahoo.com", "analytics.yahoo.com",
    "sentry.io", "bugsnag.com", "heap.io", "heapanalytics.com", "pendo.io",
    "chartbeat.com", "parsely.com", "adsrvr.org", "3lift.com", "media.net"
)

private val FP_JS = """
(function () {
  try {
    var seed = SEED_PLACEHOLDER;
    var hit = function (i) { return ((Math.imul(i + 1, 2654435761) ^ seed) >>> 0) % 100 < 6; };
    var perturb = function (d) { for (var i = 0; i < d.length; i += 4) if (hit(i)) d[i] ^= 1; };
    var def = function (o, k, v) {
      try { Object.defineProperty(o, k, { get: function () { return v; }, configurable: true }); } catch (e) {}
    };

    // Canvas: copy pe noise, original safe
    var oGet = CanvasRenderingContext2D.prototype.getImageData;
    var oURL = HTMLCanvasElement.prototype.toDataURL;
    var oBlob = HTMLCanvasElement.prototype.toBlob;
    var noisy = function (cv) {
      if (!cv.width || !cv.height) return cv;
      var t = document.createElement('canvas');
      t.width = cv.width; t.height = cv.height;
      var c = t.getContext('2d');
      c.drawImage(cv, 0, 0);
      var d = oGet.call(c, 0, 0, t.width, t.height);
      perturb(d.data); c.putImageData(d, 0, 0);
      return t;
    };
    CanvasRenderingContext2D.prototype.getImageData = function () {
      var d = oGet.apply(this, arguments); perturb(d.data); return d;
    };
    HTMLCanvasElement.prototype.toDataURL = function () { return oURL.apply(noisy(this), arguments); };
    HTMLCanvasElement.prototype.toBlob = function () { return oBlob.apply(noisy(this), arguments); };

    // WebGL GPU info chhupao
    [window.WebGLRenderingContext, window.WebGL2RenderingContext].forEach(function (C) {
      if (!C) return;
      var o = C.prototype.getParameter;
      C.prototype.getParameter = function (p) {
        if (p === 37445) return 'Google Inc.';
        if (p === 37446) return 'ANGLE (Generic Renderer)';
        return o.call(this, p);
      };
    });

    // Navigator standardize
    def(Navigator.prototype, 'hardwareConcurrency', 4);
    def(Navigator.prototype, 'deviceMemory', 4);
    def(Navigator.prototype, 'languages', Object.freeze(['en-US', 'en']));
    def(Navigator.prototype, 'language', 'en-US');
    def(Navigator.prototype, 'doNotTrack', '1');
    def(Navigator.prototype, 'getBattery', undefined);
    def(Navigator.prototype, 'connection', undefined);
  } catch (e) {}
})();
"""

private fun isTracker(host: String): Boolean {
    val parts = host.lowercase().split(".")
    for (i in parts.indices) {
        if (parts.drop(i).joinToString(".") in TRACKERS) return true
    }
    return false
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var status: TextView
    private var scriptHandler: ScriptHandler? = null
    private var ready = false
    private val blocked = AtomicInteger(0)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        urlBar = EditText(this).apply {
            hint = "Search ya URL"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Go"
            setOnClickListener { go(urlBar.text.toString()) }
        })
        bar.addView(Button(this).apply {
            text = "NEW ID"
            setOnClickListener { newIdentity() }
        })

        status = TextView(this).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            textSize = 12f
        }
        web = WebView(this)

        root.addView(bar)
        root.addView(status)
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        setupWebView()
        startProxy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setGeolocationEnabled(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = UA
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mediaPlaybackRequiresUserGesture = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        installScript()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (isTracker(request.url.host ?: "")) {
                    blocked.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http") {  // HTTPS-only
                    view.loadUrl(u.buildUpon().scheme("https").build().toString())
                    return true
                }
                return u.scheme != "https"  // intent:, file: etc. block
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlBar.setText(url)
                updateStatus()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) { callback?.invoke(origin, false, false) }

            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = false  // popups band
        }
    }

    private fun installScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scriptHandler?.remove()
        val seed = Random().nextInt(Int.MAX_VALUE)
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            web, FP_JS.replace("SEED_PLACEHOLDER", seed.toString()), setOf("*")
        )
    }

    private fun startProxy() {
        if (PROXY.isBlank()) {
            ready = true
            updateStatus()
            go("https://duckduckgo.com")
            return
        }
        // Fail-closed: proxy set na ho paye to browsing hi band (real IP leak nahi hoga)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            status.text = "Is phone ke WebView me proxy support nahi. Safety ke liye browsing band."
            return
        }
        try {
            val cfg = ProxyConfig.Builder().addProxyRule(PROXY).build()
            ProxyController.getInstance().setProxyOverride(
                cfg, Executor { r -> runOnUiThread(r) },
                Runnable {
                    ready = true
                    updateStatus()
                    go("https://check.torproject.org")
                }
            )
        } catch (e: Exception) {
            status.text = "Proxy error: ${e.message}"
        }
    }

    private fun updateStatus() {
        val p = if (PROXY.isBlank()) "OFF (VPN use karo)" else "ON"
        status.text = "Trackers blocked: ${blocked.get()}  |  Proxy: $p"
    }

    private fun go(input: String) {
        if (!ready) {
            status.text = "Proxy ready nahi. Orbot chalu karo aur app dobara kholo."
            return
        }
        val t = input.trim()
        if (t.isEmpty()) return
        val url = if (t.contains(".") && !t.contains(" ")) {
            if (t.contains("://")) t else "https://$t"
        } else {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(url.replaceFirst("http://", "https://"), mapOf("DNT" to "1", "Sec-GPC" to "1"))
    }

    private fun newIdentity() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
        web.clearHistory()
        web.clearFormData()
        installScript()
        web.reload()
        status.text = "Naya identity: cookies saaf, fingerprint badla"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
