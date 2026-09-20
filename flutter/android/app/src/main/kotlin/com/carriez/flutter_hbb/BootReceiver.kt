package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

const val DEBUG_BOOT_COMPLETED = "com.inforchannel.rustdesk.DEBUG_BOOT_COMPLETED"

class BootReceiver : BroadcastReceiver() {
    private val logTag = "tagBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(logTag, "onReceive ${intent.action}")

        val shouldStart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == DEBUG_BOOT_COMPLETED

        if (!shouldStart) return

        // Managed mode: make RustDesk reachable immediately, but do not
        // initialize MediaProjection here. Screen capture is requested only
        // when an authorized remote desktop session actually needs video.
        val serviceIntent = Intent(context, MainService::class.java).apply {
            action = ACT_START_LISTENER_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
