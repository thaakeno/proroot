package dev.thaakeno.proroot.display

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.Keep
import com.anland.termux.Native
import java.nio.charset.StandardCharsets

@Keep
class AnlandCallbackBridge(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var listening = false
    private var nativeUpdate: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!listening) return@OnPrimaryClipChangedListener
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return@OnPrimaryClipChangedListener

        if (text == nativeUpdate) {
            nativeUpdate = null
            return@OnPrimaryClipChangedListener
        }
        Native.nativeSendClipboard(text.toByteArray(StandardCharsets.UTF_8))
    }

    @Keep
    fun nativeSetClipboardText(text: String) {
        nativeUpdate = text
        clipboard.setPrimaryClip(ClipData.newPlainText("Linux", text))
    }

    @Keep
    fun nativeClipListening(enabled: Boolean) {
        if (listening == enabled) return
        listening = enabled
        if (enabled) clipboard.addPrimaryClipChangedListener(listener)
        else clipboard.removePrimaryClipChangedListener(listener)
    }

    @Keep
    fun nativeClipboardSync() {
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        Native.nativeSendClipboard(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun dispose() {
        if (listening) clipboard.removePrimaryClipChangedListener(listener)
        listening = false
    }
}
