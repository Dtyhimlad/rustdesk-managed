package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent as KeyEventAndroid
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.graphics.Rect
import android.media.AudioManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private fun notifyInputState() {
        val inputState = isOpen.toString()
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputState)
            )
        }
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var mouseDownX = 0
    private var mouseDownY = 0
    private var mouseDragged = false
    private var remoteControlDown = false
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var fakeEditTextForTextStateCalculation: EditText? = null

    private val overlayWindowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var remoteCursorView: View? = null
    private var remoteCursorParams: WindowManager.LayoutParams? = null
    private var projectionRequestView: Button? = null
    private var projectionRequestTimeout: Runnable? = null

    private data class NodeSnapshot(
        val node: AccessibilityNodeInfo,
        val bounds: Rect
    )

    private var lastX = 0
    private var lastY = 0

    private val volumeController: VolumeController by lazy { VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) }

    private fun isTvDevice(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun updateRemoteCursor(x: Int, y: Int) {
        if (!isTvDevice()) return
        overlayHandler.post {
            try {
                var cursor = remoteCursorView
                var params = remoteCursorParams
                if (cursor == null || params == null) {
                    val size = (22 * resources.displayMetrics.density).toInt().coerceAtLeast(22)
                    val cursorBackground = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(230, 255, 255, 255))
                        setStroke((2 * resources.displayMetrics.density).toInt().coerceAtLeast(2), Color.BLACK)
                    }
                    cursor = View(this).apply {
                        background = cursorBackground
                        elevation = 12f
                    }
                    params = WindowManager.LayoutParams(
                        size,
                        size,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                    overlayWindowManager.addView(cursor, params)
                    remoteCursorView = cursor
                    remoteCursorParams = params
                }

                params.x = (x - params.width / 2).coerceAtLeast(0)
                params.y = (y - params.height / 2).coerceAtLeast(0)
                overlayWindowManager.updateViewLayout(cursor, params)
            } catch (error: Exception) {
                Log.e(logTag, "Unable to update TV cursor overlay", error)
            }
        }
    }

    fun hideRemoteCursor() {
        overlayHandler.post {
            remoteCursorView?.let {
                try {
                    overlayWindowManager.removeView(it)
                } catch (ignored: Exception) {
                }
            }
            remoteCursorView = null
            remoteCursorParams = null
        }
    }

    fun showProjectionRequestOverlay() {
        if (!isTvDevice()) return
        overlayHandler.post {
            if (projectionRequestView != null) return@post
            try {
                val horizontalPadding = (28 * resources.displayMetrics.density).toInt()
                val verticalPadding = (18 * resources.displayMetrics.density).toInt()
                val requestButton = Button(this).apply {
                    text = "Remote support request\nPress OK to allow screen sharing"
                    textSize = 20f
                    isAllCaps = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    setOnClickListener {
                        removeProjectionRequestOverlay()
                        val permissionIntent =
                            Intent(this@InputService, PermissionRequestTransparentActivity::class.java).apply {
                                action = ACT_REQUEST_MEDIA_PROJECTION
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                        try {
                            startActivity(permissionIntent)
                        } catch (error: Exception) {
                            Log.e(logTag, "Unable to open screen sharing permission", error)
                        }
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                overlayWindowManager.addView(requestButton, params)
                projectionRequestView = requestButton
                requestButton.requestFocus()

                val timeout = Runnable { removeProjectionRequestOverlay() }
                projectionRequestTimeout = timeout
                overlayHandler.postDelayed(timeout, 60_000L)
            } catch (error: Exception) {
                Log.e(logTag, "Unable to show screen sharing request overlay", error)
            }
        }
    }

    fun hideProjectionRequestOverlay() {
        overlayHandler.post { removeProjectionRequestOverlay() }
    }

    private fun removeProjectionRequestOverlay() {
        projectionRequestTimeout?.let { overlayHandler.removeCallbacks(it) }
        projectionRequestTimeout = null
        projectionRequestView?.let {
            try {
                overlayWindowManager.removeView(it)
            } catch (ignored: Exception) {
            }
        }
        projectionRequestView = null
    }

    private fun collectVisibleNodes(
        node: AccessibilityNodeInfo?,
        nodes: MutableList<NodeSnapshot>,
        limit: Int = 512
    ) {
        if (node == null || nodes.size >= limit) return
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                nodes.add(NodeSnapshot(node, bounds))
            }
        }
        for (index in 0 until node.childCount) {
            collectVisibleNodes(node.getChild(index), nodes, limit)
            if (nodes.size >= limit) return
        }
    }

    private fun nodeCanClick(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_CLICK
        }
    }

    private fun clickTvNodeAt(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<NodeSnapshot>()
        collectVisibleNodes(root, nodes)
        val target = nodes
            .asSequence()
            .filter { it.bounds.contains(x, y) && nodeCanClick(it.node) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
            ?.node
            ?: return false
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(logTag, "TV node click at $x,$y success:$clicked")
        return clicked
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performTvCompatibleClick(x: Int, y: Int, duration: Long) {
        if (!isTvDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            performClick(x, y, duration)
            return
        }
        overlayHandler.post {
            // Android's documented compatibility order is ACTION_CLICK first,
            // followed by a coordinate gesture when an app exposes no usable node.
            if (!clickTvNodeAt(x, y)) {
                performClick(x, y, duration)
            }
        }
    }

    private fun directionalScore(current: Rect, candidate: Rect, direction: Int): Long? {
        val currentX = current.centerX()
        val currentY = current.centerY()
        val candidateX = candidate.centerX()
        val candidateY = candidate.centerY()
        val deltaX = candidateX - currentX
        val deltaY = candidateY - currentY
        val major: Int
        val minor: Int
        val overlapsBeam: Boolean
        when (direction) {
            View.FOCUS_UP -> {
                if (deltaY >= 0) return null
                major = -deltaY
                minor = abs(deltaX)
                overlapsBeam = candidate.right >= current.left && candidate.left <= current.right
            }
            View.FOCUS_DOWN -> {
                if (deltaY <= 0) return null
                major = deltaY
                minor = abs(deltaX)
                overlapsBeam = candidate.right >= current.left && candidate.left <= current.right
            }
            View.FOCUS_LEFT -> {
                if (deltaX >= 0) return null
                major = -deltaX
                minor = abs(deltaY)
                overlapsBeam = candidate.bottom >= current.top && candidate.top <= current.bottom
            }
            View.FOCUS_RIGHT -> {
                if (deltaX <= 0) return null
                major = deltaX
                minor = abs(deltaY)
                overlapsBeam = candidate.bottom >= current.top && candidate.top <= current.bottom
            }
            else -> return null
        }
        val beamPenalty = if (overlapsBeam) 0L else 1_000_000_000L
        return beamPenalty + major.toLong() * 10_000L + minor
    }

    private fun moveTvAccessibilityFocus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<NodeSnapshot>()
        collectVisibleNodes(root, nodes)
        val current = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodes.firstOrNull { it.node.isAccessibilityFocused || it.node.isFocused }?.node
            ?: root
        val currentBounds = Rect().also { current.getBoundsInScreen(it) }

        // Prefer Android's native TV focus order. Reject only a clearly
        // cross-axis result (for example, LEFT returned for an UP request),
        // which is seen in a few vendor player UIs.
        current.focusSearch(direction)?.let { nativeNext ->
            val nativeBounds = Rect().also { nativeNext.getBoundsInScreen(it) }
            val nativeDirectionMatches = current == root ||
                direction == View.FOCUS_FORWARD ||
                direction == View.FOCUS_BACKWARD ||
                nativeBounds == currentBounds ||
                directionalScore(currentBounds, nativeBounds, direction) != null
            if (nativeDirectionMatches && focusTvNode(nativeNext)) {
                Log.d(logTag, "TV native focus direction:$direction success:true")
                return true
            }
        }

        val next = nodes
            .asSequence()
            .filter {
                it.node != current &&
                    (it.node.isFocusable || it.node.isClickable) &&
                    directionalScore(currentBounds, it.bounds, direction) != null
            }
            .minByOrNull { directionalScore(currentBounds, it.bounds, direction) ?: Long.MAX_VALUE }
            ?.node
            ?: return false
        val focused = focusTvNode(next)
        Log.d(logTag, "TV spatial focus direction:$direction success:$focused")
        return focused
    }

    private fun focusTvNode(node: AccessibilityNodeInfo): Boolean {
        val accessibilityFocused =
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val inputFocused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return accessibilityFocused || inputFocused
    }

    private fun clickTvAccessibilityFocus(): Boolean {
        var node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow
            ?: return false
        while (!nodeCanClick(node)) {
            node = node.parent ?: return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryHandleTvNavigationKey(event: KeyEventAndroid): Boolean {
        if (!isTvDevice()) return false
        val supportedKey = when (event.keyCode) {
            KeyEventAndroid.KEYCODE_DPAD_UP,
            KeyEventAndroid.KEYCODE_DPAD_DOWN,
            KeyEventAndroid.KEYCODE_DPAD_LEFT,
            KeyEventAndroid.KEYCODE_DPAD_RIGHT,
            KeyEventAndroid.KEYCODE_TAB,
            KeyEventAndroid.KEYCODE_DPAD_CENTER,
            KeyEventAndroid.KEYCODE_ENTER,
            KeyEventAndroid.KEYCODE_NUMPAD_ENTER,
            KeyEventAndroid.KEYCODE_SPACE,
            KeyEventAndroid.KEYCODE_BACK,
            KeyEventAndroid.KEYCODE_ESCAPE -> true
            else -> false
        }
        if (!supportedKey) return false
        if (event.action != KeyEventAndroid.ACTION_DOWN) return true

        if (Build.VERSION.SDK_INT >= 33) {
            return when (event.keyCode) {
                KeyEventAndroid.KEYCODE_DPAD_UP -> performGlobalAction(GLOBAL_ACTION_DPAD_UP)
                KeyEventAndroid.KEYCODE_DPAD_DOWN -> performGlobalAction(GLOBAL_ACTION_DPAD_DOWN)
                KeyEventAndroid.KEYCODE_DPAD_LEFT -> performGlobalAction(GLOBAL_ACTION_DPAD_LEFT)
                KeyEventAndroid.KEYCODE_DPAD_RIGHT -> performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT)
                KeyEventAndroid.KEYCODE_DPAD_CENTER,
                KeyEventAndroid.KEYCODE_ENTER,
                KeyEventAndroid.KEYCODE_NUMPAD_ENTER,
                KeyEventAndroid.KEYCODE_SPACE -> performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
                KeyEventAndroid.KEYCODE_BACK,
                KeyEventAndroid.KEYCODE_ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> false
            }
        }

        return when (event.keyCode) {
            KeyEventAndroid.KEYCODE_DPAD_UP -> moveTvAccessibilityFocus(View.FOCUS_UP)
            KeyEventAndroid.KEYCODE_DPAD_DOWN -> moveTvAccessibilityFocus(View.FOCUS_DOWN)
            KeyEventAndroid.KEYCODE_DPAD_LEFT -> moveTvAccessibilityFocus(View.FOCUS_LEFT)
            KeyEventAndroid.KEYCODE_DPAD_RIGHT -> moveTvAccessibilityFocus(View.FOCUS_RIGHT)
            KeyEventAndroid.KEYCODE_TAB -> moveTvAccessibilityFocus(View.FOCUS_FORWARD)
            KeyEventAndroid.KEYCODE_DPAD_CENTER,
            KeyEventAndroid.KEYCODE_ENTER,
            KeyEventAndroid.KEYCODE_NUMPAD_ENTER,
            KeyEventAndroid.KEYCODE_SPACE -> clickTvAccessibilityFocus()
            KeyEventAndroid.KEYCODE_BACK,
            KeyEventAndroid.KEYCODE_ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> false
        }
    }

    private fun isRemotePasteShortcut(
        keyEvent: KeyEvent,
        event: KeyEventAndroid?,
        textToCommit: String?
    ): Boolean {
        val controlPressed = remoteControlDown || event?.isCtrlPressed == true ||
            keyEvent.getModifiersList().any {
                it == hbb.MessageOuterClass.ControlKey.Control ||
                    it == hbb.MessageOuterClass.ControlKey.RControl
            }
        if (!controlPressed) return false
        val chr = if (keyEvent.hasChr()) keyEvent.getChr() else -1
        return event?.keyCode == KeyEventAndroid.KEYCODE_V ||
            chr == 'v'.code || chr == 'V'.code || chr == KeyEventAndroid.KEYCODE_V ||
            textToCommit?.equals("v", ignoreCase = true) == true
    }

    private fun updateRemoteControlState(keyEvent: KeyEvent) {
        if (!keyEvent.hasControlKey()) return
        val controlKey = keyEvent.getControlKey()
        if (
            controlKey == hbb.MessageOuterClass.ControlKey.Control ||
            controlKey == hbb.MessageOuterClass.ControlKey.RControl
        ) {
            remoteControlDown = keyEvent.getDown() && !keyEvent.getPress()
            Log.d(logTag, "Remote control key held:$remoteControlDown")
        }
    }

    private fun pasteRemoteClipboardText(): Boolean {
        val text = MainApplication.rdClipboardManager?.remoteTextForPaste()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val event = KeyEventAndroid(KeyEventAndroid.ACTION_DOWN, KeyEventAndroid.KEYCODE_UNKNOWN)
        for (node in possibleAccessibiltyNodes()) {
            if (
                node.isEditable &&
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ) {
                Log.d(logTag, "Remote clipboard pasted through accessibility action")
                return true
            }
            if (trySendKeyEvent(event, node, text)) {
                Log.d(logTag, "Remote clipboard inserted into focused field")
                return true
            }
        }
        Log.w(logTag, "Remote clipboard received but no editable field is focused")
        return false
    }

    private fun nodeSupportsAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        return node.actionList.any { it.id == action }
    }

    private fun scrollNodeOrParent(node: AccessibilityNodeInfo?, action: Int): Boolean {
        var current = node
        repeat(32) {
            val candidate = current ?: return false
            if (
                (candidate.isScrollable || nodeSupportsAction(candidate, action)) &&
                candidate.performAction(action)
            ) {
                return true
            }
            val parent = candidate.parent
            if (parent == candidate) return false
            current = parent
        }
        return false
    }

    private fun scrollTvAccessibility(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val nodes = mutableListOf<NodeSnapshot>()
        collectVisibleNodes(root, nodes)

        // Mouse-wheel semantics: prefer the scrollable element under the
        // remote cursor, then the focused element, then any visible scroller.
        val underCursor = nodes
            .asSequence()
            .filter { it.bounds.contains(mouseX, mouseY) }
            .sortedBy { it.bounds.width().toLong() * it.bounds.height().toLong() }
        for (snapshot in underCursor) {
            if (scrollNodeOrParent(snapshot.node, action)) return true
        }

        val focused = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (scrollNodeOrParent(focused, action)) return true

        for (snapshot in nodes) {
            if (
                nodeSupportsAction(snapshot.node, action) &&
                snapshot.node.performAction(action)
            ) {
                return true
            }
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun enqueueWheelGesture(forward: Boolean) {
        val maxX = (resources.displayMetrics.widthPixels - 1).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - 1).coerceAtLeast(0)
        var startY = mouseY.coerceIn(0, maxY)
        var endY = (startY + if (forward) -WHEEL_STEP else WHEEL_STEP).coerceIn(0, maxY)
        if (startY == endY && maxY > 0) {
            startY = if (forward) WHEEL_STEP.coerceAtMost(maxY) else (maxY - WHEEL_STEP).coerceAtLeast(0)
            endY = (startY + if (forward) -WHEEL_STEP else WHEEL_STEP).coerceIn(0, maxY)
        }
        if (startY == endY) return

        val path = Path().apply {
            moveTo(mouseX.coerceIn(0, maxX).toFloat(), startY.toFloat())
            lineTo(mouseX.coerceIn(0, maxX).toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, WHEEL_DURATION)
        wheelActionsQueue.offer(GestureDescription.Builder().addStroke(stroke).build())
        consumeWheelActions()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performWheelScroll(forward: Boolean) {
        if (!isTvDevice()) {
            enqueueWheelGesture(forward)
            return
        }
        overlayHandler.post {
            if (!scrollTvAccessibility(forward)) {
                enqueueWheelGesture(forward)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            updateRemoteCursor(mouseX, mouseY)
            val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
            if (leftIsDown && delta > 2) {
                mouseDragged = true
            }
            if (isWaitingLongPress) {
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            mouseDownX = mouseX
            mouseDownY = mouseY
            mouseDragged = false
            startGesture(mouseX, mouseY)
            return
        }

        // Continue only actual drag packets. Sending a continuation and an end
        // back-to-back for a simple click is rejected by some Android TV ROMs.
        if (leftIsDown && mask == LEFT_MOVE) {
            continueGesture(mouseX, mouseY)
        }

        // left up, was down
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                val simpleClick = !mouseDragged && isWaitingLongPress
                leftIsDown = false
                isWaitingLongPress = false
                if (simpleClick) {
                    stroke = null
                    touchPath.reset()
                    performTvCompatibleClick(
                        mouseDownX,
                        mouseDownY,
                        ViewConfiguration.getTapTimeout().toLong()
                    )
                } else {
                    endGesture(mouseX, mouseY)
                }
                return
            }
        }

        if (mask == RIGHT_UP) {
            longPress(mouseX, mouseY)
            return
        }

        if (mask == BACK_UP) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // long WHEEL_BUTTON_DOWN -> GLOBAL_ACTION_RECENTS
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        // wheel button up
        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            performWheelScroll(forward = true)
            return
        }

        if (mask == WHEEL_UP) {
            performWheelScroll(forward = false)
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) {
            return
        } else {
            isWheelActionsPolling = true
        }
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: let {
            isWheelActionsPolling = false
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        try {
            val longPressStroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(longPressStroke)
            Log.d(logTag, "performClick x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) {
        performClick(x, y, longPressDuration)
    }

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            touchPath.reset()
        } else {
            touchPath = Path()
        }
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x
        lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) {
            duration = 1
        }
        try {
            if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration,
                        willContinue
                    )
                } else {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = stroke?.continueStroke(touchPath, 0, duration, willContinue)
                } else {
                    stroke = null
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            }
            stroke?.let {
                val builder = GestureDescription.Builder()
                builder.addStroke(it)
                Log.d(logTag, "doDispatchGesture x:$x y:$y time:$duration")
                dispatchGesture(builder.build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture, willContinue:$willContinue, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x
            lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGestureBelowO(x: Int, y: Int) {
        try {
            touchPath.lineTo(x.toFloat(), y.toFloat())
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            if (duration <= 0) {
                duration = 1
            }
            val stroke = GestureDescription.StrokeDescription(
                touchPath,
                0,
                duration
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            Log.d(logTag, "end gesture x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            endGestureBelowO(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        updateRemoteControlState(keyEvent)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

        // [down] indicates the key's state(down or up).
        // [press] indicates a click event(down and up).
        // https://github.com/rustdesk/rustdesk/blob/3a7594755341f023f56fa4b6a43b60d6b47df88d/flutter/lib/models/input_model.dart#L688
        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                val chr = keyEvent.getChr()
                if (chr != null) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
        } else if (keyboardMode == KeyboardMode.Translate) {
        } else {
        }

        Log.d(logTag, "onKeyEvent $keyEvent textToCommit:$textToCommit")

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        if (isRemotePasteShortcut(keyEvent, ke, textToCommit)) {
            if (keyEvent.getDown() || keyEvent.getPress()) {
                Handler(Looper.getMainLooper()).post { pasteRemoteClipboardText() }
            }
            return
        }
        ke?.let { event ->
            if (tryHandleVolumeKeyEvent(event)) {
                return
            } else if (tryHandlePowerKeyEvent(event)) {
                return
            } else if (tryHandleTvNavigationKey(event)) {
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.let { inputMethod ->
                inputMethod.getCurrentInputConnection()?.let { inputConnection ->
                    if (textToCommit != null) {
                        textToCommit?.let { text ->
                            inputConnection.commitText(text, 1, null)
                        }
                    } else {
                        ke?.let { event ->
                            inputConnection.sendKeyEvent(event)
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                inputConnection.sendKeyEvent(actionUpEvent)
                            }
                        }
                    }
                }
            }
        } else {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                ke?.let { event ->
                    val possibleNodes = possibleAccessibiltyNodes()
                    Log.d(logTag, "possibleNodes:$possibleNodes")
                    for (item in possibleNodes) {
                        val success = trySendKeyEvent(event, item, textToCommit)
                        if (success) {
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                trySendKeyEvent(actionUpEvent, item, textToCommit)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            else -> {
                return false
            }
        }
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            // Perform power dialog action when action is up
            if (event.action == KeyEventAndroid.ACTION_UP) {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            }
            return true
        }
        return false
    }

    private fun insertAccessibilityNode(list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo) {
        if (node == null) {
            return
        }
        if (list.contains(node)) {
            return
        }
        list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable() && node.isFocusable()) {
            return node
        }
        val childCount = node.getChildCount()
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable() && child.isFocusable()) {
                    return child
                }
                if (Build.VERSION.SDK_INT < 33) {
                    child.recycle()
                }
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) {
                    if (child != result) {
                        child.recycle()
                    }
                }
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()

        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        val rootInActiveWindow = getRootInActiveWindow()

        Log.d(logTag, "focusInput:$focusInput focusAccessibilityInput:$focusAccessibilityInput rootInActiveWindow:$rootInActiveWindow")

        if (focusInput != null) {
            if (focusInput.isFocusable() && focusInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusInput)
            } else {
                insertAccessibilityNode(latestList, focusInput)
            }
        }

        if (focusAccessibilityInput != null) {
            if (focusAccessibilityInput.isFocusable() && focusAccessibilityInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusAccessibilityInput)
            } else {
                insertAccessibilityNode(latestList, focusAccessibilityInput)
            }
        }

        val childFromFocusInput = findChildNode(focusInput)
        Log.d(logTag, "childFromFocusInput:$childFromFocusInput")

        if (childFromFocusInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusInput)
        }

        val childFromFocusAccessibilityInput = findChildNode(focusAccessibilityInput)
        if (childFromFocusAccessibilityInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusAccessibilityInput)
        }
        Log.d(logTag, "childFromFocusAccessibilityInput:$childFromFocusAccessibilityInput")

        if (rootInActiveWindow != null) {
            insertAccessibilityNode(linkedList, rootInActiveWindow)
        }

        for (item in latestList) {
            insertAccessibilityNode(linkedList, item)
        }

        return linkedList
    }

    private fun trySendKeyEvent(event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?): Boolean {
        node.refresh()
        this.fakeEditTextForTextStateCalculation?.setSelection(0,0)
        this.fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.getText()
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) {
            isShowingHint = node.isShowingHintText()
        }

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd

        if (text != null) {
            if (textSelectionStart > text.length) {
                textSelectionStart = text.length
            }
            if (textSelectionEnd > text.length) {
                textSelectionEnd = text.length
            }
            if (textSelectionStart > textSelectionEnd) {
                textSelectionStart = textSelectionEnd
            }
        }

        var success = false

        Log.d(logTag, "existing text:$text textToCommit:$textToCommit textSelectionStart:$textSelectionStart textSelectionEnd:$textSelectionEnd")

        if (textToCommit != null) {
            if ((textSelectionStart == -1) || (textSelectionEnd == -1)) {
                val newText = textToCommit
                this.fakeEditTextForTextStateCalculation?.setText(newText)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                this.fakeEditTextForTextStateCalculation?.setText(text)
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
                this.fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) {
                this.fakeEditTextForTextStateCalculation?.setText(null)
            } else {
                this.fakeEditTextForTextStateCalculation?.setText(text)
            }
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                Log.d(logTag, "setting selection $textSelectionStart $textSelectionEnd")
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
            }

            this.fakeEditTextForTextStateCalculation?.let {
                // This is essiential to make sure layout object is created. OnKeyDown may not work if layout is not created.
                val rect = Rect()
                node.getBoundsInScreen(rect)

                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    val succ = it.onKeyDown(event.getKeyCode(), event)
                    Log.d(logTag, "onKeyDown $succ")
                } else if (event.action == KeyEventAndroid.ACTION_UP) {
                    val success = it.onKeyUp(event.getKeyCode(), event)
                    Log.d(logTag, "keyup $success")
                } else {}
            }

            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        var success = false
        this.fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                it.toString()
            )
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        var success = updateTextForAccessibilityNode(node)

        if (success) {
            val selectionStart = this.fakeEditTextForTextStateCalculation?.selectionStart
            val selectionEnd = this.fakeEditTextForTextStateCalculation?.selectionEnd

            if (selectionStart != null && selectionEnd != null) {
                val arguments = Bundle()
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd
                )
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                Log.d(logTag, "Update selection to $selectionStart $selectionEnd success:$success")
            }
        }

        return success
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        notifyInputState()
        // Preserve the manifest-declared gesture capability and configure the
        // event/feedback fields explicitly. Replacing this with a blank
        // AccessibilityServiceInfo can make some TV firmware unbind the service.
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50
        info.flags = info.flags or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = info.flags or FLAG_INPUT_METHOD_EDITOR
        }
        setServiceInfo(info)
        fakeEditTextForTextStateCalculation = EditText(this)
        // Size here doesn't matter, we won't show this view.
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        hideProjectionRequestOverlay()
        hideRemoteCursor()
        remoteControlDown = false
        ctx = null
        // Keep this fallback even though onUnbind usually notifies first.
        notifyInputState()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideProjectionRequestOverlay()
        hideRemoteCursor()
        remoteControlDown = false
        ctx = null
        notifyInputState()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
