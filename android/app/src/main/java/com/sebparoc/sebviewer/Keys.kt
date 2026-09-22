package com.sebparoc.sebviewer

import android.view.KeyEvent

/** Maps Android key codes to the X11 keysym names understood by the host. */
object Keys {
    private val map = mapOf(
        KeyEvent.KEYCODE_ENTER to "Return",
        KeyEvent.KEYCODE_NUMPAD_ENTER to "Return",
        KeyEvent.KEYCODE_DEL to "BackSpace",
        KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
        KeyEvent.KEYCODE_TAB to "Tab",
        KeyEvent.KEYCODE_ESCAPE to "Escape",
        KeyEvent.KEYCODE_SPACE to "space",
        KeyEvent.KEYCODE_DPAD_UP to "Up",
        KeyEvent.KEYCODE_DPAD_DOWN to "Down",
        KeyEvent.KEYCODE_DPAD_LEFT to "Left",
        KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
        KeyEvent.KEYCODE_MOVE_HOME to "Home",
        KeyEvent.KEYCODE_MOVE_END to "End",
        KeyEvent.KEYCODE_PAGE_UP to "Page_Up",
        KeyEvent.KEYCODE_PAGE_DOWN to "Page_Down",
        KeyEvent.KEYCODE_INSERT to "Insert",
        KeyEvent.KEYCODE_CTRL_LEFT to "Control_L",
        KeyEvent.KEYCODE_CTRL_RIGHT to "Control_L",
        KeyEvent.KEYCODE_ALT_LEFT to "Alt_L",
        KeyEvent.KEYCODE_ALT_RIGHT to "Alt_L",
        KeyEvent.KEYCODE_SHIFT_LEFT to "Shift_L",
        KeyEvent.KEYCODE_SHIFT_RIGHT to "Shift_L",
        KeyEvent.KEYCODE_META_LEFT to "Super_L",
        KeyEvent.KEYCODE_META_RIGHT to "Super_L",
        KeyEvent.KEYCODE_CAPS_LOCK to "Caps_Lock",
        KeyEvent.KEYCODE_SYSRQ to "Print",
        KeyEvent.KEYCODE_MENU to "Menu",
        KeyEvent.KEYCODE_F1 to "F1", KeyEvent.KEYCODE_F2 to "F2", KeyEvent.KEYCODE_F3 to "F3",
        KeyEvent.KEYCODE_F4 to "F4", KeyEvent.KEYCODE_F5 to "F5", KeyEvent.KEYCODE_F6 to "F6",
        KeyEvent.KEYCODE_F7 to "F7", KeyEvent.KEYCODE_F8 to "F8", KeyEvent.KEYCODE_F9 to "F9",
        KeyEvent.KEYCODE_F10 to "F10", KeyEvent.KEYCODE_F11 to "F11", KeyEvent.KEYCODE_F12 to "F12",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "XF86AudioPlay",
        KeyEvent.KEYCODE_MEDIA_NEXT to "XF86AudioNext",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "XF86AudioPrev",
    )

    val MODIFIERS = setOf("Control_L", "Alt_L", "Shift_L", "Super_L")

    /** Name for a special (non-printing) key, or null. */
    fun special(keyCode: Int): String? = map[keyCode]

    fun isModifier(keyCode: Int): Boolean = map[keyCode] in MODIFIERS
}
