package com.example.pushuplock

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class LockedApp(
    val packageName: String,
    var minutesPerRep: Int = 10,
    var remainingSeconds: Int = 0
)

object AppLockManager {
    private const val PREF = "pushup_lock_prefs"
    private const val KEY_LOCKS = "locks_json"

    private val gson = Gson()
    private var initialized = false
    private var cache: MutableList<LockedApp> = mutableListOf()

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LOCKS, null)
        cache = if (json != null) {
            try {
                val type = object : TypeToken<MutableList<LockedApp>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        initialized = true
    }

    private fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCKS, gson.toJson(cache)).apply()
    }

    fun getAll(context: Context): List<LockedApp> {
        init(context)
        return cache
    }

    fun setLocked(context: Context, pkg: String, minutesPerRep: Int) {
        init(context)
        val e = cache.find { it.packageName == pkg }
        if (e == null) {
            cache.add(LockedApp(pkg, minutesPerRep, 0))
        } else {
            e.minutesPerRep = minutesPerRep
        }
        save(context)
    }

    fun removeLock(context: Context, pkg: String) {
        init(context)
        cache.removeAll { it.packageName == pkg }
        save(context)
    }

    fun getLocked(context: Context, pkg: String): LockedApp? {
        init(context)
        return cache.find { it.packageName == pkg }
    }

    /** Add seconds (used by service after receiving the GRANT_TIME broadcast). */
    fun grantSeconds(context: Context, pkg: String, seconds: Int) {
        init(context)
        val e = cache.find { it.packageName == pkg } ?: return
        e.remainingSeconds = (e.remainingSeconds + seconds).coerceAtLeast(0)
        save(context)
    }

    fun setRemainingSeconds(context: Context, pkg: String, seconds: Int) {
        init(context)
        val e = cache.find { it.packageName == pkg } ?: return
        e.remainingSeconds = seconds.coerceAtLeast(0)
        save(context)
    }

    fun grantSeconds(context: Context, pkg: String, seconds: Long) =
        grantSeconds(context, pkg, seconds.toInt())

    fun setRemainingSeconds(context: Context, pkg: String, seconds: Long) =
        setRemainingSeconds(context, pkg, seconds.toInt())
}
