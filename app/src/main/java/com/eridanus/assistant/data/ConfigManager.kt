package com.eridanus.assistant.data

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("eridanus_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "http://192.168.1.100:5007") ?: "http://192.168.1.100:5007"
        set(value) = prefs.edit().putString("server_url", value.trimEnd('/')).apply()

    var authToken: String
        get() = prefs.getString("auth_token", "") ?: ""
        set(value) = prefs.edit().putString("auth_token", value.trim()).apply()

    var bindQqId: Long
        get() = prefs.getLong("bind_qq_id", 1840094972L)
        set(value) = prefs.edit().putLong("bind_qq_id", value).apply()

    var botName: String
        get() = prefs.getString("bot_name", "Eridanus") ?: "Eridanus"
        set(value) = prefs.edit().putString("bot_name", if (value.isBlank()) "Eridanus" else value.trim()).apply()

    var atBotDefault: Boolean
        get() = prefs.getBoolean("at_bot_default", true)
        set(value) = prefs.edit().putBoolean("at_bot_default", value).apply()

    var isFloatingRunning: Boolean
        get() = prefs.getBoolean("is_floating_running", false)
        set(value) = prefs.edit().putBoolean("is_floating_running", value).apply()

    fun clearConfig() {
        prefs.edit().clear().apply()
    }
}
