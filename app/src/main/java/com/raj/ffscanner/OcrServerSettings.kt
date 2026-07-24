package com.raj.ffscanner

import android.content.Context
import java.net.URI

object OcrServerSettings {
    private const val PREFS_NAME = "ocr_server_settings"
    private const val KEY_SERVER_URL = "ocr_server_url"

    fun getUrl(context: Context): String {
        val savedUrl = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)
            ?.trim()
        return if (savedUrl != null && isValid(savedUrl)) {
            savedUrl
        } else {
            context.getString(R.string.default_ocr_server_url)
        }
    }

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trim())
            .apply()
    }

    fun isValid(url: String): Boolean {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return false
        }

        return try {
            val uri = URI(trimmedUrl)
            uri.host != null
        } catch (_: Exception) {
            false
        }
    }
}
