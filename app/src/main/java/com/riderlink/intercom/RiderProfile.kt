package com.riderlink.intercom

import android.content.Context
import android.os.Build
import com.riderlink.intercom.protocol.JoinRequest
import java.security.SecureRandom

/**
 * Who this phone's rider is to a group: a display name (editable later, M1 roster slice) and a random
 * per-install rider key, so a Host recognises the same rider rejoining from a new address.
 */
class RiderProfile(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var displayName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    /** False until the rider has saved a name of their own (the first-run prompt uses it). */
    val hasChosenName: Boolean
        get() = prefs.contains(KEY_NAME)

    val riderKey: Int
        get() {
            val existing = prefs.getInt(KEY_RIDER_KEY, 0)
            if (existing != 0) return existing
            var key = 0
            while (key == 0) key = SecureRandom().nextInt()
            prefs.edit().putInt(KEY_RIDER_KEY, key).apply()
            return key
        }

    fun joinRequest(): JoinRequest =
        JoinRequest(displayName, JoinRequest.FLAG_ANDROID or JoinRequest.FLAG_OPUS, riderKey)

    private fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Rider"

    private companion object {
        const val PREFS = "riderlink_profile"
        const val KEY_NAME = "display_name"
        const val KEY_RIDER_KEY = "rider_key"
    }
}
