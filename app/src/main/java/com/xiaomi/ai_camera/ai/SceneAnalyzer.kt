package com.xiaomi.ai_camera.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SceneAnalyzer {

    enum class SceneType(val label: String) {
        PORTRAIT("人像"), LANDSCAPE("风景"), FOOD("美食"), NIGHT("夜景"),
        MACRO("微距"), SPORT("运动"), DOCUMENT("文档"), INDOOR("室内"), UNKNOWN("自动")
    }

    data class SceneConfig(
        val sceneType: SceneType,
        val aperture: Float,
        val iso: Int,
        val exposureComp: Int,
        val saturation: Float,
        val description: String
    )

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )

    suspend fun analyzeScene(bitmap: Bitmap): SceneType = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        labeler.process(image)
            .addOnSuccessListener { labels ->
                val map = labels.associate { it.text.lowercase() to it.confidence }
                val scene = when {
                    map.keys.any { it in listOf("person", "face", "selfie", "portrait") } -> SceneType.PORTRAIT
                    map.keys.any { it in listOf("landscape", "mountain", "sky", "nature", "outdoor") } -> SceneType.LANDSCAPE
                    map.keys.any { it in listOf("food", "meal", "dish", "restaurant") } -> SceneType.FOOD
                    map.keys.any { it in listOf("night", "dark", "low light") } -> SceneType.NIGHT
                    map.keys.any { it in listOf("close-up", "macro", "flower", "insect") } -> SceneType.MACRO
                    map.keys.any { it in listOf("sport", "action", "running") } -> SceneType.SPORT
                    map.keys.any { it in listOf("document", "text", "paper", "book") } -> SceneType.DOCUMENT
                    map.keys.any { it in listOf("indoor", "room", "furniture") } -> SceneType.INDOOR
                    else -> SceneType.UNKNOWN
                }
                cont.resume(scene)
            }
            .addOnFailureListener { cont.resume(SceneType.UNKNOWN) }
    }

    fun getSceneConfig(scene: SceneType): SceneConfig = when (scene) {
        SceneType.PORTRAIT -> SceneConfig(scene, 1.63f, 100, 0, 1.1f, "人像：大光圈虚化背景")
        SceneType.LANDSCAPE -> SceneConfig(scene, 4.0f, 100, 0, 1.2f, "风景：小光圈捕捉细节")
        SceneType.FOOD -> SceneConfig(scene, 2.0f, 200, 1, 1.3f, "美食：暖色调增强食欲")
        SceneType.NIGHT -> SceneConfig(scene, 1.63f, 3200, -1, 0.9f, "夜景：高ISO捕捉细节")
        SceneType.MACRO -> SceneConfig(scene, 2.8f, 200, 0, 1.1f, "微距：近距离拍摄")
        SceneType.SPORT -> SceneConfig(scene, 2.0f, 800, 0, 1.0f, "运动：高速快门")
        SceneType.DOCUMENT -> SceneConfig(scene, 4.0f, 100, 0, 0.8f, "文档：清晰锐利")
        SceneType.INDOOR -> SceneConfig(scene, 2.0f, 800, 0, 1.0f, "室内：平衡光线")
        SceneType.UNKNOWN -> SceneConfig(scene, 2.8f, 400, 0, 1.0f, "自动模式")
    }

    fun close() {
        try { labeler.close() } catch (_: Exception) {}
    }
}
