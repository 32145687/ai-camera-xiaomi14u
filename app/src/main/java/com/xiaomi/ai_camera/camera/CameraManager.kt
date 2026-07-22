package com.xiaomi.ai_camera.camera

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import com.xiaomi.ai_camera.ai.SceneAnalyzer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class XiaomiCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "XiaomiCameraManager"
    }

    enum class FlashMode { AUTO, ON, OFF, TORCH }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lock = ReentrantLock()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    @Volatile private var currentCameraId = ""
    @Volatile private var isSwitching = false
    @Volatile var flashMode = FlashMode.AUTO
        private set
    @Volatile var currentZoom = 1f
        private set

    data class CameraInfo(
        val id: String, val facing: Int, val focalLengths: FloatArray,
        val apertures: FloatArray, val fovDegrees: Float, val displayName: String
    )

    private val availableCameras = Collections.synchronizedList(mutableListOf<CameraInfo>())

    interface CameraCallback {
        fun onCameraOpened(cameraId: String)
        fun onCameraClosed()
        fun onCameraError(error: String)
        fun onPhotoSaved(filePath: String)
    }

    @Volatile private var callback: CameraCallback? = null
    fun setCallback(cb: CameraCallback) { callback = cb }

    fun startBackgroundThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join(1000) } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    fun scanCameras(): List<CameraInfo> {
        val newList = mutableListOf<CameraInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
                    val fov = calculateFov(chars, focalLengths)
                    val name = identifyCamera(id, facing, focalLengths, fov)
                    newList.add(CameraInfo(id, facing, focalLengths, apertures, fov, name))
                } catch (e: CameraAccessException) { Log.e(TAG, "Failed to read camera $id: ${e.message}") }
            }
        } catch (e: Exception) { callback?.onCameraError("扫描相机失败: ${e.message}") }

        lock.withLock { availableCameras.clear(); availableCameras.addAll(newList) }
        Log.d(TAG, "Found ${newList.size} cameras: ${newList.joinToString { it.displayName }}")
        return newList.toList()
    }

    private fun calculateFov(chars: CameraCharacteristics, focalLengths: FloatArray): Float {
        if (focalLengths.isEmpty()) return 60f
        val focal = focalLengths[0]
        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 6.17f
        return (2.0 * Math.toDegrees(Math.atan((sensorWidth / 2).toDouble() / focal.toDouble()))).toFloat()
    }

    private fun identifyCamera(id: String, facing: Int, focalLengths: FloatArray, fov: Float): String {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "前置"
        val mainFocal = focalLengths.firstOrNull() ?: 0f
        return when {
            mainFocal < 2.5f -> "超广角 12mm"
            mainFocal in 5.0f..7.0f -> "主摄 23mm"
            mainFocal in 7.0f..9.5f -> "长焦 75mm"
            mainFocal > 9.5f -> "潜望 120mm"
            fov > 100f -> "超广角"
            fov > 50f -> "主摄"
            else -> "相机 $id"
        }
    }

    fun getAvailableCameras(): List<CameraInfo> {
        lock.withLock { if (availableCameras.isEmpty()) scanCameras(); return availableCameras.toList() }
    }

    fun getBackCameras(): List<CameraInfo> = getAvailableCameras().filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    fun getFrontCameras(): List<CameraInfo> = getAvailableCameras().filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

    fun getCurrentCameraId(): String = currentCameraId
    fun getCameraInfo(cameraId: String): CameraInfo? = lock.withLock { availableCameras.find { it.id == cameraId } }
    fun getCameraDisplayName(cameraId: String): String = getCameraInfo(cameraId)?.displayName ?: "相机 $cameraId"

    fun isVariableApertureSupported(cameraId: String): Boolean = try {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        apertures != null && apertures.size > 1
    } catch (e: Exception) { false }

    fun openCamera(cameraId: String, surface: Surface) {
        Log.d(TAG, "Opening camera: $cameraId")
        lock.withLock { currentCameraId = cameraId; previewSurface = surface; safeCloseImageReader() }

        try {
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                callback?.onCameraError("没有相机权限"); return
            }
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val photoSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

            val reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 1)
            lock.withLock { imageReader = reader }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    lock.withLock { cameraDevice = camera }
                    callback?.onCameraOpened(cameraId)
                    createPreviewSession(surface)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    lock.withLock { cameraDevice?.close(); cameraDevice = null }
                    callback?.onCameraClosed()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    lock.withLock { cameraDevice?.close(); cameraDevice = null; safeCloseImageReader() }
                    callback?.onCameraError("相机 $cameraId 错误: $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            lock.withLock { safeCloseImageReader() }
            callback?.onCameraError("打开相机失败: ${e.message}")
        }
    }

    private fun createPreviewSession(surface: Surface) {
        try {
            val device = lock.withLock { cameraDevice } ?: return
            val reader = lock.withLock { imageReader } ?: return
            device.createCaptureSession(listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        lock.withLock { captureSession = session }; startPreview(surface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { callback?.onCameraError("预览配置失败") }
                }, cameraHandler)
        } catch (e: CameraAccessException) { callback?.onCameraError("创建会话失败: ${e.message}") }
    }

    private fun startPreview(surface: Surface) {
        try {
            val device = lock.withLock { cameraDevice } ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFlashMode(this)
            }
            builder?.build()?.let { request -> lock.withLock { captureSession?.setRepeatingRequest(request, null, cameraHandler) } }
        } catch (e: CameraAccessException) { callback?.onCameraError("预览失败: ${e.message}") }
    }

    private fun applyFlashMode(builder: CaptureRequest.Builder) {
        when (flashMode) {
            FlashMode.AUTO -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            FlashMode.ON -> { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE) }
            FlashMode.OFF -> { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF) }
            FlashMode.TORCH -> { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) }
        }
    }

    fun cycleFlashMode(): FlashMode {
        flashMode = when (flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.AUTO
        }
        // 重新应用到当前预览
        val device = lock.withLock { cameraDevice } ?: return flashMode
        val session = lock.withLock { captureSession } ?: return flashMode
        val surface = lock.withLock { previewSurface } ?: return flashMode
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFlashMode(this)
            }
            builder?.build()?.let { session.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: Exception) { Log.e(TAG, "cycleFlashMode failed: ${e.message}") }
        return flashMode
    }

    fun setZoom(ratio: Float) {
        val device = lock.withLock { cameraDevice } ?: return
        val chars = cameraManager.getCameraCharacteristics(currentCameraId)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val clamped = ratio.coerceIn(1f, maxZoom)
        currentZoom = clamped

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cropW = (sensorRect.width() / clamped).toInt()
        val cropH = (sensorRect.height() / clamped).toInt()
        val cropX = (sensorRect.width() - cropW) / 2
        val cropY = (sensorRect.height() - cropH) / 2

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            lock.withLock { previewSurface }?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
            applyFlashMode(this)
        }
        builder?.build()?.let { request -> lock.withLock { captureSession?.setRepeatingRequest(request, null, cameraHandler) } }
    }

    fun takePhoto() {
        val device = lock.withLock { cameraDevice } ?: return
        val reader = lock.withLock { imageReader } ?: return
        val session = lock.withLock { captureSession } ?: return

        val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
        val orientation = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                applyFlashMode(this)
            }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                    val fileName = "AI_Camera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/AI_Camera")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                        callback?.onPhotoSaved(it.toString())
                    } ?: callback?.onCameraError("无法创建照片记录")
                } catch (e: Exception) { callback?.onCameraError("保存照片失败: ${e.message}") }
                finally { image.close() }
            }, cameraHandler)

            builder?.build()?.let { session.capture(it, null, cameraHandler) }
        } catch (e: CameraAccessException) { callback?.onCameraError("拍照失败: ${e.message}") }
    }

    fun applySceneConfig(config: SceneAnalyzer.SceneConfig) {
        try {
            val device = lock.withLock { cameraDevice } ?: return
            val session = lock.withLock { captureSession } ?: return

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                lock.withLock { previewSurface }?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFlashMode(this)

                val chars = cameraManager.getCameraCharacteristics(currentCameraId)
                val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (aeRange != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, config.exposureComp.coerceIn(aeRange.lower, aeRange.upper))
                }

                if (isVariableApertureSupported(currentCameraId)) {
                    set(CaptureRequest.LENS_APERTURE, config.aperture)
                }

                // 设置ISO（仅在场景需要且设备支持时）
                if (config.iso > 0 && config.sceneType == SceneAnalyzer.SceneType.NIGHT) {
                    set(CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                }
            }

            builder?.build()?.let { request -> lock.withLock { session.setRepeatingRequest(request, null, cameraHandler) } }
        } catch (e: Exception) { Log.e(TAG, "applySceneConfig failed: ${e.message}") }
    }

    fun switchCamera(surface: Surface) {
        if (isSwitching) return
        isSwitching = true

        var backCameras = getBackCameras()
        if (backCameras.isEmpty()) {
            scanCameras()
            backCameras = getBackCameras()
            if (backCameras.isEmpty()) { isSwitching = false; callback?.onCameraError("未发现后置相机"); return }
        }

        val currentIndex = backCameras.indexOfFirst { it.id == currentCameraId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % backCameras.size
        closeAndReopen(backCameras[nextIndex].id, surface)
    }

    fun switchToFrontBack(surface: Surface) {
        if (isSwitching) return
        isSwitching = true
        val currentFacing = getCameraInfo(currentCameraId)?.facing ?: CameraCharacteristics.LENS_FACING_BACK
        val targetFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val target = getAvailableCameras().find { it.facing == targetFacing }
        if (target == null) { isSwitching = false; callback?.onCameraError("未找到目标相机"); return }
        closeAndReopen(target.id, surface)
    }

    fun switchToFocalLength(focalLengthType: String, surface: Surface) {
        if (isSwitching) return
        val target = getAvailableCameras().find { it.displayName.contains(focalLengthType) }
        if (target == null) { callback?.onCameraError("未找到 ${focalLengthType} 相机"); return }
        if (target.id == currentCameraId) return
        isSwitching = true
        closeAndReopen(target.id, surface)
    }

    private fun closeAndReopen(newCameraId: String, surface: Surface) {
        lock.withLock {
            try { captureSession?.close() } catch (_: Exception) {}; captureSession = null
            try { cameraDevice?.close() } catch (_: Exception) {}; cameraDevice = null
        }

        val handler = cameraHandler
        if (handler != null) {
            handler.postDelayed({
                try { openCamera(newCameraId, surface) } finally { isSwitching = false }
            }, 200)
        } else {
            try { openCamera(newCameraId, surface) } finally { isSwitching = false }
        }
    }

    fun closeCamera() {
        lock.withLock {
            try { captureSession?.close() } catch (_: Exception) {}; captureSession = null
            try { cameraDevice?.close() } catch (_: Exception) {}; cameraDevice = null
            safeCloseImageReader()
        }
    }

    private fun safeCloseImageReader() { try { imageReader?.close() } catch (_: Exception) {}; imageReader = null }
    fun getCameraCount(): Int = cameraManager.cameraIdList.size
}
