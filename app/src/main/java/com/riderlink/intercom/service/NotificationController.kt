package com.riderlink.intercom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.ui.HomeActivity
import com.riderlink.intercom.ui.RideActivity

/**
 * Manages the foreground service notification channel and status/action updates.
 */
class NotificationController(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun update(isConnected: Boolean, linkState: LinkState, isMuted: Boolean) {
        manager.notify(NOTIFICATION_ID, build(isConnected, linkState, isMuted))
    }

    fun build(isConnected: Boolean, linkState: LinkState, isMuted: Boolean): Notification {
        val targetActivity = if (isConnected) RideActivity::class.java else HomeActivity::class.java
        val launchIntent = Intent(context, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(context, IntercomForegroundService::class.java).apply {
            action = IntercomForegroundService.ACTION_TOGGLE_MUTE
        }
        val mutePending = PendingIntent.getService(
            context,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, IntercomForegroundService::class.java).apply {
            action = IntercomForegroundService.ACTION_STOP_RIDE
        }
        val stopPending = PendingIntent.getService(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val linkText = when (linkState) {
            LinkState.CONNECTED -> context.getString(R.string.notif_state_connected)
            LinkState.RECONNECTING -> context.getString(R.string.notif_state_reconnecting)
            LinkState.WAITING_FOR_PEER -> context.getString(R.string.notif_state_waiting)
            else -> context.getString(R.string.notif_state_ready)
        }
        val muteLabel = if (isMuted) {
            context.getString(R.string.notif_action_unmute)
        } else {
            context.getString(R.string.notif_action_mute)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(linkText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .addAction(0, muteLabel, mutePending)
            .addAction(0, context.getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "riderlink_intercom_channel"
        const val NOTIFICATION_ID = 1001
    }
}
