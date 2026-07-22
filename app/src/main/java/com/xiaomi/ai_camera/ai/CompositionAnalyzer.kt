package com.xiaomi.ai_camera.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * 高级AI构图分析器
 * 使用深度学习模型实时分析取景画面，评分当前构图，推荐最佳裁剪区域和拍摄角度
 */
class CompositionAnalyzer(private val context: Context) {

    // 构图评分结果
    data class CompositionScore(
        val overallScore: Float,        // 总体评分 0-100
        val ruleOfThirdsScore: Float,   // 三分法评分
        val symmetryScore: Float,       // 对称性评分
        val balanceScore: Float,        // 平衡性评分
        val leadingLinesScore: Float,   // 引导线评分
        val depthScore: Float,          // 深度感评分
        val colorHarmonyScore: Float,   // 色彩和谐评分
        val subjectScore: Float,        // 主体突出度评分
        val level: CompositionLevel     // 构图等级
    )

    // 构图等级
    enum class CompositionLevel(val label: String, val emoji: String) {
        EXCELLENT("优秀", "S"),
        GOOD("良好", "A"),
        AVERAGE("一般", "B"),
        BELOW_AVERAGE("较差", "C"),
        POOR("差", "D")
    }

    // 裁剪建议
    data class CropRecommendation(
        val cropRect: RectF,            // 推荐裁剪区域 (归一化坐标 0-1)
        val aspectRatio: Float,         // 推荐宽高比
        val reason: String,             // 建议原因
        val improvedScore: Float        // 裁剪后预期评分
    )

    // 拍摄角度建议
    data class AngleRecommendation(
        val pitchAngle: Float,          // 俯仰角建议 (度)
        val rollAngle: Float,           // 横滚角建议 (度)
        val description: String,        // 描述
        val priority: Int               // 优先级 1-5
    )

    // 构图模式
    enum class CompositionPattern(val label: String) {
        RULE_OF_THIRDS("三分法"),
        GOLDEN_RATIO("黄金比例"),
        GOLDEN_SPIRAL("黄金螺旋"),
        DIAGONAL("对角线"),
        SYMMETRY("对称"),
        CENTER("中心"),
        LEADING_LINES("引导线"),
        FRAME_WITHIN_FRAME("框中框"),
        PATTERN("图案重复"),
        MINIMALIST("极简")
    }

    // TFLite解释器
    private var interpreter: Interpreter? = null
    private val modelInputSize = 224 // 模型输入尺寸

    // 分析参数
    private val analysisInterval = 100L // 分析间隔(ms)
    private var lastAnalysisTime = 0L

    // 网格分析参数
    private val gridSize = 3 // 3x3网格
    private val edgeThreshold = 0.3f
    private val colorBins = 8 // 颜色直方图bin数

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "composition_model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            // 模型加载失败，使用基于规则的分析
            interpreter = null
        }
    }

    /**
     * 分析构图 - 主入口
     * 节流由调用方（CameraViewModel.quickAnalyze）控制
     */
    fun analyzeComposition(bitmap: Bitmap): CompositionScore {
        // 尝试使用深度学习模型
        val modelScore = analyzeWithModel(bitmap)
        if (modelScore != null) return modelScore

        // 回退到基于规则的分析
        return analyzeWithRules(bitmap)
    }

    /**
     * 使用深度学习模型分析
     */
    private fun analyzeWithModel(bitmap: Bitmap): CompositionScore? {
        val interp = interpreter ?: return null

        return try {
            // 预处理图片
            val resized = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
            val inputBuffer = preprocessImage(resized)

            // 准备输出缓冲区 - 8个评分维度
            val outputBuffer = Array(1) { FloatArray(8) }

            // 运行推理
            interp.run(inputBuffer, outputBuffer)

            val scores = outputBuffer[0]
            val overallScore = scores[0] * 100f
            val level = getLevel(overallScore)

            CompositionScore(
                overallScore = overallScore.coerceIn(0f, 100f),
                ruleOfThirdsScore = (scores[1] * 100f).coerceIn(0f, 100f),
                symmetryScore = (scores[2] * 100f).coerceIn(0f, 100f),
                balanceScore = (scores[3] * 100f).coerceIn(0f, 100f),
                leadingLinesScore = (scores[4] * 100f).coerceIn(0f, 100f),
                depthScore = (scores[5] * 100f).coerceIn(0f, 100f),
                colorHarmonyScore = (scores[6] * 100f).coerceIn(0f, 100f),
                subjectScore = (scores[7] * 100f).coerceIn(0f, 100f),
                level = level
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 预处理图片为模型输入格式
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * modelInputSize * modelInputSize * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(modelInputSize * modelInputSize)
        bitmap.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

        for (pixel in pixels) {
            // RGB归一化到[-1, 1]
            buffer.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1f)
            buffer.putFloat(((pixel shr 8 and 0xFF) / 127.5f) - 1f)
            buffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * 基于规则的构图分析
     */
    private fun analyzeWithRules(bitmap: Bitmap): CompositionScore {
        val width = bitmap.width
        val height = bitmap.height

        // 1. 三分法分析
        val thirdsScore = analyzeRuleOfThirds(bitmap)

        // 2. 对称性分析
        val symScore = analyzeSymmetry(bitmap)

        // 3. 平衡性分析
        val balScore = analyzeBalance(bitmap)

        // 4. 引导线分析
        val leadScore = analyzeLeadingLines(bitmap)

        // 5. 深度感分析
        val depthScore = analyzeDepth(bitmap)

        // 6. 色彩和谐分析
        val colorScore = analyzeColorHarmony(bitmap)

        // 7. 主体突出度分析
        val subjScore = analyzeSubjectEmphasis(bitmap)

        // 计算总分 (加权平均)
        val overall = (
            thirdsScore * 0.20f +
            symScore * 0.10f +
            balScore * 0.15f +
            leadScore * 0.15f +
            depthScore * 0.10f +
            colorScore * 0.15f +
            subjScore * 0.15f
        )

        return CompositionScore(
            overallScore = overall,
            ruleOfThirdsScore = thirdsScore,
            symmetryScore = symScore,
            balanceScore = balScore,
            leadingLinesScore = leadScore,
            depthScore = depthScore,
            colorHarmonyScore = colorScore,
            subjectScore = subjScore,
            level = getLevel(overall)
        )
    }

    /**
     * 三分法分析 - 检测主体是否在三分线交叉点附近
     */
    private fun analyzeRuleOfThirds(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8

        // 计算每个网格的视觉权重
        val weights = Array(gridSize) { FloatArray(gridSize) }
        var totalWeight = 0f

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = getBrightness(pixel)
                val contrast = abs(brightness - 0.5f) * 2
                weights[x * gridSize / w][y * gridSize / h] += contrast
                totalWeight += contrast
            }
        }

        // 归一化
        if (totalWeight > 0) {
            for (i in weights.indices)
                for (j in weights[i].indices)
                    weights[i][j] /= totalWeight
        }

        // 三分法交叉点权重
        val thirdPoints = listOf(
            Pair(1, 1), Pair(1, 2), Pair(2, 1), Pair(2, 2)
        )
        val thirdWeight = thirdPoints.sumOf { (x, y) -> weights[x][y].toDouble() }.toFloat()

        // 优秀构图时交叉点应有较高权重
        return (thirdWeight * 400).coerceIn(0f, 100f)
    }

    /**
     * 对称性分析
     */
    private fun analyzeSymmetry(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 4
        var horizontalDiff = 0f
        var verticalDiff = 0f
        var count = 0

        // 水平对称
        for (y in 0 until h step step) {
            for (x in 0 until w / 2 step step) {
                val left = getBrightness(bitmap.getPixel(x, y))
                val right = getBrightness(bitmap.getPixel(w - 1 - x, y))
                horizontalDiff += abs(left - right)
                count++
            }
        }

        // 垂直对称
        for (y in 0 until h / 2 step step) {
            for (x in 0 until w step step) {
                val top = getBrightness(bitmap.getPixel(x, y))
                val bottom = getBrightness(bitmap.getPixel(x, h - 1 - y))
                verticalDiff += abs(top - bottom)
            }
        }

        val avgHDiff = if (count > 0) horizontalDiff / count else 1f
        val avgVDiff = if (count > 0) verticalDiff / count else 1f

        // 差异越小，对称性越高
        val hSym = ((1f - avgHDiff) * 100).coerceIn(0f, 100f)
        val vSym = ((1f - avgVDiff) * 100).coerceIn(0f, 100f)

        return (hSym + vSym) / 2
    }

    /**
     * 平衡性分析 - 检测画面视觉重量分布
     */
    private fun analyzeBalance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8

        var leftWeight = 0f
        var rightWeight = 0f
        var topWeight = 0f
        var bottomWeight = 0f

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val pixel = bitmap.getPixel(x, y)
                val weight = getVisualWeight(pixel)

                if (x < w / 2) leftWeight += weight else rightWeight += weight
                if (y < h / 2) topWeight += weight else bottomWeight += weight
            }
        }

        val hBalance = 1f - abs(leftWeight - rightWeight) / maxOf(leftWeight + rightWeight, 1f)
        val vBalance = 1f - abs(topWeight - bottomWeight) / maxOf(topWeight + bottomWeight, 1f)

        return ((hBalance + vBalance) / 2 * 100).coerceIn(0f, 100f)
    }

    /**
     * 引导线分析 - 检测线条引导视觉焦点
     */
    private fun analyzeLeadingLines(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 4

        var horizontalEdges = 0
        var verticalEdges = 0
        var diagonalEdges = 0
        var totalPixels = 0

        for (y in step until h - step step step) {
            for (x in step until w - step step step) {
                val center = getBrightness(bitmap.getPixel(x, y))
                val left = getBrightness(bitmap.getPixel(x - step, y))
                val right = getBrightness(bitmap.getPixel(x + step, y))
                val top = getBrightness(bitmap.getPixel(x, y - step))
                val bottom = getBrightness(bitmap.getPixel(x, y + step))

                val hEdge = abs(left - right)
                val vEdge = abs(top - bottom)

                if (hEdge > edgeThreshold) horizontalEdges++
                if (vEdge > edgeThreshold) verticalEdges++
                if (hEdge > edgeThreshold && vEdge > edgeThreshold) diagonalEdges++

                totalPixels++
            }
        }

        val edgeRatio = (horizontalEdges + verticalEdges + diagonalEdges).toFloat() / maxOf(totalPixels, 1)
        val lineScore = when {
            edgeRatio > 0.1f && edgeRatio < 0.4f -> 80f // 适度的线条
            edgeRatio > 0.05f -> 60f
            else -> 40f
        }

        return lineScore
    }

    /**
     * 深度感分析
     */
    private fun analyzeDepth(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8

        // 简化：通过亮度渐变估算深度
        var topBrightness = 0f
        var bottomBrightness = 0f
        var count = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val brightness = getBrightness(bitmap.getPixel(x, y))
                if (y < h / 2) topBrightness += brightness else bottomBrightness += brightness
                count++
            }
        }

        val halfCount = count / 2
        topBrightness /= maxOf(halfCount, 1)
        bottomBrightness /= maxOf(halfCount, 1)

        // 天空通常较亮，地面较暗，有一定差异表示深度
        val depthDiff = abs(topBrightness - bottomBrightness)
        return (depthDiff * 200).coerceIn(0f, 100f)
    }

    /**
     * 色彩和谐分析
     */
    private fun analyzeColorHarmony(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8

        val hueHistogram = FloatArray(colorBins)
        var totalPixels = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val pixel = bitmap.getPixel(x, y)
                val hue = getHue(pixel)
                val bin = (hue / 360f * colorBins).toInt().coerceIn(0, colorBins - 1)
                hueHistogram[bin] = hueHistogram[bin] + 1f
                totalPixels++
            }
        }

        // 归一化
        for (i in hueHistogram.indices) {
            hueHistogram[i] = hueHistogram[i] / maxOf(totalPixels, 1)
        }

        // 计算色彩集中度（和谐的色彩通常集中在少数几个色相）
        val sortedBins = hueHistogram.sortedDescending()
        val top3Concentration = sortedBins.take(3).sum()

        // 互补色检测
        var complementaryScore = 0f
        for (i in 0 until colorBins / 2) {
            complementaryScore += minOf(hueHistogram[i], hueHistogram[i + colorBins / 2])
        }

        return ((top3Concentration * 60 + complementaryScore * 40) * 100).coerceIn(0f, 100f)
    }

    /**
     * 主体突出度分析
     */
    private fun analyzeSubjectEmphasis(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8

        // 计算中心区域与边缘区域的对比度差异
        var centerContrast = 0f
        var edgeContrast = 0f
        var centerCount = 0
        var edgeCount = 0

        for (y in step until h - step step step) {
            for (x in step until w - step step step) {
                val center = getBrightness(bitmap.getPixel(x, y))
                val neighbors = listOf(
                    getBrightness(bitmap.getPixel(x - step, y)),
                    getBrightness(bitmap.getPixel(x + step, y)),
                    getBrightness(bitmap.getPixel(x, y - step)),
                    getBrightness(bitmap.getPixel(x, y + step))
                )
                val contrast = neighbors.map { abs(it - center) }.average().toFloat()

                val isCenter = x > w * 0.25f && x < w * 0.75f && y > h * 0.25f && y < h * 0.75f
                if (isCenter) {
                    centerContrast += contrast
                    centerCount++
                } else {
                    edgeContrast += contrast
                    edgeCount++
                }
            }
        }

        val avgCenterContrast = if (centerCount > 0) centerContrast / centerCount else 0f
        val avgEdgeContrast = if (edgeCount > 0) edgeContrast / edgeCount else 0f

        // 中心对比度高于边缘表示主体突出
        val emphasis = if (avgEdgeContrast > 0) {
            (avgCenterContrast / avgEdgeContrast).coerceIn(0f, 2f) / 2f
        } else {
            0.5f
        }

        return (emphasis * 100).coerceIn(0f, 100f)
    }

    /**
     * 生成裁剪建议
     */
    fun generateCropRecommendations(bitmap: Bitmap, currentScore: CompositionScore): List<CropRecommendation> {
        val recommendations = mutableListOf<CropRecommendation>()

        // 1. 三分法裁剪
        if (currentScore.ruleOfThirdsScore < 70) {
            recommendations.add(CropRecommendation(
                cropRect = RectF(0.1f, 0.1f, 0.9f, 0.9f),
                aspectRatio = 4f / 3f,
                reason = "调整构图使主体位于三分线交叉点",
                improvedScore = (currentScore.overallScore + 15).coerceAtMost(100f)
            ))
        }

        // 2. 黄金比例裁剪
        val goldenRatio = 1.618f
        recommendations.add(CropRecommendation(
            cropRect = RectF(0.05f, 0.1f, 0.95f, 0.7f),
            aspectRatio = goldenRatio,
            reason = "黄金比例裁剪，提升视觉美感",
            improvedScore = (currentScore.overallScore + 10).coerceAtMost(100f)
        ))

        // 3. 正方形裁剪（适合社交媒体）
        val centerCrop = RectF(0.125f, 0.125f, 0.875f, 0.875f)
        recommendations.add(CropRecommendation(
            cropRect = centerCrop,
            aspectRatio = 1f,
            reason = "1:1裁剪，突出中心主体",
            improvedScore = (currentScore.overallScore + 5).coerceAtMost(100f)
        ))

        // 4. 16:9宽幅裁剪（适合风景）
        if (currentScore.balanceScore > 50) {
            recommendations.add(CropRecommendation(
                cropRect = RectF(0f, 0.15f, 1f, 0.65f),
                aspectRatio = 16f / 9f,
                reason = "宽幅裁剪，增强风景感",
                improvedScore = (currentScore.overallScore + 8).coerceAtMost(100f)
            ))
        }

        // 5. 根据主体位置智能裁剪
        val subjectRect = detectSubjectRegion(bitmap)
        if (subjectRect != null) {
            val expandBy = 0.1f
            val smartCrop = RectF(
                (subjectRect.left - expandBy).coerceIn(0f, 1f),
                (subjectRect.top - expandBy).coerceIn(0f, 1f),
                (subjectRect.right + expandBy).coerceIn(0f, 1f),
                (subjectRect.bottom + expandBy).coerceIn(0f, 1f)
            )
            recommendations.add(CropRecommendation(
                cropRect = smartCrop,
                aspectRatio = (smartCrop.width()) / (smartCrop.height()),
                reason = "智能裁剪，突出检测到的主体",
                improvedScore = (currentScore.overallScore + 20).coerceAtMost(100f)
            ))
        }

        return recommendations.sortedByDescending { it.improvedScore }.take(3)
    }

    /**
     * 检测主体区域
     */
    private fun detectSubjectRegion(bitmap: Bitmap): RectF? {
        val w = bitmap.width
        val h = bitmap.height
        val step = 8
        val gridSize = 8
        val weights = Array(gridSize) { FloatArray(gridSize) }

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val gx = (x * gridSize / w).coerceIn(0, gridSize - 1)
                val gy = (y * gridSize / h).coerceIn(0, gridSize - 1)
                val pixel = bitmap.getPixel(x, y)
                weights[gx][gy] += getVisualWeight(pixel)
            }
        }

        // 找到权重最高的区域
        var maxWeight = 0f
        var maxGx = gridSize / 2
        var maxGy = gridSize / 2

        for (i in weights.indices) {
            for (j in weights[i].indices) {
                if (weights[i][j] > maxWeight) {
                    maxWeight = weights[i][j]
                    maxGx = i
                    maxGy = j
                }
            }
        }

        // 转换为归一化坐标
        val left = maxGx.toFloat() / gridSize
        val top = maxGy.toFloat() / gridSize
        val right = (maxGx + 1).toFloat() / gridSize
        val bottom = (maxGy + 1).toFloat() / gridSize

        return RectF(left, top, right, bottom)
    }

    /**
     * 生成拍摄角度建议
     */
    fun generateAngleRecommendations(bitmap: Bitmap, currentScore: CompositionScore): List<AngleRecommendation> {
        val recommendations = mutableListOf<AngleRecommendation>()

        // 基于当前评分生成建议
        if (currentScore.depthScore < 50) {
            recommendations.add(AngleRecommendation(
                pitchAngle = -15f,
                rollAngle = 0f,
                description = "稍微俯拍，增加画面深度感",
                priority = 1
            ))
        }

        if (currentScore.symmetryScore < 60) {
            recommendations.add(AngleRecommendation(
                pitchAngle = 0f,
                rollAngle = 0f,
                description = "保持水平，提升对称性",
                priority = 2
            ))
        }

        if (currentScore.ruleOfThirdsScore < 50) {
            recommendations.add(AngleRecommendation(
                pitchAngle = 5f,
                rollAngle = -5f,
                description = "轻微调整角度，改善构图平衡",
                priority = 3
            ))
        }

        if (currentScore.leadingLinesScore < 40) {
            recommendations.add(AngleRecommendation(
                pitchAngle = -10f,
                rollAngle = 0f,
                description = "降低拍摄角度，捕捉更多引导线",
                priority = 4
            ))
        }

        // 通用建议
        recommendations.add(AngleRecommendation(
            pitchAngle = 0f,
            rollAngle = 0f,
            description = "保持水平，避免倾斜",
            priority = 5
        ))

        return recommendations.sortedBy { it.priority }.take(3)
    }

    /**
     * 检测主要构图模式
     */
    fun detectCompositionPattern(bitmap: Bitmap): CompositionPattern {
        val symmetry = analyzeSymmetry(bitmap)
        val balance = analyzeBalance(bitmap)
        val thirds = analyzeRuleOfThirds(bitmap)

        return when {
            symmetry > 55 -> CompositionPattern.SYMMETRY
            balance > 50 -> CompositionPattern.CENTER
            thirds > 45 -> CompositionPattern.RULE_OF_THIRDS
            else -> CompositionPattern.RULE_OF_THIRDS
        }
    }

    // 辅助函数
    private fun getBrightness(pixel: Int): Float {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        return (0.299f * r + 0.587f * g + 0.114f * b)
    }

    private fun getVisualWeight(pixel: Int): Float {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val saturation = maxOf(r, g, b) - minOf(r, g, b)
        val brightness = (r + g + b) / 3f
        return saturation * 0.6f + brightness * 0.4f
    }

    private fun getHue(pixel: Int): Float {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val diff = max - min

        if (diff < 0.001f) return 0f

        val hue = when (max) {
            r -> ((g - b) / diff) % 6
            g -> (b - r) / diff + 2
            else -> (r - g) / diff + 4
        }

        return (hue * 60 + 360) % 360
    }

    private fun getLevel(score: Float): CompositionLevel = when {
        score >= 85 -> CompositionLevel.EXCELLENT
        score >= 70 -> CompositionLevel.GOOD
        score >= 50 -> CompositionLevel.AVERAGE
        score >= 30 -> CompositionLevel.BELOW_AVERAGE
        else -> CompositionLevel.POOR
    }

    private fun getDefaultScore() = CompositionScore(
        overallScore = 50f,
        ruleOfThirdsScore = 50f,
        symmetryScore = 50f,
        balanceScore = 50f,
        leadingLinesScore = 50f,
        depthScore = 50f,
        colorHarmonyScore = 50f,
        subjectScore = 50f,
        level = CompositionLevel.AVERAGE
    )

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
