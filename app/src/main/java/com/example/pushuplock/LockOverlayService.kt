package com.example.pushuplock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class LockOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "pushup_lock_channel"
        const val ACTION_REMOVE_OVERLAY = "com.example.pushuplock.action.REMOVE_OVERLAY"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var targetPackage: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // receive action from PushUpActivity
            val pkg = intent?.getStringExtra("targetPackage")
            val grantedSeconds = intent?.getLongExtra("grantedSeconds", 0L) ?: 0L
            if (pkg != null && pkg == targetPackage) {
                // update model and remove overlay
                AppLockManager.grantSeconds(applicationContext, pkg, grantedSeconds)
                removeOverlay()
                stopForeground(true)
                stopSelf()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(unlockReceiver, IntentFilter("com.example.pushuplock.ACTION_UNLOCKED"))
        NotificationUtil.createChannel(this, CHANNEL_ID, "PushUp Lock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra("targetPackage")
        val showCountdownOnly = intent?.getBooleanExtra("showCountdownOnly", false) ?: false

        startForeground(1, createNotification())

        if (intent?.action == ACTION_REMOVE_OVERLAY) {
            removeOverlay()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Show overlay unless remainingSeconds > 0 and showCountdownOnly true
        showOverlay(targetPackage ?: return START_NOT_STICKY, showCountdownOnly)
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PushUp Lock active")
            .setContentText("Monitoring locked apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .build()
    }

    private fun showOverlay(pkg: String, countdownOnly: Boolean) {
        removeOverlay()
        val locked = AppLockManager.getLocked(applicationContext, pkg) ?: return

        // If countdownOnly and there is remaining time, don't block
        if (locked.remainingSeconds > 0 && countdownOnly) return

        // permission check: must have Draw over other apps
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps' for PushUpLock in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_lock, null)
        overlayView = view

        // IMPORTANT: do NOT include FLAG_NOT_FOCUSABLE if you want the overlay to take input.
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_FULLSCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            // center and make sure overlay is on top
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // optionally set animations
        }

        val tv = view.findViewById<TextView>(R.id.lock_text)
        val startBtn = view.findViewById<Button>(R.id.start_pushups)
        val cancelBtn = view.findViewById<Button>(R.id.btn_close)
        tv.text = "This app is locked. Do push-ups to unlock."

        startBtn.setOnClickListener {
            val launch = Intent(this, PushUpActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("targetPackage", pkg)
            }
            startActivity(launch)
        }

        cancelBtn.setOnClickListener {
            // optional: allow temporarily backing out but keep overlay (or remove)
            // removeOverlay()
        }

        try {
            // Add view to the window manager; because the view is focusable it will block touches
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        removeOverlay()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
