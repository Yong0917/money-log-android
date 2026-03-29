package com.moneylogs.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MoneyLogsFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM 수신: ${message.notification?.title}")

        val title = message.notification?.title ?: "머니로그"
        val body = message.notification?.body ?: return
        val screen = message.data["screen"] ?: "/ledger/daily"
        val recurringId = message.data["recurringId"]

        showNotification(title, body, screen, recurringId)
    }

    // FCM 토큰이 갱신될 때 호출됨
    // 새 토큰을 WebView의 window.onFcmTokenRefreshed 콜백으로 전달
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM 토큰 갱신: $token")
        // 저장해 두고 다음 앱 실행 시 AndroidBridge.getFcmToken()으로 전달
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    private fun showNotification(title: String, body: String, screen: String, recurringId: String? = null) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 채널 생성 (Android 8.0+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "고정비 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "고정비 결제일 알림"
        }
        notificationManager.createNotificationChannel(channel)

        // 알림 탭 시 앱 열기 + 해당 화면으로 딥링크
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SCREEN, screen)
            if (recurringId != null) putExtra(EXTRA_RECURRING_ID, recurringId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val TAG = "MoneyLogsFCM"
        const val CHANNEL_ID = "recurring_notifications"
        const val PREFS_NAME = "moneylogs_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_RECURRING_ID = "recurringId"
    }
}
