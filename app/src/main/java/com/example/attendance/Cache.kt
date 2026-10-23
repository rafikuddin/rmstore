package com.example.attendance

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Last good server response per screen, kept on the phone so screens can open instantly. */
object ResponseCache {
    private fun prefs(ctx: Context) = SecureStore.prefs(ctx, "response_cache_secure")

    /** Saved response if it is younger than maxAgeMs, otherwise null. */
    fun get(ctx: Context, key: String, maxAgeMs: Long): JSONObject? {
        return try {
            val raw = prefs(ctx).getString(key, null) ?: return null
            val wrap = JSONObject(raw)
            if (System.currentTimeMillis() - wrap.optLong("t") > maxAgeMs) null else wrap.getJSONObject("d")
        } catch (e: Exception) {
            null
        }
    }

    fun put(ctx: Context, key: String, data: JSONObject) {
        val wrap = JSONObject().put("t", System.currentTimeMillis()).put("d", data)
        prefs(ctx).edit().putString(key, wrap.toString()).apply()
    }

    fun remove(ctx: Context, vararg keys: String) {
        val e = prefs(ctx).edit()
        keys.forEach { e.remove(it) }
        e.apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // Cache keys, so every screen and every invalidation agrees on the names.
    fun homeKey(user: String) = "home:$user"
    fun calKey(user: String) = "cal:$user"
    fun leavesKey(user: String) = "leaves:$user"
    fun nightKey(user: String) = "night:$user"
    fun teamKey(user: String, of: String?) = "team:$user:${of ?: ""}"
}

const val HOUR_MS = 60L * 60L * 1000L

/**
 * Shows the last saved response immediately (if there is one and it is fresh enough), then asks
 * the server and shows the up-to-date answer when it arrives. [busy] is dimmed while loading.
 * [onError] gets the message and whether something saved was already on screen.
 */
fun AppCompatActivity.apiCached(
    cacheKey: String,
    body: JSONObject,
    maxAgeMs: Long,
    busy: View? = null,
    onData: (JSONObject) -> Unit,
    onError: (String?, Boolean) -> Unit
): Boolean {
    val cached = ResponseCache.get(this, cacheKey, maxAgeMs)
    if (cached != null) onData(cached)
    busy?.let { Spin.start(it, true) }          // the ⟳ button spins until the answer arrives
    api(body) { res, err ->
        busy?.let { Spin.stop(it) }
        if (res != null) {
            ResponseCache.put(this, cacheKey, res)
            onData(res)
        } else {
            onError(err, cached != null)
        }
    }
    return cached != null
}
