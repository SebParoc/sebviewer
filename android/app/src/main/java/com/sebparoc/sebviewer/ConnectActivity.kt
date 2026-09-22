package com.sebparoc.sebviewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText

class ConnectActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var pinInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var emptyHosts: TextView
    private val adapter = HostAdapter { onHostPicked(it) }
    private var discovery: HostDiscovery? = null

    private val remote = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val err = r.data?.getStringExtra(RemoteActivity.EXTRA_ERROR)
        status.text = err ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        prefs = Prefs(this)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        pinInput = findViewById(R.id.pinInput)
        status = findViewById(R.id.statusText)
        emptyHosts = findViewById(R.id.emptyHosts)
        val list = findViewById<RecyclerView>(R.id.hostList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        hostInput.setText(prefs.host)
        portInput.setText(prefs.port.toString())
        pinInput.setText(prefs.pin)

        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        discovery = HostDiscovery(this) { hosts ->
            adapter.submit(hosts)
            emptyHosts.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        discovery?.start()
    }

    override fun onStop() {
        discovery?.stop()
        super.onStop()
    }

    private fun onHostPicked(h: HostDiscovery.Host) {
        hostInput.setText(h.address)
        portInput.setText(h.port.toString())
        if (pinInput.text.isNullOrBlank()) pinInput.requestFocus() else connect()
    }

    private fun connect() {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 7788
        val pin = pinInput.text?.toString()?.trim().orEmpty()
        if (host.isEmpty()) { status.text = getString(R.string.error_empty_host); return }
        if (pin.isEmpty()) { status.text = getString(R.string.error_empty_pin); return }
        prefs.host = host
        prefs.port = port
        prefs.pin = pin
        status.text = getString(R.string.connecting)
        val i = Intent(this, RemoteActivity::class.java)
            .putExtra(RemoteActivity.EXTRA_HOST, host)
            .putExtra(RemoteActivity.EXTRA_PORT, port)
            .putExtra(RemoteActivity.EXTRA_PIN, pin)
        remote.launch(i)
    }

    private class HostAdapter(private val onClick: (HostDiscovery.Host) -> Unit) :
        RecyclerView.Adapter<HostAdapter.VH>() {
        private var items: List<HostDiscovery.Host> = emptyList()

        fun submit(list: List<HostDiscovery.Host>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.hostName)
            val address: TextView = v.findViewById(R.id.hostAddress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val h = items[position]
            holder.name.text = h.name
            holder.address.text = "${h.address}:${h.port}"
            holder.itemView.setOnClickListener { onClick(h) }
        }
    }
}
