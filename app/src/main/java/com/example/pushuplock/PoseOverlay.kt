package com.example.pushuplock

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pose: Pose? = null
    private var readiness: Float = 0f
    private var calibrated: Boolean = false
    private var statusText: String = ""
    private var statusColor: Int = Color.GREEN

    private val paintBone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.RED
    }
    private val paintJoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val paintId = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val paintHUD = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        color = Color.WHITE
    }
    private val paintBarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
    }
    private val paintBarFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.YELLOW
        pathEffect = DashPathEffect(floatArrayOf(16f, 16f), 0f)
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }


    fun update(
        pose: Pose?,
        readiness: Float,
        calibrated: Boolean,
        status: String = "",
        statusColor: Int = Color.GREEN
    ) {
        this.pose = pose
        this.readiness = readiness.coerceIn(0f, 1f)
        this.calibrated = calibrated
        this.statusText = status
        this.statusColor = statusColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barW = width * 0.6f
        val barH = 18f
        val barX = (width - barW) / 2f
        val barY = 24f
        canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, 9f, 9f, paintBarBg)

        val fillColor = when {
            readiness < 0.33f -> Color.RED
            readiness < 0.66f -> 0xFFFFC107.toInt()
            else -> 0xFF4CAF50.toInt()
        }
        paintBarFill.color = fillColor
        canvas.drawRoundRect(
            barX, barY, barX + barW * readiness, barY + barH, 9f, 9f, paintBarFill
        )

        paintHUD.color = Color.WHITE
        val readinessLabel = when {
            !calibrated -> "CALIBRATING…"
            readiness < 1f -> "ALIGN YOUR FORM"
            else -> "READY ✓"
        }
        canvas.drawText(readinessLabel, barX, barY + barH + 34f, paintHUD)

        if (statusText.isNotEmpty()) {
            statusPaint.color = statusColor
            val pad = 16f
            val textW = statusPaint.measureText(statusText)
            canvas.drawRoundRect(pad, pad + 64f, pad + textW + 28f, pad + 64f + 56f, 12f, 12f, chipBg)
            canvas.drawText(statusText, pad + 14f, pad + 64f + 40f, statusPaint)
        }

        val p = pose ?: return

        fun L(id: Int) = p.getPoseLandmark(id)?.position
        fun connect(a: PointF?, b: PointF?) {
            if (a == null || b == null) return
            canvas.drawLine(a.x, a.y, b.x, b.y, paintBone)
        }
        fun dot(id: Int, pt: PointF?) {
            if (pt == null) return
            canvas.drawCircle(pt.x, pt.y, 8f, paintJoint)
            canvas.drawText(id.toString(), pt.x + 10f, pt.y - 10f, paintId)
        }

        val lw = L(PoseLandmark.LEFT_WRIST)
        val le = L(PoseLandmark.LEFT_ELBOW)
        val ls = L(PoseLandmark.LEFT_SHOULDER)
        val nose = L(PoseLandmark.NOSE)
        val rs = L(PoseLandmark.RIGHT_SHOULDER)
        val re = L(PoseLandmark.RIGHT_ELBOW)
        val rw = L(PoseLandmark.RIGHT_WRIST)

        connect(lw, le); connect(le, ls)
        connect(ls, nose); connect(nose, rs)
        connect(rs, re); connect(re, rw)

        dot(1, lw); dot(2, le); dot(3, nose); dot(4, rs); dot(5, re); dot(6, rw)

        if (calibrated && ls != null && rs != null) {
            val y = (ls.y + rs.y) / 2f
            canvas.drawLine(32f, y, width - 32f, y, gatePaint)
        }
    }
}
