package com.maxplay.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * MaxPlay Native Kotlin MainActivity
 * ----------------------------------------------------------------------
 * Hosts the MaxPlay application in a hardware-accelerated, CORS-free Android WebView:
 * - Disables universal file & cross-origin security blocks (`allowUniversalAccessFromFileURLs = true`)
 * - Registers `window.MaxPlayNative` (@JavascriptInterface) for inbuilt stream scraping & segment downloads
 * - Supports both bundled local offline assets (`file:///android_asset/webapp/index.html`)
 *   and live remote cloud synchronization
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileUploadCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val bgNotificationScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile
    var autoPipEnabled: Boolean = true
    @Volatile
    var isVideoCurrentlyPlaying: Boolean = false
    private var popunderDialog: android.app.Dialog? = null
    private var popunderWebView: WebView? = null

    fun getScraperContainer(): ViewGroup = rootContainer

    companion object {
        // Primary entry: HTTPS virtual origin mapped to assets/webapp/ inside APK (avoids file:// ES module & path block!)
        const val LOCAL_BUNDLE_URL = "https://appassets.androidplatform.net/index.html"
        const val LOCAL_ADMIN_BUNDLE_URL = "https://appassets.androidplatform.net/admin.html"
        const val LIVE_APP_URL = "https://ais-pre-yzg6gpi5jxavdg2ivlhyef-775675507395.asia-southeast1.run.app"
        const val LIVE_ADMIN_URL = "https://ais-pre-yzg6gpi5jxavdg2ivlhyef-775675507395.asia-southeast1.run.app/admin.html"
        const val FIRESTORE_MESSAGES_REST_URL =
            "https://firestore.googleapis.com/v1/projects/gen-lang-client-0291911037/databases/ai-studio-maxplay-e5163e97-7cd5-4a42-875c-8b5dce9fd72d/documents/messages?pageSize=60"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val prefs = getSharedPreferences("maxplay_native_prefs", MODE_PRIVATE)
            autoPipEnabled = prefs.getBoolean("auto_pip", true)
        } catch (_: Exception) {}

        // Keep screen on during streaming & downloads and use deep #0A0A0A status bar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0A0A0A")
        window.navigationBarColor = Color.parseColor("#0A0A0A")

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootContainer.addView(webView)
        setContentView(rootContainer)

        configureCorsFreeWebView()
        InbuiltStreamScraper.prewarmByseAttestationAsync(this)

        val isAdminApp = packageName.endsWith(".admin", ignoreCase = true)

        // Load bundled webapp assets over HTTPS virtual origin if present in APK, otherwise load live MaxPlay URL
        val targetAssetFile = if (isAdminApp) "admin.html" else "index.html"
        val hasLocalBundle = try {
            assets.list("webapp")?.contains(targetAssetFile) == true
        } catch (_: Exception) {
            false
        }

        if (isAdminApp) {
            if (hasLocalBundle) {
                webView.loadUrl(LOCAL_ADMIN_BUNDLE_URL)
            } else {
                webView.loadUrl(LIVE_ADMIN_URL)
            }
        } else {
            if (hasLocalBundle) {
                webView.loadUrl(LOCAL_BUNDLE_URL)
            } else {
                webView.loadUrl(LIVE_APP_URL)
            }
        }

        // Create notification channel & request notification + microphone permissions upfront
        createNotificationChannel()
        requestStartupPermissions()

        // Start native background poller for Firestore Admin messages so notifications arrive even when minimized
        if (!isAdminApp) {
            startBackgroundAnnouncementMonitor()
        }

        // Check if launched from tapping a status-bar notification
        handleNotificationIntent(intent)

        // Handle Android hardware Back button (Triangle button) smoothly via React SPA handler first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isNativePopunderAdOpen()) {
                    closeNativePopunderAd()
                    return
                }
                if (customFullscreenView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                webView.evaluateJavascript(
                    "(function() { return typeof window.__handleMaxPlayHardwareBack === 'function' ? String(window.__handleMaxPlayHardwareBack()) : 'UNHANDLED'; })()"
                ) { result ->
                    val clean = result?.trim()?.removeSurrounding("\"") ?: "UNHANDLED"
                    if (clean == "HANDLED") {
                        // Handled inside React SPA (popped screen, closed modal, or showed exit confirmation)
                        return@evaluateJavascript
                    } else if (clean == "EXIT_CONFIRMED") {
                        finish()
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("open_messages", false) == true) {
            intent.removeExtra("open_messages")
            webView.postDelayed({
                try {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('MAXPLAY_OPEN_MESSAGES_SCREEN'));",
                        null
                    )
                } catch (_: Exception) {}
            }, 900)
        }

        // Handle content share deep link: maxplay://watch/<id>, https://.../open/<id>, or ?watch=<id>
        val dataUri = intent?.data
        if (dataUri != null) {
            var targetContentId: String? = dataUri.getQueryParameter("watch")?.trim()
            if (targetContentId.isNullOrEmpty()) {
                val scheme = dataUri.scheme?.lowercase() ?: ""
                val host = dataUri.host?.lowercase() ?: ""
                val path = dataUri.path ?: ""
                if (scheme == "maxplay" && host == "watch") {
                    targetContentId = path.trimStart('/').trim()
                } else if (path.startsWith("/open/")) {
                    targetContentId = path.removePrefix("/open/").trimStart('/').trim()
                }
            }
            if (!targetContentId.isNullOrEmpty()) {
                val safeId = org.json.JSONObject.quote(targetContentId)
                intent.data = null
                val dispatchJs = """
                    (function() {
                        window.__MAXPLAY_PENDING_WATCH_ID = $safeId;
                        window.dispatchEvent(new CustomEvent('MAXPLAY_OPEN_WATCH_CONTENT', { detail: { contentId: $safeId } }));
                    })();
                """.trimIndent()
                webView.postDelayed({
                    try {
                        webView.evaluateJavascript(dispatchJs, null)
                    } catch (_: Exception) {}
                }, 700)
                webView.postDelayed({
                    try {
                        webView.evaluateJavascript(dispatchJs, null)
                    } catch (_: Exception) {}
                }, 1800)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureCorsFreeWebView() {
        // Security Hardening: Explicitly disable remote Chrome DevTools WebView inspection & check runtime integrity
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            verifyRuntimeSecurityIntegrity()
        } catch (_: Exception) {}

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true

            // Critical: Disable browser CORS restrictions for local & cross-origin requests!
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = InbuiltStreamScraper.DEFAULT_USER_AGENT
        }

        // Attach Native Kotlin Bridge (`window.MaxPlayNative`)
        webView.addJavascriptInterface(MaxPlayNativeBridge(this, webView), "MaxPlayNative")

        // Attach CORS-Free OkHttp Interceptor & HTTPS Local Asset Client
        webView.webViewClient = CorsFreeWebViewClient(this)

        // Fullscreen HTML5 Video, File Chooser (Local Video & .maxplay Receive), Microphone Permission & Orientation Handler
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                runOnUiThread {
                    try {
                        request?.grant(request.resources)
                    } catch (_: Exception) {}
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                try {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                    val wantsVideo = acceptTypes.any { it.contains("video", ignoreCase = true) }
                    val wantsAudio = acceptTypes.any {
                        it.contains("audio", ignoreCase = true) ||
                        it.contains(".mp3", ignoreCase = true) ||
                        it.contains(".m4a", ignoreCase = true) ||
                        it.contains(".flac", ignoreCase = true)
                    }
                    val allowMulti = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE || wantsAudio

                    val pickerIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = when {
                            wantsVideo -> "video/*"
                            wantsAudio -> "audio/*"
                            else -> "*/*"
                        }
                        if (allowMulti) {
                            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        android.content.Intent.createChooser(pickerIntent, "Select File for MaxPlay"),
                        3002
                    )
                    return true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    return false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customFullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customFullscreenView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                rootContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setImmersiveSystemUi(true)
            }

            override fun onHideCustomView() {
                val view = customFullscreenView ?: return
                rootContainer.removeView(view)
                customFullscreenView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                setImmersiveSystemUi(false)
            }
        }
    }

    private fun setImmersiveSystemUi(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Adjusts Android hardware window screen brightness (10 to 100%).
     */
    fun setHardwareBrightness(percent: Int) {
        runOnUiThread {
            try {
                val clamped = percent.coerceIn(5, 100)
                val lp = window.attributes
                lp.screenBrightness = (clamped / 100f).coerceIn(0.05f, 1.0f)
                window.attributes = lp
            } catch (_: Exception) {}
        }
    }

    /**
     * Reads current hardware window screen brightness (10 to 100%).
     */
    fun getHardwareBrightness(): Int {
        return try {
            val current = window.attributes.screenBrightness
            if (current < 0f) {
                val sys = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
                ((sys / 255f) * 100f).toInt().coerceIn(10, 100)
            } else {
                (current * 100f).toInt().coerceIn(10, 100)
            }
        } catch (_: Exception) {
            75
        }
    }

    /**
     * Adjusts Android STREAM_MUSIC hardware volume (0 to 100%).
     */
    fun setHardwareVolume(percent: Int) {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val clamped = percent.coerceIn(0, 100)
            val targetVol = Math.round((clamped / 100f) * maxVol).coerceIn(0, maxVol)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
        } catch (_: Exception) {}
    }

    /**
     * Reads current Android STREAM_MUSIC hardware volume percentage (0 to 100%).
     */
    fun getHardwareVolume(): Int {
        return try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return 100
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            Math.round((curVol.toFloat() / maxVol.toFloat()) * 100f).coerceIn(0, 100)
        } catch (_: Exception) {
            100
        }
    }

    /**
     * Dynamically sets the Android top system Status Bar color (where time, network, and battery indicators sit)
     * to match the Hero Banner's ambient glow color.
     */
    fun setStatusBarColorHex(hexColor: String) {
        runOnUiThread {
            try {
                window.statusBarColor = Color.parseColor(hexColor)
            } catch (_: Exception) {
                window.statusBarColor = Color.parseColor("#0A0A0A")
            }
        }
    }

    fun setAutoPipPreference(enabled: Boolean) {
        autoPipEnabled = enabled
        try {
            getSharedPreferences("maxplay_native_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("auto_pip", enabled)
                .apply()
        } catch (_: Exception) {}
    }

    fun setVideoPlayingState(isPlaying: Boolean) {
        isVideoCurrentlyPlaying = isPlaying
    }

    /**
     * Automatically triggers Native Android Picture-in-Picture (PiP) when the user presses Home / minimizes the app
     * while a video is actively playing (regardless of the in-app Back button Auto-Miniplayer toggle).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoCurrentlyPlaying) {
            enterNativePipMode()
        }
    }

    /**
     * Runtime anti-tamper, debugger, and Frida hook inspection guard.
     */
    private fun verifyRuntimeSecurityIntegrity() {
        try {
            if (android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()) {
                finishAffinity()
                return
            }
            val mapsFile = java.io.File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                val mapsContent = mapsFile.readText()
                if (mapsContent.contains("frida-agent", ignoreCase = true) ||
                    mapsContent.contains("frida-gadget", ignoreCase = true) ||
                    mapsContent.contains("xposedbridge", ignoreCase = true)
                ) {
                    finishAffinity()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Enters native Android Picture-in-Picture (PiP) floating window mode (Android 8.0+).
     * Immediately notifies the React player so ONLY the video player fills the PiP window.
     */
    fun enterNativePipMode(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                runOnUiThread {
                    try {
                        webView.evaluateJavascript(
                            "window.__onMaxPlayPipModeChanged && window.__onMaxPlayPipModeChanged(true);",
                            null
                        )
                        val params = android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(android.util.Rational(16, 9))
                            .build()
                        enterPictureInPictureMode(params)
                    } catch (_: Exception) {}
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "window.__onMaxPlayPipModeChanged && window.__onMaxPlayPipModeChanged($isInPictureInPictureMode);",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private var bgDownloadWakeLock: android.os.PowerManager.WakeLock? = null

    private fun createNotificationChannel() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val channel = android.app.NotificationChannel(
                    "maxplay_inbox_alerts",
                    "MaxPlay Messages & Updates",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Real-time notifications for MaxPlay inbox messages and updates"
                    enableVibration(true)
                }
                val dlChannel = android.app.NotificationChannel(
                    "maxplay_bg_downloads",
                    "MaxPlay Background Downloads",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live background download progress for movies and episodes"
                    enableVibration(false)
                }
                val musicChannel = android.app.NotificationChannel(
                    "maxplay_music_player",
                    "MaxPlay Music Player",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification shade and lock-screen playback controls for MaxPlay Music"
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm?.createNotificationChannel(channel)
                nm?.createNotificationChannel(dlChannel)
                nm?.createNotificationChannel(musicChannel)
            }
        } catch (_: Exception) {}
    }

    private fun requestStartupPermissions() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val needed = mutableListOf<String>()
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        needed.add("android.permission.POST_NOTIFICATIONS")
                    }
                    if (checkSelfPermission("android.permission.READ_MEDIA_VIDEO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        needed.add("android.permission.READ_MEDIA_VIDEO")
                    }
                    if (checkSelfPermission("android.permission.READ_MEDIA_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        needed.add("android.permission.READ_MEDIA_AUDIO")
                    }
                } else {
                    if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.RECORD_AUDIO)
                }
                if (needed.isNotEmpty()) {
                    requestPermissions(needed.toTypedArray(), 1001)
                }
            }
        } catch (_: Exception) {}
    }

    fun requestBackgroundDownloadAndNotifPermission(): Boolean {
        return try {
            runOnUiThread {
                try {
                    requestStartupPermissions()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                            val prefs = getSharedPreferences("maxplay_native_prefs", MODE_PRIVATE)
                            val alreadyPrompted = prefs.getBoolean("bg_battery_prompted", false)
                            if (!alreadyPrompted) {
                                prefs.edit().putBoolean("bg_battery_prompted", true).apply()
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    fun updateBackgroundDownloadState(active: Boolean, title: String, progress: Int) {
        runOnUiThread {
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                if (active) {
                    webView.resumeTimers()
                    if (bgDownloadWakeLock == null) {
                        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                        bgDownloadWakeLock = pm?.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK,
                            "MaxPlay::BackgroundDownloadWakeLock"
                        )?.apply {
                            setReferenceCounted(false)
                        }
                    }
                    if (bgDownloadWakeLock?.isHeld == false) {
                        bgDownloadWakeLock?.acquire(4 * 60 * 60 * 1000L)
                    }

                    if (nm != null) {
                        val pct = progress.coerceIn(0, 100)
                        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.app.Notification.Builder(this, "maxplay_bg_downloads")
                        } else {
                            @Suppress("DEPRECATION")
                            android.app.Notification.Builder(this)
                        }
                        builder.setSmallIcon(R.drawable.ic_stat_maxplay)
                            .setContentTitle(title.ifBlank { "Downloading in MaxPlay" })
                            .setContentText("Downloading in background ($pct%)")
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setProgress(100, pct, pct <= 0)
                        nm.notify(9099, builder.build())
                    }
                } else {
                    if (bgDownloadWakeLock?.isHeld == true) {
                        bgDownloadWakeLock?.release()
                    }
                    nm?.cancel(9099)
                }
            } catch (_: Exception) {}
        }
    }

    fun requestVideoStoragePermission(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO")
                } else {
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                val missing = perms.filter {
                    checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    runOnUiThread {
                        try {
                            requestPermissions(missing.toTypedArray(), 1002)
                        } catch (_: Exception) {}
                    }
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 || requestCode == 1002) {
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('MAXPLAY_LOCAL_VIDEOS_PERMISSION_GRANTED')); window.dispatchEvent(new CustomEvent('MAXPLAY_NOTIFICATION_PERMISSION_GRANTED'));",
                        null
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Triggers a real native Android status-bar notification with optional BigPictureStyle attached image & tap-to-open intent.
     */
    fun showNativeStatusNotification(title: String, body: String, imageUrl: String = "") {
        InbuiltStreamScraper.backgroundExecutor.execute {
            val bitmap: android.graphics.Bitmap? = if (imageUrl.startsWith("http", ignoreCase = true)) {
                try {
                    val req = okhttp3.Request.Builder()
                        .url(imageUrl)
                        .header("User-Agent", InbuiltStreamScraper.DEFAULT_USER_AGENT)
                        .build()
                    InbuiltStreamScraper.okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                        } else null
                    }
                } catch (_: Exception) {
                    null
                }
            } else null

            val appLogoBitmap: android.graphics.Bitmap? = try {
                android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return@runOnUiThread
                    val intent = android.content.Intent(this, MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("open_messages", true)
                    }
                    val pendingFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    } else {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(this, 2001, intent, pendingFlags)

                    val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.app.Notification.Builder(this, "maxplay_inbox_alerts")
                    } else {
                        @Suppress("DEPRECATION")
                        android.app.Notification.Builder(this)
                    }

                    builder.setSmallIcon(R.drawable.ic_stat_maxplay)
                        .setContentTitle(title.ifBlank { "MaxPlay" })
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)

                    if (bitmap != null) {
                        builder.setLargeIcon(bitmap)
                        builder.setStyle(
                            android.app.Notification.BigPictureStyle()
                                .bigPicture(bitmap)
                                .setBigContentTitle(title.ifBlank { "MaxPlay" })
                                .setSummaryText(body)
                        )
                    } else {
                        if (appLogoBitmap != null) {
                            builder.setLargeIcon(appLogoBitmap)
                        }
                        builder.setStyle(
                            android.app.Notification.BigTextStyle()
                                .setBigContentTitle(title.ifBlank { "MaxPlay" })
                                .bigText(body)
                        )
                    }

                    val notifId = ((title + "|" + body).hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
                    nm.notify(notifId, builder.build())
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Background poller that checks Firestore `messages` collection every 15 seconds so users receive
     * instant status-bar notifications (with attached image!) even when the app is minimized in background.
     */
    private fun startBackgroundAnnouncementMonitor() {
        bgNotificationScheduler.scheduleWithFixedDelay({
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return@scheduleWithFixedDelay
                    }
                }

                val prefs = getSharedPreferences("maxplay_native_notifs", MODE_PRIVATE)
                val isInitialized = prefs.getBoolean("initialized", false)
                val seenSet = HashSet(prefs.getStringSet("seen_doc_ids", emptySet()) ?: emptySet())

                val req = okhttp3.Request.Builder()
                    .url(FIRESTORE_MESSAGES_REST_URL)
                    .header("User-Agent", InbuiltStreamScraper.DEFAULT_USER_AGENT)
                    .build()

                InbuiltStreamScraper.okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bodyStr = resp.body?.string() ?: return@use
                    val root = org.json.JSONObject(bodyStr)
                    val docs = root.optJSONArray("documents") ?: return@use

                    var newestNewTitle = ""
                    var newestNewBody = ""
                    var newestNewImg = ""
                    var newestTimestamp = ""

                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val fullName = doc.optString("name", "")
                        val docId = fullName.substringAfterLast("/")
                        if (docId.isBlank()) continue

                        val fields = doc.optJSONObject("fields") ?: continue
                        val targetType = fields.optJSONObject("targetType")?.optString("stringValue", "all") ?: "all"
                        if (targetType == "user") continue

                        if (!seenSet.contains(docId)) {
                            seenSet.add(docId)
                            val createdAt = fields.optJSONObject("createdAt")?.optString("stringValue", "")
                                ?: doc.optString("createTime", "")
                            if (newestNewTitle.isBlank() || createdAt >= newestTimestamp) {
                                newestTimestamp = createdAt
                                newestNewTitle = fields.optJSONObject("title")?.optString("stringValue", "") ?: ""
                                newestNewBody = fields.optJSONObject("body")?.optString("stringValue", "") ?: "New update in MaxPlay"
                                val bannerUrl = fields.optJSONObject("bannerUrl")?.optString("stringValue", "") ?: ""
                                val imageUrl = fields.optJSONObject("imageUrl")?.optString("stringValue", "") ?: ""
                                val attachedFields = fields.optJSONObject("attachedContent")
                                    ?.optJSONObject("mapValue")
                                    ?.optJSONObject("fields")
                                val backdropUrl = attachedFields?.optJSONObject("backdropUrl")?.optString("stringValue", "") ?: ""
                                val posterUrl = attachedFields?.optJSONObject("posterUrl")?.optString("stringValue", "") ?: ""
                                newestNewImg = listOf(bannerUrl, imageUrl, backdropUrl, posterUrl).firstOrNull { it.isNotBlank() } ?: ""
                            }
                        }
                    }

                    prefs.edit()
                        .putBoolean("initialized", true)
                        .putStringSet("seen_doc_ids", seenSet)
                        .apply()

                    // Notify immediately when a new announcement is published
                    if (isInitialized && newestNewTitle.isNotBlank()) {
                        showNativeStatusNotification(newestNewTitle, newestNewBody, newestNewImg)
                    }
                }
            } catch (_: Exception) {}
        }, 6, 15, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * Shares an exported `.maxplay` container file using Android's native Share Sheet (Quick Share / Bluetooth / WhatsApp / Save).
     */
    fun shareMaxPlayFile(file: java.io.File): Boolean {
        return try {
            runOnUiThread {
                try {
                    val builder = android.os.StrictMode.VmPolicy.Builder()
                    android.os.StrictMode.setVmPolicy(builder.build())
                    val uri = android.net.Uri.fromFile(file)
                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_TITLE, file.name)
                        putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            "Open this .maxplay file in MaxPlay > Downloads > Transfer > Receive Files."
                        )
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(sendIntent, "Send ${file.name} via"))
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Launches Android's built-in Speech Recognizer intent and delivers spoken text to `window.__onMaxPlayVoiceResult(text)`.
     */
    fun startNativeVoiceInput() {
        runOnUiThread {
            try {
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak movie, series, or anime name...")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 3001)
            } catch (_: Exception) {
                webView.evaluateJavascript(
                    "window.__onMaxPlayVoiceResult && window.__onMaxPlayVoiceResult('');",
                    null
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001) {
            val matches = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim() ?: ""
            val escaped = org.json.JSONObject.quote(spoken)
            webView.evaluateJavascript(
                "window.__onMaxPlayVoiceResult && window.__onMaxPlayVoiceResult($escaped);",
                null
            )
        } else if (requestCode == 3002) {
            val callback = fileUploadCallback
            fileUploadCallback = null
            if (resultCode == RESULT_OK && data != null) {
                val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                    ?: data.data?.let { arrayOf(it) }
                callback?.onReceiveValue(results)
            } else {
                callback?.onReceiveValue(null)
            }
        } else if (requestCode == 3005) {
            val pendingFile = pendingApkInstallFile
            if (pendingFile != null && pendingFile.exists()) {
                launchAndroidPackageInstaller(pendingFile)
            }
        }
    }

    private var pendingApkInstallFile: java.io.File? = null

    private fun emitApkUpdateProgress(
        status: String,
        progress: Int,
        downloadedMb: Double,
        totalMb: Double,
        version: String,
        message: String
    ) {
        runOnUiThread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("status", status)
                    put("progress", progress)
                    put("downloadedMb", downloadedMb)
                    put("totalMb", totalMb)
                    put("version", version)
                    put("message", message)
                }.toString()
                webView.evaluateJavascript(
                    "window.__onMaxPlayApkUpdateProgress && window.__onMaxPlayApkUpdateProgress($payload);",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Normalizes common cloud hosting links (Google Drive, Dropbox, GitHub, MediaFire) into direct binary APK URLs.
     */
    private fun normalizeDirectApkUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed

        // 1. Google Drive: /file/d/{ID}/view or open?id={ID} or uc?id={ID}
        if (trimmed.contains("drive.google.com") || trimmed.contains("drive.usercontent.google.com")) {
            val fileIdMatch = Regex("""(?:/file/d/|/d/|[?&]id=)([a-zA-Z0-9_-]{15,})""").find(trimmed)
            val fileId = fileIdMatch?.groupValues?.getOrNull(1)
            if (!fileId.isNullOrBlank()) {
                return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            }
        }

        // 2. Dropbox: ?dl=0 -> ?dl=1
        if (trimmed.contains("dropbox.com")) {
            return if (trimmed.contains("dl=0")) {
                trimmed.replace("dl=0", "dl=1")
            } else if (!trimmed.contains("dl=1") && !trimmed.contains("raw=1")) {
                if (trimmed.contains("?")) "$trimmed&dl=1" else "$trimmed?dl=1"
            } else {
                trimmed
            }
        }

        // 3. GitHub blob URL -> raw URL
        if (trimmed.contains("github.com/") && trimmed.contains("/blob/")) {
            return if (trimmed.contains("?")) "$trimmed&raw=true" else "$trimmed?raw=true"
        }

        return trimmed
    }

    /**
     * Checks whether a downloaded file is a genuine, parseable Android APK archive (valid ZIP header + PackageInfo).
     */
    private fun isValidDownloadedApkFile(apkFile: java.io.File): Boolean {
        if (!apkFile.exists() || apkFile.length() < 64 * 1024L) return false
        try {
            java.io.FileInputStream(apkFile).use { fis ->
                val magic = ByteArray(4)
                if (fis.read(magic) != 4) return false
                // Every APK is a ZIP archive starting with 'P', 'K', 0x03, 0x04
                if (magic[0] != 0x50.toByte() ||
                    magic[1] != 0x4B.toByte() ||
                    magic[2] != 0x03.toByte() ||
                    magic[3] != 0x04.toByte()
                ) {
                    return false
                }
            }
            @Suppress("DEPRECATION")
            val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            return pkgInfo != null && !pkgInfo.packageName.isNullOrBlank()
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * If a cloud host (e.g. Google Drive virus-scan warning or MediaFire page) returns an HTML page instead of binary APK,
     * extracts the real direct binary download URL from the HTML markup.
     */
    private fun extractDirectApkUrlFromHtml(html: String, baseUrl: String): String? {
        try {
            // 1. Google Drive confirmation form: <form id="download-form" action="https://drive.usercontent.google.com/download" ...>
            if (html.contains("drive.usercontent.google.com/download") || html.contains("export=download")) {
                val idMatch = Regex("""name="id"\s+value="([^"]+)"""").find(html)
                val confirmMatch = Regex("""name="confirm"\s+value="([^"]+)"""").find(html)
                val uuidMatch = Regex("""name="uuid"\s+value="([^"]+)"""").find(html)
                val idVal = idMatch?.groupValues?.getOrNull(1)
                if (!idVal.isNullOrBlank()) {
                    val confirmVal = confirmMatch?.groupValues?.getOrNull(1) ?: "t"
                    val uuidPart = uuidMatch?.groupValues?.getOrNull(1)?.let { "&uuid=$it" } ?: ""
                    return "https://drive.usercontent.google.com/download?id=$idVal&export=download&confirm=$confirmVal$uuidPart"
                }
            }

            // 2. MediaFire direct download button: href="https://download1234.mediafire.com/.../*.apk"
            val mfMatch = Regex("""https?://download[0-9]*\.mediafire\.com/[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(html)
            if (mfMatch != null) return mfMatch.value

            // 3. Any explicit direct .apk link inside href="..." or src="..."
            val directApkMatch = Regex("""https?://[^\s"'<>]+\.apk(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE).find(html)
            if (directApkMatch != null && !directApkMatch.value.equals(baseUrl, ignoreCase = true)) {
                return directApkMatch.value.replace("&amp;", "&")
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Downloads the Admin's APK update in the background with live 0% -> 100% progress,
     * verifies the APK package integrity, and launches Android's native Package Installer automatically.
     */
    fun startNativeApkUpdate(apkUrl: String, versionName: String, title: String) {
        val cleanUrl = apkUrl.trim()
        if (cleanUrl.isBlank()) {
            emitApkUpdateProgress("error", 0, 0.0, 0.0, versionName, "Invalid APK download link.")
            return
        }

        kotlin.concurrent.thread(start = true, name = "MaxPlayApkUpdater") {
            var apkFile: java.io.File? = null
            try {
                val safeVer = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "latest" }
                val baseDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: externalCacheDir
                    ?: filesDir
                    ?: cacheDir
                val updateDir = java.io.File(baseDir, "apk_updates")
                if (!updateDir.exists()) updateDir.mkdirs()
                val targetFile = java.io.File(updateDir, "MaxPlay_Update_$safeVer.apk")
                apkFile = targetFile
                if (targetFile.exists()) targetFile.delete()

                emitApkUpdateProgress("downloading", 2, 0.0, 0.0, versionName, "Connecting to update server...")

                var targetDownloadUrl = normalizeDirectApkUrl(cleanUrl)

                fun downloadStreamToApk(urlToFetch: String, allowHtmlRedirect: Boolean): Boolean {
                    val req = okhttp3.Request.Builder()
                        .url(urlToFetch)
                        .header("User-Agent", InbuiltStreamScraper.DEFAULT_USER_AGENT)
                        .header("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
                        .build()

                    InbuiltStreamScraper.okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw Exception("Server returned HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw Exception("Empty APK stream")
                        val contentType = (resp.header("Content-Type") ?: "").lowercase()

                        // If server returned an HTML interstitial page, try extracting the real binary APK link
                        if (contentType.contains("text/html")) {
                            if (allowHtmlRedirect) {
                                val htmlPreview = body.string()
                                val extractedUrl = extractDirectApkUrlFromHtml(htmlPreview, urlToFetch)
                                if (!extractedUrl.isNullOrBlank()) {
                                    targetDownloadUrl = extractedUrl
                                    return downloadStreamToApk(extractedUrl, false)
                                }
                            }
                            throw Exception("URL returned HTML webpage instead of binary APK file")
                        }

                        val contentLength = body.contentLength()
                        val totalMb = if (contentLength > 0) (contentLength.toDouble() / (1024.0 * 1024.0)) else 42.0

                        body.byteStream().use { input ->
                            java.io.FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var bytesRead: Int
                                var totalRead = 0L
                                var lastEmitTime = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= 150) {
                                        lastEmitTime = now
                                        val dlMb = totalRead.toDouble() / (1024.0 * 1024.0)
                                        val pct = if (contentLength > 0) {
                                            ((totalRead * 100L) / contentLength).toInt().coerceIn(3, 99)
                                        } else {
                                            ((dlMb / totalMb) * 95.0).toInt().coerceIn(5, 95)
                                        }
                                        emitApkUpdateProgress(
                                            "downloading",
                                            pct,
                                            Math.round(dlMb * 10.0) / 10.0,
                                            Math.round(totalMb * 10.0) / 10.0,
                                            versionName,
                                            "Downloading MaxPlay $versionName ($pct%)..."
                                        )
                                    }
                                }
                                output.flush()
                                try { output.fd.sync() } catch (_: Exception) {}
                            }
                        }
                        return true
                    }
                }

                downloadStreamToApk(targetDownloadUrl, true)

                // Verify that the downloaded file is a genuine, valid Android APK package before launching PackageInstaller
                if (!isValidDownloadedApkFile(targetFile)) {
                    try { targetFile.delete() } catch (_: Exception) {}
                    throw Exception("Downloaded file is not a valid Android APK package")
                }

                try {
                    targetFile.setReadable(true, false)
                } catch (_: Exception) {}

                val finalMb = Math.round((targetFile.length().toDouble() / (1024.0 * 1024.0)) * 10.0) / 10.0
                emitApkUpdateProgress(
                    "installing",
                    100,
                    finalMb,
                    finalMb,
                    versionName,
                    "Verified APK ($finalMb MB)! Launching Android App Installer..."
                )
                showNativeStatusNotification(
                    title.ifBlank { "MaxPlay $versionName Ready to Install" },
                    "Tap to install the verified MaxPlay APK update.",
                    ""
                )
                installDownloadedApk(targetFile)
            } catch (e: Exception) {
                try { apkFile?.delete() } catch (_: Exception) {}
                // If link is an external hosting page (e.g., Google Drive folder / Mega / webpage),
                // open in Android system browser so user can download the APK without a corrupt package error
                emitApkUpdateProgress(
                    "browser_fallback",
                    100,
                    0.0,
                    0.0,
                    versionName,
                    "Opening official APK download in browser..."
                )
                runOnUiThread {
                    try {
                        val browserIntent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(cleanUrl)
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(browserIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun installDownloadedApk(apkFile: java.io.File) {
        runOnUiThread {
            try {
                if (!isValidDownloadedApkFile(apkFile)) {
                    emitApkUpdateProgress(
                        "error",
                        0,
                        0.0,
                        0.0,
                        "",
                        "APK package verification failed. Please check the APK download URL in Admin Panel."
                    )
                    return@runOnUiThread
                }
                pendingApkInstallFile = apkFile
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (!packageManager.canRequestPackageInstalls()) {
                        val permIntent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:$packageName")
                        )
                        @Suppress("DEPRECATION")
                        startActivityForResult(permIntent, 3005)
                        return@runOnUiThread
                    }
                }
                launchAndroidPackageInstaller(apkFile)
            } catch (_: Exception) {}
        }
    }

    private fun launchAndroidPackageInstaller(apkFile: java.io.File) {
        try {
            if (!apkFile.exists() || apkFile.length() < 64 * 1024L) return
            try { apkFile.setReadable(true, false) } catch (_: Exception) {}

            val apkUri: android.net.Uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )
            } catch (_: Exception) {
                val builder = android.os.StrictMode.VmPolicy.Builder()
                android.os.StrictMode.setVmPolicy(builder.build())
                android.net.Uri.fromFile(apkFile)
            }

            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(android.content.Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(android.content.Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
            }

            // Explicitly grant read URI permission to all package installer activities on the device
            try {
                @Suppress("DEPRECATION")
                val resInfoList = packageManager.queryIntentActivities(
                    installIntent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )
                for (resolveInfo in resInfoList) {
                    val pkgName = resolveInfo.activityInfo?.packageName ?: continue
                    grantUriPermission(
                        pkgName,
                        apkUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (_: Exception) {}

            startActivity(installIntent)
        } catch (_: Exception) {}
    }

    fun isNativePopunderAdOpen(): Boolean {
        return try {
            popunderDialog?.isShowing == true
        } catch (_: Exception) {
            false
        }
    }

    fun closeNativePopunderAd(): Boolean {
        runOnUiThread {
            try {
                popunderWebView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            } catch (_: Exception) {}
            popunderWebView = null
            try {
                if (popunderDialog?.isShowing == true) {
                    popunderDialog?.dismiss()
                }
            } catch (_: Exception) {}
            popunderDialog = null
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun openNativePopunderAd(adUrl: String): Boolean {
        val cleanUrl = adUrl.trim()
        if (cleanUrl.isBlank()) return false
        runOnUiThread {
            try {
                if (popunderDialog?.isShowing == true) return@runOnUiThread

                // Build automatic Adsterra mirror failover queue for the exact same publisher placement key
                val mirrorDomains = listOf(
                    "www.highperformanceformat.com",
                    "www.highratecpm.com",
                    "www.topcreativeformat.com",
                    "www.profitableratecpmnetwork.com"
                )
                val knownAdsterraHosts = listOf(
                    "profitableratecpmnetwork.com",
                    "highperformanceformat.com",
                    "highratecpm.com",
                    "topcreativeformat.com",
                    "effectivegatecpm.com"
                )

                val candidateUrls = mutableListOf<String>()
                try {
                    val parsedUri = android.net.Uri.parse(cleanUrl)
                    val host = (parsedUri.host ?: "").lowercase()
                    val isAdsterra = knownAdsterraHosts.any { host.contains(it) }
                    if (isAdsterra) {
                        // Put highperformanceformat.com and highratecpm.com first when profitableratecpmnetwork.com has ISP SSL blocks
                        for (mirrorHost in mirrorDomains) {
                            val rebuilt = parsedUri.buildUpon().authority(mirrorHost).scheme("https").build().toString()
                            if (!candidateUrls.contains(rebuilt)) {
                                candidateUrls.add(rebuilt)
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (!candidateUrls.contains(cleanUrl)) {
                    candidateUrls.add(0, cleanUrl)
                }

                var currentMirrorIndex = 0
                var isAdRevealed = false
                var triedOkHttpFallback = false

                val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.setCancelable(true)
                dialog.setOnCancelListener {
                    closeNativePopunderAd()
                }
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        closeNativePopunderAd()
                        true
                    } else {
                        false
                    }
                }

                val density = resources.displayMetrics.density
                val container = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#0A0A0A"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val topBar = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#121216"))
                    val padH = (14 * density).toInt()
                    val padV = (10 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val labelView = android.widget.TextView(this).apply {
                    text = "Sponsored • Press Back to return to MaxPlay"
                    setTextColor(Color.parseColor("#A1A1AA"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val closeBtn = android.widget.TextView(this).apply {
                    text = "✕ Return to Video"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val padBtnH = (14 * density).toInt()
                    val padBtnV = (6 * density).toInt()
                    setPadding(padBtnH, padBtnV, padBtnH, padBtnV)
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#6366F1"))
                    ).apply {
                        cornerRadius = 999f
                    }
                    setOnClickListener {
                        closeNativePopunderAd()
                    }
                }

                topBar.addView(labelView)
                topBar.addView(closeBtn)

                fun revealAdDialogIfReady() {
                    if (isAdRevealed) return
                    if (popunderDialog != dialog || !dialog.isShowing) return
                    isAdRevealed = true
                    try {
                        dialog.window?.apply {
                            clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            val lp = attributes
                            lp.alpha = 1.0f
                            attributes = lp
                        }
                    } catch (_: Exception) {}
                }

                val adWeb = WebView(this).apply {
                    setBackgroundColor(Color.parseColor("#0A0A0A"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                    val mobileChromeUa = (settings.userAgentString ?: "")
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                        .trim()
                        .ifBlank {
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = mobileChromeUa
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val tempWebView = WebView(this@MainActivity)
                            tempWebView.webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    val targetUrl = request?.url?.toString()
                                    if (!targetUrl.isNullOrBlank()) {
                                        view?.loadUrl(targetUrl)
                                    }
                                    try { v?.destroy() } catch (_: Exception) {}
                                    return true
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                                    if (!url.isNullOrBlank()) {
                                        view?.loadUrl(url)
                                    }
                                    try { v?.destroy() } catch (_: Exception) {}
                                    return true
                                }
                            }
                            transport.webView = tempWebView
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webViewClient = object : android.webkit.WebViewClient() {
                        private fun handleAdUrl(view: WebView?, rawTarget: String?): Boolean {
                            val target = rawTarget?.trim() ?: return true
                            if (target.startsWith("http://", ignoreCase = true) ||
                                target.startsWith("https://", ignoreCase = true)
                            ) {
                                return false
                            }
                            if (target.startsWith("intent://", ignoreCase = true)) {
                                try {
                                    val parsedIntent = android.content.Intent.parseUri(
                                        target,
                                        android.content.Intent.URI_INTENT_SCHEME
                                    )
                                    val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                                    if (!fallbackUrl.isNullOrBlank() &&
                                        (fallbackUrl.startsWith("http://", ignoreCase = true) ||
                                            fallbackUrl.startsWith("https://", ignoreCase = true))
                                    ) {
                                        view?.loadUrl(fallbackUrl)
                                        return true
                                    }
                                    if (parsedIntent.resolveActivity(packageManager) != null) {
                                        parsedIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(parsedIntent)
                                        closeNativePopunderAd()
                                    }
                                } catch (_: Exception) {}
                                return true
                            }
                            if (target.startsWith("market://", ignoreCase = true)) {
                                try {
                                    val playHttp = "https://play.google.com/store/apps/" + target.removePrefix("market://")
                                    view?.loadUrl(playHttp)
                                } catch (_: Exception) {}
                                return true
                            }
                            return true
                        }

                        private fun tryNextMirrorOrFallback(view: WebView?, failingUrl: String?) {
                            try {
                                view?.stopLoading()
                            } catch (_: Exception) {}

                            if (currentMirrorIndex + 1 < candidateUrls.size) {
                                currentMirrorIndex++
                                val nextMirrorUrl = candidateUrls[currentMirrorIndex]
                                view?.post {
                                    if (popunderDialog?.isShowing == true) {
                                        view.loadUrl(nextMirrorUrl)
                                    }
                                }
                                return
                            }

                            if (!triedOkHttpFallback) {
                                triedOkHttpFallback = true
                                Thread {
                                    var recoveredHtml: String? = null
                                    var recoveredBaseUrl: String = candidateUrls.firstOrNull() ?: cleanUrl
                                    for (cand in candidateUrls) {
                                        try {
                                            val req = okhttp3.Request.Builder()
                                                .url(cand)
                                                .header("User-Agent", mobileChromeUa)
                                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                                .build()
                                            InbuiltStreamScraper.okHttpClient.newCall(req).execute().use { res ->
                                                if (res.isSuccessful) {
                                                    val bodyStr = res.body?.string()
                                                    if (!bodyStr.isNullOrBlank() && bodyStr.length > 90) {
                                                        recoveredHtml = bodyStr
                                                        recoveredBaseUrl = cand
                                                    }
                                                }
                                            }
                                            if (!recoveredHtml.isNullOrBlank()) break
                                        } catch (_: Exception) {}
                                    }

                                    runOnUiThread {
                                        if (popunderDialog?.isShowing != true) return@runOnUiThread
                                        if (!recoveredHtml.isNullOrBlank()) {
                                            try {
                                                view?.loadDataWithBaseURL(
                                                    recoveredBaseUrl,
                                                    recoveredHtml!!,
                                                    "text/html",
                                                    "UTF-8",
                                                    null
                                                )
                                            } catch (_: Exception) {
                                                closeNativePopunderAd()
                                            }
                                        } else {
                                            closeNativePopunderAd()
                                        }
                                    }
                                }.start()
                            } else {
                                closeNativePopunderAd()
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            return handleAdUrl(view, request?.url?.toString())
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return handleAdUrl(view, url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val loadedUrl = (url ?: "").trim()
                            if (loadedUrl.isBlank() || loadedUrl == "about:blank") return

                            view?.evaluateJavascript(
                                "(function(){try{var b=document.body;if(!b)return 'EMPTY';var t=(b.innerText||'').trim();if(t.indexOf('ERR_')!==-1||t.indexOf('Web page not available')!==-1||t.indexOf('can\\x27t be reached')!==-1)return 'ERROR';var hasMedia=b.querySelectorAll('img,iframe,canvas,video,a,button,input,form').length>0;if(t.length>12||hasMedia)return 'OK';return 'WAIT';}catch(e){return 'WAIT';}})();"
                            ) { result ->
                                val status = (result ?: "").replace("\"", "").trim()
                                when (status) {
                                    "ERROR" -> tryNextMirrorOrFallback(view, loadedUrl)
                                    "OK" -> revealAdDialogIfReady()
                                    else -> {
                                        // Allow 1.2s for JS redirect or dynamic ad DOM injection before re-checking
                                        view.postDelayed({
                                            if (popunderDialog?.isShowing != true) return@postDelayed
                                            view.evaluateJavascript(
                                                "(function(){try{var b=document.body;if(!b)return 'EMPTY';var t=(b.innerText||'').trim();if(t.indexOf('ERR_')!==-1||t.indexOf('Web page not available')!==-1)return 'ERROR';var hasMedia=b.querySelectorAll('img,iframe,canvas,video,a,button,input,form').length>0;return (t.length>8||hasMedia)?'OK':'EMPTY';}catch(e){return 'EMPTY';}})();"
                                            ) { retryRes ->
                                                val retryStatus = (retryRes ?: "").replace("\"", "").trim()
                                                if (retryStatus == "OK") {
                                                    revealAdDialogIfReady()
                                                } else {
                                                    tryNextMirrorOrFallback(view, loadedUrl)
                                                }
                                            }
                                        }, 1200)
                                    }
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request == null || request.isForMainFrame) {
                                tryNextMirrorOrFallback(view, request?.url?.toString())
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            tryNextMirrorOrFallback(view, failingUrl)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                tryNextMirrorOrFallback(view, request.url?.toString())
                            }
                        }

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.cancel()
                            tryNextMirrorOrFallback(view, error?.url)
                        }
                    }
                }

                container.addView(topBar)
                container.addView(adWeb)
                dialog.setContentView(container)

                // Keep dialog completely invisible and pass-through until real ad content finishes loading
                dialog.window?.apply {
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    val lp = attributes
                    lp.alpha = 0f
                    attributes = lp
                }

                popunderDialog = dialog
                popunderWebView = adWeb
                dialog.show()
                adWeb.loadUrl(candidateUrls.first())

                // Safety timeout: if no mirror renders real content within 9.5 seconds, dismiss silently
                adWeb.postDelayed({
                    if (popunderDialog == dialog && !isAdRevealed) {
                        closeNativePopunderAd()
                    }
                }, 9500)
            } catch (_: Exception) {}
        }
        return true
    }

    private var musicMediaSession: android.media.session.MediaSession? = null
    private var musicControlReceiverRegistered = false
    private var musicWakeLock: android.os.PowerManager.WakeLock? = null

    private val musicControlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.getStringExtra("cmd") ?: return
            dispatchMusicActionToWebView(action)
        }
    }

    private fun dispatchMusicActionToWebView(action: String, seekTimeSec: Double = -1.0) {
        runOnUiThread {
            try {
                val safeAction = org.json.JSONObject.quote(action)
                val js = "window.dispatchEvent(new CustomEvent('MAXPLAY_NATIVE_MEDIA_ACTION', { detail: { action: $safeAction, seekTime: $seekTimeSec } }));"
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun ensureMusicMediaSession() {
        if (musicMediaSession == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                val session = android.media.session.MediaSession(this, "MaxPlayMusicSession")
                session.setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() {
                        dispatchMusicActionToWebView("play")
                    }
                    override fun onPause() {
                        dispatchMusicActionToWebView("pause")
                    }
                    override fun onSkipToNext() {
                        dispatchMusicActionToWebView("next")
                    }
                    override fun onSkipToPrevious() {
                        dispatchMusicActionToWebView("prev")
                    }
                    override fun onSeekTo(pos: Long) {
                        dispatchMusicActionToWebView("seek", pos / 1000.0)
                    }
                    override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                        dispatchMusicActionToWebView(action, 0.0)
                    }
                })
                session.isActive = true
                musicMediaSession = session
            } catch (_: Exception) {}
        }
        if (!musicControlReceiverRegistered) {
            try {
                val filter = android.content.IntentFilter("com.maxplay.app.MUSIC_CONTROL")
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(musicControlReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(musicControlReceiver, filter)
                }
                musicControlReceiverRegistered = true
            } catch (_: Exception) {}
        }
    }

    private var nativeEqualizer: android.media.audiofx.Equalizer? = null
    private var nativeBassBoost: android.media.audiofx.BassBoost? = null
    private var nativeLoudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var lastAppliedAudioFxKey: String = ""

    /**
     * Applies hardware-accelerated Android Equalizer, BassBoost, and LoudnessEnhancer (100% / 200% / 300% Volume Boost)
     * directly on Android's output mix without ever silencing or CORS-tainting the WebView <audio> element.
     * Session-0 audiofx objects are only attached when non-neutral effects are actually requested and deduplicated.
     */
    fun applyNativeMusicAudioEffects(
        enabled: Boolean,
        bandsDb: DoubleArray,
        bassLevel: Int,
        immersiveLevel: Int,
        volumeBoostPct: Int
    ): Boolean {
        return try {
            val fxKey = "$enabled|${bandsDb.joinToString(",")}|$bassLevel|$immersiveLevel|$volumeBoostPct"
            if (fxKey == lastAppliedAudioFxKey) {
                return true
            }
            lastAppliedAudioFxKey = fxKey

            val wantsEq = enabled && bandsDb.any { kotlin.math.abs(it) > 0.05 }
            val wantsBass = enabled && bassLevel > 0
            val extraGainMb = when {
                volumeBoostPct >= 300 -> 1800
                volumeBoostPct >= 200 -> 1000
                enabled && immersiveLevel > 0 -> (immersiveLevel.coerceIn(0, 100) * 6)
                else -> 0
            }
            val wantsLoudness = extraGainMb > 0

            runOnUiThread {
                try {
                    if (wantsEq && nativeEqualizer == null) {
                        try {
                            nativeEqualizer = android.media.audiofx.Equalizer(0, 0)
                        } catch (_: Exception) {}
                    }
                    if (wantsBass && nativeBassBoost == null) {
                        try {
                            nativeBassBoost = android.media.audiofx.BassBoost(0, 0)
                        } catch (_: Exception) {}
                    }
                    if (wantsLoudness && nativeLoudnessEnhancer == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                        try {
                            nativeLoudnessEnhancer = android.media.audiofx.LoudnessEnhancer(0)
                        } catch (_: Exception) {}
                    }

                    nativeEqualizer?.let { eq ->
                        eq.enabled = wantsEq
                        if (wantsEq) {
                            val numBands = eq.numberOfBands.toInt()
                            val range = eq.bandLevelRange
                            val minLevel = range.getOrNull(0)?.toInt() ?: -1200
                            val maxLevel = range.getOrNull(1)?.toInt() ?: 1200
                            for (b in 0 until minOf(numBands, bandsDb.size)) {
                                val targetMb = (bandsDb[b] * 100.0).toInt().coerceIn(minLevel, maxLevel).toShort()
                                try {
                                    eq.setBandLevel(b.toShort(), targetMb)
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    nativeBassBoost?.let { bb ->
                        bb.enabled = wantsBass
                        if (wantsBass && bb.strengthSupported) {
                            val strength = (bassLevel.coerceIn(0, 100) * 10).toShort()
                            try {
                                bb.setStrength(strength)
                            } catch (_: Exception) {}
                        }
                    }

                    nativeLoudnessEnhancer?.let { le ->
                        le.enabled = wantsLoudness
                        if (wantsLoudness) {
                            try {
                                le.setTargetGain(extraGainMb)
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Shares the actual local MP3/Audio song file via Android's native Share Sheet (WhatsApp, Telegram, Nearby Share, etc.)
     */
    fun shareDeviceAudioFile(audioUrlOrId: String, title: String, artist: String): Boolean {
        return try {
            runOnUiThread {
                try {
                    val clean = audioUrlOrId.trim()
                    val mediaId: Long? = when {
                        clean.contains("/device-audio/") -> {
                            clean.substringAfter("/device-audio/")
                                .substringBefore("?")
                                .substringBefore("/")
                                .trim()
                                .toLongOrNull()
                        }
                        clean.startsWith("media_") -> {
                            clean.removePrefix("media_").trim().toLongOrNull()
                        }
                        else -> clean.toLongOrNull()
                    }

                    if (mediaId != null && mediaId > 0L) {
                        val contentUri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                        val mimeType = contentResolver.getType(contentUri) ?: "audio/mpeg"
                        val displayTitle = if (artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
                            "$title - $artist"
                        } else {
                            title.ifBlank { "MaxPlay Song" }
                        }
                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = if (mimeType.startsWith("audio/")) mimeType else "audio/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
                            putExtra(android.content.Intent.EXTRA_TITLE, displayTitle)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, displayTitle)
                            clipData = android.content.ClipData.newRawUri(displayTitle, contentUri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(sendIntent, "Share \"$title\" via")
                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(chooser)
                    } else {
                        val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                            putExtra(android.content.Intent.EXTRA_TEXT, "Listening to $title by $artist on MaxPlay")
                        }
                        val chooser = android.content.Intent.createChooser(fallbackIntent, "Share \"$title\" via")
                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(chooser)
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveMusicArtworkBitmap(artworkUrl: String): android.graphics.Bitmap? {
        return try {
            if (artworkUrl.startsWith("data:image/")) {
                val base64Part = artworkUrl.substringAfter("base64,", "")
                if (base64Part.isNotEmpty()) {
                    val bytes = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
            if (artworkUrl.contains("/device-audio-art/")) {
                val after = artworkUrl.substringAfter("/device-audio-art/")
                val albumId = after.substringBefore("?").substringBefore("/").toLongOrNull() ?: 0L
                val songId = after.substringAfter("songId=", "").substringBefore("&").toLongOrNull() ?: 0L
                val artDir = java.io.File(cacheDir, "audio_art")
                val cacheKey = if (albumId > 0L) "alb_$albumId" else "sng_$songId"
                val cachedFile = java.io.File(artDir, "$cacheKey.jpg")
                if (cachedFile.exists() && cachedFile.length() > 256L) {
                    return android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath)
                }
                if (songId > 0L) {
                    val songUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        songId
                    )
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this, songUri)
                        val bytes = retriever.embeddedPicture
                        if (bytes != null && bytes.isNotEmpty()) {
                            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
            }
            android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("WakelockTimeout")
    fun updateMusicNotificationState(
        title: String,
        artist: String,
        album: String,
        isPlaying: Boolean,
        artworkUrl: String,
        durationMs: Long,
        positionMs: Long,
        isFavorite: Boolean = false,
        playMode: String = "repeat_all"
    ) {
        InbuiltStreamScraper.backgroundExecutor.execute {
            val artBitmap = resolveMusicArtworkBitmap(artworkUrl)
            runOnUiThread {
                try {
                    ensureMusicMediaSession()
                    if (isPlaying) {
                        webView.resumeTimers()
                        if (musicWakeLock == null) {
                            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                            musicWakeLock = pm?.newWakeLock(
                                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                                "MaxPlay::MusicPlaybackWakeLock"
                            )?.apply { setReferenceCounted(false) }
                        }
                        if (musicWakeLock?.isHeld == false) {
                            musicWakeLock?.acquire(4 * 60 * 60 * 1000L)
                        }
                    } else {
                        if (musicWakeLock?.isHeld == true) {
                            musicWakeLock?.release()
                        }
                    }

                    val repeatLabel = when (playMode) {
                        "repeat_one" -> "Repeat One"
                        "shuffle" -> "Shuffle"
                        else -> "Repeat All"
                    }
                    val favIconRes = if (isFavorite) R.drawable.ic_music_heart_filled else R.drawable.ic_music_heart

                    val session = musicMediaSession
                    if (session != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val stateBuilder = android.media.session.PlaybackState.Builder()
                            .setActions(
                                android.media.session.PlaybackState.ACTION_PLAY or
                                android.media.session.PlaybackState.ACTION_PAUSE or
                                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                android.media.session.PlaybackState.ACTION_SEEK_TO
                            )
                            .addCustomAction(
                                android.media.session.PlaybackState.CustomAction.Builder(
                                    "repeat_cycle",
                                    repeatLabel,
                                    R.drawable.ic_music_repeat
                                ).build()
                            )
                            .addCustomAction(
                                android.media.session.PlaybackState.CustomAction.Builder(
                                    "fav_toggle",
                                    if (isFavorite) "Favourited" else "Favourite",
                                    favIconRes
                                ).build()
                            )
                            .setState(
                                if (isPlaying) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                                positionMs.coerceAtLeast(0L),
                                if (isPlaying) 1.0f else 0f
                            )
                        session.setPlaybackState(stateBuilder.build())

                        val metaBuilder = android.media.MediaMetadata.Builder()
                            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "MaxPlay Audio" })
                            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, artist.ifBlank { "Unknown artist" })
                            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, album.ifBlank { "MaxPlay" })
                            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
                        if (artBitmap != null) {
                            metaBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap)
                        }
                        session.setMetadata(metaBuilder.build())
                    }

                    val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return@runOnUiThread
                    val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    } else {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    fun buildCmdPendingIntent(reqCode: Int, cmd: String): android.app.PendingIntent {
                        val intent = android.content.Intent("com.maxplay.app.MUSIC_CONTROL").apply {
                            setPackage(packageName)
                            putExtra("cmd", cmd)
                        }
                        return android.app.PendingIntent.getBroadcast(this, reqCode, intent, flags)
                    }

                    val openIntent = android.content.Intent(this, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    val contentPi = android.app.PendingIntent.getActivity(this, 9200, openIntent, flags)

                    val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.app.Notification.Builder(this, "maxplay_music_player")
                    } else {
                        @Suppress("DEPRECATION")
                        android.app.Notification.Builder(this)
                    }

                    builder.setSmallIcon(R.drawable.ic_stat_maxplay)
                        .setContentTitle(title.ifBlank { "MaxPlay Music" })
                        .setContentText(artist.ifBlank { "Unknown artist" })
                        .setSubText(album.ifBlank { "MaxPlay" })
                        .setContentIntent(contentPi)
                        .setOngoing(isPlaying)
                        .setOnlyAlertOnce(true)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)

                    if (artBitmap != null) {
                        builder.setLargeIcon(artBitmap)
                    }

                    // 5 Actions: [0] Repeat/Shuffle (Left) | [1] Previous | [2] Play/Pause | [3] Next | [4] Heart Favourite (Right)
                    val repeatAction = android.app.Notification.Action.Builder(
                        R.drawable.ic_music_repeat,
                        repeatLabel,
                        buildCmdPendingIntent(9200, "repeat_cycle")
                    ).build()
                    val prevAction = android.app.Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        "Previous",
                        buildCmdPendingIntent(9201, "prev")
                    ).build()
                    val playPauseAction = android.app.Notification.Action.Builder(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (isPlaying) "Pause" else "Play",
                        buildCmdPendingIntent(9202, "toggle")
                    ).build()
                    val nextAction = android.app.Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        "Next",
                        buildCmdPendingIntent(9203, "next")
                    ).build()
                    val favAction = android.app.Notification.Action.Builder(
                        favIconRes,
                        if (isFavorite) "Favourited" else "Favourite",
                        buildCmdPendingIntent(9204, "fav_toggle")
                    ).build()

                    builder.addAction(repeatAction)
                    builder.addAction(prevAction)
                    builder.addAction(playPauseAction)
                    builder.addAction(nextAction)
                    builder.addAction(favAction)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        // Show Previous (1), Play/Pause (2), Next (3) in compact view, and all 5 in expanded & lock screen
                        val mediaStyle = android.app.Notification.MediaStyle()
                            .setShowActionsInCompactView(1, 2, 3)
                        if (session != null) {
                            mediaStyle.setMediaSession(session.sessionToken)
                        }
                        builder.style = mediaStyle
                    }

                    nm.notify(9105, builder.build())
                } catch (_: Exception) {}
            }
        }
    }

    fun cancelMusicNotificationState() {
        runOnUiThread {
            try {
                if (musicWakeLock?.isHeld == true) {
                    musicWakeLock?.release()
                }
                val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.cancel(9105)
                musicMediaSession?.isActive = false
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try {
            cancelMusicNotificationState()
            try { nativeEqualizer?.release() } catch (_: Exception) {}
            try { nativeBassBoost?.release() } catch (_: Exception) {}
            try { nativeLoudnessEnhancer?.release() } catch (_: Exception) {}
            if (musicControlReceiverRegistered) {
                unregisterReceiver(musicControlReceiver)
                musicControlReceiverRegistered = false
            }
            musicMediaSession?.release()
            closeNativePopunderAd()
            bgNotificationScheduler.shutdownNow()
            MaxPlayOfflineVault.stopDirectTransferServer()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
