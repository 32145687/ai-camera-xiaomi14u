package com.xiaomi.ai_camera.ui.camera

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomi.ai_camera.ai.CompositionAnalyzer
import com.xiaomi.ai_camera.viewmodel.CameraViewModel

@Composable
fun CameraControls(
    viewModel: CameraViewModel,
    onCaptureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compositionScore by viewModel.compositionScore.collectAsState()
    val sceneConfig by viewModel.sceneConfig.collectAsState()
    val bestShotScore by viewModel.bestShotScore.collectAsState()
    val autoCapture by viewModel.autoCaptureEnabled.collectAsState()
    val currentCameraId by viewModel.currentCameraId.collectAsState()
    val motionState by viewModel.motionState.collectAsState()
    val cropRecommendations by viewModel.cropRecommendations.collectAsState()
    val angleRecommendations by viewModel.angleRecommendations.collectAsState()
    val showCropOverlay by viewModel.showCropOverlay.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val showCompositionOverlay by viewModel.showCompositionOverlay.collectAsState()

    // 焦段列表
    val allFocalLengths = listOf("超广角", "主摄", "长焦", "潜望")
    // 只显示可用的焦段
    val availableFocalLengths = remember(availableCameras) {
        val availableNames = availableCameras.map { it.displayName }
        allFocalLengths.filter { type -> availableNames.any { it.contains(type) } }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // === 构图评分栏 ===
        CompositionScoreBar(compositionScore)

        Spacer(Modifier.height(8.dp))

        // === 场景 + 建议栏 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sceneConfig.description, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("f/${sceneConfig.aperture} | ISO ${sceneConfig.iso}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }

            if (bestShotScore > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("抓拍", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                    Text("$bestShotScore", color = when {
                        bestShotScore >= 80 -> Color.Green
                        bestShotScore >= 60 -> Color.Yellow
                        else -> Color.Red
                    }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // === 角度建议 ===
        if (angleRecommendations.isNotEmpty()) {
            AngleSuggestionBar(angleRecommendations.first())
            Spacer(Modifier.height(6.dp))
        }

        // === 稳定性指示器 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (motionState.isStable) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                null,
                tint = if (motionState.isStable) Color.Green else Color.Yellow,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (motionState.isStable) "稳定" else "晃动中",
                color = Color.White, fontSize = 11.sp
            )
            Spacer(Modifier.width(16.dp))
            Text("稳定性: ${motionState.stabilityScore}%", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))

        // === 焦段选择（只显示可用的） ===
        if (availableFocalLengths.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                availableFocalLengths.forEach { type ->
                    val isActive = viewModel.cameraManager.getCameraDisplayName(currentCameraId).contains(type)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) Color(0xFF1976D2) else Color.White.copy(alpha = 0.15f))
                            .clickable { viewModel.switchToFocalLength(type) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            color = if (isActive) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // === 主控制行 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 构图线开关
            ControlButton(
                icon = Icons.Filled.GridOn,
                label = "构图",
                isActive = showCompositionOverlay,
                onClick = { viewModel.toggleCompositionOverlay() }
            )

            // 拍照按钮
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onCaptureClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            // 相机信息
            ControlButton(
                icon = Icons.Filled.CameraAlt,
                label = viewModel.cameraManager.getCameraDisplayName(currentCameraId),
                onClick = { viewModel.switchCamera() }
            )
        }

        Spacer(Modifier.height(12.dp))

        // === 功能行 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ControlButton(
                icon = Icons.Filled.AutoAwesome,
                label = "AI抓拍",
                isActive = autoCapture,
                onClick = { viewModel.toggleAutoCapture() }
            )
            ControlButton(
                icon = Icons.Filled.Crop,
                label = "裁剪",
                isActive = showCropOverlay,
                onClick = { viewModel.toggleCropOverlay() }
            )
            ControlButton(icon = Icons.Filled.FlashAuto, label = "闪光", onClick = {})
        }

        // === 裁剪建议列表 ===
        if (showCropOverlay && cropRecommendations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CropRecommendationList(cropRecommendations, viewModel)
        }
    }
}

@Composable
private fun CompositionScoreBar(score: CompositionAnalyzer.CompositionScore) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when (score.level) {
                            CompositionAnalyzer.CompositionLevel.EXCELLENT -> Color(0xFF4CAF50)
                            CompositionAnalyzer.CompositionLevel.GOOD -> Color(0xFF8BC34A)
                            CompositionAnalyzer.CompositionLevel.AVERAGE -> Color(0xFFFFC107)
                            CompositionAnalyzer.CompositionLevel.BELOW_AVERAGE -> Color(0xFFFF9800)
                            CompositionAnalyzer.CompositionLevel.POOR -> Color(0xFFF44336)
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("${score.level.emoji} ${score.level.label}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                "构图 ${String.format("%.0f", score.overallScore)}分",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ScoreChip("三分", score.ruleOfThirdsScore)
            ScoreChip("对称", score.symmetryScore)
            ScoreChip("平衡", score.balanceScore)
            ScoreChip("线条", score.leadingLinesScore)
            ScoreChip("深度", score.depthScore)
            ScoreChip("色彩", score.colorHarmonyScore)
            ScoreChip("主体", score.subjectScore)
        }
    }
}

@Composable
private fun ScoreChip(label: String, score: Float) {
    val color = when {
        score >= 70 -> Color.Green.copy(alpha = 0.7f)
        score >= 50 -> Color.Yellow.copy(alpha = 0.7f)
        else -> Color.Red.copy(alpha = 0.7f)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 8.sp)
        Text("${score.toInt()}", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AngleSuggestionBar(recommendation: CompositionAnalyzer.AngleRecommendation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A237E).copy(alpha = 0.6f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Explore, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(recommendation.description, color = Color.White, fontSize = 12.sp)
            Text(
                "俯仰: ${String.format("%.0f", recommendation.pitchAngle)}° | 横滚: ${String.format("%.0f", recommendation.rollAngle)}°",
                color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CropRecommendationList(
    recommendations: List<CompositionAnalyzer.CropRecommendation>,
    viewModel: CameraViewModel
) {
    Column {
        Text("裁剪建议", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        recommendations.forEachIndexed { index, rec ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { viewModel.selectCropRecommendation(rec.cropRect) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            when (index) {
                                0 -> Color.Green
                                1 -> Color.Yellow
                                else -> Color.Cyan
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(rec.reason, color = Color.White, fontSize = 11.sp)
                    Text(
                        "预期评分: ${String.format("%.0f", rec.improvedScore)} | ${String.format("%.2f", rec.aspectRatio)}:1",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            icon, null,
            tint = if (isActive) Color.Yellow else Color.White,
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            color = if (isActive) Color.Yellow else Color.White,
            fontSize = 9.sp
        )
    }
}
