package com.xiaomi.ai_camera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaomi.ai_camera.ai.*
import com.xiaomi.ai_camera.camera.XiaomiCameraManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val cameraManager = XiaomiCameraManager(application)
    private val compositionAnalyzer = CompositionAnalyzer(application)
    private val sceneAnalyzer = SceneAnalyzer()
    private val faceDetector = FaceDetector()
    private val motionTracker = MotionTracker()

    // 构图评分
    private val _compositionScore = MutableStateFlow(
        CompositionAnalyzer.CompositionScore(50f, 50f, 50f, 50f, 50f, 50f, 50f, 50f, CompositionAnalyzer.CompositionLevel.AVERAGE)
    )
    val compositionScore: StateFlow<CompositionAnalyzer.CompositionScore> = _compositionScore.asStateFlow()

    // 裁剪建议
    private val _cropRecommendations = MutableStateFlow<List<CompositionAnalyzer.CropRecommendation>>(emptyList())
    val cropRecommendations: StateFlow<List<CompositionAnalyzer.CropRecommendation>> = _cropRecommendations.asStateFlow()

    // 角度建议
    private val _angleRecommendations = MutableStateFlow<List<CompositionAnalyzer.AngleRecommendation>>(emptyList())
    val angleRecommendations: StateFlow<List<CompositionAnalyzer.AngleRecommendation>> = _angleRecommendations.asStateFlow()

    // 检测到的构图模式
    private val _detectedPattern = MutableStateFlow(CompositionAnalyzer.CompositionPattern.RULE_OF_THIRDS)
    val detectedPattern: StateFlow<CompositionAnalyzer.CompositionPattern> = _detectedPattern.asStateFlow()

    // 场景类型
    private val _currentScene = MutableStateFlow(SceneAnalyzer.SceneType.UNKNOWN)
    val currentScene: StateFlow<SceneAnalyzer.SceneType> = _currentScene.asStateFlow()

    // 场景配置
    private val _sceneConfig = MutableStateFlow(sceneAnalyzer.getSceneConfig(SceneAnalyzer.SceneType.UNKNOWN))
    val sceneConfig: StateFlow<SceneAnalyzer.SceneConfig> = _sceneConfig.asStateFlow()

    // 人脸检测结果
    private val _faceResults = MutableStateFlow<List<FaceDetector.FaceResult>>(emptyList())
    val faceResults: StateFlow<List<FaceDetector.FaceResult>> = _faceResults.asStateFlow()

    // 运动状态
    private val _motionState = MutableStateFlow(MotionTracker.MotionState(true, PointF(0f, 0f), 100))
    val motionState: StateFlow<MotionTracker.MotionState> = _motionState.asStateFlow()

    // 最佳拍摄评分
    private val _bestShotScore = MutableStateFlow(0)
    val bestShotScore: StateFlow<Int> = _bestShotScore.asStateFlow()

    // 自动拍摄模式
    private val _autoCaptureEnabled = MutableStateFlow(false)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    // 当前相机ID
    private val _currentCameraId = MutableStateFlow(XiaomiCameraManager.CAMERA_MAIN)
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // UI状态
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // 是否显示裁剪建议
    private val _showCropOverlay = MutableStateFlow(false)
    val showCropOverlay: StateFlow<Boolean> = _showCropOverlay.asStateFlow()

    // 当前选中的裁剪建议
    private val _selectedCrop = MutableStateFlow<RectF?>(null)
    val selectedCrop: StateFlow<RectF?> = _selectedCrop.asStateFlow()

    init {
        cameraManager.setCallback(object : XiaomiCameraManager.CameraCallback {
            override fun onCameraOpened(cameraId: String) {
                _currentCameraId.value = cameraId
                _uiState.value = _uiState.value.copy(isCameraReady = true)
            }
            override fun onCameraClosed() {
                _uiState.value = _uiState.value.copy(isCameraReady = false)
            }
            override fun onCameraError(error: String) {
                _uiState.value = _uiState.value.copy(errorMessage = error)
            }
        })
    }

    /**
     * 分析预览帧 - 核心AI分析
     */
    fun analyzePreviewFrame(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                // 1. 构图分析 (最重要)
                val score = compositionAnalyzer.analyzeComposition(bitmap)
                _compositionScore.value = score

                // 2. 检测构图模式
                _detectedPattern.value = compositionAnalyzer.detectCompositionPattern(bitmap)

                // 3. 生成裁剪建议
                _cropRecommendations.value = compositionAnalyzer.generateCropRecommendations(bitmap, score)

                // 4. 生成角度建议
                _angleRecommendations.value = compositionAnalyzer.generateAngleRecommendations(bitmap, score)

                // 5. 场景识别
                val scene = sceneAnalyzer.analyzeScene(bitmap)
                _currentScene.value = scene
                _sceneConfig.value = sceneAnalyzer.getSceneConfig(scene)

                // 6. 人脸检测
                val faces = faceDetector.detectFaces(bitmap)
                _faceResults.value = faces
                _bestShotScore.value = faceDetector.getBestShotScore(faces)

                // 7. 运动追踪
                _motionState.value = motionTracker.analyzeFrame(bitmap, null)

                // 8. 自动拍摄判断
                if (_autoCaptureEnabled.value && faceDetector.isGoodMoment(faces) && motionTracker.isReadyToCapture()) {
                    _uiState.value = _uiState.value.copy(shouldAutoCapture = true)
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleCropOverlay() {
        _showCropOverlay.value = !_showCropOverlay.value
    }

    fun selectCropRecommendation(crop: RectF) {
        _selectedCrop.value = crop
    }

    fun clearSelectedCrop() {
        _selectedCrop.value = null
    }

    fun toggleAutoCapture() {
        _autoCaptureEnabled.value = !_autoCaptureEnabled.value
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun resetAutoCapture() {
        _uiState.value = _uiState.value.copy(shouldAutoCapture = false)
    }

    fun switchCamera() {
        val surface = _uiState.value.previewSurface ?: return
        cameraManager.switchCamera(surface)
    }

    fun setPreviewSurface(surface: android.view.Surface) {
        _uiState.value = _uiState.value.copy(previewSurface = surface)
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
        compositionAnalyzer.close()
    }
}

data class CameraUiState(
    val isCameraReady: Boolean = false,
    val errorMessage: String? = null,
    val shouldAutoCapture: Boolean = false,
    val previewSurface: android.view.Surface? = null
)
