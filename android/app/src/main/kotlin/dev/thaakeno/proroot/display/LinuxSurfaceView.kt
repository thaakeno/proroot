package dev.thaakeno.proroot.display

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.anland.termux.KeyCodeMapper
import com.anland.termux.Native
import dev.thaakeno.proroot.runtime.RuntimePaths
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class LinuxSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val paths = RuntimePaths(context)
    private val callbackBridge = AnlandCallbackBridge(context)
    private val inputMethod = context.getSystemService(InputMethodManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingSurfaceRestart: Runnable? = null
    private var pendingPointerWake: Runnable? = null
    private var consumerStarted = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceFormat = 0
    private var options = DisplaySettings.current
    private var pointerX = 0f
    private var pointerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var secondLastY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastButtons = 0

    private val optionsListener: (DisplayOptions) -> Unit = { next ->
        options = next
        if (consumerStarted) {
            Native.nativeSetRefreshRate(next.refreshRate.toFloat())
            applyFrameRate(holder.surface, next.refreshRate)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
        DisplaySettings.addListener(optionsListener)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        if (consumerStarted &&
            width == surfaceWidth &&
            height == surfaceHeight &&
            format == surfaceFormat
        ) {
            cancelPendingSurfaceRestart()
            applyFrameRate(holder.surface, options.refreshRate)
            return
        }

        if (!consumerStarted) {
            surfaceWidth = width
            surfaceHeight = height
            surfaceFormat = format
            startConsumerSafely(holder.surface, width, height)
            return
        }

        // Flutter/IME animations can emit dozens of intermediate Surface sizes in
        // a few seconds. Restarting Anland for every frame repeatedly tears down
        // native render threads and was the last Android-process crash path seen
        // in the device log. Keep rendering at the previous size and reconnect
        // once the layout has been stable briefly.
        scheduleSurfaceRestart(format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cancelPendingSurfaceRestart()
        cancelPointerWake()
        stopConsumerForRuntime()
        surfaceWidth = 0
        surfaceHeight = 0
        surfaceFormat = 0
    }

    fun stopConsumerForRuntime() {
        if (!consumerStarted) return
        val error = runCatching {
            Native.nativeStop()
        }.exceptionOrNull()
        if (error == null) {
            consumerStarted = false
            return
        }

        logConsumerError("nativeStop failed", error)
    }

    fun dispose() {
        cancelPendingSurfaceRestart()
        cancelPointerWake()
        stopConsumerForRuntime()
        callbackBridge.dispose()
        DisplaySettings.removeListener(optionsListener)
        holder.removeCallback(this)
    }

    private fun scheduleSurfaceRestart(format: Int, width: Int, height: Int) {
        cancelPendingSurfaceRestart()
        val restart = Runnable {
            pendingSurfaceRestart = null
            val surface = holder.surface
            if (!surface.isValid) return@Runnable

            stopConsumerForRuntime()
            if (consumerStarted) return@Runnable

            surfaceWidth = width
            surfaceHeight = height
            surfaceFormat = format
            startConsumerSafely(surface, width, height)
        }
        pendingSurfaceRestart = restart
        mainHandler.postDelayed(restart, 220L)
    }

    private fun cancelPendingSurfaceRestart() {
        pendingSurfaceRestart?.let(mainHandler::removeCallbacks)
        pendingSurfaceRestart = null
    }

    private fun startConsumerSafely(surface: Surface, width: Int, height: Int) {
        val error = runCatching {
            startConsumer(surface, width, height)
        }.exceptionOrNull()
        if (error != null) {
            consumerStarted = false
            logConsumerError("nativeStart failed", error)
        }
    }

    private fun startConsumer(surface: Surface, width: Int, height: Int) {
        paths.ensureHostDirectories()
        Native.nativeConfigure(paths.anlandSocket.absolutePath, false, "", "")
        Native.nativeSetCompatibleMode(false)
        Native.nativeSetCustomResolution(width, height)
        Native.nativeSetRefreshRate(options.refreshRate.coerceAtLeast(60).toFloat())
        Native.nativeSetAudioLatency(20, 30)
        Native.nativeSetAudioKeepalive(true)
        Native.nativeSetMicEnabled(false)
        applyFrameRate(surface, options.refreshRate)
        Native.nativeStart(surface, callbackBridge)
        consumerStarted = true

        pointerX = width / 2f
        pointerY = height / 2f
        Native.nativeSendMouseMotion(pointerX, pointerY, 0f, 0f)
        schedulePointerWake()
    }

    private fun schedulePointerWake() {
        cancelPointerWake()
        val wake = Runnable {
            pendingPointerWake = null
            if (!consumerStarted) return@Runnable

            // The first pointer packet can be sent before the Linux producer has
            // connected. Send one harmless motion after the normal KWin connect
            // window so the compositor switches from touch-only to cursor input.
            runCatching {
                Native.nativeSendMouseMotion(pointerX, pointerY, 0.5f, 0f)
            }.onFailure { error ->
                logConsumerError("pointer wake failed", error)
            }
        }
        pendingPointerWake = wake
        mainHandler.postDelayed(wake, 3_500L)
    }

    private fun cancelPointerWake() {
        pendingPointerWake?.let(mainHandler::removeCallbacks)
        pendingPointerWake = null
    }

    private fun logConsumerError(prefix: String, error: Throwable) {
        runCatching {
            paths.logsDir.mkdirs()
            File(paths.logsDir, "anland-consumer.log").appendText(
                "$prefix: ${error.stackTraceToString()}\n",
            )
        }
    }

    private fun applyFrameRate(surface: Surface, refreshRate: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface.isValid) {
            runCatching {
                surface.setFrameRate(
                    refreshRate.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                )
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return LinuxInputConnection(this, true)
    }

    fun showKeyboard() {
        requestFocus()
        inputMethod.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun setPointerCaptureEnabled(enabled: Boolean) {
        requestFocus()
        if (enabled) requestPointerCapture() else releasePointerCapture()
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean = handleMouse(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        sendHardwareKey(event, 0) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        sendHardwareKey(event, 1) || super.onKeyUp(keyCode, event)

    private fun sendHardwareKey(event: KeyEvent, action: Int): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            !event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        ) {
            return false
        }
        val evdev = if (event.scanCode > 0) {
            event.scanCode
        } else {
            KeyCodeMapper.getScanCode(event.keyCode)
        }
        if (evdev < 0) return false
        Native.nativeSendKey(action, evdev)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return handleMouse(event)
        return when (options.inputMode) {
            InputMode.DIRECT -> handleDirectTouch(event)
            InputMode.TRACKPAD -> handleTrackpad(event)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            !event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        ) {
            return super.onGenericMotionEvent(event)
        }
        return handleMouse(event)
    }

    private fun handleDirectTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                Native.nativeSendTouch(
                    0,
                    event.getX(index),
                    event.getY(index),
                    event.getPointerId(index),
                )
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    Native.nativeSendTouch(
                        2,
                        event.getX(i),
                        event.getY(i),
                        event.getPointerId(i),
                    )
                }
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                Native.nativeSendTouch(
                    1,
                    event.getX(index),
                    event.getY(index),
                    event.getPointerId(index),
                )
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    Native.nativeSendTouch(
                        1,
                        event.getX(i),
                        event.getY(i),
                        event.getPointerId(i),
                    )
                }
                Native.nativeSendTouchFrame()
            }
        }
        return true
    }

    private fun handleTrackpad(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                secondLastY = event.y
                moved = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    secondLastY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    val dy = y - secondLastY
                    if (abs(dy) > .25f) {
                        Native.nativeSendMouseScroll(0, dy * .7f)
                        moved = true
                    }
                    secondLastY = y
                    return true
                }

                val dx = event.x - lastX
                val dy = event.y - lastY
                if (hypot(
                        (event.x - downX).toDouble(),
                        (event.y - downY).toDouble(),
                    ) > 6.0
                ) {
                    moved = true
                }

                val acceleration = 1.35f
                pointerX = (pointerX + dx * acceleration).coerceIn(0f, width.toFloat())
                pointerY = (pointerY + dy * acceleration).coerceIn(0f, height.toFloat())
                Native.nativeSendMouseMotion(
                    pointerX,
                    pointerY,
                    dx * acceleration,
                    dy * acceleration,
                )
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTime
                if (!moved && duration < 280) {
                    Native.nativeSendMouseButton(0x110, true)
                    Native.nativeSendMouseButton(0x110, false)
                }
                return true
            }
        }
        return true
    }

    private fun handleMouse(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vertical != 0f) Native.nativeSendMouseScroll(0, -vertical * 10f)
            if (horizontal != 0f) Native.nativeSendMouseScroll(1, horizontal * 10f)
            return true
        }

        val relativeX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val relativeY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        pointerX = if (relativeX != 0f) {
            (pointerX + relativeX).coerceIn(0f, width.toFloat())
        } else {
            event.x.coerceIn(0f, width.toFloat())
        }
        pointerY = if (relativeY != 0f) {
            (pointerY + relativeY).coerceIn(0f, height.toFloat())
        } else {
            event.y.coerceIn(0f, height.toFloat())
        }

        Native.nativeSendMouseMotion(pointerX, pointerY, relativeX, relativeY)
        syncButtons(event.buttonState)
        return true
    }

    private fun syncButtons(buttonState: Int) {
        val mappings = arrayOf(
            MotionEvent.BUTTON_PRIMARY to 0x110,
            MotionEvent.BUTTON_SECONDARY to 0x111,
            MotionEvent.BUTTON_TERTIARY to 0x112,
            MotionEvent.BUTTON_BACK to 0x113,
            MotionEvent.BUTTON_FORWARD to 0x114,
        )
        for ((androidButton, evdev) in mappings) {
            val wasDown = lastButtons and androidButton != 0
            val isDown = buttonState and androidButton != 0
            if (wasDown != isDown) Native.nativeSendMouseButton(evdev, isDown)
        }
        lastButtons = buttonState
    }
}
