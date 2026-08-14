package com.turbotext.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Handles "respond via message" (e.g. declining an incoming call with a
 * text reply) — required for default SMS apps.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (address != null && body != null) {
            SmsRepository(applicationContext).sendMessage(address, body)
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
