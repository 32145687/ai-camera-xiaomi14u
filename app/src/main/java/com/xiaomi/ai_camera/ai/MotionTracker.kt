package com.xiaomi.ai_camera.ai

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

class MotionTracker {

    data class MotionState(
        val isStable: Boolean,
        val velocity: PointF,
        val stabilityScore: Int
    )

    private val frameHistory = mutableListOf<FrameData>()
    private val maxHistorySize = 10
    private val velocityThreshold = 10f

    data class FrameData(val timestamp: Long, val brightness: Float, val motionVector: PointF)

    fun analyzeFrame(currentFrame: Bitmap, previousFrame: Bitmap?): MotionState {
        if (previousFrame == null) return MotionState(true, PointF(0f, 0f), 100)

        val motionVector = calculateMotionVector(currentFrame, previousFrame)
        frameHistory.add(FrameData(System.currentTimeMillis(), calculateBrightness(currentFrame), motionVector))
        if (frameHistory.size > maxHistorySize) frameHistory.removeAt(0)

        return calculateMotionState()
    }

    private fun calculateMotionVector(current: Bitmap, previous: Bitmap): PointF {
        val diff = calculateBrightness(current) - calculateBrightness(previous)
        return PointF(diff * 10, diff * 5)
    }

    private fun calculateBrightness(bitmap: Bitmap): Float {
        var total = 0L
        var count = 0
        for (x in 0 until bitmap.width step 10) {
            for (y in 0 until bitmap.height step 10) {
                val p = bitmap.getPixel(x, y)
                total += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                count++
            }
        }
        return if (count > 0) total.toFloat() / count else 0f
    }

    private fun calculateMotionState(): MotionState {
        if (frameHistory.size < 2) return MotionState(true, PointF(0f, 0f), 100)
        val avgV = PointF(
            frameHistory.map { it.motionVector.x }.average().toFloat(),
            frameHistory.map { it.motionVector.y }.average().toFloat()
        )
        val speed = sqrt(avgV.x * avgV.x + avgV.y * avgV.y)
        val score = when {
            speed < 5 -> 100
            speed < 10 -> 70
            speed < 20 -> 40
            else -> 10
        }
        return MotionState(speed < velocityThreshold, avgV, score)
    }

    fun isReadyToCapture(): Boolean {
        if (frameHistory.size < 3) return false
        return frameHistory.takeLast(3).all {
            sqrt(it.motionVector.x * it.motionVector.x + it.motionVector.y * it.motionVector.y) < velocityThreshold
        }
    }

    fun reset() { frameHistory.clear() }
}
