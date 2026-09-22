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
}
