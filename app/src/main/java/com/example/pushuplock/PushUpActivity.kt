package com.example.pushuplock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

import java.util.concurrent.Executors

class PushUpActivity : AppCompatActivity() {
    private val TAG = "PushUpActivity"
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var tvReps: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var detector: PoseDetector

    private var baselineDiff = Float.NaN
    private var state = "up"
    private var lastDownTime = 0L
    private var repCount = 0

    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup)
        previewView = findViewById(R.id.previewView)
        tvReps = findViewById(R.id.tvReps)
        targetPackage = intent.getStringExtra("targetPackage")

        // Use default PoseDetectorOptions with STREAM_MODE for live detection
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()

        detector = PoseDetection.getClient(options)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(inputImage)
                .addOnSuccessListener { pose ->
                    analyzePose(pose)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Pose detection failure", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun analyzePose(pose: Pose) {
        val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

        if (lShoulder == null || rShoulder == null || lHip == null || rHip == null) {
            return
        }

        val shoulderY = (lShoulder.position.y + rShoulder.position.y) / 2f
        val hipY = (lHip.position.y + rHip.position.y) / 2f
        val diff = shoulderY - hipY

        if (baselineDiff.isNaN()) {
            baselineDiff = diff
            return
        }

        val downThreshold = baselineDiff + 60f
        val upThreshold = baselineDiff + 20f
        val now = System.currentTimeMillis()

        if (state == "up" && diff > downThreshold) {
            state = "down"
            lastDownTime = now
        } else if (state == "down" && diff < upThreshold && now - lastDownTime > 400) {
            repCount++
            runOnUiThread {
                tvReps.text = "Reps: $repCount"
            }
            state = "up"

            // Grant time based on locked config for target package
            val locked = AppLockManager.getLocked(this, targetPackage ?: "")
            val minutes = locked?.minutesPerRep ?: 10
            val grantedSeconds = minutes * 60L
            AppLockManager.grantSeconds(this, targetPackage ?: "", grantedSeconds)

            // Broadcast to overlay service to unlock
            val intent = Intent("com.example.pushuplock.ACTION_UNLOCKED").apply {
                putExtra("targetPackage", targetPackage)
                putExtra("grantedSeconds", grantedSeconds)
            }
            sendBroadcast(intent)

            finish() // finish after first rep to return to unlocked app
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.close()
        cameraExecutor.shutdown()
    }
}
