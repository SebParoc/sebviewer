package com.sebparoc.sebviewer

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible view that owns the soft keyboard. Text typed into it is forwarded
 * to the host as text, special keys as key names.
 */
class KeyboardInputView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onText: ((String) -> Unit)? = null
    var onKey: ((String) -> Unit)? = null

    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        composing = ""
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                replaceComposing(t)
                composing = t
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) {
                    repeat(composing.length) { onKey?.invoke("BackSpace") }
                    composing = ""
                }
                repeat(beforeLength) { onKey?.invoke("BackSpace") }
                repeat(afterLength) { onKey?.invoke("Delete") }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onKey?.invoke("Return")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                val special = Keys.special(event.keyCode)
                if (special != null) {
                    onKey?.invoke(special)
                } else {
                    val ch = event.unicodeChar
                    if (ch != 0) onText?.invoke(String(Character.toChars(ch)))
                }
                return true
            }
        }
    }

    /** Emulate replacing the current composing text with [text]. */
    private fun replaceComposing(text: String) {
        val common = composing.commonPrefixWith(text).length
        repeat(composing.length - common) { onKey?.invoke("BackSpace") }
        val tail = text.substring(common)
        if (tail.isNotEmpty()) onText?.invoke(tail)
    }
}
