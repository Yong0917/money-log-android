package com.moneylogs.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View

    // 업데이트 플로우 결과 수신 런처
    private lateinit var updateLauncher: ActivityResultLauncher<IntentSenderRequest>

    // OAuth 진행 중 여부 추적 (Custom Tab → 앱 복귀 시 처리용)
    private var isOAuthInProgress = false

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

        // 웹앱에서 Android WebView 환경 감지 및 커스텀 스킴 제공
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
                // Google OAuth URL → Chrome Custom Tab으로 열기 (WebView 차단 우회)
                if (url.startsWith("https://accounts.google.com")) {
                    isOAuthInProgress = true
                    CustomTabsIntent.Builder()
                        .build()
                        .launchUrl(this@MainActivity, request.url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 웹 로드 완료 → 오버레이를 300ms에 걸쳐 페이드아웃
                loadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { loadingOverlay.visibility = View.GONE }
                    .start()
            }
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

        // App Link(OAuth 콜백)로 실행된 경우 해당 URL을 WebView에 로드
        val startUrl = intent?.data?.toString() ?: "https://moneylogs.vercel.app/"
        webView.loadUrl(startUrl)
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

    // Chrome Custom Tab에서 OAuth 완료 후 콜백 URL로 돌아올 때 호출됨
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        isOAuthInProgress = false  // 콜백 정상 수신
        intent.data?.let { uri ->
            if (uri.scheme == "com.moneylogs.app") {
                when (uri.host) {
                    "auth-callback" -> {
                        // OAuth 완료 후 Custom Tab이 앱 딥링크로 복귀하면,
                        // code를 WebView의 웹 콜백으로 전달하여 서버에서 세션 교환 수행
                        val callbackUrl = buildString {
                            append("https://moneylogs.vercel.app/auth/callback")
                            append("?code=").append(android.net.Uri.encode(uri.getQueryParameter("code") ?: ""))

                            uri.getQueryParameter("next")?.let { next ->
                                append("&next=").append(android.net.Uri.encode(next))
                            }
                            uri.getQueryParameter("error")?.let { error ->
                                append("&error=").append(android.net.Uri.encode(error))
                            }
                            uri.getQueryParameter("error_code")?.let { errorCode ->
                                append("&error_code=").append(android.net.Uri.encode(errorCode))
                            }
                            uri.getQueryParameter("error_description")?.let { errorDescription ->
                                append("&error_description=").append(android.net.Uri.encode(errorDescription))
                            }

                            append("&android=1")
                        }
                        webView.loadUrl(callbackUrl)
                    }

                    "done" -> {
                        // Chrome Custom Tab에서 받은 세션 토큰을 WebView의 set-session 페이지로 전달
                        // Chrome Custom Tab과 WebView는 쿠키가 공유되지 않으므로
                        // WebView에서 직접 supabase.auth.setSession()을 호출해야 함
                        val accessToken = uri.getQueryParameter("access_token") ?: ""
                        val refreshToken = uri.getQueryParameter("refresh_token") ?: ""
                        val next = uri.getQueryParameter("next") ?: "/ledger/daily"
                        val setSessionUrl = "https://moneylogs.vercel.app/auth/set-session" +
                            "?access_token=${android.net.Uri.encode(accessToken)}" +
                            "&refresh_token=${android.net.Uri.encode(refreshToken)}" +
                            "&next=${android.net.Uri.encode(next)}"
                        webView.loadUrl(setSessionUrl)
                    }

                    else -> webView.loadUrl(uri.toString())
                }
            } else {
                webView.loadUrl(uri.toString())
            }
        }
    }

    // 웹앱에 Android WebView 환경임을 알리는 브릿지
    // @JavascriptInterface 메서드가 없으면 JS에서 객체가 노출되지 않으므로 반드시 메서드 필요
    inner class AndroidBridge {
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
        // OAuth 중에 Custom Tab이 닫혔지만 App Link 콜백이 오지 않은 경우
        // (사용자 취소 또는 App Link 미작동) → 로그인 페이지 새로고침으로 버튼 활성화
        if (isOAuthInProgress) {
            isOAuthInProgress = false
            webView.reload()
        }
    }

    override fun onPause() {
        super.onPause()
        // 앱이 백그라운드로 가거나 종료될 때 쿠키를 디스크에 강제 저장
        // 이 없으면 프로세스 종료 시 세션 쿠키가 유실됨
        CookieManager.getInstance().flush()
    }
}
