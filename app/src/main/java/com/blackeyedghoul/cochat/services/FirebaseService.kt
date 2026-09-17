package com.blackeyedghoul.cochat.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blackeyedghoul.cochat.Home
import com.blackeyedghoul.cochat.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.random.Random

/**
 * Receiving side of CoChat's original FCM experiment.
 *
 * Notification sending is intentionally no longer implemented in the Android client. A trusted
 * backend can still target a device/token and this service will render the incoming data payload.
 */
class FirebaseService : FirebaseMessagingService() {

    private val channelId = "cochat_messages"

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Keep the historical user model usable for a trusted notification backend without
        // storing any sender credential in the APK.
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .update("fcmToken", token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CoChat messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Message notifications"
                enableLights(true)
                lightColor = Color.WHITE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, Home::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val title = remoteMessage.data["title"] ?: "CoChat"
        val message = remoteMessage.data["message"] ?: return

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.app_logo_img)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Random.nextInt(), notification)
    }
}
