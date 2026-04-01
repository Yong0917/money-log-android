package com.moneylogs.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.messaging.FirebaseMessagingService
import com.moneylogs.app.BuildConfig
import com.google.firebase.messaging.RemoteMessage
import kotlin.random.Random

class MoneyLogsFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "FCM 수신: ${message.notification?.title}")
        }

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
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "FCM 토큰 갱신")
        }
        // 저장해 두고 다음 앱 실행 시 AndroidBridge.getFcmToken()으로 전달
        getEncryptedPrefs(this)
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

        // 알림마다 고유 ID 생성 — System.currentTimeMillis().toInt() 은 오버플로우 위험
        val notificationId = Random.nextInt(1, Int.MAX_VALUE)

        // 알림 탭 시 앱 열기 + 해당 화면으로 딥링크
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SCREEN, screen)
            if (recurringId != null) putExtra(EXTRA_RECURRING_ID, recurringId)
        }
        // requestCode를 notificationId와 동일하게 사용해 다중 알림 Intent 충돌 방지
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
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

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "MoneyLogsFCM"
        const val CHANNEL_ID = "recurring_notifications"
        const val PREFS_NAME = "moneylogs_secure_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_RECURRING_ID = "recurringId"

        // FCM 토큰을 암호화된 SharedPreferences에 저장/읽기
        // 초기화 실패 시 (예: 에뮬레이터 키스토어 미지원) 일반 SharedPreferences로 폴백
        fun getEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences 초기화 실패, 일반 SharedPreferences 사용", e)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
