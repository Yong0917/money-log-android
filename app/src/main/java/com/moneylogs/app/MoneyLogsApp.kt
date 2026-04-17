package com.moneylogs.app

import android.app.Application
import android.webkit.WebView
import com.google.firebase.FirebaseApp

class MoneyLogsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // WebView 엔진(Chromium) 사전 초기화 — MainActivity 시작 전에 렌더러 프로세스를 미리 띄워
        // 첫 화면이 보이는 시간을 단축한다.
        WebView(applicationContext).destroy()

        // Firebase 수동 초기화 — AndroidManifest에서 FirebaseInitProvider(ContentProvider)를
        // 제거했으므로 여기서 직접 호출해야 한다.
        // ContentProvider가 수행하던 내부 리플렉션/초기화 스캔 비용을 덜어 앱 시작 시간을 약간 단축.
        // FirebaseMessaging.getInstance() 호출 전에 반드시 완료되어야 하므로 메인 스레드에서 동기 호출.
        FirebaseApp.initializeApp(this)
    }
}
