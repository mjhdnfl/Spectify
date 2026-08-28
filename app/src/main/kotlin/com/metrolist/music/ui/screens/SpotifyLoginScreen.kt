/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.SslErrorHandler
import android.net.http.SslError
import com.metrolist.music.utils.BrokenLogin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.spotify_login)) },
            navigationIcon = {
                IconButton(
                    onClick = { navController.navigateUp() },
                    onLongClick = { navController.backToMain() },
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            }
        )

        if (isLoading || isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val bl = BrokenLogin.nextId("login")
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                            userAgentString = USER_AGENT_DESKTOP
                        }

                        BrokenLogin.i(
                            bl, "webview.create",
                            BrokenLogin.kv(
                                "webViewPackage" to BrokenLogin.trap(bl, "webview.package") {
                                    WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" }
                                },
                                "userAgent" to BrokenLogin.shortUrl(settings.userAgentString, 110),
                                "javaScript" to settings.javaScriptEnabled,
                                "domStorage" to settings.domStorageEnabled,
                                "thirdPartyCookies" to cookieManager.acceptThirdPartyCookies(this),
                                "acceptCookie" to cookieManager.acceptCookie(),
                                "mixedContent" to settings.mixedContentMode,
                                "algorithmicDarkening" to BrokenLogin.trap(bl, "webview.darkening") {
                                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                                        settings.isAlgorithmicDarkeningAllowed
                                    } else {
                                        "n/a<33"
                                    }
                                },
                                "uiNightMode" to BrokenLogin.trap(bl, "webview.nightMode") {
                                    ctx.resources.configuration.uiMode and
                                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                                },
                                "loginUrl" to BrokenLogin.shortUrl(SpotifyAuth.LOGIN_URL),
                            ),
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                if (message != null) {
                                    val op = when (message.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> "console.error"
                                        ConsoleMessage.MessageLevel.WARNING -> "console.warn"
                                        else -> "console"
                                    }
                                    val details = BrokenLogin.kv(
                                        "level" to message.messageLevel(),
                                        "line" to message.lineNumber(),
                                        "source" to BrokenLogin.shortUrl(message.sourceId(), 90),
                                        "msg" to message.message(),
                                    )
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        BrokenLogin.e(bl, op, details)
                                    } else {
                                        BrokenLogin.d(bl, op, details)
                                    }
                                }
                                return true
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (newProgress % 25 == 0) {
                                    BrokenLogin.d(bl, "progress", BrokenLogin.kv("percent" to newProgress))
                                }
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                BrokenLogin.i(bl, "title", BrokenLogin.kv("title" to title))
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                Timber.d("SpotifyLogin: page started: $url")
                                BrokenLogin.i(bl, "page.started", BrokenLogin.kv("url" to BrokenLogin.shortUrl(url)))
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                val isMainFrame = request?.isForMainFrame == true
                                val details = BrokenLogin.kv(
                                    "mainFrame" to isMainFrame,
                                    "code" to BrokenLogin.trap(bl, "error.code") { error?.errorCode },
                                    "desc" to BrokenLogin.trap(bl, "error.desc") { error?.description?.toString() },
                                    "method" to request?.method,
                                    "url" to BrokenLogin.shortUrl(request?.url?.toString()),
                                )
                                if (isMainFrame) BrokenLogin.e(bl, "page.error", details)
                                else BrokenLogin.w(bl, "resource.error", details)
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                val isMainFrame = request?.isForMainFrame == true
                                val details = BrokenLogin.kv(
                                    "mainFrame" to isMainFrame,
                                    "status" to errorResponse?.statusCode,
                                    "reason" to errorResponse?.reasonPhrase,
                                    "mime" to errorResponse?.mimeType,
                                    "url" to BrokenLogin.shortUrl(request?.url?.toString()),
                                )
                                if (isMainFrame) BrokenLogin.e(bl, "page.httpError", details)
                                else BrokenLogin.w(bl, "resource.httpError", details)
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                BrokenLogin.e(
                                    bl, "page.sslError",
                                    BrokenLogin.kv(
                                        "primaryError" to error?.primaryError,
                                        "url" to BrokenLogin.shortUrl(error?.url),
                                    ),
                                )
                                super.onReceivedSslError(view, handler, error)
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                BrokenLogin.e(
                                    bl, "renderer.gone",
                                    BrokenLogin.kv(
                                        "didCrash" to detail?.didCrash(),
                                        "rendererPriorityAtExit" to detail?.rendererPriorityAtExit(),
                                    ),
                                )
                                BrokenLogin.trap(bl, "renderer.destroy") { view?.destroy() }
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Timber.d("SpotifyLogin: page finished: $url")
                                BrokenLogin.i(
                                    bl, "page.finished",
                                    BrokenLogin.kv(
                                        "url" to BrokenLogin.shortUrl(url),
                                        "progress" to BrokenLogin.trap(bl, "page.progress") { view?.progress },
                                        "contentHeight" to BrokenLogin.trap(bl, "page.height") { view?.contentHeight },
                                        "title" to BrokenLogin.trap(bl, "page.title") { view?.title },
                                    ) + " " + BrokenLogin.describeCookies(
                                        BrokenLogin.trap(bl, "page.cookies") {
                                            CookieManager.getInstance().getCookie("https://open.spotify.com")
                                        },
                                    ),
                                )
                                applyLoginLayoutFix(bl, view)
                                probeDom(bl, view)

                                if (url?.startsWith("https://open.spotify.com") == true &&
                                    tokenFetchStarted.compareAndSet(false, true)
                                ) {
                                    handleLoginSuccess(
                                        bl = bl,
                                        view = view,
                                        context = context,
                                        scope = scope,
                                        navController = navController,
                                        setProcessing = { isProcessing = it },
                                        setStatus = { statusMessage = it },
                                        setError = { hasError = it },
                                        tokenFetchStarted = tokenFetchStarted
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                Timber.d("SpotifyLogin: navigating to: $requestUrl")
                                BrokenLogin.i(
                                    bl, "navigate",
                                    BrokenLogin.kv(
                                        "url" to BrokenLogin.shortUrl(requestUrl),
                                        "mainFrame" to request.isForMainFrame,
                                        "redirect" to request.isRedirect,
                                    ),
                                )

                                if (requestUrl.startsWith("https://open.spotify.com")) {
                                    val spDc = extractSpDcCookie()
                                    BrokenLogin.i(
                                        bl, "redirect.toOpenSpotify",
                                        BrokenLogin.kv("spDc" to BrokenLogin.redact(spDc)),
                                    )
                                    if (spDc != null && tokenFetchStarted.compareAndSet(false, true)) {
                                        handleLoginSuccess(
                                            bl = bl,
                                            view = view,
                                            context = context,
                                            scope = scope,
                                            navController = navController,
                                            setProcessing = { isProcessing = it },
                                            setStatus = { statusMessage = it },
                                            setError = { hasError = it },
                                            tokenFetchStarted = tokenFetchStarted
                                        )
                                        return true
                                    }
                                    return false
                                }
                                return false
                            }
                        }
                        
                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                }
            )

            if (isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!hasError) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = statusMessage.ifEmpty { stringResource(R.string.spotify_logging_in) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        if (hasError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = {
                                hasError = false
                                isProcessing = false
                                statusMessage = ""
                                tokenFetchStarted.set(false)
                                retryCount++
                            }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun probeDom(bl: String, view: WebView?) {
    if (!BrokenLogin.ENABLED || view == null) return
    BrokenLogin.i(
        bl, "viewState",
        BrokenLogin.kv(
            "width" to view.width,
            "height" to view.height,
            "alpha" to view.alpha,
            "visibility" to view.visibility,
            "isShown" to view.isShown,
            "attached" to view.isAttachedToWindow,
            "hwAccelerated" to view.isHardwareAccelerated,
            "layerType" to view.layerType,
            "scaleX" to view.scaleX,
            "scaleY" to view.scaleY,
            "translationY" to view.translationY,
            "parent" to (view.parent?.javaClass?.simpleName ?: "NONE"),
        ),
    )
    val js = """
        (function () {
          try {
            var b = document.body;
            var vis = 0, painted = 0, all = document.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              var r = el.getBoundingClientRect();
              if (r.width > 0 && r.height > 0) {
                vis++;
                var cs = getComputedStyle(el);
                if (cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.01) painted++;
              }
            }
            var mid = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
            var midDesc = mid ? (mid.tagName + (mid.id ? '#' + mid.id : '') +
                (mid.className && typeof mid.className === 'string'
                  ? '.' + mid.className.trim().split(/\s+/).slice(0, 2).join('.') : '')) : 'null';
            var inp = document.querySelector('input');
            var inpDesc = '-';
            if (inp) {
              var ir = inp.getBoundingClientRect();
              var ics = getComputedStyle(inp);
              inpDesc = Math.round(ir.x) + ',' + Math.round(ir.y) + ' ' +
                Math.round(ir.width) + 'x' + Math.round(ir.height) +
                ' vis=' + ics.visibility + ' op=' + ics.opacity + ' disp=' + ics.display;
            }
            function col(el, prop) {
              return el ? getComputedStyle(el)[prop] : '-';
            }
            var chain = [];
            var node = inp;
            for (var depth = 0; node && depth < 10; depth++) {
              var ncs = getComputedStyle(node);
              chain.push({
                t: node.tagName +
                   (node.className && typeof node.className === 'string'
                     ? '.' + node.className.trim().split(/\s+/).slice(0, 1).join('') : ''),
                h: ncs.height,
                ch: node.clientHeight,
                oh: node.offsetHeight,
                ov: ncs.overflow,
                pos: ncs.position,
                disp: ncs.display,
                tr: ncs.transform === 'none' ? '-' : 'set',
                cont: ncs.contain
              });
              node = node.parentElement;
            }
            var btn = document.querySelector('button');
            var colours = {
              htmlBg: col(document.documentElement, 'backgroundColor'),
              bodyColor: col(b, 'color'),
              inputColor: col(inp, 'color'),
              inputBg: col(inp, 'backgroundColor'),
              buttonColor: col(btn, 'color'),
              buttonBg: col(btn, 'backgroundColor'),
              colorScheme: col(document.documentElement, 'colorScheme'),
              prefersDark: window.matchMedia &&
                window.matchMedia('(prefers-color-scheme: dark)').matches
            };
            return JSON.stringify({
              readyState: document.readyState,
              bodyChildren: b ? b.children.length : -1,
              htmlLen: b ? b.innerHTML.length : -1,
              textLen: b ? (b.innerText || '').trim().length : -1,
              elements: all.length,
              visibleElements: vis,
              paintedElements: painted,
              centreElement: midDesc,
              firstInput: inpDesc,
              inputs: document.querySelectorAll('input').length,
              passwordInputs: document.querySelectorAll('input[type=password]').length,
              buttons: document.querySelectorAll('button').length,
              iframes: document.querySelectorAll('iframe').length,
              forms: document.querySelectorAll('form').length,
              innerW: window.innerWidth,
              innerH: window.innerHeight,
              bodyH: b ? b.scrollHeight : -1,
              bg: b ? getComputedStyle(b).backgroundColor : '-',
              htmlH: document.documentElement.clientHeight,
              htmlScrollH: document.documentElement.scrollHeight,
              bodyOverflow: b ? getComputedStyle(b).overflow : '-',
              htmlOverflow: getComputedStyle(document.documentElement).overflow,
              colours: colours,
              ancestors: chain
            });
          } catch (e) {
            return JSON.stringify({ probeError: String(e) });
          }
        })();
    """.trimIndent()
    BrokenLogin.trap(bl, "dom.probe") {
        view.evaluateJavascript(js) { result ->
            BrokenLogin.i(bl, "dom", result?.trim('"')?.replace("\\\"", "\"") ?: "null")
        }
    }
}

private fun applyLoginLayoutFix(bl: String, view: WebView?) {
    if (view == null) return
    val js = """
        (function () {
          try {
            var m = document.querySelector('main');
            var before = m ? getComputedStyle(m).height : '-';
            var box = '-';
            if (m) {
              var s = getComputedStyle(m);
              box = 'pos=' + s.position + ' top=' + s.top + ' bottom=' + s.bottom +
                    ' minH=' + s.minHeight + ' maxH=' + s.maxHeight + ' flex=' + s.flex +
                    ' basis=' + s.flexBasis + ' align=' + s.alignSelf + ' box=' + s.boxSizing;
            }
            var id = 'meld-login-layout-fix';
            if (!document.getElementById(id)) {
              var st = document.createElement('style');
              st.id = id;
              st.textContent =
                'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
                'body > div { height: auto !important; min-height: 100% !important; }' +
                'main { position: static !important; height: auto !important;' +
                '       min-height: 100dvh !important; max-height: none !important;' +
                '       overflow: visible !important; }';
              document.head.appendChild(st);
            }
            var after = m ? getComputedStyle(m).height : '-';
            return JSON.stringify({
              mainBefore: before,
              mainAfter: after,
              mainBox: box,
              bodyH: document.body ? document.body.scrollHeight : -1,
              innerH: window.innerHeight,
              applied: !!document.getElementById(id)
            });
          } catch (e) {
            return JSON.stringify({ probeError: String(e) });
          }
        })();
    """.trimIndent()
    BrokenLogin.trap(bl, "layoutFix") {
        view.evaluateJavascript(js) { result ->
            BrokenLogin.i(bl, "layoutFix", result?.trim('"')?.replace("\\\"", "\"") ?: "null")
        }
    }
}

private fun extractSpDcCookie(): String? {
    val allCookies = CookieManager.getInstance().getCookie("https://open.spotify.com")
    if (allCookies.isNullOrBlank()) return null

    return allCookies.split(";")
        .mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .firstOrNull { it.first == "sp_dc" && it.second.isNotBlank() }
        ?.second
}

private fun handleLoginSuccess(
    bl: String,
    view: WebView?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    navController: NavController,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
) {
    val cookieManager = CookieManager.getInstance()
    val allCookies = cookieManager.getCookie("https://open.spotify.com")
    BrokenLogin.i(bl, "token.begin", BrokenLogin.describeCookies(allCookies))

    val cookieMap = allCookies?.split(";")
        ?.mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                parts[0].trim() to parts[1].trim()
            } else {
                null
            }
        }?.toMap() ?: emptyMap()

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        BrokenLogin.e(bl, "token.noCookie", BrokenLogin.kv("cookieNames" to cookieMap.keys.joinToString(",").ifEmpty { "NONE" }))
        tokenFetchStarted.set(false)
        return
    }

    val spKey = cookieMap["sp_key"] ?: ""

    setProcessing(true)
    setError(false)
    setStatus(context.getString(R.string.spotify_status_verifying))

    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        try {
            context.dataStore.edit { it[SpotifySpDcKey] = spDc; it[SpotifySpKeyKey] = spKey }
            withContext(Dispatchers.Main) { setStatus(context.getString(R.string.spotify_status_connecting)) }
            
            val tokenResult = SpotifyAuth.fetchAccessToken(spDc, spKey)
            tokenResult.onFailure { BrokenLogin.fail(bl, "token.fetch", it) }
            val token = tokenResult.getOrThrow()
            
            BrokenLogin.i(bl, "token.ok", BrokenLogin.kv("anonymous" to token.isAnonymous, "expiresAt" to token.accessTokenExpirationTimestampMs))
            Spotify.accessToken = token.accessToken

            withContext(Dispatchers.Main) { setStatus(context.getString(R.string.spotify_status_loading_profile)) }
            Spotify.me().onSuccess { user ->
                context.dataStore.edit { it[SpotifyUsernameKey] = user.displayName ?: user.id; it[SpotifyUserIdKey] = user.id }
            }.onFailure { e ->
                Timber.w(e, "SpotifyLogin: could not fetch profile")
            }

            context.dataStore.edit { it[SpotifyAccessTokenKey] = token.accessToken; it[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs }

            withContext(Dispatchers.Main) {
                setStatus(context.getString(R.string.spotify_login_success))
                delay(500)
                navController.navigateUp()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BrokenLogin.fail(bl, "login.failed", e)
            withContext(Dispatchers.Main) {
                setError(true)
                setStatus(classifyLoginError(context, e))
            }
            tokenFetchStarted.set(false)
        }
    }
}

private fun classifyLoginError(context: Context, e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        "anonymous" in msg || "expired" in msg -> context.getString(R.string.spotify_login_error_expired)
        "HTTP 403" in msg || "HTTP 401" in msg -> context.getString(R.string.spotify_login_error_rejected)
        "gist" in msg.lowercase() || "nuance" in msg.lowercase() -> context.getString(R.string.spotify_login_error_network)
        "UnknownHostException" in msg || "timeout" in msg.lowercase() -> context.getString(R.string.spotify_login_error_network)
        else -> context.getString(R.string.spotify_login_error)
    }
}

private const val USER_AGENT_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
