package com.example.sshvpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(
    val id: String,
    val config: SshConfig,
) {
    val displayName: String
        get() = config.name.ifBlank { "${config.username}@${config.host}:${config.port}" }
}

/** Simple multi-profile store backed by SharedPreferences. */
class ProfileStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Profile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", UUID.randomUUID().toString())
                val cfg = SshConfig.fromJson(o.getJSONObject("config").toString()) ?: continue
                out.add(Profile(id, cfg))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(profile: Profile) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        write(all)
        setActiveId(profile.id)
    }

    fun delete(id: String) {
        val all = list().filter { it.id != id }
        write(all)
        if (getActiveId() == id) {
            setActiveId(all.firstOrNull()?.id)
        }
    }

    fun getActive(): Profile? {
        val id = getActiveId() ?: return list().firstOrNull()
        return list().firstOrNull { it.id == id } ?: list().firstOrNull()
    }

    fun getActiveId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun write(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("config", JSONObject(p.config.toJson()))
            })
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "karoon_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_id"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
