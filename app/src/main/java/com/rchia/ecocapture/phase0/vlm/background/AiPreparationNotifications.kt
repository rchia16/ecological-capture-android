package com.rchia.ecocapture.phase0.vlm.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.WorkManager
import com.rchia.ecocapture.phase0.MainActivity
import java.util.UUID

object AiPreparationNotifications {
    const val OPEN_REVIEW = "com.rchia.ecocapture.OPEN_AI_REVIEW"
    const val ONGOING_ID = 7101
    private const val CHANNEL = "ai_preparation"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AI suggestion preparation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Preparation status, cancellation and suggestion readiness."
            })
    }

    fun allowed(context: Context): Boolean {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun notification(context: Context, title: String, ongoing: Boolean, workId: UUID? = null): Notification {
        createChannel(context)
        val open = PendingIntent.getActivity(context, 7100,
            Intent(context, MainActivity::class.java).setAction(OPEN_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(if (ongoing) "You can leave the app. Cancel stops this preparation." else "Open Review Recordings to review the suggestion.")
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply {
                if (workId != null) addAction(Notification.Action.Builder(null, "Cancel",
                    WorkManager.getInstance(context).createCancelPendingIntent(workId)).build())
            }.build()
    }

    fun ready(context: Context, clipId: String) {
        if (allowed(context)) context.getSystemService(NotificationManager::class.java)
            .notify("ai-ready-$clipId", 7102, notification(context, "AI suggestion ready", false))
    }
}
