package com.xiaomi.ai_camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
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

/**
 * 小米14U专用Camera2管理器
 * 支持四摄切换、拍照、可变光圈控制、场景自动调整
 */
class XiaomiCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "XiaomiCameraManager"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentCameraId = ""
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var currentPreviewRequest: CaptureRequest? = null

    data class CameraInfo(
        val id: String,
        val facing: Int,
        val focalLengths: FloatArray,
        val apertures: FloatArray,
        val fovDegrees: Float,
        val displayName: String
    )

    private val availableCameras = mutableListOf<CameraInfo>()

    interface CameraCallback {
        fun onCameraOpened(cameraId: String)
        fun onCameraClosed()
        fun onCameraError(error: String)
        fun onPhotoSaved(filePath: String)
    }

    private var callback: CameraCallback? = null

    fun setCallback(cb: CameraCallback) { callback = cb }

    fun startBackgroundThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        Log.d(TAG, "Background thread started")
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join(1000) } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    /**
     * 扫描并识别所有可用相机 - 支持逻辑多摄
     */
    fun scanCameras(): List<CameraInfo> {
        availableCameras.clear()
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Found ${cameraIds.size} camera IDs: ${cameraIds.joinToString()}")

            for (id in cameraIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
                    val fov = calculateFov(chars, focalLengths)
                    val name = identifyCamera(id, facing, focalLengths, fov)

                    Log.d(TAG, "Camera $id: facing=$facing, focal=${focalLengths.joinToString()}, fov=$fov, name=$name")

                    val info = CameraInfo(id, facing, focalLengths, apertures, fov, name)
                    availableCameras.add(info)
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Failed to read camera $id: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanCameras failed: ${e.message}")
            callback?.onCameraError("扫描相机失败: ${e.message}")
        }

        Log.d(TAG, "Total cameras found: ${availableCameras.size}")
        return availableCameras.toList()
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
        if (availableCameras.isEmpty()) scanCameras()
        return availableCameras.toList()
    }

    /**
     * 获取后置相机列表（不包含前置）
     */
    fun getBackCameras(): List<CameraInfo> {
        return getAvailableCameras().filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    }

    fun getCurrentCameraId(): String = currentCameraId

    fun getCameraInfo(cameraId: String): CameraInfo? = availableCameras.find { it.id == cameraId }

    fun getCameraDisplayName(cameraId: String): String = getCameraInfo(cameraId)?.displayName ?: "相机 $cameraId"

    fun isVariableApertureSupported(cameraId: String): Boolean {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            apertures != null && apertures.size > 1
        } catch (e: Exception) { false }
    }

    fun openCamera(cameraId: String, surface: Surface) {
        Log.d(TAG, "Opening camera: $cameraId")
        currentCameraId = cameraId
        previewSurface = surface

        // 关闭旧的 ImageReader
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try {
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                callback?.onCameraError("没有相机权限")
                return
            }

            // 查询最优拍照分辨率
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val jpegSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val photoSize = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

            imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 1)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d(TAG, "Camera $cameraId opened successfully")
                    callback?.onCameraOpened(cameraId)
                    createPreviewSession(surface)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                    callback?.onCameraClosed()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera $cameraId error: $error")
                    cameraDevice?.close()
                    cameraDevice = null
                    callback?.onCameraError("相机 $cameraId 错误: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            callback?.onCameraError("没有相机权限")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera $cameraId: ${e.message}")
            callback?.onCameraError("无法访问相机 $cameraId: ${e.message}")
        } catch (e: IllegalArgumentException) {
            callback?.onCameraError("无效的相机ID: $cameraId")
        }
    }

    private fun createPreviewSession(surface: Surface) {
        try {
            val device = cameraDevice ?: return
            val reader = imageReader ?: return

            val surfaces = listOf(surface, reader.surface)
            device.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(surface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        callback?.onCameraError("预览配置失败")
                    }
                }, cameraHandler
            )
        } catch (e: CameraAccessException) {
            callback?.onCameraError("创建会话失败: ${e.message}")
        }
    }

    private fun startPreview(surface: Surface) {
        try {
            val device = cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            builder?.build()?.let {
                currentPreviewRequest = it
                captureSession?.setRepeatingRequest(it, null, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("预览失败: ${e.message}")
        }
    }

    /**
     * 拍照并保存
     */
    fun takePhoto() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "AI_Camera_$timestamp.jpg"

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/AI_Camera")
                    }

                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(bytes)
                        }
                        callback?.onPhotoSaved(it.toString())
                    }
                } catch (e: Exception) {
                    callback?.onCameraError("保存照片失败: ${e.message}")
                } finally {
                    image.close()
                }
            }, cameraHandler)

            captureBuilder?.build()?.let { session.capture(it, null, cameraHandler) }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("拍照失败: ${e.message}")
        }
    }

    /**
     * 设置曝光补偿
     */
    fun setExposureCompensation(value: Int) {
        try {
            val device = cameraDevice ?: return
            val chars = cameraManager.getCameraCharacteristics(currentCameraId)
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
            val clamped = value.coerceIn(range.lower, range.upper)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            builder?.build()?.let {
                currentPreviewRequest = it
                captureSession?.setRepeatingRequest(it, null, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setExposureCompensation failed: ${e.message}")
        }
    }

    /**
     * 设置ISO
     */
    fun setISO(iso: Int) {
        try {
            val device = cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 33333333) // 1/30s
            }
            builder?.build()?.let {
                currentPreviewRequest = it
                captureSession?.setRepeatingRequest(it, null, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setISO failed: ${e.message}")
        }
    }

    /**
     * 设置光圈（可变光圈相机）
     */
    fun setAperture(aperture: Float) {
        try {
            val device = cameraDevice ?: return
            if (!isVariableApertureSupported(currentCameraId)) return

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.LENS_APERTURE, aperture)
            }
            builder?.build()?.let {
                currentPreviewRequest = it
                captureSession?.setRepeatingRequest(it, null, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setAperture failed: ${e.message}")
        }
    }

    /**
     * 应用场景配置 - 自动调整曝光参数
     */
    fun applySceneConfig(config: SceneAnalyzer.SceneConfig) {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                // 应用曝光补偿
                val chars = cameraManager.getCameraCharacteristics(currentCameraId)
                val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (aeRange != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, config.exposureComp.coerceIn(aeRange.lower, aeRange.upper))
                }

                // 应用可变光圈（如果支持）
                if (isVariableApertureSupported(currentCameraId)) {
                    set(CaptureRequest.LENS_APERTURE, config.aperture)
                }
            }

            builder?.build()?.let {
                currentPreviewRequest = it
                session.setRepeatingRequest(it, null, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applySceneConfig failed: ${e.message}")
        }
    }

    /**
     * 切换到下一个后置相机（不在前后之间轮转）
     */
    fun switchCamera(surface: Surface) {
        val backCameras = getBackCameras()
        if (backCameras.isEmpty()) {
            scanCameras()
            val retry = getBackCameras()
            if (retry.isEmpty()) {
                callback?.onCameraError("未发现后置相机")
                return
            }
            switchCamera(surface)
            return
        }

        val currentIndex = backCameras.indexOfFirst { it.id == currentCameraId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % backCameras.size
        val nextCamera = backCameras[nextIndex]

        closeAndReopen(nextCamera.id, surface)
    }

    /**
     * 切换到指定焦段
     */
    fun switchToFocalLength(focalLengthType: String, surface: Surface) {
        val cameras = getAvailableCameras()
        val target = cameras.find { it.displayName.contains(focalLengthType) }
        if (target == null) {
            callback?.onCameraError("未找到 ${focalLengthType} 相机")
            return
        }
        if (target.id == currentCameraId) return

        closeAndReopen(target.id, surface)
    }

    private fun closeAndReopen(newCameraId: String, surface: Surface) {
        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Exception) {}
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}

        cameraHandler?.postDelayed({
            openCamera(newCameraId, surface)
        }, 150)
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
    }

    fun getCameraCount(): Int = cameraManager.cameraIdList.size
}
