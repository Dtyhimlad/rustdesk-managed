package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "RustDesk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val PROJECTION_REQUEST_NOTIFY_ID = 2
const val NOTIFY_ID_OFFSET = 100

const val ACT_START_LISTENER_SERVICE = "com.inforchannel.rustdesk.START_LISTENER_SERVICE"
const val ACT_MEDIA_PROJECTION_DENIED = "com.inforchannel.rustdesk.MEDIA_PROJECTION_DENIED"

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer && !isStart) {
                            if (mediaProjection == null) {
                                pendingCaptureRequest = true
                                requestMediaProjectionForCapture()
                            } else {
                                startCapture()
                            }
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!switchOutVoiceCall()) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                showVoiceCallError("Failed to switch out voice call.")
                            }
                        }
                    } else {
                        if (!switchToVoiceCall()) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            showVoiceCallError(audioRecordHandle.getVoiceCallStartError())
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture(releaseProjection = true)
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var captureRestartPending = false
    private var captureRestartInVoiceCall = false
    private val mediaProjectionResultReceiver =
        object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RES_FAILED) {
                    cancelMediaProjectionRecovery()
                }
            }
        }
    private var mediaProjectionForegroundService = false
    private var microphoneForegroundService = false
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pendingCaptureRequest = false
    private var projectionRequestInFlight = false

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var explicitShutdown = false

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        val homePath = applicationContext.getExternalFilesDir(null)?.absolutePath
            ?: applicationContext.filesDir.absolutePath
        FFI.startServer(configPath, homePath, "")

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        if (!explicitShutdown) {
            scheduleListenerRestart()
        }
        super.onDestroy()
    }

    // Swiping the app away from recents destroys the UI but this service keeps
    // the process alive, so outgoing sessions would stay connected with no way
    // to close them. Incoming connections are unaffected: the service keeps
    // running so the device stays reachable.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(logTag, "onTaskRemoved, closing outgoing sessions and preserving listener")
        FFI.closeAllSessions()
        scheduleListenerRestart()
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun scheduleListenerRestart() {
        if (explicitShutdown) return
        val restartIntent = Intent(applicationContext, MainService::class.java).apply {
            action = ACT_START_LISTENER_SERVICE
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
        } else {
            FLAG_UPDATE_CURRENT
        }
        val restartIntentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext,
                3011,
                restartIntent,
                pendingIntentFlags
            )
        } else {
            PendingIntent.getService(
                applicationContext,
                3011,
                restartIntent,
                pendingIntentFlags
            )
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_000L,
            restartIntentSender
        )
        Log.d(logTag, "Listener service restart scheduled")
    }

    private var isHalfScale: Boolean? = null;

    private fun isTvDevice(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val lacksTouchscreen = !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val isAutomotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val isWatch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback ||
            (lacksTouchscreen && !isAutomotive && !isWatch)
    }

    private fun isTvOrConstrainedProcess(): Boolean {
        val is32Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Process.is64Bit()
        return isTvDevice() || is32Bit
    }

    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val maxDimension = max(w,h)
        val minDimension = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = maxDimension
            h = minDimension
        } else {
            w = minDimension
            h = maxDimension
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            val tvCaptureLimit = if (
                isTvDevice() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                maxDimension > 2560
            ) {
                1280
            } else {
                1920
            }
            if (isTvOrConstrainedProcess() && maxDimension > tvCaptureLimit) {
                scale = (maxDimension + tvCaptureLimit - 1) / tvCaptureLimit
                w /= scale
                h /= scale
                dpi = max(1, dpi / scale)
                Log.d(logTag, "TV capture limited to " + w + "x" + h + ", scale:" + scale)
            } else if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACT_START_LISTENER_SERVICE -> {
                createForegroundNotification()
                FFI.startService()
                Log.d(logTag, "listener service started")
            }

            ACT_INIT_MEDIA_PROJECTION_AND_SERVICE -> {
                createForegroundNotification()
                if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                    FFI.startService()
                }
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                    notificationManager.cancel(PROJECTION_REQUEST_NOTIFY_ID)
                    InputService.ctx?.hideProjectionRequestOverlay()
                    projectionRequestInFlight = false
                    if (pendingCaptureRequest) {
                        captureRestartPending = true
                    }
                    pendingCaptureRequest = false
                    replaceMediaProjection(mediaProjectionManager, it)
                } ?: run {
                    Log.d(logTag, "MediaProjection result missing; requesting capture permission")
                    requestMediaProjectionForCapture()
                }
            }

            ACT_MEDIA_PROJECTION_DENIED -> {
                notificationManager.cancel(PROJECTION_REQUEST_NOTIFY_ID)
                InputService.ctx?.hideProjectionRequestOverlay()
                projectionRequestInFlight = false
                pendingCaptureRequest = false
                cancelMediaProjectionRecovery()
                _isReady = false
                checkMediaPermission()
                Log.d(logTag, "MediaProjection request denied or cancelled")
            }

            null -> {
                createForegroundNotification()
                FFI.startService()
                Log.d(logTag, "listener service recreated")
            }
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjectionForCapture() {
        if (mediaProjection != null) {
            if (pendingCaptureRequest) {
                pendingCaptureRequest = false
                startCapture()
            }
            return
        }
        if (projectionRequestInFlight) return
        projectionRequestInFlight = true
        Handler(Looper.getMainLooper()).post {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection(recovery: Boolean = false) {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recovery) {
                putExtra(EXT_MEDIA_PROJECTION_RESULT_RECEIVER, mediaProjectionResultReceiver)
            }
        }

        if (MainActivity.isInForeground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                startActivity(intent)
                return
            } catch (error: Exception) {
                Log.w(logTag, "Direct MediaProjection permission launch failed", error)
            }
        }

        InputService.ctx?.showProjectionRequestOverlay()
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
        } else {
            FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            PROJECTION_REQUEST_NOTIFY_ID,
            intent,
            pendingIntentFlags
        )
        val notification = NotificationCompat.Builder(this, notificationChannel)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setContentTitle("Remote access request")
            .setContentText("Select to allow screen sharing")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        notificationManager.notify(PROJECTION_REQUEST_NOTIFY_ID, notification)
    }

    @Synchronized
    private fun cancelMediaProjectionRecovery() {
        captureRestartPending = false
        captureRestartInVoiceCall = false
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        if (useVP9) {
            // TODO
            return null
        }

        return try {
            val maxImages = if (isTvOrConstrainedProcess()) 2 else 4
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO buffers:$maxImages")
            imageReader = ImageReader.newInstance(
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                PixelFormat.RGBA_8888,
                maxImages
            ).apply {
                setOnImageAvailableListener({ imageReader: ImageReader ->
                    try {
                        imageReader.acquireLatestImage().use { image ->
                            if (image == null || !isStart) return@setOnImageAvailableListener
                            val buffer = image.planes[0].buffer
                            buffer.rewind()
                            FFI.onVideoFrameUpdate(buffer)
                        }
                    } catch (ignored: Exception) {
                    }
                }, serviceHandler)
            }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        } catch (error: Throwable) {
            Log.e(logTag, "Unable to allocate the screen capture surface", error)
            imageReader = null
            null
        }
    }

    private fun releaseMediaProjection() {
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (error: Exception) {
                Log.w(logTag, "Failed to unregister MediaProjection callback", error)
            }
        }
        try {
            projection?.stop()
        } catch (error: Exception) {
            Log.w(logTag, "Failed to stop MediaProjection cleanly", error)
        }
    }

    @Synchronized
    private fun handleMediaProjectionStopped(stoppedProjection: MediaProjection) {
        if (mediaProjection !== stoppedProjection) {
            return
        }
        Log.w(logTag, "MediaProjection stopped")
        setMediaProjectionForegroundService(false)
        stopCapture(releaseProjection = false)
        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {
        }
        virtualDisplay = null
        mediaProjection = null
        mediaProjectionCallback = null
        pendingCaptureRequest = false
        projectionRequestInFlight = false
        _isReady = false
        checkMediaPermission()
    }

    @Synchronized
    private fun replaceMediaProjection(
        mediaProjectionManager: MediaProjectionManager,
        resultIntent: Intent,
    ) {
        val wasCapturing = isStart
        val restartCapture = wasCapturing || captureRestartPending
        val restartInVoiceCall = if (wasCapturing) {
            audioRecordHandle.isVoiceCallActive()
        } else {
            captureRestartInVoiceCall
        }
        val hadProjection = mediaProjection != null
        if (!setMediaProjectionForegroundService(true)) {
            if (!hadProjection) {
                cancelMediaProjectionRecovery()
                _isReady = false
                checkMediaPermission()
            }
            return
        }
        val projection = try {
            mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, resultIntent)
        } catch (error: Exception) {
            Log.e(logTag, "Failed to create MediaProjection", error)
            null
        }
        if (projection == null) {
            if (!hadProjection) {
                cancelMediaProjectionRecovery()
                _isReady = false
                setMediaProjectionForegroundService(false)
                checkMediaPermission()
            }
            return
        }
        if (wasCapturing) {
            stopCapture()
        }
        captureRestartPending = restartCapture
        virtualDisplay?.release()
        virtualDisplay = null
        releaseMediaProjection()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handleMediaProjectionStopped(projection)
            }
        }
        try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (error: Exception) {
            Log.e(logTag, "Failed to register MediaProjection callback", error)
            try {
                projection.stop()
            } catch (ignored: Exception) {
            }
            if (!hadProjection) {
                cancelMediaProjectionRecovery()
                _isReady = false
                setMediaProjectionForegroundService(false)
                checkMediaPermission()
            }
            return
        }
        mediaProjection = projection
        mediaProjectionCallback = callback
        _isReady = true
        checkMediaPermission()
        if (restartCapture) {
            captureRestartPending = false
            startCapture(restartInVoiceCall)
        }
    }

    private fun showVoiceCallError(message: String) {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to message))
        }
    }

    @Synchronized
    private fun startMicrophoneCapture(startAudio: () -> Boolean): Boolean {
        if (!setMicrophoneForegroundService(true)) {
            return false
        }
        if (startAudio()) {
            return true
        }
        setMicrophoneForegroundService(false)
        return false
    }

    @Synchronized
    private fun stopMicrophoneCapture(stopAudio: () -> Boolean): Boolean {
        val stopped = stopAudio()
        val foregroundServiceUpdated = setMicrophoneForegroundService(false)
        return stopped && foregroundServiceUpdated
    }

    @Synchronized
    private fun switchToVoiceCall(): Boolean {
        if (captureRestartPending) {
            captureRestartInVoiceCall = true
        }
        return startMicrophoneCapture {
            audioRecordHandle.switchToVoiceCall(mediaProjection)
        }
    }

    @Synchronized
    private fun switchOutVoiceCall(): Boolean {
        captureRestartInVoiceCall = false
        val switched = audioRecordHandle.switchOutVoiceCall(mediaProjection)
        val foregroundServiceUpdated = setMicrophoneForegroundService(false)
        return switched && foregroundServiceUpdated
    }

    @Synchronized
    fun onVoiceCallStarted(): Boolean {
        if (captureRestartPending) {
            captureRestartInVoiceCall = true
        }
        return startMicrophoneCapture {
            audioRecordHandle.onVoiceCallStarted(mediaProjection)
        }
    }

    @Synchronized
    fun onVoiceCallClosed(): Boolean {
        captureRestartInVoiceCall = false
        return stopMicrophoneCapture {
            audioRecordHandle.onVoiceCallClosed(mediaProjection)
        }
    }

    fun startCapture(): Boolean {
        return startCapture(false)
    }

    @Synchronized
    private fun startCapture(inVoiceCall: Boolean): Boolean {
        if (isStart) {
            return true
        }
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        captureRestartInVoiceCall = inVoiceCall
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        val projection = mediaProjection ?: return false
        val videoStarted = try {
            surface = createSurface()
            if (surface == null) {
                false
            } else if (useVP9) {
                startVP9VideoRecorder(projection)
            } else {
                startRawVideoRecorder(projection)
            }
        } catch (error: Throwable) {
            Log.e(logTag, "Screen capture initialization failed", error)
            false
        }
        if (!videoStarted) {
            if (!captureRestartPending) {
                captureRestartInVoiceCall = false
            }
            releaseFailedVideoCapture()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isTvOrConstrainedProcess()) {
            val audioStarted = if (inVoiceCall) {
                switchToVoiceCall()
            } else {
                audioRecordHandle.createAudioRecorder(false, mediaProjection) &&
                        audioRecordHandle.startAudioRecorder()
            }
            Log.d(logTag, if (audioStarted) "audio recorder start" else "audio recorder start failed")
        } else if (isTvOrConstrainedProcess()) {
            Log.d(logTag, "Skipping system audio capture on TV or constrained process")
        }
        captureRestartInVoiceCall = false
        checkMediaPermission()
        _isStart = true
        FFI.setFrameRawEnable("video",true)
        MainApplication.rdClipboardManager?.setCaptureStarted(_isStart)
        return true
    }

    private fun releaseFailedVideoCapture() {
        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {
        }
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            try {
                it.signalEndOfInputStream()
                it.stop()
            } catch (error: Exception) {
                Log.w(logTag, "Failed to stop video encoder after capture failure", error)
            } finally {
                try {
                    it.release()
                } catch (ignored: Exception) {
                }
            }
        }
        videoEncoder = null
        surface?.release()
        surface = null
    }

    @Synchronized
    fun stopCapture(releaseProjection: Boolean = false) {
        Log.d(logTag, "Stop Capture, releaseProjection:$releaseProjection")
        InputService.ctx?.hideProjectionRequestOverlay()
        InputService.ctx?.hideRemoteCursor()
        captureRestartPending = false
        captureRestartInVoiceCall = false
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainApplication.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        if (releaseProjection) {
            try {
                virtualDisplay?.release()
            } catch (error: Exception) {
                Log.w(logTag, "Failed to release VirtualDisplay", error)
            }
            virtualDisplay = null
        } else if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            try {
                it.signalEndOfInputStream()
                it.stop()
            } catch (error: Exception) {
                Log.w(logTag, "Failed to stop video encoder cleanly", error)
            } finally {
                try {
                    it.release()
                } catch (error: Exception) {
                    Log.w(logTag, "Failed to release video encoder", error)
                }
            }
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()
        surface = null

        // release audio
        stopMicrophoneCapture {
            _isAudioStart = false
            audioRecordHandle.tryReleaseAudio()
            true
        }

        if (releaseProjection) {
            notificationManager.cancel(PROJECTION_REQUEST_NOTIFY_ID)
            pendingCaptureRequest = false
            projectionRequestInFlight = false
            _isReady = false
            releaseMediaProjection()
            setMediaProjectionForegroundService(false)
            checkMediaPermission()
        }
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        explicitShutdown = true
        _isReady = false
        _isAudioStart = false
        pendingCaptureRequest = false
        projectionRequestInFlight = false

        stopCapture(releaseProjection = true)

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjectionForegroundService = false
        microphoneForegroundService = false
        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection): Boolean {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        val captureSurface = surface
        if (captureSurface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return false
        }
        return createOrSetVirtualDisplay(mp, captureSurface)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection): Boolean {
        createMediaCodec()
        val encoder = videoEncoder ?: return false
        val inputSurface = encoder.createInputSurface()
        surface = inputSurface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            inputSurface.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        encoder.setCallback(cb)
        encoder.start()
        return createOrSetVirtualDisplay(mp, inputSurface)
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface): Boolean {
        return try {
            val existingDisplay = virtualDisplay
            if (existingDisplay != null) {
                existingDisplay.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                existingDisplay.setSurface(s)
                true
            } else {
                val display = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, null, null
                )
                if (display == null) {
                    Log.e(logTag, "createOrSetVirtualDisplay failed")
                    handleVirtualDisplayFailure()
                } else {
                    virtualDisplay = display
                    true
                }
            }
        } catch (error: Throwable) {
            Log.e(logTag, "Unable to create the screen capture display", error)
            if (error is SecurityException) {
                handleVirtualDisplayFailure()
            } else {
                false
            }
        }
    }

    private fun handleVirtualDisplayFailure(): Boolean {
        captureRestartPending = true
        virtualDisplay?.release()
        virtualDisplay = null
        releaseMediaProjection()
        setMediaProjectionForegroundService(false)
        _isReady = false
        checkMediaPermission()
        requestMediaProjection(true)
        return false
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(DEFAULT_NOTIFY_ID, notification, foregroundServiceType())
        } else {
            startForeground(DEFAULT_NOTIFY_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun foregroundServiceType(): Int {
        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Keep a valid FGS type while the unattended host is idle and no capture type is active.
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (mediaProjectionForegroundService) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && microphoneForegroundService) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return serviceType
    }

    private fun setMediaProjectionForegroundService(enabled: Boolean): Boolean {
        return updateForegroundServiceTypes(enabled, microphoneForegroundService)
    }

    private fun setMicrophoneForegroundService(enabled: Boolean): Boolean {
        return updateForegroundServiceTypes(mediaProjectionForegroundService, enabled)
    }

    private fun updateForegroundServiceTypes(
        mediaProjectionEnabled: Boolean,
        microphoneEnabled: Boolean,
    ): Boolean {
        if (mediaProjectionForegroundService == mediaProjectionEnabled &&
            microphoneForegroundService == microphoneEnabled) {
            return true
        }
        val previousMediaProjection = mediaProjectionForegroundService
        val previousMicrophone = microphoneForegroundService
        mediaProjectionForegroundService = mediaProjectionEnabled
        microphoneForegroundService = microphoneEnabled
        return try {
            createForegroundNotification()
            true
        } catch (error: SecurityException) {
            mediaProjectionForegroundService = previousMediaProjection
            microphoneForegroundService = previousMicrophone
            Log.e(logTag, "Failed to update foreground service types", error)
            false
        } catch (error: IllegalStateException) {
            mediaProjectionForegroundService = previousMediaProjection
            microphoneForegroundService = previousMicrophone
            Log.e(logTag, "Failed to update foreground service types", error)
            false
        }
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }
}
