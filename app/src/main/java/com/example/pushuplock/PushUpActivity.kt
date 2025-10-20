package com.example.pushuplock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlin.math.*

class PushUpActivity : AppCompatActivity() {

    private val CALIB_MS = 900L          // time to learn your TOP pose
    private val HOLD_MS = 180L           // minimum bottom hold
    private val READY_THRESH = 0.92f     // readiness gate (0..1) to start
    private val PUSH_THRESH = 0.65       // push probability to accept DOWN
    private val ALPHA = 0.28f             // smoothing (higher = snappier)

    private val DOWN_FRACTION = 0.13f    // how far nose must go down from TOP
    private val UP_FRACTION   = 0.05f    // how close back to TOP to finish rep

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var tvReps: TextView
    private lateinit var tvEarned: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvDebug: TextView

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var detector: PoseDetector
    private var camera: Camera? = null

    // ---------- App lock plumbing ----------
    private var targetPackage: String? = null

    private enum class Phase { CALIB, WAIT_READY, UP, DOWN }
    private var phase = Phase.CALIB
    private var reps = 0
    private var secondsGranted = 0

    // ---------- Geometry / thresholds ----------
    private var imgW = 0; private var imgH = 0
    private var downDeltaPx = 0f
    private var upDeltaPx = 0f

    // ---------- Calibration / smoothing ----------
    private var t0 = 0L
    private var topNoseY = Float.NaN
    private var sNoseY = Float.NaN
    private var lastTs = 0L
    private var bottomTs = 0L
    private var sElbowAngle = 170.0

    private val camPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else finish()
        }

    private fun now() = System.currentTimeMillis()
    private fun vib(ms: Int = 25) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
        } catch (_: Exception) {}
    }
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.poseOverlay)
        tvReps = findViewById(R.id.tvReps)
        tvEarned = findViewById(R.id.tvEarned)
        tvHint = findViewById(R.id.tvHint)
        tvDebug = findViewById(R.id.tvDebug)

        targetPackage = intent.getStringExtra(LockOverlayService.EXTRA_TARGET_PACKAGE)
            ?: intent.getStringExtra("targetPackage")

        ContextCompat.startForegroundService(
            this,
            Intent(this, LockOverlayService::class.java).apply {
                action = LockOverlayService.ACTION_REMOVE_OVERLAY
                putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, targetPackage)
            }
        )

        val opts = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        detector = PoseDetection.getClient(opts)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else camPerm.launch(Manifest.permission.CAMERA)

        tvReps.text = "Reps: 0"
        tvEarned.text = "Earned: 0m"
        tvHint.text = "Hold top position to calibrate…"
        t0 = now()
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(exec) { px -> analyze(px) }
                }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(px: ImageProxy) {
        imgW = px.width; imgH = px.height
        if (downDeltaPx == 0f) {
            downDeltaPx = imgH * DOWN_FRACTION
            upDeltaPx   = imgH * UP_FRACTION
        }

        val media = px.image ?: run { px.close(); return }
        val input = InputImage.fromMediaImage(media, px.imageInfo.rotationDegrees)

        detector.process(input)
            .addOnSuccessListener { pose -> onPose(pose) }
            .addOnFailureListener { e -> Log.e("PushUp", "pose fail", e) }
            .addOnCompleteListener { px.close() }
    }

    // ===================== Pose logic =====================

    private fun onPose(pose: Pose) {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE) ?: run {
            overlay.update(null, 0f, calibrated = (phase != Phase.CALIB), status = "No face", statusColor = Color.RED)
            return
        }

        // elbows (optional but helpful)
        val Ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val Le = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val Lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val Rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val Re = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val Rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftOK = Ls != null && Le != null && Lw != null
        val rightOK = Rs != null && Re != null && Rw != null
        val angL = if (leftOK) elbowAngle(Ls!!, Le!!, Lw!!) else null
        val angR = if (rightOK) elbowAngle(Rs!!, Re!!, Rw!!) else null
        val elbowAngle = when {
            angL != null && angR != null -> max(angL, angR)
            angL != null -> angL
            angR != null -> angR
            else -> 170.0
        }

        val y = nose.position.y
        if (sNoseY.isNaN()) sNoseY = y
        sNoseY = (ALPHA * y + (1f - ALPHA) * sNoseY)

        sElbowAngle = ALPHA * elbowAngle + (1f - ALPHA) * sElbowAngle


        val t = now()
        if (lastTs == 0L) lastTs = t
        val dt = (t - lastTs).coerceAtLeast(16)
        lastTs = t

        when (phase) {
            Phase.CALIB -> {
                if (topNoseY.isNaN()) topNoseY = sNoseY
                topNoseY = lerp(topNoseY.toDouble(), sNoseY.toDouble(), 0.10).toFloat()
                val left = (CALIB_MS - (t - t0)).coerceAtLeast(0)
                tvHint.text = "Calibrating… ${left}ms"
                overlay.update(pose, readiness = 0f, calibrated = false, status = "Calibrating", statusColor = Color.YELLOW)
                if (t - t0 >= CALIB_MS) {
                    phase = Phase.WAIT_READY
                    tvHint.text = "Align until bar is GREEN, then go"
                    vib(15)
                }
                return
            }
            else -> { /* continue */ }
        }

        // readiness score: near-top + elbows mostly straight + face seen
        val straightAmt = ((sElbowAngle - 145.0) / 20.0).coerceIn(0.0, 1.0)               // 0..1
        val topAmt = (1.0 - (abs((sNoseY - topNoseY).toDouble()) / (upDeltaPx * 5))).coerceIn(0.0, 1.0)
        val handsOK = if (leftOK || rightOK) 1.0 else 0.6
        val readiness = (0.5 * topAmt + 0.3 * straightAmt + 0.2 * handsOK).toFloat()

        // push probability: down amount + elbow bend + downward velocity
        val downAmt = ((sNoseY - topNoseY) / max(1f, downDeltaPx)).toDouble().coerceIn(0.0, 2.0) // 0..2
        val bendAmt = ((170.0 - sElbowAngle) / 50.0).coerceIn(0.0, 1.0)
        val vDown = (sNoseY - topNoseY) / dt.toDouble() // >0 means moving down
        val velAmt = ((vDown - 0.001) / 0.004).coerceIn(0.0, 1.0)
        val pushProb = (0.5 * downAmt + 0.3 * bendAmt + 0.2 * velAmt).coerceIn(0.0, 1.0)

        val status = if (pushProb >= PUSH_THRESH) "Pushing (%.2f)".format(pushProb)
        else "Not pushing (%.2f)".format(pushProb)
        val statusColor = if (pushProb >= PUSH_THRESH) Color.GREEN else Color.RED
        val calibratedFlag = phase != Phase.CALIB

        overlay.update(
            pose = pose,
            readiness = readiness,
            calibrated = calibratedFlag,
            status = status,
            statusColor = statusColor
        )

        when (phase) {
            Phase.WAIT_READY -> {
                if (readiness >= READY_THRESH) {
                    phase = Phase.UP
                    tvHint.text = "Ready ✓  Go DOWN"
                    vib(15)
                }
            }

            Phase.UP -> {
                val movedDown = (sNoseY - topNoseY) > downDeltaPx
                if (movedDown && pushProb >= PUSH_THRESH) {
                    phase = Phase.DOWN
                    bottomTs = t
                    tvHint.text = "Hold…"
                }
            }

            Phase.DOWN -> {
                val held = (t - bottomTs) >= HOLD_MS
                val nearTop = (topNoseY - sNoseY) > -upDeltaPx  // sNoseY <= top + upDelta
                if (held && nearTop) {
                    onRep()
                    topNoseY = lerp(topNoseY.toDouble(), sNoseY.toDouble(), 0.15).toFloat()
                    phase = Phase.UP
                    tvHint.text = "Up ✓  Go again"
                }
            }

            else -> Unit
        }

        tvDebug.text = "state=$phase  Δdown=%.0f  downPx=%.0f  upPx=%.0f  angle=%.1f  P=%.2f  R=%.2f"
            .format((sNoseY - topNoseY), downDeltaPx, upDeltaPx, sElbowAngle, pushProb, readiness)
    }

    private fun onRep() {
        reps++
        vib()
        tvReps.text = "Reps: $reps"

        val pkg = targetPackage ?: return
        val minPerRep = AppLockManager.getLocked(this, pkg)?.minutesPerRep ?: 10
        val grant = minPerRep * 60
        secondsGranted += grant
        tvEarned.text = "Earned: ${secondsGranted / 60}m"

        // tell overlay to add time
        sendBroadcast(Intent(LockOverlayService.ACTION_GRANT_TIME).apply {
            putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, pkg)
            putExtra("grantSeconds", grant)
        })
    }


    private fun elbowAngle(s: PoseLandmark, e: PoseLandmark, w: PoseLandmark): Double {
        val ax = s.position.x - e.position.x
        val ay = s.position.y - e.position.y
        val bx = w.position.x - e.position.x
        val by = w.position.y - e.position.y
        val dot = (ax * bx + ay * by)
        val magA = sqrt((ax * ax + ay * ay).toDouble()).coerceAtLeast(1e-6)
        val magB = sqrt((bx * bx + by * by).toDouble()).coerceAtLeast(1e-6)
        val cos = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }


    override fun onDestroy() {
        super.onDestroy()
        try { detector.close() } catch (_: Exception) {}
        exec.shutdown()
    }
}
