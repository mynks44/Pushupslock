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

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return kotlin.math.sqrt(dx*dx + dy*dy)
}
private fun dist(a: PoseLandmark?, b: PoseLandmark?): Float {
    if (a == null || b == null) return 0f
    return dist(a.position.x, a.position.y, b.position.x, b.position.y)
}


class PushUpActivity : AppCompatActivity() {

    // ===== Tunables (safe defaults) =====
// ===== Tunables (distance-agnostic) =====
    private val CALIB_TOP_MS = 900L
    private val HOLD_BOTTOM_MS = 180L
    private val EMA_ALPHA_POS = 0.28f
    private val EMA_ALPHA_ANG = 0.28f

    // readiness / gating (more tolerant but robust)
    private val READY_THRESH = 0.70f
    private val MIN_TORSO_ANGLE = 110.0
    private val PUSH_PROB_THRESH = 0.50

// down = how far nose should travel relative to shoulder width
// up   = hysteresis band
    private val K_DOWN_FROM_SHOULDER = 2.1f   // ~2.1 × shoulder width
    private val K_UP_FROM_SHOULDER   = 0.55f  // ~0.55 × shoulder width

    // clamps (so thresholds never become crazy)
    private val MIN_DOWN_PX = 12f
    private val MAX_DOWN_FRAC_OF_IMG = 0.30f
    private val MIN_UP_PX = 8f
    private val MAX_UP_FRAC_OF_IMG = 0.15f
       // back-up threshold (of height)

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var tvReps: TextView
    private lateinit var tvEarned: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvHud: TextView

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var detector: PoseDetector

    private var targetPackage: String? = null

    private enum class Phase { CALIB_TOP, WAIT_READY, UP, DESCENT, BOTTOM_HOLD, ASCENT }
    private var phase = Phase.CALIB_TOP
    private var reps = 0
    private var secondsGranted = 0

    // ===== Geometry =====
    private var imgH = 0
    private var downPx = 0f
    private var upPx = 0f
    private var topNoseY = Float.NaN
    private var sNoseY = Float.NaN
    private var sElbowAngle = 170.0
    private var sTorsoAngle = 170.0 // angle between shoulder–hip

    private var t0 = 0L
    private var lastTs = 0L
    private var bottomTs = 0L

    private var learnedBottomDelta = Float.NaN   // how far down from top was the first true bottom
    private val BOTTOM_ADAPT_RATE = 0.10f        // slow adapt to new bottoms

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
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
    private fun clamp01(x: Double) = x.coerceIn(0.0, 1.0)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.poseOverlay)
        tvReps = findViewById(R.id.tvReps)
        tvEarned = findViewById(R.id.tvEarned)
        tvHint = findViewById(R.id.tvHint)
        tvDebug = findViewById(R.id.tvDebug)
        tvHud = findViewById(R.id.tvHud)

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
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else camPerm.launch(Manifest.permission.CAMERA)

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
                .build().also { ia ->
                    ia.setAnalyzer(exec) { px -> analyze(px) }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(px: ImageProxy) {
        imgH = px.height
//        if (downPx == 0f) {
//            downPx = imgH * DOWN_FRACTION
//            upPx   = imgH * UP_FRACTION
//        }

        val media = px.image ?: run { px.close(); return }
        val input = InputImage.fromMediaImage(media, px.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { pose -> onPose(pose) }
            .addOnFailureListener { e -> Log.e("PushUp", "pose fail", e) }
            .addOnCompleteListener { px.close() }
    }


    private fun onPose(pose: Pose) {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE) ?: run {
            overlay.update(null, 0f, calibrated = (phase != Phase.CALIB_TOP), status = "No face", statusColor = Color.RED)
            tvHud.text = "face:0 | elbows:0 | torso:- | depth:- | vel:- | P:- | state:$phase | reps:$reps"
            return
        }

        val Ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val Le = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val Lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val Rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val Re = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val Rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val Lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val Rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

        val elbowsOK = (Ls!=null && Le!=null && Lw!=null) || (Rs!=null && Re!=null && Rw!=null)
        val torsoOK  = (Ls!=null && Lh!=null) && (Rs!=null && Rh!=null)

        fun d(a: PoseLandmark?, b: PoseLandmark?): Float {
            if (a == null || b == null) return 0f
            val dx = a.position.x - b.position.x
            val dy = a.position.y - b.position.y
            return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }

        var scale = d(Ls, Rs)
        if (scale <= 0f) {
            val sh = d(Ls, Lh)
            val rh = d(Rs, Rh)
            scale = if (sh > 0f && rh > 0f) (sh + rh) * 0.5f else 0f
        }

        val K_DOWN_FROM_SHOULDER = 2.1f     // how far to travel down relative to shoulder width
        val K_UP_FROM_SHOULDER   = 0.55f    // hysteresis band near top
        val MIN_DOWN_PX = 12f
        val MIN_UP_PX   = 8f
        val MAX_DOWN_FRAC_OF_IMG = 0.30f
        val MAX_UP_FRAC_OF_IMG   = 0.15f

        if (imgH == 0) imgH = previewView.height

        if (scale > 0f && imgH > 0) {
            val imgCapDown = imgH * MAX_DOWN_FRAC_OF_IMG
            val imgCapUp   = imgH * MAX_UP_FRAC_OF_IMG

            var downTarget = K_DOWN_FROM_SHOULDER * scale
            var upTarget   = K_UP_FROM_SHOULDER   * scale

            downTarget = downTarget.coerceAtLeast(MIN_DOWN_PX).coerceAtMost(imgCapDown)
            upTarget   = upTarget.coerceAtLeast(MIN_UP_PX).coerceAtMost(imgCapUp)

            if (downPx == 0f) {
                downPx = downTarget
                upPx = upTarget
            } else {
                downPx = 0.20f * downTarget + 0.80f * downPx
                upPx   = 0.20f * upTarget   + 0.80f * upPx
            }
        }

        val elbowAng = max(
            elbowsOK.let { if (Ls!=null && Le!=null && Lw!=null) elbowAngle(Ls,Le,Lw) else 0.0 },
            elbowsOK.let { if (Rs!=null && Re!=null && Rw!=null) elbowAngle(Rs,Re,Rw) else 0.0 }
        ).let { if (it==0.0) 170.0 else it }

        val torsoAng = if (torsoOK) {
            val a = if (Ls!=null && Lh!=null) shoulderHipAngle(Ls, Lh) else 180.0
            val b = if (Rs!=null && Rh!=null) shoulderHipAngle(Rs, Rh) else 180.0
            max(a, b)
        } else 180.0

        val y = nose.position.y
        if (sNoseY.isNaN()) sNoseY = y
        sNoseY      = EMA_ALPHA_POS * y + (1f - EMA_ALPHA_POS) * sNoseY
        sElbowAngle = EMA_ALPHA_ANG * elbowAng + (1f - EMA_ALPHA_ANG) * sElbowAngle
        sTorsoAngle = EMA_ALPHA_ANG * torsoAng + (1f - EMA_ALPHA_ANG) * sTorsoAngle

        val t = now()
        if (lastTs == 0L) lastTs = t
        val dt = (t - lastTs).coerceAtLeast(16)
        lastTs = t

        val top = if (topNoseY.isNaN()) sNoseY else topNoseY
        val relY = sNoseY - top
        val vDown = relY / dt.toDouble()

        val downAmt = clamp01(relY / max(1f, downPx).toDouble())
        val bendAmt = clamp01((170.0 - sElbowAngle) / 50.0)
        val velAmt  = clamp01((vDown - 0.001) / 0.004)
        val pushProb = (0.5 * downAmt + 0.3 * bendAmt + 0.2 * velAmt)

        val nearTop       = clamp01(1.0 - abs(relY / (upPx * 5)))
        val straightArms  = clamp01((sElbowAngle - 145.0) / 20.0)
        val straightTorso = clamp01((sTorsoAngle - MIN_TORSO_ANGLE) / (180.0 - MIN_TORSO_ANGLE))
        val readiness     = (0.6 * nearTop + 0.3 * straightArms + 0.1 * straightTorso).toFloat()

        // overlay
        val label = if (pushProb >= PUSH_PROB_THRESH)
            "Pushing (%.2f)".format(pushProb)
        else
            "Not pushing (%.2f)".format(pushProb)

        overlay.update(
            pose = pose,
            readiness = readiness,
            calibrated = (phase != Phase.CALIB_TOP),
            status = label,
            statusColor = if (pushProb >= PUSH_PROB_THRESH) Color.GREEN else Color.RED
        )

        // HUD
        tvHud.text = "face:1 | elbows:${if (elbowsOK)1 else 0} | torso:${"%.0f".format(sTorsoAngle)} " +
                "| depth:${"%.0f".format(relY)} | vel:${"%.3f".format(vDown)} | P:${"%.2f".format(pushProb)} " +
                "| state:$phase | reps:$reps"

        when (phase) {
            Phase.CALIB_TOP -> {
                if (topNoseY.isNaN()) topNoseY = sNoseY
                topNoseY = (0.10 * sNoseY + 0.90 * topNoseY).toFloat()
                val left = (CALIB_TOP_MS - (t - t0)).coerceAtLeast(0)
                tvHint.text = "Calibrating… ${left}ms"
                if (t - t0 >= CALIB_TOP_MS) {
                    phase = Phase.WAIT_READY
                    tvHint.text = "Align until bar is GREEN, then go"
                    vib(15)
                }
                return
            }

            Phase.WAIT_READY -> {
                val pass = readiness >= 0.70f
                val fallback = (sElbowAngle >= 165.0 && abs(relY) <= upPx * 3)
                if (pass || fallback) {
                    phase = Phase.UP
                    tvHint.text = "Ready ✓  Go DOWN"
                    vib(15)
                }
            }

            Phase.UP -> {
                val movedDown = relY > downPx * 0.9f
                val torsoOk = sTorsoAngle >= MIN_TORSO_ANGLE - 10.0
                if ((movedDown && pushProb >= PUSH_PROB_THRESH && torsoOk) || relY > downPx * 1.2f) {
                    phase = Phase.DESCENT
                    bottomTs = t
                }
            }

            Phase.DESCENT -> {
                val bottomLikely = vDown < 0.0005 || relY > downPx * 1.25f
                if (bottomLikely) {
                    phase = Phase.BOTTOM_HOLD
                    bottomTs = t
                    val targetBottom = max(relY, downPx)
                    learnedBottomDelta = if (learnedBottomDelta.isNaN())
                        targetBottom
                    else
                        0.90f * learnedBottomDelta + 0.10f * targetBottom
                }
            }

            Phase.BOTTOM_HOLD -> {
                if (t - bottomTs >= HOLD_BOTTOM_MS) {
                    phase = Phase.ASCENT
                }
            }

            Phase.ASCENT -> {
                val backNearTop = relY <= upPx
                if (backNearTop) {
                    if (!learnedBottomDelta.isNaN()) {
                        downPx = 0.80f * downPx + 0.20f * learnedBottomDelta
                    }
                    onRep()
                    phase = Phase.UP
                    tvHint.text = "Up ✓  Go again"
                }
            }
        }

        tvDebug.text = "state=$phase Δdown=${"%.0f".format(sNoseY - (if (topNoseY.isNaN()) sNoseY else topNoseY))} " +
                "downPx=${"%.0f".format(downPx)} upPx=${"%.0f".format(upPx)} " +
                "angE=${"%.1f".format(sElbowAngle)} angT=${"%.1f".format(sTorsoAngle)} " +
                "P=${"%.2f".format(pushProb)}"
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

        // grant time to overlay
        sendBroadcast(Intent(LockOverlayService.ACTION_GRANT_TIME).apply {
            putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, pkg)
            putExtra("grantSeconds", grant)
        })

        // Want instant unlock on first rep? Uncomment:
        // finish()
    }

    private fun elbowAngle(s: PoseLandmark, e: PoseLandmark, w: PoseLandmark): Double {
        val ax = s.position.x - e.position.x
        val ay = s.position.y - e.position.y
        val bx = w.position.x - e.position.x
        val by = w.position.y - e.position.y
        val dot = ax*bx + ay*by
        val magA = sqrt((ax*ax + ay*ay).toDouble()).coerceAtLeast(1e-6)
        val magB = sqrt((bx*bx + by*by).toDouble()).coerceAtLeast(1e-6)
        val cos = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private fun shoulderHipAngle(s: PoseLandmark, h: PoseLandmark): Double {
        val dx = (h.position.x - s.position.x).toDouble()
        val dy = (h.position.y - s.position.y).toDouble()
        val theta = Math.toDegrees(atan2(dy, dx))
        return 180.0 - abs(180.0 - abs(theta))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { detector.close() } catch (_: Exception) {}
        exec.shutdown()
    }
}
