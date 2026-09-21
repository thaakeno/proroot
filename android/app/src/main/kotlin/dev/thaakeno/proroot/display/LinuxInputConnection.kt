package dev.thaakeno.proroot.display

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.anland.termux.KeyCodeMapper
import com.anland.termux.Native
import java.nio.charset.StandardCharsets

class LinuxInputConnection(
    targetView: View,
    fullEditor: Boolean,
) : BaseInputConnection(targetView, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!text.isNullOrEmpty()) {
            Native.nativeSendTextInput(text.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

    override fun finishComposingText(): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtMost(64)) {
            Native.nativeSendKey(0, 14)
            Native.nativeSendKey(1, 14)
        }
        repeat(afterLength.coerceAtMost(64)) {
            Native.nativeSendKey(0, 111)
            Native.nativeSendKey(1, 111)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val evdev = if (event.scanCode > 0) event.scanCode else KeyCodeMapper.getScanCode(event.keyCode)
        if (evdev < 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> Native.nativeSendKey(0, evdev)
            KeyEvent.ACTION_UP -> Native.nativeSendKey(1, evdev)
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (actionCode == EditorInfo.IME_ACTION_DONE || actionCode == EditorInfo.IME_ACTION_GO) {
            Native.nativeSendKey(0, 28)
            Native.nativeSendKey(1, 28)
            return true
        }
        return super.performEditorAction(actionCode)
    }
}
