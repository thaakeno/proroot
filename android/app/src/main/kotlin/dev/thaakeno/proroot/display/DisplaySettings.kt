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
        current = DisplayOptions(
            refreshRate = refreshRate.coerceIn(60, 165),
            scale = scale.coerceIn(0.75, 2.0),
            inputMode = if (inputMode == "direct") InputMode.DIRECT else InputMode.TRACKPAD,
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
