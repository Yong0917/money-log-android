package com.moneylogs.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.animation.PathInterpolator
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.messaging.FirebaseMessaging
import com.moneylogs.app.BuildConfig
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View

    // 머니로그 디자인 splash 상태
    private var splashShimmerAnimator: ObjectAnimator? = null
    private var splashShownAt: Long = 0L
    private var splashHidden: Boolean = false

    // 업데이트 플로우 결과 수신 런처
    private lateinit var updateLauncher: ActivityResultLauncher<IntentSenderRequest>

    // 알림 권한 요청 런처 (Android 13+)
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    // 파일 선택 콜백 (엑셀 가져오기 및 영수증 첨부용)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var filePickerLauncher: ActivityResultLauncher<String>

    // 카메라 촬영 런처 (영수증 스캔 - 카메라로 찍기)
    private var cameraImageUri: Uri? = null
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>

    // WebView onPermissionRequest 용 카메라 런타임 권한 요청
    private var pendingPermissionRequest: PermissionRequest? = null
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    // 파일 picker 경로에서 카메라 권한 요청 후 카메라 실행
    private lateinit var fileCameraPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        // 알림 권한 런처 초기화 (onCreate 초반부에 등록해야 함)
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 허용/거부 결과 — 별도 처리 없이 FCM 토큰만 가져감 */ }

        // 파일 선택 런처 초기화 (갤러리/파일: <input type="file"> 지원)
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(uri))
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

        // WebView getUserMedia() 카메라 권한 런처 (onPermissionRequest 연동)
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            pendingPermissionRequest = null
            if (granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                request.deny()
            }
        }

        // 카메라 촬영 런처 초기화 (<input capture="environment"> 지원)
        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(cameraImageUri!!))
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
            cameraImageUri = null
        }

        // 파일 picker 카메라 권한 런처 — 권한 허용 후 카메라 실행
        fileCameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted && cameraImageUri != null) {
                cameraLauncher.launch(cameraImageUri!!)
            } else {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
                cameraImageUri = null
            }
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // 머니로그 디자인 splash 시작 시각 기록 + shimmer 무한 애니메이션 시작
        splashShownAt = SystemClock.elapsedRealtime()
        startSplashShimmer()

        // WebView 자체 배경색을 웹 스플래시 배경(#FAF8F3)과 맞춰 첫 페인트 전 흰색 깜빡임 제거
        webView.setBackgroundColor(Color.parseColor("#FAF8F3"))

        // 디버그 빌드에서만 chrome://inspect 원격 디버깅 허용
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // settings를 먼저 적용해야 loadUrl 시점에 모든 설정이 유효함
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT  // HTTP 캐시 헤더 준수
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            // 광고성 미디어가 제스처 없이 자동재생되는 것 차단
            mediaPlaybackRequiresUserGesture = true
            // JS에서 User-Agent로 WebView 환경 감지 가능하도록 커스텀 문자열 추가
            userAgentString = "$userAgentString MoneyLogsApp/Android"
        }

        // 오프스크린 프리래스터: 화면 밖 콘텐츠를 미리 래스터화해 스크롤/전환 부드러움 향상
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(webView.settings, true)
        }

        // 렌더러 프로세스 우선순위를 IMPORTANT로 — 백그라운드 전환 시에도 렌더러가 빠르게 죽지 않도록
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)

        // 파일 선택 다이얼로그 처리 (<input type="file"> 지원 — 엑셀 가져오기 및 영수증 첨부)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // 이전 콜백이 남아있으면 취소 처리
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                // capture="environment" 속성이 있으면 카메라 앱으로 직접 촬영
                if (fileChooserParams.isCaptureEnabled) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "receipt_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    }
                    cameraImageUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                    if (cameraImageUri == null) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                    } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        cameraLauncher.launch(cameraImageUri!!)
                    } else {
                        // 권한 미허용 → 요청 후 결과에서 카메라 실행
                        fileCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    // 갤러리 / 파일 선택
                    val acceptTypes = fileChooserParams.acceptTypes
                    val mimeType = if (!acceptTypes.isNullOrEmpty() && acceptTypes[0].isNotBlank()) {
                        acceptTypes[0]
                    } else {
                        "*/*"
                    }
                    filePickerLauncher.launch(mimeType)
                }
                return true
            }

            // WebView getUserMedia() 등 카메라 접근 요청 → Android 런타임 권한 연동
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val cameraRequested = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!cameraRequested) {
                    request.deny()
                    return
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Google OAuth URL은 WebView 대신 Chrome Custom Tabs으로 열어야 함
                // WebView에서 열면 Google 정책상 Error 403: disallowed_useragent 발생
                if (url.contains("accounts.google.com")) {
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(this@MainActivity, request.url)
                    return true
                }
                return false
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                // onPageFinished보다 먼저 호출 — 첫 픽셀이 화면에 그려지는 시점에 splash 숨김 시도.
                // OAuth callback / 알림 탭 진입 등으로 다중 호출 가능하나 hideSplashWhenReady() 가 splashHidden 플래그로 first-only.
                hideSplashWhenReady()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 웹앱이 Android 래퍼 내부 실행임을 안정적으로 감지할 수 있도록 플래그 주입
                view.evaluateJavascript(
                    """
                        (function() {
                          window.__MONEYLOGS_ANDROID_APP__ = true;
                          try {
                            localStorage.setItem('moneylogs:platform', 'android');
                          } catch (e) {}
                        })();
                    """.trimIndent()
                ) { result ->
                    if (BuildConfig.DEBUG && result != null && result != "null") {
                        Log.d(TAG, "JS 주입 결과: $result")
                    }
                }
            }
        }

        // 웹앱에서 Android WebView 환경 감지 및 커스텀 스킴 제공
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        // settings + WebViewClient 설정 직후 즉시 로드 — 불필요한 초기화 대기 없이 네트워크 요청 시작
        // about:blank 워밍업 불필요 — 실제 URL 로드가 엔진 초기화를 겸함
        if (intent?.data != null) {
            handleIncomingUri(intent.data!!)
        } else {
            webView.loadUrl("https://moneylogs.vercel.app/")
        }

        // 뒤로가기 버튼 처리
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentUrl = webView.url ?: ""
                // 로그인 화면에서 뒤로가기 → 앱 종료
                if (currentUrl.contains("/auth/login")) {
                    finish()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // 업데이트 런처 등록 (앱 업데이트 플로우 완료 결과 수신)
        updateLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                // 업데이트 거절/취소 시 앱 종료 — 강제 업데이트
//                finish()
            }
        }

        // Play Store 업데이트 체크 — splash 종료 + 첫 페이지 렌더 안정화 후 호출
        Handler(Looper.getMainLooper()).postDelayed({ checkForUpdate() }, 1500L)

        // 안전망: 페이지 로드 실패/지연 시 splash가 영원히 떠있는 것 방지 (5초 후 강제 숨김)
        loadingOverlay.postDelayed({ hideSplashWhenReady() }, 5000L)

        // 알림 권한 요청 (Android 13+) + FCM 토큰 캐싱
        requestNotificationPermissionAndCacheToken()

        // 알림 탭으로 앱이 열린 경우 해당 화면 딥링크
        intent?.getStringExtra(MoneyLogsFirebaseMessagingService.EXTRA_SCREEN)?.let { screen ->
            val recurringId = intent.getStringExtra(MoneyLogsFirebaseMessagingService.EXTRA_RECURRING_ID)
            navigateWebViewToScreen(screen, recurringId)
        }

    }

    private fun requestNotificationPermissionAndCacheToken() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 권한 여부와 관계없이 토큰 캐싱 (토큰은 권한 없이도 발급됨)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            MoneyLogsFirebaseMessagingService.getEncryptedPrefs(this)
                .edit()
                .putString(MoneyLogsFirebaseMessagingService.KEY_FCM_TOKEN, token)
                .apply()
        }
    }

    private fun navigateWebViewToScreen(screen: String, recurringId: String? = null) {
        val url = buildString {
            append("https://moneylogs.vercel.app")
            append(screen)
            if (recurringId != null) append("?openRecurring=${Uri.encode(recurringId)}")
        }
        webView.loadUrl(url)
    }

    private fun checkForUpdate() {
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // 업데이트가 있으면 즉시 강제 업데이트 플로우 시작 (거절 불가)
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    // OAuth 결과 딥링크 또는 알림 탭으로 앱이 다시 열릴 때 호출됨
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Uri 딥링크 (OAuth 콜백 등) 우선 처리
        if (intent.data != null) {
            handleIncomingUri(intent.data!!)
            return
        }
        // 알림 탭 딥링크 (앱이 백그라운드/포그라운드 상태일 때)
        intent.getStringExtra(MoneyLogsFirebaseMessagingService.EXTRA_SCREEN)?.let { screen ->
            val recurringId = intent.getStringExtra(MoneyLogsFirebaseMessagingService.EXTRA_RECURRING_ID)
            navigateWebViewToScreen(screen, recurringId)
        }
    }

    private fun handleIncomingUri(uri: Uri) {
        if (uri.scheme == "com.moneylogs.app") {
            when (uri.host) {
                "auth-callback" -> {
                    // OAuth 완료 후 딥링크로 앱에 복귀하면,
                    // code를 WebView의 웹 콜백으로 전달하여 서버에서 세션 교환 수행
                    val callbackUrl = buildString {
                        append("https://moneylogs.vercel.app/auth/callback")
                        append("?code=").append(Uri.encode(uri.getQueryParameter("code") ?: ""))

                        uri.getQueryParameter("next")?.let { next ->
                            append("&next=").append(Uri.encode(next))
                        }
                        uri.getQueryParameter("error")?.let { error ->
                            append("&error=").append(Uri.encode(error))
                        }
                        uri.getQueryParameter("error_code")?.let { errorCode ->
                            append("&error_code=").append(Uri.encode(errorCode))
                        }
                        uri.getQueryParameter("error_description")?.let { errorDescription ->
                            append("&error_description=").append(Uri.encode(errorDescription))
                        }

                        append("&android=1")
                    }
                    webView.loadUrl(callbackUrl)
                }

                else -> {
                    // 알 수 없는 호스트는 로드하지 않음 — 외부 앱의 악의적 Intent 스푸핑 방어
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "알 수 없는 딥링크 호스트 무시: ${uri.host}")
                    }
                }
            }
        }
        // 알 수 없는 스킴은 로드하지 않음 — 외부 앱의 악의적 Intent 방어
    }

    // 웹앱에 Android WebView 환경임을 알리는 브릿지
    // static 중첩 클래스 + WeakReference: inner class는 Activity를 강참조하여 메모리 누수 위험
    class AndroidBridge(activity: MainActivity) {
        private val activityRef = WeakReference(activity)

        @JavascriptInterface
        fun getPlatform(): String = "android"

        // 웹앱에서 호출 → EncryptedSharedPreferences에 캐싱된 FCM 토큰 반환
        @JavascriptInterface
        fun getFcmToken(): String {
            val activity = activityRef.get() ?: return ""
            return MoneyLogsFirebaseMessagingService.getEncryptedPrefs(activity)
                .getString(MoneyLogsFirebaseMessagingService.KEY_FCM_TOKEN, "") ?: ""
        }

        // 웹앱에서 Base64 인코딩된 파일 데이터를 받아 Downloads 폴더에 저장
        @JavascriptInterface
        fun downloadFile(base64Data: String, filename: String, mimeType: String) {
            val activity = activityRef.get() ?: return
            try {
                // Base64 입력 크기 제한 (50MB) — 과도한 입력으로 인한 메모리 폭발 방지
                val maxBase64Length = 50L * 1024 * 1024 * 4 / 3
                if (base64Data.length > maxBase64Length) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "파일이 너무 큽니다 (최대 50MB)", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val decoded = Base64.decode(base64Data, Base64.DEFAULT)

                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }

                val resolver = activity.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val uri = resolver.insert(collection, contentValues) ?: run {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "파일 저장에 실패했습니다", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                resolver.openOutputStream(uri)?.use { it.write(decoded) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                activity.runOnUiThread {
                    Toast.makeText(activity, "📥 $filename 저장됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "저장 권한이 없습니다", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 백그라운드에서 복귀 시 WebView JS 타이머·네트워크 재개
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // 백그라운드 진입 시 WebView JS 타이머·네트워크 중단 → 배터리 절약
        webView.onPause()
        webView.pauseTimers()
        // 앱이 백그라운드로 가거나 종료될 때 쿠키를 디스크에 강제 저장
        // 이 없으면 프로세스 종료 시 세션 쿠키가 유실됨
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // shimmer animator 정지 — ViewTreeObserver/View 참조 누수 방지
        splashShimmerAnimator?.cancel()
        splashShimmerAnimator = null

        // WebView native 리소스 명시적 해제 — 메모리 누수 방지
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * 웹 SplashScreen.tsx 의 shimmerSlide 무한 애니메이션 재현.
     * track 폭이 layout 후 결정되므로 doOnLayout 콜백 안에서 시작.
     */
    private fun startSplashShimmer() {
        val track = findViewById<View>(R.id.splashShimmerTrack)
        val bar = findViewById<View>(R.id.splashShimmerBar)
        track.doOnLayout {
            val trackWidth = track.width.toFloat()
            // bar 폭은 track 폭의 35% (웹의 width: 35% 대응)
            bar.layoutParams = bar.layoutParams.also {
                it.width = (trackWidth * 0.35f).toInt()
            }
            bar.requestLayout()

            // 웹 SplashScreen 의 shimmerSlide: -40% → 120%
            val anim = ObjectAnimator.ofFloat(
                bar,
                "translationX",
                -trackWidth * 0.4f,
                trackWidth * 1.2f
            ).apply {
                duration = 2200L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
            }
            anim.start()
            splashShimmerAnimator = anim
        }
    }

    /**
     * splash 오버레이를 숨김. 최소 600ms 노출 보장 후 220ms fade-out.
     * onPageCommitVisible / 안전망 timeout 어디서 호출되더라도 splashHidden 플래그로 first-only.
     */
    private fun hideSplashWhenReady() {
        if (splashHidden) return
        splashHidden = true
        val elapsed = SystemClock.elapsedRealtime() - splashShownAt
        val remaining = (600L - elapsed).coerceAtLeast(0L)
        loadingOverlay.postDelayed({
            loadingOverlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    loadingOverlay.visibility = View.GONE
                    splashShimmerAnimator?.cancel()
                    splashShimmerAnimator = null
                }
                .start()
        }, remaining)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
