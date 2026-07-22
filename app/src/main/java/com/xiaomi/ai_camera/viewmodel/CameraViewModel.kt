package com.xiaomi.ai_camera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "CameraViewModel" }

    val cameraManager = XiaomiCameraManager(application)
    private val compositionAnalyzer = CompositionAnalyzer(application)
    private val sceneAnalyzer = SceneAnalyzer()
    private val faceDetector = FaceDetector()
    private val motionTracker = MotionTracker()

    private val isAnalyzing = AtomicBoolean(false)
    private var heavyAnalysisCounter = 0
    @Volatile private var previousSmallBitmap: Bitmap? = null

    // 使用 Mutex 保护 _uiState 的读-改-写
    private val uiStateMutex = Mutex()

    private val _compositionScore = MutableStateFlow(CompositionAnalyzer.CompositionScore(50f, 50f, 50f, 50f, 50f, 50f, 50f, 50f, CompositionAnalyzer.CompositionLevel.AVERAGE))
    val compositionScore: StateFlow<CompositionAnalyzer.CompositionScore> = _compositionScore.asStateFlow()
    private val _cropRecommendations = MutableStateFlow<List<CompositionAnalyzer.CropRecommendation>>(emptyList())
    val cropRecommendations: StateFlow<List<CompositionAnalyzer.CropRecommendation>> = _cropRecommendations.asStateFlow()
    private val _angleRecommendations = MutableStateFlow<List<CompositionAnalyzer.AngleRecommendation>>(emptyList())
    val angleRecommendations: StateFlow<List<CompositionAnalyzer.AngleRecommendation>> = _angleRecommendations.asStateFlow()
    private val _detectedPattern = MutableStateFlow(CompositionAnalyzer.CompositionPattern.RULE_OF_THIRDS)
    val detectedPattern: StateFlow<CompositionAnalyzer.CompositionPattern> = _detectedPattern.asStateFlow()
    private val _currentScene = MutableStateFlow(SceneAnalyzer.SceneType.UNKNOWN)
    val currentScene: StateFlow<SceneAnalyzer.SceneType> = _currentScene.asStateFlow()
    private val _sceneConfig = MutableStateFlow(sceneAnalyzer.getSceneConfig(SceneAnalyzer.SceneType.UNKNOWN))
    val sceneConfig: StateFlow<SceneAnalyzer.SceneConfig> = _sceneConfig.asStateFlow()
    private val _faceResults = MutableStateFlow<List<FaceDetector.FaceResult>>(emptyList())
    val faceResults: StateFlow<List<FaceDetector.FaceResult>> = _faceResults.asStateFlow()
    private val _motionState = MutableStateFlow(MotionTracker.MotionState(true, PointF(0f, 0f), 100))
    val motionState: StateFlow<MotionTracker.MotionState> = _motionState.asStateFlow()
    private val _bestShotScore = MutableStateFlow(0)
    val bestShotScore: StateFlow<Int> = _bestShotScore.asStateFlow()
    private val _autoCaptureEnabled = MutableStateFlow(false)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()
    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()
    private val _availableCameras = MutableStateFlow<List<XiaomiCameraManager.CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<XiaomiCameraManager.CameraInfo>> = _availableCameras.asStateFlow()
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private val _showCompositionOverlay = MutableStateFlow(true)
    val showCompositionOverlay: StateFlow<Boolean> = _showCompositionOverlay.asStateFlow()
    private val _showCropOverlay = MutableStateFlow(false)
    val showCropOverlay: StateFlow<Boolean> = _showCropOverlay.asStateFlow()
    private val _selectedCrop = MutableStateFlow<RectF?>(null)
    val selectedCrop: StateFlow<RectF?> = _selectedCrop.asStateFlow()
    private val _lastPhotoPath = MutableStateFlow<String?>(null)
    val lastPhotoPath: StateFlow<String?> = _lastPhotoPath.asStateFlow()
    private val _isTakingPhoto = MutableStateFlow(false)
    val isTakingPhoto: StateFlow<Boolean> = _isTakingPhoto.asStateFlow()
    private val _flashMode = MutableStateFlow(XiaomiCameraManager.FlashMode.AUTO)
    val flashMode: StateFlow<XiaomiCameraManager.FlashMode> = _flashMode.asStateFlow()

    init {
        cameraManager.setCallback(object : XiaomiCameraManager.CameraCallback {
            override fun onCameraOpened(cameraId: String) {
                _currentCameraId.value = cameraId
                viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(isCameraReady = true) } }
                _availableCameras.value = cameraManager.getAvailableCameras()
            }
            override fun onCameraClosed() {
                viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(isCameraReady = false) } }
            }
            override fun onCameraError(error: String) {
                Log.e(TAG, error)
                viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(errorMessage = error) } }
            }
            override fun onPhotoSaved(filePath: String) {
                _lastPhotoPath.value = filePath
                _isTakingPhoto.value = false
                viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(errorMessage = "照片已保存") } }
            }
        })
    }

    fun takePhoto() {
        if (_isTakingPhoto.value) return
        _isTakingPhoto.value = true
        cameraManager.takePhoto()
    }

    fun cycleFlashMode() {
        _flashMode.value = cameraManager.cycleFlashMode()
    }

    fun setZoom(ratio: Float) {
        cameraManager.setZoom(ratio)
    }

    fun quickAnalyze(bitmap: Bitmap) {
        if (!isAnalyzing.compareAndSet(false, true)) return

        viewModelScope.launch {
            var smallBitmap: Bitmap? = null
            try {
                smallBitmap = withContext(Dispatchers.Default) {
                    val w = bitmap.width.coerceAtMost(320)
                    val h = bitmap.height.coerceAtMost(240)
                    try { Bitmap.createScaledBitmap(bitmap, w, h, true) } catch (e: Exception) { null }
                }
                if (smallBitmap == null) { isAnalyzing.set(false); return@launch }

                // 构图分析 + 模式检测（复用结果，避免重复计算）
                val analysisResult = withContext(Dispatchers.Default) { compositionAnalyzer.analyzeCompositionWithDetails(smallBitmap) }
                _compositionScore.value = analysisResult.score
                _detectedPattern.value = analysisResult.pattern

                // 每2次重型分析
                heavyAnalysisCounter++
                if (heavyAnalysisCounter >= 2) {
                    heavyAnalysisCounter = 0
                    withContext(Dispatchers.Default) {
                        val scene = sceneAnalyzer.analyzeScene(smallBitmap)
                        _currentScene.value = scene
                        val config = sceneAnalyzer.getSceneConfig(scene)
                        _sceneConfig.value = config
                        cameraManager.applySceneConfig(config)

                        val faces = faceDetector.detectFaces(smallBitmap)
                        _faceResults.value = faces
                        _bestShotScore.value = faceDetector.getBestShotScore(faces)

                        if (_autoCaptureEnabled.value && faceDetector.isGoodMoment(faces) && motionTracker.isReadyToCapture()) {
                            uiStateMutex.withLock { _uiState.value = _uiState.value.copy(shouldAutoCapture = true) }
                        }
                    }
                }

                // 运动追踪
                val prevBitmap = previousSmallBitmap
                withContext(Dispatchers.Default) { _motionState.value = motionTracker.analyzeFrame(smallBitmap, prevBitmap) }
                prevBitmap?.recycle()
                previousSmallBitmap = try { smallBitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { null }

                // 裁剪建议
                if (_showCropOverlay.value) {
                    withContext(Dispatchers.Default) {
                        _cropRecommendations.value = compositionAnalyzer.generateCropRecommendations(smallBitmap, analysisResult.score)
                        _angleRecommendations.value = compositionAnalyzer.generateAngleRecommendations(smallBitmap, analysisResult.score)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "quickAnalyze failed: ${e.message}")
                uiStateMutex.withLock { _uiState.value = _uiState.value.copy(errorMessage = "分析出错: ${e.message}") }
            } finally {
                smallBitmap?.recycle()
                isAnalyzing.set(false)
            }
        }
    }

    fun toggleCompositionOverlay() { _showCompositionOverlay.value = !_showCompositionOverlay.value }
    fun toggleCropOverlay() { _showCropOverlay.value = !_showCropOverlay.value }
    fun selectCropRecommendation(crop: RectF) { _selectedCrop.value = crop }
    fun clearSelectedCrop() { _selectedCrop.value = null }
    fun toggleAutoCapture() { _autoCaptureEnabled.value = !_autoCaptureEnabled.value }
    fun clearError() { viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(errorMessage = null) } } }
    fun resetAutoCapture() { viewModelScope.launch { uiStateMutex.withLock { _uiState.value = _uiState.value.copy(shouldAutoCapture = false) } } }
    fun switchCamera() { _uiState.value.previewSurface?.let { cameraManager.switchCamera(it) } }
    fun switchToFrontBack() { _uiState.value.previewSurface?.let { cameraManager.switchToFrontBack(it) } }
    fun switchToFocalLength(type: String) { _uiState.value.previewSurface?.let { cameraManager.switchToFocalLength(type, it) } }
    fun setPreviewSurface(surface: android.view.Surface) { _uiState.value = _uiState.value.copy(previewSurface = surface) }

    override fun onCleared() {
        super.onCleared()
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
        compositionAnalyzer.close()
        sceneAnalyzer.close()
        faceDetector.close()
        previousSmallBitmap?.recycle()
        previousSmallBitmap = null
    }
}

data class CameraUiState(
    val isCameraReady: Boolean = false,
    val errorMessage: String? = null,
    val shouldAutoCapture: Boolean = false,
    val previewSurface: android.view.Surface? = null
)
