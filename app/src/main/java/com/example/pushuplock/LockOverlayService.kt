package com.example.pushuplock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class LockOverlayService : Service() {

    companion object {
        const val ACTION_REMOVE_OVERLAY = "com.example.pushuplock.action.REMOVE_OVERLAY"
        const val ACTION_GRANT_TIME    = "com.example.pushuplock.GRANT_TIME"

        const val EXTRA_TARGET_PACKAGE = "targetPackage"
        const val EXTRA_SHOW_COUNTDOWN_ONLY = "showCountdownOnly"
    }

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var currentTarget: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    private var tvLock: TextView? = null
    private var btnStart: Button? = null
    private var btnClose: Button? = null

//    refresh the foreground notification text
    private fun updateForeground(text: String) {
        startForeground(1001, NotificationUtil.foregroundNotification(this, text))
    }

    private val grantReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_GRANT_TIME) return
            val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return
            val grantSeconds = intent.getIntExtra("grantSeconds", 0)
            val locked = AppLockManager.getLocked(applicationContext, pkg) ?: return

            AppLockManager.setRemainingSeconds(
                applicationContext,
                pkg,
                locked.remainingSeconds + grantSeconds
            )

            // switch to non-blocking countdown
            showCountdown(pkg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1001, NotificationUtil.foregroundNotification(this, "Lock active"))
        registerReceiver(grantReceiver, IntentFilter(ACTION_GRANT_TIME))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return START_NOT_STICKY

        if (intent.action == ACTION_REMOVE_OVERLAY) {
            if (pkg == currentTarget) {
                stopTick()
                removeOverlay()
                currentTarget = null
            }
            return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Enable 'Display over other apps' in Settings for PushUp Lock.",
                Toast.LENGTH_LONG
            ).show()
            return START_NOT_STICKY
        }

        if (pkg != currentTarget) {
            stopTick()
            removeOverlay()
            currentTarget = pkg
        }

        val countdownOnly = intent.getBooleanExtra(EXTRA_SHOW_COUNTDOWN_ONLY, false)
        val locked = AppLockManager.getLocked(applicationContext, pkg)

        when {
            locked == null -> {
                stopTick(); removeOverlay()
            }
            locked.remainingSeconds <= 0 -> { // time up -> hard block
                showHardBlock(pkg)
            }
            countdownOnly -> { // time left -> allow usage, run countdown
                showCountdown(pkg)
            }
            else -> { // safety
                showHardBlock(pkg)
            }
        }

        updateForeground("Locking $pkg")
        return START_STICKY
    }


//    full-screen overlay that forces a push-up
    private fun showHardBlock(pkg: String) {
        stopTick()
        inflateOverlayIfNeeded()

        tvLock?.text = "This app is locked. Do one push-up to unlock."
        btnStart?.visibility = View.VISIBLE
        btnClose?.visibility = View.GONE

    btnStart?.setOnClickListener {
        // Drop the overlay *before* opening the camera
        stopTick()
        removeOverlay()
        currentTarget = null

        val i = Intent(applicationContext, PushUpActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_TARGET_PACKAGE, pkg)
        }
        startActivity(i)
    }


        updateForeground("Locked: do a push-up to unlock $pkg")
    }


    private fun showCountdown(pkg: String) {
        val la = AppLockManager.getLocked(applicationContext, pkg) ?: run {
            removeOverlay(); return
        }

        removeOverlay()

        updateForeground("Time left in $pkg: ${formatSeconds(la.remainingSeconds)}")

        startTick(pkg)
    }

    private fun startTick(pkg: String) {
        if (ticking) return
        ticking = true

        handler.post(object : Runnable {
            override fun run() {
                val la = AppLockManager.getLocked(applicationContext, pkg)

                if (la == null) {
                    stopTick()
                    removeOverlay()
                    return
                }

                if (la.remainingSeconds <= 0) {
                    stopTick()
                    showHardBlock(pkg) // re-lock
                    return
                }

                // decrement and update notification
                AppLockManager.setRemainingSeconds(applicationContext, pkg, la.remainingSeconds - 1)
                updateForeground("Time left in $pkg: ${formatSeconds(la.remainingSeconds - 1)}")

                if (ticking) handler.postDelayed(this, 1000L)
            }
        })
    }

    private fun stopTick() {
        if (!ticking) return
        ticking = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun inflateOverlayIfNeeded() {
        if (overlayView != null) return

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_lock, null).also { v ->
            tvLock = v.findViewById(R.id.lock_text)
            btnStart = v.findViewById(R.id.start_pushups)
            btnClose = v.findViewById(R.id.btn_close)
            btnClose?.setOnClickListener {
                // keep hard lock; do nothing
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        // do NOT set NOT_FOCUSABLE: we want to block touches in hard lock

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        wm.addView(overlayView, lp)
    }

    private fun removeOverlay() {
        overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayView = null
        tvLock = null
        btnStart = null
        btnClose = null
    }

    private fun formatSeconds(total: Int): String {
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTick()
        removeOverlay()
        try { unregisterReceiver(grantReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
