package com.vasiliastyper.engine

import android.content.Context

object GeminiSettings {
    private const val PREF = "gemini_settings"
    private const val KEY = "gemini_api_key"

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, key.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
