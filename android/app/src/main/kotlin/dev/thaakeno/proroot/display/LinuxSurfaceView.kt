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

class LinuxSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val paths = RuntimePaths(context)
    private val callbackBridge = AnlandCallbackBridge(context)
    private val inputMethod = context.getSystemService(InputMethodManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingSurfaceRestart: Runnable? = null
    private var consumerStarted = false
    private var runtimeStopping = false
    private var consumerWidth = 0
    private var consumerHeight = 0
    private var surfaceFormat = 0
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var options = DisplaySettings.current

    private var pointerX = 0f
    private var pointerY = 0f
    private var pointerPrimed = false
    private var lastButtons = 0

    private val touchpad = AnlandTouchpadController(
        context,
        object : AnlandTouchpadController.Output {
            override fun movePointer(dx: Float, dy: Float) {
                moveRelativePointer(dx, dy)
            }

            override fun sendButton(button: Int, pressed: Boolean) {
                if (!consumerStarted) return
                primePointerForTrackpad()
                Native.nativeSendMouseButton(button, pressed)
            }

            override fun sendScroll(axis: Int, value: Float) {
                if (!consumerStarted) return
                primePointerForTrackpad()
                Native.nativeSendMouseScroll(axis, value)
            }
        },
    )

    private val optionsListener: (DisplayOptions) -> Unit = { next ->
        val previousMode = options.inputMode
        options = next

        if (consumerStarted) {
            Native.nativeSetRefreshRate(next.refreshRate.toFloat())
            applyFrameRate(holder.surface, next.refreshRate)
        }

        if (previousMode != next.inputMode) {
            touchpad.cancel()
            syncButtons(0)
            pointerPrimed = false
            if (next.inputMode == InputMode.DIRECT) {
                runCatching { releasePointerCapture() }
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        holder.addCallback(this)
        DisplaySettings.addListener(optionsListener)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        if (runtimeStopping || width <= 0 || height <= 0) return
        surfaceFormat = format

        if (!consumerStarted) {
            consumerWidth = width
            consumerHeight = height
            startConsumerSafely(holder.surface, width, height)
            return
        }

        // SurfaceView/Flutter can report transient height changes while the IME or
        // system bars animate. The native consumer does not need to be torn down
        // for those. Repeated nativeStop/nativeStart cycles were the dominant
        // black-screen and Android-process crash path in device logs.
        applyFrameRate(holder.surface, options.refreshRate)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutWidth = w
        layoutHeight = h

        if (runtimeStopping || !consumerStarted || w <= 0 || h <= 0) return

        // Keyboard/nav-bar changes are height-only in portrait. Keep Anland's
        // transport alive. A width change is an actual orientation/window-size
        // change, so reconnect once after the layout has settled.
        if (oldw > 0 && abs(w - consumerWidth) >= 8) {
            scheduleSurfaceRestart(surfaceFormat, w, h)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cancelPendingSurfaceRestart()
        releaseInputState()
        stopConsumerForRuntime()
        consumerWidth = 0
        consumerHeight = 0
        surfaceFormat = 0
    }

    fun prepareForRuntimeStop() {
        runtimeStopping = true
        cancelPendingSurfaceRestart()
        releaseInputState()
        stopConsumerForRuntime()
    }

    fun stopConsumerForRuntime() {
        if (!consumerStarted) return
        val error = runCatching {
            Native.nativeStop()
        }.exceptionOrNull()

        if (error == null) {
            consumerStarted = false
            pointerPrimed = false
        } else {
            logConsumerError("nativeStop failed", error)
        }
    }

    fun dispose() {
        runtimeStopping = true
        cancelPendingSurfaceRestart()
        releaseInputState()
        stopConsumerForRuntime()
        callbackBridge.dispose()
        DisplaySettings.removeListener(optionsListener)
        holder.removeCallback(this)
    }

    private fun releaseInputState() {
        touchpad.cancel()
        if (consumerStarted && lastButtons != 0) syncButtons(0)
        runCatching { releasePointerCapture() }
    }

    private fun scheduleSurfaceRestart(format: Int, width: Int, height: Int) {
        cancelPendingSurfaceRestart()
        val restart = Runnable {
            pendingSurfaceRestart = null
            if (runtimeStopping || width <= 0 || height <= 0) return@Runnable

            val surface = holder.surface
            if (!surface.isValid) return@Runnable

            releaseInputState()
            stopConsumerForRuntime()
            if (consumerStarted) return@Runnable

            surfaceFormat = format
            consumerWidth = width
            consumerHeight = height
            startConsumerSafely(surface, width, height)
        }
        pendingSurfaceRestart = restart

        // Long enough to ride out Android rotation/inset animations, short enough
        // that a real orientation change still feels immediate.
        mainHandler.postDelayed(restart, 650L)
    }

    private fun cancelPendingSurfaceRestart() {
        pendingSurfaceRestart?.let(mainHandler::removeCallbacks)
        pendingSurfaceRestart = null
    }

    private fun startConsumerSafely(surface: Surface, width: Int, height: Int) {
        if (runtimeStopping || !surface.isValid) return
        val error = runCatching {
            startConsumer(surface, width, height)
        }.exceptionOrNull()
        if (error != null) {
            consumerStarted = false
            logConsumerError("nativeStart failed", error)
        }
    }

    private fun startConsumer(surface: Surface, width: Int, height: Int) {
        check(!runtimeStopping) { "Display consumer is stopping" }
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
        consumerWidth = width
        consumerHeight = height
        pointerX = width / 2f
        pointerY = height / 2f
        pointerPrimed = false
        lastButtons = 0
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
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return LinuxInputConnection(this, true)
    }

    fun showKeyboard() {
        requestFocus()
        inputMethod.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun setPointerCaptureEnabled(enabled: Boolean) {
        requestFocus()
        if (enabled && options.inputMode == InputMode.TRACKPAD) {
            requestPointerCapture()
        } else {
            releasePointerCapture()
        }
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean =
        handleMouse(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasPointerCapture()) {
            releasePointerCapture()
            return true
        }
        return sendHardwareKey(event, 0) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        sendHardwareKey(event, 1) || super.onKeyUp(keyCode, event)

    private fun sendHardwareKey(event: KeyEvent, action: Int): Boolean {
        if (runtimeStopping || !consumerStarted) return false
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
        if (runtimeStopping || !consumerStarted) return true
        requestFocus()

        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return handleMouse(event)
        }

        return when (options.inputMode) {
            InputMode.DIRECT -> handleDirectTouch(event)
            InputMode.TRACKPAD -> {
                primePointerForTrackpad()
                touchpad.onTouch(event)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (runtimeStopping || !consumerStarted) return true
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
        val targetWidth = consumerWidth.takeIf { it > 0 } ?: width
        val targetHeight = consumerHeight.takeIf { it > 0 } ?: height
        val scaleX = if (width > 0) targetWidth.toFloat() / width else 1f
        val scaleY = if (height > 0) targetHeight.toFloat() / height else 1f

        fun x(i: Int) = (event.getX(i) * scaleX).coerceIn(0f, targetWidth.toFloat())
        fun y(i: Int) = (event.getY(i) * scaleY).coerceIn(0f, targetHeight.toFloat())

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                Native.nativeSendTouch(
                    0,
                    x(index),
                    y(index),
                    event.getPointerId(index),
                )
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    Native.nativeSendTouch(
                        2,
                        x(i),
                        y(i),
                        event.getPointerId(i),
                    )
                }
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                Native.nativeSendTouch(
                    1,
                    x(index),
                    y(index),
                    event.getPointerId(index),
                )
                Native.nativeSendTouchFrame()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    Native.nativeSendTouch(
                        1,
                        x(i),
                        y(i),
                        event.getPointerId(i),
                    )
                }
                Native.nativeSendTouchFrame()
            }
        }
        return true
    }

    private fun primePointerForTrackpad() {
        if (pointerPrimed || !consumerStarted || options.inputMode != InputMode.TRACKPAD) {
            return
        }

        val targetWidth = consumerWidth.takeIf { it > 0 } ?: width
        val targetHeight = consumerHeight.takeIf { it > 0 } ?: height
        pointerX = targetWidth / 2f
        pointerY = targetHeight / 2f

        // Do this only after the user actually interacts in trackpad mode. The
        // old startup "pointer wake" made a cursor appear even in Direct mode.
        Native.nativeSendMouseMotion(pointerX, pointerY, 0.01f, 0f)
        pointerPrimed = true
    }

    private fun moveRelativePointer(dx: Float, dy: Float) {
        if (!consumerStarted) return
        primePointerForTrackpad()

        val maxX = (consumerWidth.takeIf { it > 0 } ?: width).toFloat()
        val maxY = (consumerHeight.takeIf { it > 0 } ?: height).toFloat()
        pointerX = (pointerX + dx).coerceIn(0f, maxX)
        pointerY = (pointerY + dy).coerceIn(0f, maxY)
        Native.nativeSendMouseMotion(pointerX, pointerY, dx, dy)
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
        val maxX = (consumerWidth.takeIf { it > 0 } ?: width).toFloat()
        val maxY = (consumerHeight.takeIf { it > 0 } ?: height).toFloat()

        if (relativeX != 0f || relativeY != 0f) {
            pointerX = (pointerX + relativeX).coerceIn(0f, maxX)
            pointerY = (pointerY + relativeY).coerceIn(0f, maxY)
            Native.nativeSendMouseMotion(
                pointerX,
                pointerY,
                relativeX,
                relativeY,
            )
        } else if (
            event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        ) {
            val sx = if (width > 0) maxX / width else 1f
            val sy = if (height > 0) maxY / height else 1f
            val nextX = (event.x * sx).coerceIn(0f, maxX)
            val nextY = (event.y * sy).coerceIn(0f, maxY)
            val dx = nextX - pointerX
            val dy = nextY - pointerY
            pointerX = nextX
            pointerY = nextY
            Native.nativeSendMouseMotion(pointerX, pointerY, dx, dy)
        }

        pointerPrimed = true
        syncButtons(effectiveButtonState(event))
        return true
    }

    private fun effectiveButtonState(event: MotionEvent): Int {
        var state = event.buttonState
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> state = state or event.actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> state = state and event.actionButton.inv()
            MotionEvent.ACTION_CANCEL -> state = 0
        }
        return state
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
            if (wasDown != isDown && consumerStarted) {
                Native.nativeSendMouseButton(evdev, isDown)
            }
        }
        lastButtons = buttonState
    }
}
