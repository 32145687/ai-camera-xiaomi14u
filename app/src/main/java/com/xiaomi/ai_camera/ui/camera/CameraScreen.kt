package com.xiaomi.ai_camera.ui.camera

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaomi.ai_camera.viewmodel.CameraViewModel

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val compositionScore by viewModel.compositionScore.collectAsState()
    val detectedPattern by viewModel.detectedPattern.collectAsState()
    val cropRecommendations by viewModel.cropRecommendations.collectAsState()
    val showCropOverlay by viewModel.showCropOverlay.collectAsState()
    val selectedCrop by viewModel.selectedCrop.collectAsState()
    val currentScene by viewModel.currentScene.collectAsState()

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.shouldAutoCapture) {
        if (uiState.shouldAutoCapture) {
            Toast.makeText(context, "检测到最佳拍摄时机！", Toast.LENGTH_SHORT).show()
            viewModel.resetAutoCapture()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 相机预览
        CameraPreview(viewModel = viewModel, modifier = Modifier.fillMaxSize())

        // 构图叠加层
        CompositionOverlay(
            compositionScore = compositionScore,
            detectedPattern = detectedPattern,
            cropRecommendations = cropRecommendations,
            showCropOverlay = showCropOverlay,
            selectedCrop = selectedCrop,
            modifier = Modifier.fillMaxSize()
        )

        // 顶部信息栏
        TopInfoBar(
            sceneType = currentScene.label,
            patternName = detectedPattern.label,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(8.dp)
                .statusBarsPadding()
        )

        // 底部控制栏
        CameraControls(
            viewModel = viewModel,
            onCaptureClick = {
                viewModel.takePhoto()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

@Composable
private fun TopInfoBar(
    sceneType: String,
    patternName: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sceneType,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "构图: $patternName",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
    }
}
