package com.example.pushuplock

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class LockedApp(
    val packageName: String,
    var minutesPerRep: Int = 10,
    var remainingSeconds: Long = 0L
)

object AppLockManager {
    private const val PREF = "pushup_lock_prefs"
    private const val KEY_LOCKS = "locks_json"
    private val gson = Gson()
    private var cache: MutableList<LockedApp> = mutableListOf()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LOCKS, null)
        cache = if (json != null) {
            val type = object : TypeToken<MutableList<LockedApp>>() {}.type
            gson.fromJson(json, type)
        } else mutableListOf()
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
        val existing = cache.find { it.packageName == pkg }
        if (existing == null) cache.add(LockedApp(pkg, minutesPerRep))
        else existing.minutesPerRep = minutesPerRep
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

    // grant seconds to a package (called from PushUpActivity)
    fun grantSeconds(context: Context, pkg: String, seconds: Long) {
        init(context)
        val la = cache.find { it.packageName == pkg }
        if (la != null) {
            la.remainingSeconds = (la.remainingSeconds + seconds)
            save(context)
        }
    }

    fun setRemainingSeconds(context: Context, pkg: String, seconds: Long) {
        init(context)
        val la = cache.find { it.packageName == pkg }
        la?.apply {
            remainingSeconds = seconds
            save(context)
        }
    }
}
