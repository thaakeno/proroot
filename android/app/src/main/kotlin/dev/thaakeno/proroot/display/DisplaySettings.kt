package dev.thaakeno.proroot.display

import java.util.concurrent.CopyOnWriteArraySet

enum class InputMode {
    TRACKPAD,
    DIRECT,
}

data class DisplayOptions(
    val refreshRate: Int = 120,
    val scale: Double = 1.0,
    val inputMode: InputMode = InputMode.TRACKPAD,
)

object DisplaySettings {
    private val listeners = CopyOnWriteArraySet<(DisplayOptions) -> Unit>()

    @Volatile
    var current = DisplayOptions()
        private set

    fun update(refreshRate: Int, scale: Double, inputMode: String) {
        val normalizedMode = inputMode.trim().lowercase()
        current = DisplayOptions(
            refreshRate = refreshRate.coerceIn(60, 165),
            scale = scale.coerceIn(0.75, 2.0),
            // "touch" was used by older builds. Keep accepting it so existing
            // preferences migrate cleanly instead of silently becoming trackpad.
            inputMode = if (normalizedMode == "direct" || normalizedMode == "touch") {
                InputMode.DIRECT
            } else {
                InputMode.TRACKPAD
            },
        )
        listeners.forEach { listener -> listener(current) }
    }

    fun addListener(listener: (DisplayOptions) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (DisplayOptions) -> Unit) {
        listeners -= listener
    }
}
