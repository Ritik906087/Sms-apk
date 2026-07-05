package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SmsReceiver", "onReceive: ${intent.action}")
        if (intent.action == "android.provider.Telephony.SMS_DELIVER") {
            val bundle = intent.extras
            if (bundle != null) {
                try {
                    val pdus = bundle.get("pdus") as? Array<*>
                    val format = bundle.getString("format")
                    if (pdus != null) {
                        for (pdu in pdus) {
                            val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                SmsMessage.createFromPdu(pdu as ByteArray, format)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsMessage.createFromPdu(pdu as ByteArray)
                            }
                            val sender = smsMessage.displayOriginatingAddress ?: "Unknown"
                            val body = smsMessage.displayMessageBody ?: ""
                            val timestamp = smsMessage.timestampMillis

                            // Save message to ContentProvider (Inbox)
                            saveSmsToInbox(context, sender, body, timestamp)

                            // Show standard system notification
                            showNotification(context, sender, body)
                        }

                        // Send local broadcast to notify UI components to refresh
                        val refreshIntent = Intent("com.example.SMS_REFRESH")
                        context.sendBroadcast(refreshIntent)
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error parsing SMS", e)
                }
            }
        }
    }

    private fun saveSmsToInbox(context: Context, sender: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put("address", sender)
                put("body", body)
                put("date", timestamp)
                put("read", 0) // Unread
                put("type", 1) // 1 = inbox (received)
            }
            val uri = context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
            Log.d("SmsReceiver", "Saved SMS to inbox: $uri")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Failed to save SMS to inbox", e)
        }
    }

    private fun showNotification(context: Context, sender: String, body: String) {
        val channelId = "sms_channel"
        val notificationId = System.currentTimeMillis().toInt()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS Notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("sender_number", sender)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat) // Standard Android system chat icon
            .setContentTitle(sender)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
