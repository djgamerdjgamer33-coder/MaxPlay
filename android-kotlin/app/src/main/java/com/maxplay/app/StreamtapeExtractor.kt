package com.maxplay.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * MaxPlay Server 3 (Streamtape) On-Device Client-Side Extractor
 * ----------------------------------------------------------------------
 * Extracts IP-bound direct stream URLs (`https://streamtape.com/get_video?...&stream=1`)
 * on the user's Android device so the stream token matches the device's IP & User-Agent.
 *
 * Dual-Layer Pipeline:
 * 1. Fast OkHttp + Substring JS Evaluator (< 600ms):
 *    - Fetches watch/embed HTML with exact User-Agent & Referer: https://streamtape.com/
 *    - Evaluates the LAST `getElementById('robotlink'|'ideoolink'|'botlink').innerHTML = ...`
 *      assignment (completely ignoring Streamtape's fake static <span> token trap).
 * 2. Offscreen Headless WebView Fallback (500ms polling up to 15s):
 *    - Executes full page JS in a real offscreen WebView with ad/popup blocking.
 *    - Captures exact `webView.settings.userAgentString` paired with the extracted token.
 *    - Guarantees cleanup (`stopLoading()` + `destroy()` + view removal) in `finally`.
 * 3. Direct CDN Redirect + Content-Length Prober:
 *    - Resolves HTTP 302 final CDN location (`tapecontent.net`) and real file size in bytes.
 */
class StreamtapeExtractor(
    private val activity: Activity,
    private val mainWebView: WebView? = null
) {

    data class ExtractedStream(
        val directUrl: String,
        val userAgent: String,
        val referer: String = "https://streamtape.com/",
        val cdnUrl: String = "",
        val sizeBytes: Long = 0L,
        val mimeType: String = "video/mp4"
    )

    companion object {
        const val STREAMTAPE_REFERER = "https://streamtape.com/"

        val STREAMTAPE_DOMAINS = listOf(
            "streamtape.", "strtape.", "shavetape.", "streamta.pe", "tapecontent.net"
        )

        private val BLOCKED_AD_KEYWORDS = listOf(
            "doubleclick", "googlesyndication", "adservice", "popads", "popcash",
            "exoclick", "magsrv", "juicyads", "a-ads", "adsco", "onclick",
            "bet365", "1xbet", "casino", "clickadu", "hilltopads",
            "propellerads", "adsterra", "acscdn", "usrpubtrk", "tsyndicate"
        )

        fun isStreamtapeUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase().trim()
            return STREAMTAPE_DOMAINS.any { lower.contains(it) }
        }

        fun isDirectGetVideoUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase().trim()
            return lower.contains("/get_video") || lower.contains("tapecontent.net")
        }

        /**
         * Normalizes extracted Streamtape URLs:
         * - Prepends `https:` if protocol-relative (`//streamtape.com/get_video...`)
         * - Prepends `https://streamtape.com` if root-relative (`/get_video...`)
         * - Appends `&stream=1` if missing so server permits progressive MP4/MKV streaming
         */
        fun normalizeExtractedDirectUrl(raw: String): String {
            var resolved = raw.replace("\\/", "/").replace("&amp;", "&").trim()
            if (resolved.isEmpty()) return ""
            resolved = when {
                resolved.startsWith("https://", ignoreCase = true) ||
                    resolved.startsWith("http://", ignoreCase = true) -> resolved
                resolved.startsWith("//") -> "https:$resolved"
                resolved.startsWith("/") -> "https://streamtape.com$resolved"
                else -> "https://${resolved.trimStart('/')}"
            }
            if (!resolved.contains("stream=1", ignoreCase = true)) {
                resolved += if (resolved.contains("?")) "&stream=1" else "?stream=1"
            }
            return resolved
        }

        /**
         * Evaluates Streamtape's obfuscated JS string concatenation & `.substring(start[, end])` / `.slice(...)`
         * expression without executing arbitrary code on the main UI thread.
         */
        fun evaluateStreamtapeJsExpr(expr: String): String {
            if (expr.isBlank()) return ""
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            var inQuote: Char? = null
            var parenDepth = 0
            var i = 0
            while (i < expr.length) {
                val ch = expr[i]
                if (inQuote != null) {
                    if (ch == '\\' && i + 1 < expr.length) {
                        cur.append(ch).append(expr[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == inQuote) {
                        inQuote = null
                    }
                    cur.append(ch)
                } else {
                    when {
                        ch == '\'' || ch == '"' -> {
                            inQuote = ch
                            cur.append(ch)
                        }
                        ch == '(' -> {
                            parenDepth++
                            cur.append(ch)
                        }
                        ch == ')' -> {
                            parenDepth = maxOf(0, parenDepth - 1)
                            cur.append(ch)
                        }
                        ch == '+' && parenDepth == 0 -> {
                            val token = cur.toString().trim()
                            if (token.isNotEmpty()) parts.add(token)
                            cur.setLength(0)
                        }
                        else -> cur.append(ch)
                    }
                }
                i++
            }
            val tail = cur.toString().trim()
            if (tail.isNotEmpty()) parts.add(tail)

            fun hasMatchingOuterParens(s: String): Boolean {
                if (!s.startsWith("(") || !s.endsWith(")")) return false
                var depth = 0
                var q: Char? = null
                var esc = false
                for (idx in s.indices) {
                    val ch = s[idx]
                    if (esc) { esc = false; continue }
                    if (q != null) {
                        if (ch == '\\') esc = true else if (ch == q) q = null
                        continue
                    }
                    when (ch) {
                        '\'', '"' -> q = ch
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0 && idx < s.length - 1) return false
                        }
                    }
                }
                return depth == 0
            }

            val suffixPattern = Pattern.compile("^(.*)\\.(substring|substr|slice)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)\\s*$", Pattern.DOTALL)

            fun evalTerm(rawTerm: String, depth: Int = 0): String {
                if (depth > 16) return ""
                var s = rawTerm.trim()
                if (s.isEmpty()) return ""

                val ops = mutableListOf<Triple<String, Int, Int?>>()
                while (true) {
                    val m = suffixPattern.matcher(s)
                    if (!m.matches()) break
                    s = (m.group(1) ?: "").trim()
                    ops.add(0, Triple(m.group(2) ?: "substring", m.group(3)?.toIntOrNull() ?: 0, m.group(4)?.toIntOrNull()))
                }

                var value = when {
                    hasMatchingOuterParens(s) -> evaluateStreamtapeJsExpr(s.substring(1, s.length - 1))
                    (s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")) ->
                        s.substring(1, s.length - 1)
                            .replace("\\/", "/")
                            .replace("\\'", "'")
                            .replace("\\\"", "\"")
                    else -> ""
                }

                for ((opName, startIdx, endIdx) in ops) {
                    val safeStart = startIdx.coerceIn(0, value.length)
                    value = when (opName) {
                        "substr" -> {
                            if (endIdx != null) {
                                val safeEnd = (safeStart + endIdx).coerceIn(safeStart, value.length)
                                value.substring(safeStart, safeEnd)
                            } else value.substring(safeStart)
                        }
                        else -> {
                            if (endIdx != null) {
                                val safeEnd = endIdx.coerceIn(safeStart, value.length)
                                value.substring(safeStart, safeEnd)
                            } else value.substring(safeStart)
                        }
                    }
                }
                return value
            }

            val out = StringBuilder()
            for (part in parts) {
                out.append(evalTerm(part, 1))
            }
            return out.toString().trim()
        }

        /**
         * Extracts the genuine `get_video` URL from Streamtape HTML by scanning ALL assignments to
         * `getElementById('robotlink'|'ideoolink'|'botlink').innerHTML = ...` and evaluating from LAST to FIRST
         * so the static fake-token DOM trap is ignored.
         */
        fun extractDirectUrlFromHtml(html: String): String? {
            if (html.isBlank()) return null
            val assignPattern = Pattern.compile(
                """getElementById\s*\(\s*['"](?:robotlink|ideoolink|botlink)['"]\s*\)\.innerHTML\s*=\s*([^;]+);""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = assignPattern.matcher(html)
            val expressions = mutableListOf<String>()
            while (matcher.find()) {
                val expr = matcher.group(1)?.trim()
                if (!expr.isNullOrEmpty()) {
                    expressions.add(expr)
                }
            }
            for (idx in expressions.indices.reversed()) {
                val evaluated = evaluateStreamtapeJsExpr(expressions[idx])
                if (evaluated.contains("get_video?", ignoreCase = true) &&
                    evaluated.contains("token=", ignoreCase = true)
                ) {
                    val normalized = normalizeExtractedDirectUrl(evaluated)
                    if (normalized.isNotEmpty()) return normalized
                }
            }
            return null
        }

        fun inferMetadataFromPage(sourceUrl: String, html: String): Triple<String, List<String>, String> {
            val ogMatch = Pattern.compile(
                """<meta[^>]+(?:name|property)=["']og:title["'][^>]+content=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            val titleTagMatch = Pattern.compile(
                """<title>([^<]+)</title>""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)

            val ogTitle = if (ogMatch.find()) (ogMatch.group(1) ?: "") else if (titleTagMatch.find()) (titleTagMatch.group(1) ?: "") else ""
            val decodedUrl = try {
                URLDecoder.decode(sourceUrl, "UTF-8")
            } catch (_: Exception) {
                sourceUrl
            }
            val rawMeta = "$ogTitle $decodedUrl".lowercase()

            val quality = when {
                rawMeta.contains("2160p") || rawMeta.contains("4k") -> "2160p"
                rawMeta.contains("1080p") || rawMeta.contains("fhd") || rawMeta.contains("bluray") -> "1080p"
                rawMeta.contains("720p") || rawMeta.contains("hd") -> "720p"
                rawMeta.contains("480p") -> "480p"
                rawMeta.contains("360p") -> "360p"
                else -> "1080p"
            }

            val audioTracks = mutableListOf<String>()
            fun addAudio(lang: String) {
                if (!audioTracks.contains(lang)) audioTracks.add(lang)
            }
            if (rawMeta.contains("hindi") || Regex("\\bhin\\b").containsMatchIn(rawMeta)) addAudio("Hindi")
            if (rawMeta.contains("japanese") || Regex("\\bjpn\\b|\\bjap\\b").containsMatchIn(rawMeta)) addAudio("Japanese")
            if (rawMeta.contains("english") || Regex("\\beng\\b").containsMatchIn(rawMeta)) addAudio("English")
            if (rawMeta.contains("tamil") || Regex("\\btam\\b").containsMatchIn(rawMeta)) addAudio("Tamil")
            if (rawMeta.contains("telugu") || Regex("\\btel\\b").containsMatchIn(rawMeta)) addAudio("Telugu")
            if (rawMeta.contains("korean") || Regex("\\bkor\\b").containsMatchIn(rawMeta)) addAudio("Korean")
            if ((rawMeta.contains("dual_audio") || rawMeta.contains("dual audio") || rawMeta.contains("multi")) && audioTracks.isEmpty()) {
                addAudio("Hindi")
                addAudio("English")
            }

            val mimeType = if (rawMeta.contains(".mkv") && !rawMeta.contains(".mkv.mp4") && !rawMeta.endsWith(".mp4")) "video/x-matroska" else "video/mp4"
            return Triple(quality, audioTracks, mimeType)
        }
    }

    @Volatile
    private var cachedMainUa: String? = null

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                cachedMainUa = mainWebView?.settings?.userAgentString?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {}
        } else {
            Handler(Looper.getMainLooper()).post {
                try {
                    cachedMainUa = mainWebView?.settings?.userAgentString?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {}
            }
        }
    }

    private fun resolveUserAgent(): String {
        cachedMainUa?.takeIf { it.isNotBlank() }?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                val ua = mainWebView?.settings?.userAgentString?.takeIf { it.isNotBlank() }
                if (ua != null) {
                    cachedMainUa = ua
                    return ua
                }
            } catch (_: Exception) {}
        }
        return InbuiltStreamScraper.DEFAULT_USER_AGENT
    }

    /**
     * Resolves Streamtape's HTTP 302 CDN redirect (`tapecontent.net`) and Content-Length size in bytes
     * without buffering the video payload.
     */
    fun resolveRedirectAndSize(
        directUrl: String,
        userAgent: String,
        referer: String = STREAMTAPE_REFERER
    ): Triple<String, Long, String> {
        var resolvedCdnUrl = directUrl
        var sizeBytes = 0L
        var mimeType = "video/mp4"

        // Single Range: bytes=0-0 GET resolves 302 redirect + exact total file size + MIME in 1 round-trip!
        try {
            val rangeReq = Request.Builder()
                .url(directUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Origin", "https://streamtape.com")
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
                .build()

            InbuiltStreamScraper.okHttpClient.newCall(rangeReq).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (finalUrl.startsWith("http")) {
                    resolvedCdnUrl = finalUrl
                }
                val cr = resp.header("Content-Range") ?: ""
                val slashIdx = cr.lastIndexOf('/')
                if (slashIdx != -1) {
                    val total = cr.substring(slashIdx + 1).trim().toLongOrNull() ?: 0L
                    if (total > 100_000L) sizeBytes = total
                }
                if (sizeBytes <= 0L) {
                    val cl = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    if (cl > 100_000L) sizeBytes = cl
                }
                val ct = (resp.header("Content-Type") ?: "").substringBefore(";").trim().lowercase()
                if (ct.startsWith("video/")) mimeType = ct
            }
        } catch (_: Exception) {}

        return Triple(resolvedCdnUrl, sizeBytes, mimeType)
    }

    /**
     * Synchronous OkHttp + Headless WebView extraction pipeline (call off the Android Main Thread).
     */
    fun extractStreamSync(sourceUrl: String): JSONObject {
        val trimmed = sourceUrl.trim()
        val ua = resolveUserAgent()

        // Already a direct get_video or tapecontent CDN URL
        if (isDirectGetVideoUrl(trimmed)) {
            val normalizedDirect = normalizeExtractedDirectUrl(trimmed)
            CorsFreeWebViewClient.registerStreamReferer(normalizedDirect, STREAMTAPE_REFERER, ua)
            val (cdnUrl, sizeBytes, mime) = resolveRedirectAndSize(normalizedDirect, ua, STREAMTAPE_REFERER)
            if (cdnUrl.isNotEmpty() && cdnUrl != normalizedDirect) {
                CorsFreeWebViewClient.registerStreamReferer(cdnUrl, STREAMTAPE_REFERER, ua)
            }
            val (quality, audios, inferredMime) = inferMetadataFromPage(trimmed, "")
            val finalMime = if (mime != "video/mp4") mime else inferredMime
            val stream = ExtractedStream(
                directUrl = normalizedDirect,
                userAgent = ua,
                referer = STREAMTAPE_REFERER,
                cdnUrl = cdnUrl,
                sizeBytes = sizeBytes,
                mimeType = finalMime
            )
            return toScraperJson(trimmed, stream, "kotlin_streamtape_direct", quality, audios)
        }

        // Layer 1: Fast OkHttp HTML fetch + JS substring evaluator (< 600ms)
        val watchCandidates = buildList {
            add(trimmed)
            val embedVariant = trimmed.replace(Regex("/v/", RegexOption.IGNORE_CASE), "/e/")
            if (embedVariant != trimmed) add(embedVariant)
        }

        for (candidateUrl in watchCandidates) {
            try {
                val req = Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", STREAMTAPE_REFERER)
                    .build()

                InbuiltStreamScraper.okHttpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string() ?: ""
                    if (html.isNotBlank()) {
                        val directUrl = extractDirectUrlFromHtml(html)
                        if (!directUrl.isNullOrBlank()) {
                            CorsFreeWebViewClient.registerStreamReferer(directUrl, STREAMTAPE_REFERER, ua)
                            val (cdnUrl, sizeBytes, probedMime) = resolveRedirectAndSize(directUrl, ua, STREAMTAPE_REFERER)
                            if (cdnUrl.isNotEmpty() && cdnUrl != directUrl) {
                                CorsFreeWebViewClient.registerStreamReferer(cdnUrl, STREAMTAPE_REFERER, ua)
                            }
                            val (quality, audios, inferredMime) = inferMetadataFromPage(trimmed, html)
                            val finalMime = if (probedMime != "video/mp4") probedMime else inferredMime
                            val stream = ExtractedStream(
                                directUrl = directUrl,
                                userAgent = ua,
                                referer = STREAMTAPE_REFERER,
                                cdnUrl = cdnUrl,
                                sizeBytes = sizeBytes,
                                mimeType = finalMime
                            )
                            return toScraperJson(trimmed, stream, "kotlin_streamtape_okhttp", quality, audios)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Layer 2: Client-side Headless Offscreen WebView Scraping (blocks ads/popups, 500ms polling up to 15s)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<JSONObject?>(null)
            extractStreamViaHeadlessWebView(trimmed, 15000L) { res ->
                resultRef.set(res)
                latch.countDown()
            }
            try {
                latch.await(18, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            resultRef.get()?.let { return it }
        }

        return JSONObject().apply {
            put("ok", false)
            put("provider", "STREAMTAPE")
            put("needsHeadlessSniffer", true)
            put("error", "Streamtape extraction timed out or link expired")
        }
    }

    /**
     * Asynchronous entry point (runs fast OkHttp first, then Headless WebView if needed).
     */
    fun extractStreamAsync(
        sourceUrl: String,
        onResult: (JSONObject) -> Unit
    ) {
        InbuiltStreamScraper.backgroundExecutor.execute {
            val res = extractStreamSync(sourceUrl)
            Handler(Looper.getMainLooper()).post {
                onResult(res)
            }
        }
    }

    /**
     * Offscreen Headless WebView Scraper for Streamtape:
     * - Polls `robotlink` / `ideoolink` / `botlink` every 500ms up to `timeoutMs` (default 15,000ms)
     * - Also listens in `shouldInterceptRequest` for `/get_video` or `tapecontent.net` requests
     * - Blocks popups, new windows, trackers, and image assets
     * - Captures exact `webView.settings.userAgentString`
     * - Strictly executes `webView.stopLoading()` + `webView.destroy()` in cleanup/`finally`
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun extractStreamViaHeadlessWebView(
        sourceUrl: String,
        timeoutMs: Long = 15000L,
        onResult: (JSONObject) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val isResolved = AtomicBoolean(false)
        val trimmed = sourceUrl.trim()

        mainHandler.post {
            var headlessWebView: WebView? = null
            var pollRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var capturedUa = InbuiltStreamScraper.DEFAULT_USER_AGENT
            var latestHtmlHint = ""

            val parentContainer: ViewGroup? = try {
                (activity as? MainActivity)?.getScraperContainer()
                    ?: activity.findViewById(android.R.id.content)
            } catch (_: Exception) {
                null
            }

            fun cleanupWebView() {
                try {
                    pollRunnable?.let { mainHandler.removeCallbacks(it) }
                    timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                } catch (_: Exception) {}
                val wv = headlessWebView
                headlessWebView = null
                if (wv != null) {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.removeAllViews()
                        wv.destroy()
                    } catch (_: Exception) {}
                }
            }

            fun finishSuccess(rawDirectUrl: String, pageTitleHint: String, methodTag: String) {
                val normalized = normalizeExtractedDirectUrl(rawDirectUrl)
                if (!normalized.contains("get_video", ignoreCase = true) &&
                    !normalized.contains("tapecontent.net", ignoreCase = true)
                ) {
                    return
                }
                if (!isResolved.compareAndSet(false, true)) return

                cleanupWebView()
                val ua = capturedUa.ifBlank { InbuiltStreamScraper.DEFAULT_USER_AGENT }
                CorsFreeWebViewClient.registerStreamReferer(normalized, STREAMTAPE_REFERER, ua)

                InbuiltStreamScraper.backgroundExecutor.execute {
                    try {
                        val (cdnUrl, sizeBytes, probedMime) = resolveRedirectAndSize(normalized, ua, STREAMTAPE_REFERER)
                        if (cdnUrl.isNotEmpty() && cdnUrl != normalized) {
                            CorsFreeWebViewClient.registerStreamReferer(cdnUrl, STREAMTAPE_REFERER, ua)
                        }
                        val (quality, audios, inferredMime) = inferMetadataFromPage(trimmed, "$pageTitleHint $latestHtmlHint")
                        val finalMime = if (probedMime != "video/mp4") probedMime else inferredMime
                        val stream = ExtractedStream(
                            directUrl = normalized,
                            userAgent = ua,
                            referer = STREAMTAPE_REFERER,
                            cdnUrl = cdnUrl,
                            sizeBytes = sizeBytes,
                            mimeType = finalMime
                        )
                        val json = toScraperJson(trimmed, stream, methodTag, quality, audios)
                        onResult(json)
                    } catch (e: Exception) {
                        val (quality, audios, inferredMime) = inferMetadataFromPage(trimmed, pageTitleHint)
                        val stream = ExtractedStream(
                            directUrl = normalized,
                            userAgent = ua,
                            referer = STREAMTAPE_REFERER,
                            cdnUrl = normalized,
                            sizeBytes = 0L,
                            mimeType = inferredMime
                        )
                        onResult(toScraperJson(trimmed, stream, methodTag, quality, audios))
                    }
                }
            }

            timeoutRunnable = Runnable {
                if (isResolved.compareAndSet(false, true)) {
                    cleanupWebView()
                    onResult(JSONObject().apply {
                        put("ok", false)
                        put("provider", "STREAMTAPE")
                        put("error", "Streamtape Headless WebView timed out after ${timeoutMs}ms")
                    })
                }
            }
            mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)

            try {
                headlessWebView = WebView(activity).apply {
                    alpha = 0.01f
                    translationX = -2500f
                    translationY = -2500f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    visibility = View.VISIBLE
                    layoutParams = FrameLayout.LayoutParams(320, 180)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = resolveUserAgent()
                    }
                    capturedUa = settings.userAgentString ?: InbuiltStreamScraper.DEFAULT_USER_AGENT

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            return false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val navUrl = request?.url?.toString() ?: return false
                            val lower = navUrl.lowercase()
                            if (BLOCKED_AD_KEYWORDS.any { lower.contains(it) }) return true
                            if (request.isForMainFrame && !isStreamtapeUrl(navUrl) && lower != "about:blank") {
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val lower = reqUrl.lowercase()
                            val pathOnly = (request.url?.path ?: "").lowercase()

                            // Intercept real Streamtape direct video request if triggered by player
                            if (lower.contains("get_video?") && lower.contains("token=")) {
                                mainHandler.post {
                                    finishSuccess(reqUrl, "", "kotlin_streamtape_webview_intercept")
                                }
                            }

                            // Block ad networks, popups, analytics, stylesheets, and heavy images to keep scrape ultra fast
                            if (BLOCKED_AD_KEYWORDS.any { lower.contains(it) } ||
                                pathOnly.endsWith(".png") ||
                                pathOnly.endsWith(".jpg") ||
                                pathOnly.endsWith(".jpeg") ||
                                pathOnly.endsWith(".gif") ||
                                pathOnly.endsWith(".webp") ||
                                pathOnly.endsWith(".woff2") ||
                                pathOnly.endsWith(".woff")
                            ) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                }

                parentContainer?.addView(headlessWebView)

                val domProbeJs = """
                    (function() {
                        try {
                            window.open = function() { return null; };
                            var titleText = '';
                            try {
                                var og = document.querySelector('meta[property="og:title"], meta[name="og:title"]');
                                titleText = (og && og.getAttribute('content')) || document.title || '';
                            } catch (e) {}

                            var ids = ['robotlink', 'ideoolink', 'botlink'];
                            for (var i = 0; i < ids.length; i++) {
                                var el = document.getElementById(ids[i]);
                                if (el) {
                                    var txt = (el.innerText || el.textContent || el.innerHTML || '').replace(/<[^>]*>/g, '').trim();
                                    if (txt && txt.indexOf('get_video') !== -1 && txt.indexOf('token=') !== -1) {
                                        return JSON.stringify({ url: txt, title: titleText });
                                    }
                                }
                            }

                            var vids = document.querySelectorAll('video, source');
                            for (var v = 0; v < vids.length; v++) {
                                var src = vids[v].currentSrc || vids[v].src || vids[v].getAttribute('src') || '';
                                if (src && (src.indexOf('get_video') !== -1 || src.indexOf('tapecontent.net') !== -1)) {
                                    return JSON.stringify({ url: src, title: titleText });
                                }
                            }
                        } catch (e) {}
                        return '';
                    })();
                """.trimIndent()

                pollRunnable = object : Runnable {
                    override fun run() {
                        if (isResolved.get() || headlessWebView == null) return
                        headlessWebView?.evaluateJavascript(domProbeJs) { rawRes ->
                            if (isResolved.get()) return@evaluateJavascript
                            if (!rawRes.isNullOrBlank() && rawRes != "null" && rawRes != "\"\"") {
                                try {
                                    val unquoted = JSONArray("[$rawRes]").optString(0, "")
                                    if (unquoted.startsWith("{")) {
                                        val obj = JSONObject(unquoted)
                                        val foundUrl = obj.optString("url", "").trim()
                                        val pageTitle = obj.optString("title", "")
                                        if (foundUrl.contains("get_video") && foundUrl.contains("token=")) {
                                            finishSuccess(foundUrl, pageTitle, "kotlin_streamtape_headless_webview")
                                            return@evaluateJavascript
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        if (!isResolved.get()) {
                            mainHandler.postDelayed(this, 500L)
                        }
                    }
                }

                headlessWebView?.loadUrl(trimmed, mapOf("Referer" to STREAMTAPE_REFERER))
                mainHandler.postDelayed(pollRunnable!!, 500L)
            } catch (e: Exception) {
                try {
                    if (isResolved.compareAndSet(false, true)) {
                        onResult(JSONObject().apply {
                            put("ok", false)
                            put("provider", "STREAMTAPE")
                            put("error", e.message ?: "Headless WebView initialization failed")
                        })
                    }
                } finally {
                    cleanupWebView()
                }
            }
        }
    }

    private fun toScraperJson(
        sourceUrl: String,
        stream: ExtractedStream,
        method: String,
        quality: String,
        audioTracks: List<String>
    ): JSONObject {
        val qualitiesArr = JSONArray().put(quality)
        val qualityLinksObj = JSONObject().put(quality, stream.directUrl)
        val audioArr = JSONArray().apply {
            audioTracks.forEach { put(it) }
        }
        val sizeMb = if (stream.sizeBytes > 100_000L) {
            maxOf(1, kotlin.math.round(stream.sizeBytes.toDouble() / (1024.0 * 1024.0)).toInt())
        } else {
            when (quality) {
                "2160p" -> 1850
                "1080p" -> 950
                "720p" -> 550
                "480p" -> 320
                else -> 220
            }
        }

        val dlArr = JSONArray().put(JSONObject().apply {
            put("id", quality)
            put(
                "label",
                when (quality) {
                    "2160p" -> "2160p 4K Ultra HD"
                    "1080p" -> "1080p Full HD"
                    "720p" -> "720p HD"
                    else -> "$quality Standard"
                }
            )
            put("sizeMb", sizeMb)
            put("sizeBytes", stream.sizeBytes)
            put("url", sourceUrl)
            put("resolvedUrl", stream.directUrl)
            put("cdnUrl", stream.cdnUrl)
            put("mimeType", stream.mimeType)
        })

        val bestPlayUrl = stream.cdnUrl.ifBlank { stream.directUrl }
        return JSONObject().apply {
            put("ok", true)
            put("m3u8Url", bestPlayUrl)
            put("directUrl", stream.directUrl)
            put("cdnUrl", bestPlayUrl)
            put("sizeBytes", stream.sizeBytes)
            put("sizeMb", sizeMb)
            put("mimeType", stream.mimeType)
            put("isDirectVideo", true)
            put("provider", "STREAMTAPE")
            put("method", method)
            put("qualities", qualitiesArr)
            put("qualityLinks", qualityLinksObj)
            put("downloadQualities", dlArr)
            put("audioTracks", audioArr)
            put("headers", JSONObject().apply {
                put("Referer", stream.referer)
                put("Origin", "https://streamtape.com")
                put("User-Agent", stream.userAgent)
            })
        }
    }
}
