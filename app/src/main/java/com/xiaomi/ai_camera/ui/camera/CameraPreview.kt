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
    var frameCount by remember { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        val previewSurface = Surface(surface)
                        viewModel.setPreviewSurface(previewSurface)
                        viewModel.cameraManager.startBackgroundThread()
                        // 扫描所有相机并打开主摄
                        val cameras = viewModel.cameraManager.scanCameras()
                        val mainCamera = cameras.find { it.displayName.contains("主摄") }
                        val cameraId = mainCamera?.id ?: "0"
                        viewModel.cameraManager.openCamera(cameraId, previewSurface)
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
                        // 每15帧分析一次（约2次/秒）
                        if (frameCount % 15 != 0) return

                        // 不在UI线程做Bitmap操作，直接传递引用
                        bitmap?.let { bm ->
                            viewModel.quickAnalyze(bm)
                        }
                    }
                }
            }
        }
    )
}
