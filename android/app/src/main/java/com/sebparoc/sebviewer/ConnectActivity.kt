package com.sebparoc.sebviewer

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ConnectActivity : AppCompatActivity() {
    /** One row in the PC list: a saved PC, a PC found on this Wi-Fi, or both. */
    data class Pc(
        val name: String,
        val address: String,
        val port: Int,
        val discovered: Boolean,
        val saved: Boolean,
        var online: Boolean? = null,
    )

    private lateinit var prefs: Prefs
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var pinInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var emptyState: View
    private lateinit var manualForm: View
    private lateinit var manualChevron: ImageView
    private val adapter = PcAdapter({ connectTo(it.address, it.port) }, { onLongPressed(it) })
    private var discovery: HostDiscovery? = null
    private var discovered: List<HostDiscovery.Host> = emptyList()
    private val probes = Executors.newCachedThreadPool()
    private val onlineCache = HashMap<String, Boolean>()

    private val remote = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        status.text = r.data?.getStringExtra(RemoteActivity.EXTRA_ERROR) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        prefs = Prefs(this)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        pinInput = findViewById(R.id.pinInput)
        status = findViewById(R.id.statusText)
        emptyState = findViewById(R.id.emptyState)
        manualForm = findViewById(R.id.manualForm)
        manualChevron = findViewById(R.id.manualChevron)
        ((emptyState as ViewGroup).getChildAt(1) as TextView).text =
            Html.fromHtml(getString(R.string.empty_body), Html.FROM_HTML_MODE_COMPACT)

        val list = findViewById<RecyclerView>(R.id.pcList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        hostInput.setText(prefs.host)
        portInput.setText(prefs.port.toString())
        pinInput.setText(prefs.pin)

        findViewById<View>(R.id.manualHeader).setOnClickListener { toggleManual() }
        findViewById<MaterialButton>(R.id.connectButton).setOnClickListener { connectManual() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connectManual(); true } else false
        }
        findViewById<View>(R.id.helpButton).setOnClickListener { showGestures() }

        discovery = HostDiscovery(this) { hosts ->
            discovered = hosts
            rebuild()
        }
    }

    private fun toggleManual() {
        val show = manualForm.visibility != View.VISIBLE
        manualForm.visibility = if (show) View.VISIBLE else View.GONE
        manualChevron.animate().rotation(if (show) 90f else 0f).setDuration(150).start()
        if (show) hostInput.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        discovery?.start()
        rebuild()
    }

    override fun onStop() {
        discovery?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        probes.shutdownNow()
        super.onDestroy()
    }

    /** Merge saved and discovered PCs into one list and probe the saved ones. */
    private fun rebuild() {
        val saved = SavedHosts.load(this)
        val rows = LinkedHashMap<String, Pc>()
        for (h in discovered) {
            rows["${h.address}:${h.port}"] = Pc(h.name, h.address, h.port, discovered = true, saved = false, online = true)
        }
        for (h in saved) {
            val key = "${h.address}:${h.port}"
            val existing = rows[key]
            if (existing != null) rows[key] = existing.copy(saved = true, name = h.name)
            else rows[key] = Pc(h.name, h.address, h.port, discovered = false, saved = true, online = onlineCache[key])
        }
        val items = rows.values.toList()
        adapter.submit(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (manualForm.visibility != View.VISIBLE && items.isEmpty() && prefs.host.isNotEmpty()) {
            // returning user with no saved PCs: keep the form handy
            manualForm.visibility = View.VISIBLE
            manualChevron.rotation = 90f
        }
        for (pc in items) if (!pc.discovered) probe(pc)
    }

    /** Quick TCP reachability check so a saved PC shows online/offline before you tap it. */
    private fun probe(pc: Pc) {
        val key = "${pc.address}:${pc.port}"
        probes.execute {
            val ok = try {
                Socket().use { it.connect(InetSocketAddress(pc.address, pc.port), 2000); true }
            } catch (_: Exception) { false }
            runOnUiThread {
                onlineCache[key] = ok
                adapter.setOnline(key, ok)
            }
        }
    }

    private fun onLongPressed(pc: Pc) {
        if (!pc.saved) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.forget_host, pc.name, pc.address))
            .setPositiveButton(R.string.forget) { _, _ ->
                SavedHosts.forget(this, SavedHost(pc.name, pc.address, pc.port)); rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGestures() {
        AlertDialog.Builder(this)
            .setTitle(R.string.gestures_title)
            .setMessage(Html.fromHtml(getString(R.string.gestures_body), Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun connectManual() {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 7788
        if (host.isEmpty()) { status.text = getString(R.string.error_empty_host); return }
        connectTo(host, port)
    }

    private fun connectTo(host: String, port: Int) {
        val pin = pinInput.text?.toString()?.trim().orEmpty()
        prefs.host = host
        prefs.port = port
        prefs.pin = pin
        status.text = ""
        remote.launch(
            Intent(this, RemoteActivity::class.java)
                .putExtra(RemoteActivity.EXTRA_HOST, host)
                .putExtra(RemoteActivity.EXTRA_PORT, port)
                .putExtra(RemoteActivity.EXTRA_PIN, pin)
        )
    }

    private class PcAdapter(
        private val onClick: (Pc) -> Unit,
        private val onLongClick: (Pc) -> Unit,
    ) : RecyclerView.Adapter<PcAdapter.VH>() {
        private var items: List<Pc> = emptyList()

        fun submit(list: List<Pc>) {
            items = list
            notifyDataSetChanged()
        }

        fun setOnline(key: String, online: Boolean) {
            items.forEachIndexed { i, pc ->
                if ("${pc.address}:${pc.port}" == key && pc.online != online) {
                    pc.online = online
                    notifyItemChanged(i)
                }
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.pcIcon)
            val name: TextView = v.findViewById(R.id.pcName)
            val address: TextView = v.findViewById(R.id.pcAddress)
            val dot: ImageView = v.findViewById(R.id.pcDot)
            val status: TextView = v.findViewById(R.id.pcStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pc, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pc = items[position]
            val ctx = holder.itemView.context
            holder.name.text = pc.name
            val where = if (pc.discovered) ctx.getString(R.string.on_this_wifi) else ctx.getString(R.string.saved)
            holder.address.text = "${pc.address}:${pc.port} · $where"
            holder.icon.setImageResource(if (pc.discovered) R.drawable.ic_wifi else R.drawable.ic_globe)
            val (color, label) = when (pc.online) {
                true -> R.color.online to R.string.online
                false -> R.color.offline to R.string.offline
                null -> R.color.unknown to R.string.checking
            }
            ImageViewCompat.setImageTintList(holder.dot, ctx.getColorStateList(color))
            holder.status.text = ctx.getString(label)
            holder.itemView.setOnClickListener { onClick(pc) }
            holder.itemView.setOnLongClickListener { onLongClick(pc); true }
        }
    }
}
