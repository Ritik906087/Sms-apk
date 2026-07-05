package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsReceiver", "MMS Broadcast received: ${intent.action}")
        // Standard stub as required for registration.
    }
}
