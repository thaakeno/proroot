package dev.thaakeno.proroot.display

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Laptop-style gesture interpreter for the phone screen.
 *
 * Behavior mirrors Anland's virtual touchpad path while keeping the Linux
 * pointer position owned by LinuxSurfaceView:
 * - one finger: relative pointer motion
 * - tap: left click
 * - long press + move: left-button drag
 * - two-finger move: vertical/horizontal scroll
 * - two-finger tap: right click
 * - three-finger tap: middle click
 */
class AnlandTouchpadController(
    context: Context,
    private val output: Output,
) {
    interface Output {
        fun movePointer(dx: Float, dy: Float)
        fun sendButton(button: Int, pressed: Boolean)
        fun sendScroll(axis: Int, value: Float)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var mode = 0
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX1 = 0f
    private var lastY1 = 0f
    private var lastX2 = 0f
    private var lastY2 = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    private var tapCandidate = false
    private var twoFingerTapCandidate = false
    private var threeFingerTapCandidate = false
    private var longPressPossible = false
    private var dragging = false
    private var multiFinger = false

    private var smoothX = 0f
    private var smoothY = 0f
    private var smoothReady = false
    private var accumulatedX = 0f
    private var accumulatedY = 0f

    var accelerationStrength: Float = 1.35f
        set(value) {
            field = value.coerceIn(0.5f, 6f)
        }

    var scrollSpeed: Float = 0.55f
        set(value) {
            field = value.coerceIn(0.15f, 2.5f)
        }

    fun cancel() {
        if (dragging) {
            output.sendButton(BUTTON_LEFT, false)
        }
        reset()
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginOneFinger(event)
            MotionEvent.ACTION_POINTER_DOWN -> pointerAdded(event)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> pointerRemoved(event)
            MotionEvent.ACTION_UP -> finish(event)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun beginOneFinger(event: MotionEvent) {
        mode = ONE_FINGER
        downTime = event.eventTime
        startX = event.x
        startY = event.y
        lastX1 = event.x
        lastY1 = event.y
        tapCandidate = true
        twoFingerTapCandidate = false
        threeFingerTapCandidate = false
        longPressPossible = true
        multiFinger = false
        resetSmoothing()
    }

    private fun pointerAdded(event: MotionEvent) {
        multiFinger = true
        tapCandidate = false
        longPressPossible = false

        if (dragging) {
            output.sendButton(BUTTON_LEFT, false)
            dragging = false
        }

        when (event.pointerCount) {
            2 -> {
                mode = TWO_FINGER
                twoFingerTapCandidate = true
                threeFingerTapCandidate = false
                lastX1 = event.getX(0)
                lastY1 = event.getY(0)
                lastX2 = event.getX(1)
                lastY2 = event.getY(1)
                lastCentroidX = (lastX1 + lastX2) / 2f
                lastCentroidY = (lastY1 + lastY2) / 2f
            }
            3 -> {
                mode = THREE_FINGER
                twoFingerTapCandidate = false
                threeFingerTapCandidate = true
                lastCentroidX = centroidX(event)
                lastCentroidY = centroidY(event)
            }
            else -> {
                mode = MULTI_FINGER
                twoFingerTapCandidate = false
                threeFingerTapCandidate = false
            }
        }
        resetSmoothing()
    }

    private fun move(event: MotionEvent) {
        when {
            event.pointerCount == 1 && mode == ONE_FINGER && !multiFinger ->
                moveOneFinger(event)
            event.pointerCount == 2 && mode == TWO_FINGER ->
                moveTwoFingers(event)
            event.pointerCount == 3 && mode == THREE_FINGER ->
                moveThreeFingers(event)
        }
    }

    private fun moveOneFinger(event: MotionEvent) {
        val x = event.x
        val y = event.y
        val totalDistance = hypot(x - startX, y - startY)

        if (!dragging &&
            longPressPossible &&
            totalDistance <= touchSlop &&
            event.eventTime - downTime >= longPressTimeoutMs
        ) {
            dragging = true
            tapCandidate = false
            output.sendButton(BUTTON_LEFT, true)
            resetSmoothing()
        }

        if (totalDistance > touchSlop) {
            tapCandidate = false
            longPressPossible = false
        }

        val rawDx = x - lastX1
        val rawDy = y - lastY1
        lastX1 = x
        lastY1 = y

        val filtered = smooth(rawDx, rawDy)
        if (filtered.first == 0f && filtered.second == 0f) return

        val distance = hypot(filtered.first, filtered.second)
        val normalizedSpeed = distance / 10f
        val dynamicScale = (
            1f +
                (accelerationStrength - 1f) *
                (normalizedSpeed / (1f + normalizedSpeed))
            ).coerceIn(0.35f, 6f)

        output.movePointer(
            filtered.first * dynamicScale,
            filtered.second * dynamicScale,
        )
    }

    private fun moveTwoFingers(event: MotionEvent) {
        val x1 = event.getX(0)
        val y1 = event.getY(0)
        val x2 = event.getX(1)
        val y2 = event.getY(1)
        val centerX = (x1 + x2) / 2f
        val centerY = (y1 + y2) / 2f
        val dx = centerX - lastCentroidX
        val dy = centerY - lastCentroidY

        lastX1 = x1
        lastY1 = y1
        lastX2 = x2
        lastY2 = y2
        lastCentroidX = centerX
        lastCentroidY = centerY

        if (abs(dx) <= 1f && abs(dy) <= 1f) return
        twoFingerTapCandidate = false

        if (abs(dy) > abs(dx) * AXIS_DOMINANCE) {
            output.sendScroll(0, -dy * scrollSpeed)
        }
        if (abs(dx) > abs(dy) * AXIS_DOMINANCE) {
            output.sendScroll(1, dx * scrollSpeed)
        }
    }

    private fun moveThreeFingers(event: MotionEvent) {
        val centerX = centroidX(event)
        val centerY = centroidY(event)
        if (hypot(centerX - lastCentroidX, centerY - lastCentroidY) > touchSlop) {
            threeFingerTapCandidate = false
        }
        lastCentroidX = centerX
        lastCentroidY = centerY
    }

    private fun pointerRemoved(event: MotionEvent) {
        val remaining = event.pointerCount - 1
        if (remaining == 1) {
            val survivingIndex = if (event.actionIndex == 0) 1 else 0
            lastX1 = event.getX(survivingIndex)
            lastY1 = event.getY(survivingIndex)
            startX = lastX1
            startY = lastY1
            downTime = event.eventTime
            mode = ONE_FINGER
            multiFinger = false
            tapCandidate = false
            longPressPossible = false
            resetSmoothing()
        }
    }

    private fun finish(event: MotionEvent) {
        val quick = event.eventTime - downTime < TAP_TIMEOUT_MS

        when {
            dragging -> output.sendButton(BUTTON_LEFT, false)
            mode == TWO_FINGER && twoFingerTapCandidate && quick ->
                click(BUTTON_RIGHT)
            mode == THREE_FINGER && threeFingerTapCandidate && quick ->
                click(BUTTON_MIDDLE)
            mode == ONE_FINGER && tapCandidate && quick ->
                click(BUTTON_LEFT)
        }
        reset()
    }

    private fun click(button: Int) {
        output.sendButton(button, true)
        output.sendButton(button, false)
    }

    private fun smooth(dx: Float, dy: Float): Pair<Float, Float> {
        val deadX = if (abs(dx) < DEAD_ZONE) 0f else dx
        val deadY = if (abs(dy) < DEAD_ZONE) 0f else dy
        if (deadX == 0f && deadY == 0f) return 0f to 0f

        if (!smoothReady) {
            smoothX = deadX
            smoothY = deadY
            smoothReady = true
        } else {
            smoothX = SMOOTHING * deadX + (1f - SMOOTHING) * smoothX
            smoothY = SMOOTHING * deadY + (1f - SMOOTHING) * smoothY
        }

        accumulatedX += smoothX
        accumulatedY += smoothY

        val outX = if (abs(accumulatedX) >= ACCUMULATION_THRESHOLD) {
            accumulatedX.also { accumulatedX = 0f }
        } else {
            0f
        }
        val outY = if (abs(accumulatedY) >= ACCUMULATION_THRESHOLD) {
            accumulatedY.also { accumulatedY = 0f }
        } else {
            0f
        }
        return outX to outY
    }

    private fun centroidX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun centroidY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun reset() {
        mode = IDLE
        tapCandidate = false
        twoFingerTapCandidate = false
        threeFingerTapCandidate = false
        longPressPossible = false
        dragging = false
        multiFinger = false
        resetSmoothing()
    }

    private fun resetSmoothing() {
        smoothX = 0f
        smoothY = 0f
        smoothReady = false
        accumulatedX = 0f
        accumulatedY = 0f
    }

    companion object {
        private const val IDLE = 0
        private const val ONE_FINGER = 1
        private const val TWO_FINGER = 2
        private const val THREE_FINGER = 3
        private const val MULTI_FINGER = 4

        private const val BUTTON_LEFT = 0x110
        private const val BUTTON_RIGHT = 0x111
        private const val BUTTON_MIDDLE = 0x112

        private const val TAP_TIMEOUT_MS = 300L
        private const val DEAD_ZONE = 0.3f
        private const val SMOOTHING = 0.45f
        private const val ACCUMULATION_THRESHOLD = 0.1f
        private const val AXIS_DOMINANCE = 0.5f
    }
}
