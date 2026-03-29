package com.moneylogs.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View

    // 업데이트 플로우 결과 수신 런처
    private lateinit var updateLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        // 스플래시 스크린 설치 — setContentView 전에 호출해야 함
        installSplashScreen()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // 오버레이 배경색을 시스템 테마에 맞게 설정
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        loadingOverlay.setBackgroundColor(
            if (isNightMode) Color.parseColor("#121317") else Color.parseColor("#FAF8F5")
        )

        // settings를 먼저 적용해야 loadUrl 시점에 모든 설정이 유효함
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true          // IndexedDB 지원 활성화
            cacheMode = WebSettings.LOAD_DEFAULT  // HTTP 캐시 헤더 준수
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            // JS에서 User-Agent로 WebView 환경 감지 가능하도록 커스텀 문자열 추가
            userAgentString = "$userAgentString MoneyLogsApp/Android"
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
                // onPageFinished보다 먼저 호출 — 첫 픽셀이 화면에 그려지는 시점에 오버레이 제거
                loadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { loadingOverlay.visibility = View.GONE }
                    .start()
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
                    """.trimIndent(),
                    null
                )
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

        // Play Store 업데이트 체크
        checkForUpdate()

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

    // OAuth 결과 딥링크로 앱이 다시 열릴 때 호출됨
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            handleIncomingUri(uri)
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

                else -> webView.loadUrl(uri.toString())
            }
        }
        // 알 수 없는 스킴은 로드하지 않음 — 외부 앱의 악의적 Intent 방어
    }

    // 웹앱에 Android WebView 환경임을 알리는 브릿지
    // static 중첩 클래스 + WeakReference: inner class는 Activity를 강참조하여 메모리 누수 위험
    class AndroidBridge(activity: MainActivity) {
        private val activityRef = WeakReference(activity)

        @android.webkit.JavascriptInterface
        fun getPlatform(): String = "android"
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
        // WebView native 리소스 명시적 해제 — 메모리 누수 방지
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
