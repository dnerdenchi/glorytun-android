package com.example.glorytun

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(context: Context) {

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "GlorytunProfiles", masterKeyAlias, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun loadProfiles(): MutableList<VpnProfile> {
        val json = prefs.getString("profiles", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val obj = arr.getJSONObject(i)
                VpnProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    ip = obj.getString("ip"),
                    port = obj.getString("port"),
                    secret = obj.getString("secret")
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveProfiles(profiles: List<VpnProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("ip", p.ip)
                put("port", p.port)
                put("secret", p.secret)
            })
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun getActiveProfileId(): String? = prefs.getString("active_profile_id", null)

    fun setActiveProfileId(id: String?) {
        if (id == null) {
            prefs.edit().remove("active_profile_id").apply()
        } else {
            prefs.edit().putString("active_profile_id", id).apply()
        }
    }

    /** 旧設定（IP/PORT/SECRET）が存在する場合、デフォルトプロファイルに移行する */
    fun migrateFromLegacy(ip: String, port: String, secret: String) {
        if (loadProfiles().isNotEmpty()) return
        if (ip.isEmpty()) return
        val profile = VpnProfile(name = "デフォルト", ip = ip, port = port, secret = secret)
        saveProfiles(listOf(profile))
        setActiveProfileId(profile.id)
    }
}
