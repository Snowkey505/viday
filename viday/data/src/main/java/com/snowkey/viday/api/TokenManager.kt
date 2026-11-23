package com.snowkey.viday.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject

class TokenManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_TOKEN = "jwt_token"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun getUserId(): Long? {
        return getPayloadJson()?.optString("sub")?.toLongOrNull()
    }

    fun getUsername(): String? {
        return getPayloadJson()?.optString("username")
    }

    fun getRole(): String? {
        return getPayloadJson()?.optString("role")
    }

    private fun getPayloadJson(): JSONObject? {
        val token = getToken() ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(
                Base64.decode(
                    parts[1],
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
            )
            JSONObject(payload)
        } catch (e: Exception) {
            null
        }
    }
}