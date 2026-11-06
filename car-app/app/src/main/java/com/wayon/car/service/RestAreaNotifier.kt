package com.wayon.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wayon.car.MainActivity

/**
 * 휴게소 도착 알림 (car-app-design.md 6-7, 7-1). 도착했는데 우리 앱이 화면에 없을 때(내비만 보고 있을 때) 띄운다.
 * 누르면 앱이 열리고 홈의 도착 카드가 보인다. 도착은 기어 P일 때만 생기므로 주행 중에는 뜨지 않는다.
 *
 * - 같은 ID로 다시 올리면 내용만 바뀌고 소리·heads-up은 처음 한 번만 (휴게소 정보가 늦게 와도 다시 울리지 않음)
 * - 떠나거나(기어 D) 앱을 열면 지운다
 */
class RestAreaNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var shownFor: String? = null

    init {
        // heads-up은 중요도 HIGH 채널이어야 뜬다
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "휴게소 도착", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "휴게소에 도착했을 때 영업 중인 매장과 편의시설을 알려 줍니다"
            }
        )
    }

    fun show(card: ArrivedCard) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("${card.name} 도착")
            .setContentText(bodyText(card))
            .setStyle(Notification.BigTextStyle().bigText(bodyText(card)))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        shownFor = card.name
    }

    fun cancel() {
        if (shownFor == null) return
        manager.cancel(NOTIFICATION_ID)
        shownFor = null
    }

    companion object {
        const val CHANNEL_ID = "wayon_rest_area"
        private const val NOTIFICATION_ID = 2 // 1은 포그라운드 서비스 알림

        /** 알림 본문: "영업 중 할리스 · 크리스피크림도넛 외 2곳\n편의시설 수유실 · 샤워실 …" (누르면 휴게소 지도) */
        internal fun bodyText(card: ArrivedCard): String {
            val map = card.map
            return when {
                map != null -> {
                    val open = map.openStoreNames()
                    val facilities = map.building.facilities.map { it.name }.distinct()
                    val openText = when {
                        open.isEmpty() -> "영업 중인 매장 정보 없음"
                        open.size <= 2 -> "영업 중 " + open.joinToString(" · ")
                        else -> "영업 중 " + open.take(2).joinToString(" · ") + " 외 ${open.size - 2}곳"
                    }
                    if (facilities.isEmpty()) openText
                    else openText + "\n편의시설 " + facilities.take(4).joinToString(" · ")
                }
                card.loadFailed -> "눌러서 휴게소 정보 보기"
                else -> "휴게소 정보를 불러오는 중"
            }
        }
    }
}
