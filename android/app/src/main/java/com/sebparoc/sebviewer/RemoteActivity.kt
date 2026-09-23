package com.sebparoc.sebviewer

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

class RemoteActivity : AppCompatActivity(), RemoteClient.Listener, RemoteScreenView.InputListener {
    private lateinit var root: View
    private lateinit var screen: RemoteScreenView
    private lateinit var keyInput: KeyboardInputView
    private lateinit var statusPill: View
    private lateinit var statusText: TextView
    private lateinit var statusDot: ImageView
    private lateinit var panel: View
    private lateinit var panelToggle: FloatingActionButton
    private lateinit var prefs: Prefs
    private var client: RemoteClient? = null
    private var host = ""
    private var port = 7788
    private var pin = ""
    private var reconnects = 0
    private var everConnected = false
    private val main = Handler(Looper.getMainLooper())
    private var hostName = ""
    private var remoteW = 0
    private var remoteH = 0
    private var frames = 0
    private var fps = 0
    private val modifierButtons = LinkedHashMap<String, MaterialButton>()
    private val hidePill = Runnable { statusPill.animate().alpha(0f).setDuration(300).start() }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        root = findViewById(R.id.root)
        screen = findViewById(R.id.screen)
        keyInput = findViewById(R.id.keyInput)
        statusPill = findViewById(R.id.statusPill)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        panel = findViewById(R.id.panel)
        panelToggle = findViewById(R.id.panelToggle)
        screen.inputListener = this
        keyInput.onText = { sendText(it) }
        keyInput.onKey = { tapKey(it) }

        // Keep everything above the soft keyboard: the screen view shrinks and re-fits.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cutout.left, cutout.top, cutout.right, ime)
            insets
        }

        panelToggle.setOnClickListener { togglePanel() }
        wirePanel()

        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 7788)
        pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        setStatus(getString(R.string.connecting), R.color.unknown, autoHide = false)
        connect()
        main.postDelayed(statsTick, 1000)

        if (!prefs.guideShown) {
            val guide = findViewById<View>(R.id.guide)
            (guide.findViewById<View>(R.id.guideOk).parent as android.view.ViewGroup).let { card ->
                (card.getChildAt(1) as TextView).text =
                    Html.fromHtml(getString(R.string.gestures_body), Html.FROM_HTML_MODE_COMPACT)
            }
            guide.visibility = View.VISIBLE
            guide.findViewById<View>(R.id.guideOk).setOnClickListener {
                prefs.guideShown = true
                guide.animate().alpha(0f).setDuration(200).withEndAction { guide.visibility = View.GONE }.start()
            }
        }
    }

    private fun connect() {
        client?.close()
        client = RemoteClient(host, port, pin, this).also { it.connect() }
    }

    // --------------------------------------------------------------- panel
    private fun togglePanel() {
        val show = panel.visibility != View.VISIBLE
        if (show) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f; panel.translationY = 40f
            panel.animate().alpha(1f).translationY(0f).setDuration(160).start()
            panelToggle.setImageResource(R.drawable.ic_close)
            showPill()
        } else {
            panel.animate().alpha(0f).translationY(40f).setDuration(120)
                .withEndAction { panel.visibility = View.GONE }.start()
            panelToggle.setImageResource(R.drawable.ic_keyboard)
        }
    }

    private fun wirePanel() {
        fun key(id: Int, name: String) = findViewById<Button>(id).setOnClickListener { tapKey(name); haptic(it) }
        fun modifier(id: Int, name: String) {
            val b = findViewById<MaterialButton>(id)
            b.addOnCheckedChangeListener { _, checked ->
                send(JSONObject().put("t", "key").put("k", name).put("d", checked))
                if (checked) modifierButtons[name] = b else modifierButtons.remove(name)
            }
        }
        val kb = findViewById<MaterialButton>(R.id.btnKeyboard)
        kb.addOnCheckedChangeListener { _, checked -> showKeyboard(checked) }
        findViewById<MaterialButton>(R.id.btnTrackpad).addOnCheckedChangeListener { _, checked ->
            screen.setTrackpad(checked)
        }
        findViewById<MaterialButton>(R.id.btnDrag).addOnCheckedChangeListener { _, checked ->
            screen.dragMode = checked
        }
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
        findViewById<Button>(R.id.btnFit).setOnClickListener { screen.fitToView(); haptic(it) }
        findViewById<Button>(R.id.btnQuality).setOnClickListener { showQualityDialog() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { finish() }

        // keep the keyboard button in sync when the IME is dismissed with the back gesture
        ViewCompat.setOnApplyWindowInsetsListener(keyInput) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (kb.isChecked != visible) kb.isChecked = visible
            insets
        }
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ------------------------------------------------------------ keyboard
    private fun showKeyboard(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            keyInput.requestFocus()
            imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(keyInput.windowToken, 0)
        }
    }

    private fun releaseModifiers() {
        if (modifierButtons.isEmpty()) return
        for (b in modifierButtons.values.toList()) b.isChecked = false // listener sends the release
        modifierButtons.clear()
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
            val combo = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
            if (combo && event.displayLabel != 0.toChar()) {
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
        val labels = arrayOf(
            getString(R.string.quality_smooth), getString(R.string.quality_balanced),
            getString(R.string.quality_sharp), getString(R.string.quality_saver),
        )
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

    private fun setStatus(text: String, color: Int, autoHide: Boolean) {
        statusText.text = text
        ImageViewCompat.setImageTintList(statusDot, getColorStateList(color))
        showPill()
        if (!autoHide) main.removeCallbacks(hidePill)
    }

    private fun showPill() {
        main.removeCallbacks(hidePill)
        statusPill.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hidePill, 4000)
    }

    private fun updateStatus() {
        if (remoteW == 0 || !everConnected) return
        statusText.text = "$hostName · ${remoteW}×$remoteH · $fps fps"
    }

    override fun onHello(width: Int, height: Int, name: String) {
        main.post {
            hostName = name
            remoteW = width; remoteH = height
            everConnected = true
            reconnects = 0
            SavedHosts.remember(this, SavedHost(name, host, port))
            setStatus("$hostName · ${remoteW}×$remoteH", R.color.online, autoHide = true)
            applyQuality(prefs.quality)
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
        val fatal = message.contains("PIN", ignoreCase = true) || message.contains("attempts")
        main.post { if (fatal) fail(message) else lost(message) }
    }

    override fun onClosed() {
        main.post { lost("Disconnected") }
    }

    /** Connection dropped: retry a few times (mobile networks switch a lot). */
    private fun lost(message: String) {
        if (isFinishing) return
        if (!everConnected || reconnects >= MAX_RECONNECTS) {
            fail(message)
            return
        }
        reconnects++
        setStatus(getString(R.string.reconnecting, reconnects, MAX_RECONNECTS), R.color.offline, autoHide = false)
        main.postDelayed({ if (!isFinishing) connect() }, 1500L * reconnects)
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
        keyInput.resetBuffer() // focus on the PC probably moved; drop stale IME context
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
        main.removeCallbacks(hidePill)
        client?.close()
        client = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_RECONNECTS = 5
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val EXTRA_ERROR = "error"
    }
}
