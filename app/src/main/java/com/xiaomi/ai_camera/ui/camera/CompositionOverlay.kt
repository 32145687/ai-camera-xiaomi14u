package com.xiaomi.ai_camera.ui.camera

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.xiaomi.ai_camera.ai.CompositionAnalyzer
import kotlin.math.*

/**
 * 高级构图叠加层
 * 显示构图辅助线、裁剪建议、评分指示器
 */
@Composable
fun CompositionOverlay(
    compositionScore: CompositionAnalyzer.CompositionScore,
    detectedPattern: CompositionAnalyzer.CompositionPattern,
    cropRecommendations: List<CompositionAnalyzer.CropRecommendation>,
    showCropOverlay: Boolean,
    selectedCrop: RectF?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 绘制当前检测到的构图模式辅助线
        drawCompositionLines(detectedPattern, w, h)

        // 绘制裁剪建议
        if (showCropOverlay && cropRecommendations.isNotEmpty()) {
            drawCropRecommendations(cropRecommendations, selectedCrop, w, h)
        }

        // 绘制评分指示器
        drawScoreIndicator(compositionScore, w, h)
    }
}

private fun DrawScope.drawCompositionLines(pattern: CompositionAnalyzer.CompositionPattern, w: Float, h: Float) {
    val lineColor = Color.White.copy(alpha = 0.5f)
    val lineWidth = 1.5f

    when (pattern) {
        CompositionAnalyzer.CompositionPattern.RULE_OF_THIRDS -> {
            val tw = w / 3; val th = h / 3
            drawLine(lineColor, Offset(tw, 0f), Offset(tw, h), lineWidth)
            drawLine(lineColor, Offset(tw * 2, 0f), Offset(tw * 2, h), lineWidth)
            drawLine(lineColor, Offset(0f, th), Offset(w, th), lineWidth)
            drawLine(lineColor, Offset(0f, th * 2), Offset(w, th * 2), lineWidth)
            listOf(Offset(tw, th), Offset(tw * 2, th), Offset(tw, th * 2), Offset(tw * 2, th * 2)).forEach {
                drawCircle(Color.Yellow.copy(alpha = 0.8f), 5f, it)
            }
        }
        CompositionAnalyzer.CompositionPattern.GOLDEN_RATIO -> {
            val phi = 1.618f
            val gw = w / phi; val gh = h / phi
            drawLine(lineColor, Offset(gw, 0f), Offset(gw, h), lineWidth)
            drawLine(lineColor, Offset(w - gw, 0f), Offset(w - gw, h), lineWidth)
            drawLine(lineColor, Offset(0f, gh), Offset(w, gh), lineWidth)
            drawLine(lineColor, Offset(0f, h - gh), Offset(w, h - gh), lineWidth)
        }
        CompositionAnalyzer.CompositionPattern.GOLDEN_SPIRAL -> {
            val path = Path().apply {
                moveTo(w / 2, h / 2)
                for (i in 0 until 100) {
                    val angle = i * 2 * PI / 100 * 3
                    val r = min(w, h) / 2 * i / 100
                    lineTo(w / 2 + r * cos(angle).toFloat(), h / 2 + r * sin(angle).toFloat())
                }
            }
            drawPath(path, lineColor, style = Stroke(lineWidth))
        }
        CompositionAnalyzer.CompositionPattern.DIAGONAL -> {
            drawLine(lineColor, Offset(0f, 0f), Offset(w, h), lineWidth)
            drawLine(lineColor, Offset(w, 0f), Offset(0f, h), lineWidth)
        }
        CompositionAnalyzer.CompositionPattern.SYMMETRY -> {
            drawLine(Color.Cyan.copy(alpha = 0.4f), Offset(w / 2, 0f), Offset(w / 2, h), lineWidth)
            drawLine(Color.Cyan.copy(alpha = 0.4f), Offset(0f, h / 2), Offset(w, h / 2), lineWidth)
        }
        CompositionAnalyzer.CompositionPattern.CENTER -> {
            drawCircle(Color.White.copy(alpha = 0.3f), 40f, Offset(w / 2, h / 2), style = Stroke(lineWidth))
            drawLine(lineColor, Offset(w / 2 - 25, h / 2), Offset(w / 2 + 25, h / 2), lineWidth)
            drawLine(lineColor, Offset(w / 2, h / 2 - 25), Offset(w / 2, h / 2 + 25), lineWidth)
        }
        CompositionAnalyzer.CompositionPattern.LEADING_LINES -> {
            // 引导线模式 - 从角落引向中心
            val cx = w / 2; val cy = h / 2
            drawLine(Color.Green.copy(alpha = 0.3f), Offset(0f, 0f), Offset(cx, cy), lineWidth)
            drawLine(Color.Green.copy(alpha = 0.3f), Offset(w, 0f), Offset(cx, cy), lineWidth)
            drawLine(Color.Green.copy(alpha = 0.3f), Offset(0f, h), Offset(cx, cy), lineWidth)
            drawLine(Color.Green.copy(alpha = 0.3f), Offset(w, h), Offset(cx, cy), lineWidth)
        }
        else -> {}
    }
}

private fun DrawScope.drawCropRecommendations(
    recommendations: List<CompositionAnalyzer.CropRecommendation>,
    selectedCrop: RectF?,
    w: Float,
    h: Float
) {
    recommendations.forEachIndexed { index, rec ->
        val isSelected = selectedCrop == rec.cropRect
        val color = when (index) {
            0 -> Color.Green.copy(alpha = if (isSelected) 0.6f else 0.3f)
            1 -> Color.Yellow.copy(alpha = if (isSelected) 0.6f else 0.3f)
            else -> Color.Cyan.copy(alpha = if (isSelected) 0.6f else 0.3f)
        }

        val left = rec.cropRect.left * w
        val top = rec.cropRect.top * h
        val right = rec.cropRect.right * w
        val bottom = rec.cropRect.bottom * h

        // 绘制裁剪区域边框
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = if (isSelected) 3f else 2f)
        )

        // 绘制半透明填充
        drawRect(
            color = color.copy(alpha = 0.1f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top)
        )

        // 绘制裁剪区域角标记
        val cornerLen = 20f
        val cornerColor = color.copy(alpha = 0.8f)
        // 左上角
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLen, top), 3f)
        drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLen), 3f)
        // 右上角
        drawLine(cornerColor, Offset(right, top), Offset(right - cornerLen, top), 3f)
        drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLen), 3f)
        // 左下角
        drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLen, bottom), 3f)
        drawLine(cornerColor, Offset(left, bottom), Offset(left, bottom - cornerLen), 3f)
        // 右下角
        drawLine(cornerColor, Offset(right, bottom), Offset(right - cornerLen, bottom), 3f)
        drawLine(cornerColor, Offset(right, bottom), Offset(right, bottom - cornerLen), 3f)
    }
}

private fun DrawScope.drawScoreIndicator(score: CompositionAnalyzer.CompositionScore, w: Float, h: Float) {
    // 顶部评分条
    val barWidth = w * 0.6f
    val barHeight = 6f
    val barX = (w - barWidth) / 2
    val barY = 50f

    // 背景
    drawRect(Color.Black.copy(alpha = 0.4f), Offset(barX, barY), Size(barWidth, barHeight))

    // 评分填充
    val fillWidth = barWidth * (score.overallScore / 100f)
    val scoreColor = when {
        score.overallScore >= 70 -> Color.Green
        score.overallScore >= 50 -> Color.Yellow
        else -> Color.Red
    }
    drawRect(scoreColor, Offset(barX, barY), Size(fillWidth, barHeight))
}
