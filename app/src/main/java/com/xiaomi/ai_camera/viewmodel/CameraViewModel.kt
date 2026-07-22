package com.xiaomi.ai_camera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaomi.ai_camera.ai.*
import com.xiaomi.ai_camera.camera.XiaomiCameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val cameraManager = XiaomiCameraManager(application)
    private val compositionAnalyzer = CompositionAnalyzer(application)
    private val sceneAnalyzer = SceneAnalyzer()
    private val faceDetector = FaceDetector()
    private val motionTracker = MotionTracker()

    // 防止并发分析
    @Volatile private var isAnalyzing = false
    private var heavyAnalysisCounter = 0

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
    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // 可用相机列表
    private val _availableCameras = MutableStateFlow<List<XiaomiCameraManager.CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<XiaomiCameraManager.CameraInfo>> = _availableCameras.asStateFlow()

    // UI状态
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // 是否显示裁剪建议
    private val _showCropOverlay = MutableStateFlow(false)
    val showCropOverlay: StateFlow<Boolean> = _showCropOverlay.asStateFlow()

    // 当前选中的裁剪建议
    private val _selectedCrop = MutableStateFlow<RectF?>(null)
    val selectedCrop: StateFlow<RectF?> = _selectedCrop.asStateFlow()

    // 拍照保存路径
    private val _lastPhotoPath = MutableStateFlow<String?>(null)
    val lastPhotoPath: StateFlow<String?> = _lastPhotoPath.asStateFlow()

    // 拍照状态
    private val _isTakingPhoto = MutableStateFlow(false)
    val isTakingPhoto: StateFlow<Boolean> = _isTakingPhoto.asStateFlow()

    init {
        cameraManager.setCallback(object : XiaomiCameraManager.CameraCallback {
            override fun onCameraOpened(cameraId: String) {
                _currentCameraId.value = cameraId
                _uiState.value = _uiState.value.copy(isCameraReady = true)
                _availableCameras.value = cameraManager.getAvailableCameras()
            }
            override fun onCameraClosed() {
                _uiState.value = _uiState.value.copy(isCameraReady = false)
            }
            override fun onCameraError(error: String) {
                _uiState.value = _uiState.value.copy(errorMessage = error)
            }
            override fun onPhotoSaved(filePath: String) {
                _lastPhotoPath.value = filePath
                _isTakingPhoto.value = false
                _uiState.value = _uiState.value.copy(errorMessage = "照片已保存到相册")
            }
        })
    }

    /**
     * 拍照
     */
    fun takePhoto() {
        if (_isTakingPhoto.value) return
        _isTakingPhoto.value = true
        cameraManager.takePhoto()
    }

    /**
     * 快速分析 - 在后台线程执行
     */
    fun quickAnalyze(bitmap: Bitmap) {
        if (isAnalyzing) return
        isAnalyzing = true

        viewModelScope.launch {
            try {
                // 在后台创建缩小的bitmap进行分析
                val smallBitmap = withContext(Dispatchers.Default) {
                    val w = bitmap.width.coerceAtMost(160)
                    val h = bitmap.height.coerceAtMost(120)
                    try {
                        Bitmap.createScaledBitmap(bitmap, w, h, true)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (smallBitmap == null) {
                    isAnalyzing = false
                    return@launch
                }

                try {
                    // 构图评分
                    val score = withContext(Dispatchers.Default) {
                        compositionAnalyzer.analyzeComposition(smallBitmap)
                    }
                    _compositionScore.value = score

                    withContext(Dispatchers.Default) {
                        _detectedPattern.value = compositionAnalyzer.detectCompositionPattern(smallBitmap)
                    }

                    // 每5次做一次重型分析
                    heavyAnalysisCounter++
                    if (heavyAnalysisCounter >= 5) {
                        heavyAnalysisCounter = 0
                        withContext(Dispatchers.Default) {
                            val scene = sceneAnalyzer.analyzeScene(smallBitmap)
                            _currentScene.value = scene
                            _sceneConfig.value = sceneAnalyzer.getSceneConfig(scene)

                            val faces = faceDetector.detectFaces(smallBitmap)
                            _faceResults.value = faces
                            _bestShotScore.value = faceDetector.getBestShotScore(faces)

                            if (_autoCaptureEnabled.value && faceDetector.isGoodMoment(faces) && motionTracker.isReadyToCapture()) {
                                _uiState.value = _uiState.value.copy(shouldAutoCapture = true)
                            }
                        }
                    }

                    // 运动追踪
                    withContext(Dispatchers.Default) {
                        _motionState.value = motionTracker.analyzeFrame(smallBitmap, null)
                    }

                    // 裁剪建议
                    if (_showCropOverlay.value) {
                        withContext(Dispatchers.Default) {
                            _cropRecommendations.value = compositionAnalyzer.generateCropRecommendations(smallBitmap, score)
                            _angleRecommendations.value = compositionAnalyzer.generateAngleRecommendations(smallBitmap, score)
                        }
                    }
                } finally {
                    if (smallBitmap !== bitmap) {
                        try { smallBitmap.recycle() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            finally {
                isAnalyzing = false
            }
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

    fun switchToFocalLength(type: String) {
        val surface = _uiState.value.previewSurface ?: return
        cameraManager.switchToFocalLength(type, surface)
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
