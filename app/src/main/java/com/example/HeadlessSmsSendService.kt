package com.example

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("HeadlessSmsSendService", "onBind: ${intent?.action}")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HeadlessSmsSendService", "onStartCommand: ${intent?.action}")
        // Standard stub required for headless SMS sending
        stopSelf()
        return START_NOT_STICKY
    }
}
