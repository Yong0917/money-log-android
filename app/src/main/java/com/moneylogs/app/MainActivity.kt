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
                // Google OAuth URL은 WebView 대신 Chrome Custom Tabs으로 열어야 함
                // WebView에서 열면 Google 정책상 Error 403: disallowed_useragent 발생
                if (url.contains("accounts.google.com")) {
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(this@MainActivity, request.url)
                    return true
                }
                return false
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

        // 딥링크/OAuth 콜백으로 실행된 경우 intent URL을 먼저 처리
        if (intent?.data != null) {
            handleIncomingUri(intent.data!!)
        } else {
            webView.loadUrl("https://moneylogs.vercel.app/")
        }
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
        } else {
            webView.loadUrl(uri.toString())
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
    }

    override fun onPause() {
        super.onPause()
        // 앱이 백그라운드로 가거나 종료될 때 쿠키를 디스크에 강제 저장
        // 이 없으면 프로세스 종료 시 세션 쿠키가 유실됨
        CookieManager.getInstance().flush()
    }
}
