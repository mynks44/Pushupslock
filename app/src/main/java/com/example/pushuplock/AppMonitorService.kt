package com.example.pushuplock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class AppMonitorService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLockManager.init(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val locked = AppLockManager.getLocked(applicationContext, pkg)
            if (locked != null) {
                // if remainingSeconds <= 0 => show overlay (user must do pushups)
                if (locked.remainingSeconds <= 0L) {
                    val intent = Intent(this, LockOverlayService::class.java).apply {
                        putExtra("targetPackage", pkg)
                    }
                    // start as foreground service to ensure overlay remains
                    startService(intent)
                } else {
                    // app has time left; start overlay service as well so it can show countdown (optionally)
                    val intent = Intent(this, LockOverlayService::class.java).apply {
                        putExtra("targetPackage", pkg)
                        putExtra("showCountdownOnly", true)
                    }
                    startService(intent)
                }
            } else {
                // If this app is not locked, ensure overlay removed
                val intent = Intent(this, LockOverlayService::class.java).apply {
                    action = LockOverlayService.ACTION_REMOVE_OVERLAY
                    putExtra("targetPackage", pkg)
                }
                startService(intent)
            }
        }
    }

    override fun onInterrupt() {}
}
