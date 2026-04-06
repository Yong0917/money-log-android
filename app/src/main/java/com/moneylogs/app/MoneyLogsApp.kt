package com.moneylogs.app

import android.app.Application
import android.webkit.WebView

class MoneyLogsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // WebView 엔진(Chromium) 사전 초기화 — MainActivity 시작 전에 렌더러 프로세스를 미리 띄워
        // 첫 화면이 보이는 시간을 단축한다.
        WebView(applicationContext).destroy()
    }
}
