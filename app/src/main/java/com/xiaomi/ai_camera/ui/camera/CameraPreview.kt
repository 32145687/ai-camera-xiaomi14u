package com.xiaomi.ai_camera.ui.camera

import android.graphics.Bitmap
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaomi.ai_camera.viewmodel.CameraViewModel

@Composable
fun CameraPreview(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            var frameCount = 0

            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        val previewSurface = Surface(surface)
                        viewModel.setPreviewSurface(previewSurface)
                        viewModel.cameraManager.startBackgroundThread()
                        viewModel.cameraManager.openCamera(
                            com.xiaomi.ai_camera.camera.XiaomiCameraManager.CAMERA_MAIN,
                            previewSurface
                        )
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        viewModel.cameraManager.closeCamera()
                        viewModel.cameraManager.stopBackgroundThread()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                        if (!uiState.isCameraReady) return

                        frameCount++
                        // 每10帧分析一次，约30fps下每秒3次
                        if (frameCount % 10 != 0) return

                        // 快速构图评分 - 只做轻量级分析
                        bitmap?.let { fullBm ->
                            // 缩小到160x120再分析，大幅降低计算量
                            val small = Bitmap.createScaledBitmap(fullBm, 160, 120, true)
                            viewModel.quickAnalyze(small)
                            if (small !== fullBm) small.recycle()
                        }
                    }
                }
            }
        }
    )
}
