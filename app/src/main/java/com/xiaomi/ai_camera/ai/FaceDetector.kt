package com.xiaomi.ai_camera.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

class FaceDetector {

    data class FaceResult(
        val bounds: Rect,
        val smilingProbability: Float,
        val leftEyeOpenProbability: Float,
        val rightEyeOpenProbability: Float,
        val headEulerAngleX: Float,
        val headEulerAngleY: Float,
        val trackingId: Int
    )

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(options)

    suspend fun detectFaces(bitmap: Bitmap): List<FaceResult> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                cont.resume(faces.map { face ->
                    FaceResult(
                        bounds = face.boundingBox,
                        smilingProbability = face.smilingProbability ?: 0f,
                        leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                        headEulerAngleX = face.headEulerAngleX,
                        headEulerAngleY = face.headEulerAngleY,
                        trackingId = face.trackingId ?: -1
                    )
                })
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    fun hasSmile(faces: List<FaceResult>, threshold: Float = 0.7f): Boolean =
        faces.any { it.smilingProbability > threshold }

    fun allEyesOpen(faces: List<FaceResult>, threshold: Float = 0.5f): Boolean =
        faces.all { it.leftEyeOpenProbability > threshold && it.rightEyeOpenProbability > threshold }

    fun allLookingAtCamera(faces: List<FaceResult>, tolerance: Float = 15f): Boolean =
        faces.all { abs(it.headEulerAngleY) < tolerance && abs(it.headEulerAngleX) < tolerance }

    fun getBestShotScore(faces: List<FaceResult>): Int {
        if (faces.isEmpty()) return 50
        val smile = (faces.maxOfOrNull { it.smilingProbability } ?: 0f) * 40
        val eyes = faces.map { (it.leftEyeOpenProbability + it.rightEyeOpenProbability) / 2 }.average().toFloat() * 30
        val looking = faces.count { abs(it.headEulerAngleY) < 15 }.toFloat() / faces.size * 30
        return (smile + eyes + looking).toInt().coerceIn(0, 100)
    }

    fun isGoodMoment(faces: List<FaceResult>): Boolean = getBestShotScore(faces) > 70
}
