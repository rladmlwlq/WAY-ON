package com.wayon.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService

/**
 * 앱의 두뇌 (car-app-design.md 2장). 화면을 닫아도 서버 통신과 차량 값 읽기를 계속한다.
 * 지금은 포그라운드로 뜨기만 하는 뼈대. ServerClient, CarSignals, NaviBridge, Rules, Voice를 여기에 붙인다.
 */
class WayOnService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WAY:ON 연결", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WAY:ON 연결 중")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // location 타입은 Android 위치 권한이 필요해서 specialUse를 씀 (car-app-design.md 7-1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "wayon_connection"
        const val NOTIFICATION_ID = 1
    }
}
