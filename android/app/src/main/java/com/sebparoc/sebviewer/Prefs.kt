package com.sebparoc.sebviewer

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sebviewer", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", 7788)
        set(v) = sp.edit().putInt("port", v).apply()

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) = sp.edit().putInt("quality", v).apply()

    var guideShown: Boolean
        get() = sp.getBoolean("guide_shown", false)
        set(v) = sp.edit().putBoolean("guide_shown", v).apply()
}

/** A PC the user connected to successfully; kept so remote (non-LAN) PCs are one tap away. */
data class SavedHost(val name: String, val address: String, val port: Int)

object SavedHosts {
    private const val KEY = "saved_hosts"

    fun load(context: Context): MutableList<SavedHost> {
        val sp = context.getSharedPreferences("sebviewer", Context.MODE_PRIVATE)
        val out = mutableListOf<SavedHost>()
        try {
            val arr = org.json.JSONArray(sp.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(SavedHost(o.getString("name"), o.getString("address"), o.getInt("port")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(context: Context, list: List<SavedHost>) {
        val arr = org.json.JSONArray()
        for (h in list) arr.put(org.json.JSONObject().put("name", h.name).put("address", h.address).put("port", h.port))
        context.getSharedPreferences("sebviewer", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Insert or refresh a host (matched by address and port) at the top of the list. */
    fun remember(context: Context, host: SavedHost) {
        val list = load(context)
        list.removeAll { it.address == host.address && it.port == host.port }
        list.add(0, host)
        save(context, list.take(20))
    }

    fun forget(context: Context, host: SavedHost) {
        val list = load(context)
        list.removeAll { it.address == host.address && it.port == host.port }
        save(context, list)
    }
}
