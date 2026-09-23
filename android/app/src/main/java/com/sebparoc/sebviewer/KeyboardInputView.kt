package com.sebparoc.sebviewer

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText

/**
 * Invisible text field that owns the soft keyboard.
 *
 * It is a real editor so the IME (Gboard etc.) can use all its features: glide
 * typing, voice input, autocorrect and suggestions.  Every change the IME makes
 * to the local buffer is turned into the equivalent keystrokes for the PC:
 * inserted text is typed, removed text becomes Backspaces, newlines become Enter.
 */
class KeyboardInputView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    var onText: ((String) -> Unit)? = null
    var onKey: ((String) -> Unit)? = null

    private var ignoreChanges = false
    private var oldSegment = ""
    private var oldLength = 0

    init {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        background = null
        isCursorVisible = false
        alpha = 0.01f
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (ignoreChanges) return
                oldSegment = s.subSequence(start, start + count).toString()
                oldLength = s.length
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (ignoreChanges) return
                val newSegment = s.subSequence(start, start + count).toString()
                val tail = oldLength - (start + before) // chars after the edit point
                emitDiff(oldSegment, newSegment, tail)
            }

            override fun afterTextChanged(s: Editable) {
                if (ignoreChanges) return
                trimIfNeeded(s)
            }
        })
    }

    /** Type the difference between what the buffer had and what it has now. */
    private fun emitDiff(old: String, new: String, tail: Int) {
        val prefix = old.commonPrefixWith(new).length
        val removed = old.length - prefix
        val inserted = new.substring(prefix)
        if (removed == 0 && inserted.isEmpty()) return
        val moves = tail.coerceAtLeast(0)
        repeat(moves) { onKey?.invoke("Left") }
        repeat(removed) { onKey?.invoke("BackSpace") }
        var run = StringBuilder()
        for (ch in inserted) {
            if (ch == '\n') {
                if (run.isNotEmpty()) { onText?.invoke(run.toString()); run = StringBuilder() }
                onKey?.invoke("Return")
            } else {
                run.append(ch)
            }
        }
        if (run.isNotEmpty()) onText?.invoke(run.toString())
        repeat(moves) { onKey?.invoke("Right") }
    }

    /** Keep the local buffer small; the IME only needs a little context. */
    private fun trimIfNeeded(s: Editable) {
        if (s.length < 600) return
        if (BaseInputConnection.getComposingSpanStart(s) >= 0) return // IME is mid-word
        withoutEcho { s.delete(0, s.length - 300) }
    }

    /** Forget local context, e.g. after clicking somewhere else on the PC. */
    fun resetBuffer() {
        withoutEcho { text?.clear() }
    }

    private inline fun withoutEcho(block: () -> Unit) {
        ignoreChanges = true
        try { block() } finally { ignoreChanges = false }
    }

    /** Keys the IME sends as key events instead of text edits (e.g. Backspace on an empty buffer). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val atStart = (text?.length ?: 0) == 0 || selectionStart == 0
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (atStart) { onKey?.invoke("BackSpace"); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> if (selectionEnd == (text?.length ?: 0)) { onKey?.invoke("Delete"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE -> {
                Keys.special(keyCode)?.let { onKey?.invoke(it) }
                resetBuffer()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
