package com.carriez.flutter_hbb

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"

        @Volatile
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
        _rdClipboardManager = RdClipboardManager(
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ).also { FFI.setClipboardManager(it) }
    }
}
