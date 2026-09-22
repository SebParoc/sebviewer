package com.sebparoc.sebviewer

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

class RemoteActivity : AppCompatActivity(), RemoteClient.Listener, RemoteScreenView.InputListener {
    private lateinit var screen: RemoteScreenView
    private lateinit var keyInput: KeyboardInputView
    private lateinit var status: TextView
    private lateinit var toolbar: View
    private lateinit var prefs: Prefs
    private var client: RemoteClient? = null
    private val main = Handler(Looper.getMainLooper())
    private var hostName = ""
    private var remoteW = 0
    private var remoteH = 0
    private var frames = 0
    private var fps = 0
    private val activeModifiers = LinkedHashMap<String, Button>()
    private var keyboardShown = false

    private val statsTick = object : Runnable {
        override fun run() {
            fps = frames
            frames = 0
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        screen = findViewById(R.id.screen)
        keyInput = findViewById(R.id.keyInput)
        status = findViewById(R.id.statusOverlay)
        toolbar = findViewById(R.id.toolbar)
        screen.inputListener = this
        keyInput.onText = { sendText(it) }
        keyInput.onKey = { tapKey(it) }

        findViewById<FloatingActionButton>(R.id.toolbarToggle).setOnClickListener {
            toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        wireToolbar()

        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 7788)
        val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        status.text = getString(R.string.connecting)
        client = RemoteClient(host, port, pin, this).also { it.connect() }
        main.postDelayed(statsTick, 1000)
    }

    private fun wireToolbar() {
        fun key(id: Int, name: String) = findViewById<Button>(id).setOnClickListener { tapKey(name) }
        fun modifier(id: Int, name: String) {
            val b = findViewById<Button>(id)
            b.setOnClickListener { toggleModifier(name, b) }
        }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { toggleKeyboard() }
        val drag = findViewById<Button>(R.id.btnDrag)
        drag.setOnClickListener {
            screen.dragMode = !screen.dragMode
            drag.alpha = if (screen.dragMode) 1f else 0.6f
        }
        drag.alpha = 0.6f
        val trackpad = findViewById<Button>(R.id.btnTrackpad)
        trackpad.setOnClickListener {
            screen.trackpadMode = !screen.trackpadMode
            trackpad.alpha = if (screen.trackpadMode) 1f else 0.6f
        }
        trackpad.alpha = 0.6f
        modifier(R.id.btnCtrl, "Control_L")
        modifier(R.id.btnAlt, "Alt_L")
        modifier(R.id.btnShift, "Shift_L")
        modifier(R.id.btnSuper, "Super_L")
        key(R.id.btnEsc, "Escape")
        key(R.id.btnTab, "Tab")
        key(R.id.btnEnter, "Return")
        key(R.id.btnBackspace, "BackSpace")
        key(R.id.btnDelete, "Delete")
        key(R.id.btnUp, "Up")
        key(R.id.btnDown, "Down")
        key(R.id.btnLeft, "Left")
        key(R.id.btnRight, "Right")
        key(R.id.btnHome, "Home")
        key(R.id.btnEnd, "End")
        key(R.id.btnPgUp, "Page_Up")
        key(R.id.btnPgDn, "Page_Down")
        key(R.id.btnVolDown, "XF86AudioLowerVolume")
        key(R.id.btnVolUp, "XF86AudioRaiseVolume")
        key(R.id.btnPlay, "XF86AudioPlay")
        findViewById<Button>(R.id.btnF).setOnClickListener { v ->
            val menu = PopupMenu(this, v)
            for (i in 1..12) menu.menu.add(0, i, i, "F$i")
            menu.setOnMenuItemClickListener { tapKey("F${it.itemId}"); true }
            menu.show()
        }
        findViewById<Button>(R.id.btnFit).setOnClickListener { screen.fitToView() }
        findViewById<Button>(R.id.btnQuality).setOnClickListener { showQualityDialog() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { finish() }
    }

    // ------------------------------------------------------------ keyboard
    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(keyInput.windowToken, 0)
            keyboardShown = false
        } else {
            keyInput.requestFocus()
            imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT)
            keyboardShown = true
        }
    }

    private fun toggleModifier(name: String, b: Button) {
        if (activeModifiers.remove(name) != null) {
            send(JSONObject().put("t", "key").put("k", name).put("d", false))
            b.alpha = 0.6f
        } else {
            activeModifiers[name] = b
            send(JSONObject().put("t", "key").put("k", name).put("d", true))
            b.alpha = 1f
            b.setBackgroundColor(getColor(R.color.key_active))
        }
    }

    private fun releaseModifiers() {
        if (activeModifiers.isEmpty()) return
        for ((name, b) in activeModifiers) {
            send(JSONObject().put("t", "key").put("k", name).put("d", false))
            b.alpha = 0.6f
        }
        activeModifiers.clear()
        // restore default tonal background by re-inflating style is heavy; alpha is enough
    }

    private fun tapKey(name: String) {
        send(JSONObject().put("t", "key").put("k", name))
        releaseModifiers()
    }

    private fun sendText(text: String) {
        send(JSONObject().put("t", "text").put("s", text))
        releaseModifiers()
    }

    /** Hardware keyboards (Bluetooth / USB). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_HOME ||
            code == KeyEvent.KEYCODE_APP_SWITCH || event.deviceId <= 0 /* virtual */) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val special = Keys.special(code)
            if (special != null) {
                if (Keys.isModifier(code)) {
                    send(JSONObject().put("t", "key").put("k", special).put("d", true))
                } else {
                    send(JSONObject().put("t", "key").put("k", special))
                }
                return true
            }
            val ch = event.unicodeChar
            val ctrl = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
            if (ctrl && event.displayLabel != 0.toChar()) {
                // send as key press so the held modifier applies (e.g. Ctrl+C)
                send(JSONObject().put("t", "key").put("k", event.displayLabel.lowercaseChar().toString()))
                return true
            }
            if (ch != 0) {
                send(JSONObject().put("t", "text").put("s", String(Character.toChars(ch))))
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP && Keys.isModifier(code)) {
            send(JSONObject().put("t", "key").put("k", Keys.special(code)!!).put("d", false))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------- quality
    private fun showQualityDialog() {
        val labels = arrayOf("Smooth (720p, 25 fps)", "Balanced (1280px, 20 fps)", "Sharp (1920px, 15 fps)", "Data saver (960px, 10 fps)")
        AlertDialog.Builder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(labels, prefs.quality) { d, which ->
                prefs.quality = which
                applyQuality(which)
                d.dismiss()
            }
            .show()
    }

    private fun applyQuality(which: Int) {
        val cfg = JSONObject().put("t", "cfg")
        when (which) {
            0 -> cfg.put("w", 1280).put("q", 50).put("fps", 25)
            1 -> cfg.put("w", 1280).put("q", 60).put("fps", 20)
            2 -> cfg.put("w", 1920).put("q", 75).put("fps", 15)
            else -> cfg.put("w", 960).put("q", 40).put("fps", 10)
        }
        send(cfg)
    }

    // ---------------------------------------------------------- transport
    private fun send(obj: JSONObject) {
        client?.send(obj)
    }

    private fun updateStatus() {
        if (remoteW == 0) return
        status.text = "$hostName · ${remoteW}×$remoteH · $fps fps"
    }

    override fun onHello(width: Int, height: Int, name: String) {
        main.post {
            hostName = name
            remoteW = width; remoteH = height
            updateStatus()
            applyQuality(prefs.quality)
            main.postDelayed({ status.visibility = View.GONE }, 4000)
        }
    }

    override fun onSize(width: Int, height: Int) {
        main.post { remoteW = width; remoteH = height }
    }

    override fun onFrame(bitmap: Bitmap) {
        main.post {
            frames++
            screen.setFrame(bitmap)
        }
    }

    override fun onError(message: String) {
        main.post { fail(message) }
    }

    override fun onClosed() {
        main.post { fail("Disconnected") }
    }

    private fun fail(message: String) {
        if (isFinishing) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    // --------------------------------------------------------------- input
    override fun onMove(nx: Float, ny: Float) {
        send(JSONObject().put("t", "move").put("x", nx.toDouble()).put("y", ny.toDouble()))
    }

    override fun onClick(button: Int, nx: Float, ny: Float) {
        send(JSONObject().put("t", "click").put("b", button).put("x", nx.toDouble()).put("y", ny.toDouble()))
        releaseModifiers()
    }

    override fun onButton(button: Int, down: Boolean, nx: Float, ny: Float) {
        send(JSONObject().put("t", "btn").put("b", button).put("d", down)
            .put("x", nx.toDouble()).put("y", ny.toDouble()))
    }

    override fun onScroll(dx: Int, dy: Int) {
        send(JSONObject().put("t", "scroll").put("dx", dx).put("dy", dy))
    }

    override fun onDestroy() {
        main.removeCallbacks(statsTick)
        client?.close()
        client = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val EXTRA_ERROR = "error"
    }
}
