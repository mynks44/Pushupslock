package com.example.pushuplock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat

class AppMonitorService : AccessibilityService() {

    companion object {
        private var lastPkg: String? = null
        private var lastTs: Long = 0L
        private const val DEBOUNCE_MS = 300L
        private val IGNORED = setOf(
            "com.example.pushuplock",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLockManager.init(applicationContext)
        Toast.makeText(this, "PushUp Lock accessibility ON", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED) return

        val now = System.currentTimeMillis()
        if (pkg == lastPkg && (now - lastTs) < DEBOUNCE_MS) return
        lastPkg = pkg; lastTs = now

        val locked = AppLockManager.getLocked(applicationContext, pkg)

        // DEBUG toast so you can see triggers
        Toast.makeText(this,
            if (locked == null) "Seen: $pkg (not locked)"
            else "Seen: $pkg (locked, secs=${locked.remainingSeconds})",
            Toast.LENGTH_SHORT
        ).show()

        val intent = Intent(this, LockOverlayService::class.java).apply {
            putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, pkg)
            if (locked == null) {
                action = LockOverlayService.ACTION_REMOVE_OVERLAY
            } else {
                putExtra(LockOverlayService.EXTRA_SHOW_COUNTDOWN_ONLY, locked.remainingSeconds > 0)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onInterrupt() {}
}
