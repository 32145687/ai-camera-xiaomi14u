package com.xiaomi.ai_camera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * 小米14U专用Camera2管理器
 * 动态检测四摄，支持无级可变光圈控制
 */
class XiaomiCameraManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentCameraId = ""

    // 动态检测到的相机信息
    data class CameraInfo(
        val id: String,
        val facing: Int,            // CameraCharacteristics.LENS_FACING
        val focalLengths: FloatArray,
        val apertures: FloatArray,
        val fovDegrees: Float,
        val displayName: String
    )

    private val availableCameras = mutableListOf<CameraInfo>()

    companion object {
        const val LENS_FACING_BACK = CameraCharacteristics.LENS_FACING_BACK
        const val LENS_FACING_FRONT = CameraCharacteristics.LENS_FACING_FRONT
    }

    interface CameraCallback {
        fun onCameraOpened(cameraId: String)
        fun onCameraClosed()
        fun onCameraError(error: String)
    }

    private var callback: CameraCallback? = null

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

    /**
     * 扫描并识别所有可用相机
     */
    fun scanCameras(): List<CameraInfo> {
        availableCameras.clear()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
                val fov = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
                    // 粗略估算FOV
                    if (it.isNotEmpty()) {
                        val focal = it[0]
                        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 6.17f
                        2 * kotlin.math.toDegrees(kotlin.math.atan((sensorWidth / 2).toDouble() / focal)).toFloat()
                    } else 60f
                } ?: 60f

                val name = identifyCamera(id, facing, focalLengths, fov)
                val info = CameraInfo(id, facing, focalLengths, apertures, fov, name)
                availableCameras.add(info)
            }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("扫描相机失败: ${e.message}")
        }
        return availableCameras.toList()
    }

    /**
     * 根据焦距和FOV识别摄像头类型
     */
    private fun identifyCamera(id: String, facing: Int, focalLengths: FloatArray, fov: Float): String {
        if (facing == LENS_FACING_FRONT) return "前置"
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

    fun getAvailableCameraIds(): List<String> = cameraManager.cameraIdList.toList()

    fun getCurrentCameraId(): String = currentCameraId

    fun getCameraInfo(cameraId: String): CameraInfo? {
        return availableCameras.find { it.id == cameraId }
    }

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics =
        cameraManager.getCameraCharacteristics(cameraId)

    fun isVariableApertureSupported(cameraId: String): Boolean {
        return try {
            val chars = getCameraCharacteristics(cameraId)
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            apertures != null && apertures.size > 1
        } catch (e: Exception) { false }
    }

    fun getApertureRange(cameraId: String): FloatArray? {
        return try {
            getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        } catch (e: Exception) { null }
    }

    fun openCamera(cameraId: String, surface: Surface) {
        currentCameraId = cameraId
        try {
            // 检查权限
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                callback?.onCameraError("没有相机权限")
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    callback?.onCameraOpened(cameraId)
                    createPreviewSession(surface)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                    callback?.onCameraClosed()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    callback?.onCameraError("相机 $cameraId 错误: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            callback?.onCameraError("没有相机权限")
        } catch (e: CameraAccessException) {
            callback?.onCameraError("无法访问相机 $cameraId: ${e.message}")
        } catch (e: IllegalArgumentException) {
            callback?.onCameraError("无效的相机ID: $cameraId")
        }
    }

    private fun createPreviewSession(surface: Surface) {
        try {
            val device = cameraDevice ?: return
            device.createCaptureSession(listOf(surface),
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
                // 尝试启用可变光圈
                try {
                    val chars = device.id.let { cameraManager.getCameraCharacteristics(it) }
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    if (apertures != null && apertures.size > 1) {
                        set(CaptureRequest.LENS_APERTURE, apertures[0])
                    }
                } catch (_: Exception) {}
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("预览失败: ${e.message}")
        }
    }

    fun setAperture(aperture: Float) {
        try {
            val device = cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                set(CaptureRequest.LENS_APERTURE, aperture)
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: Exception) {
            callback?.onCameraError("设置光圈失败: ${e.message}")
        }
    }

    fun setISO(iso: Int) {
        try {
            val device = cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: Exception) {
            callback?.onCameraError("设置ISO失败: ${e.message}")
        }
    }

    /**
     * 切换到下一个相机
     */
    fun switchCamera(surface: Surface) {
        val cameras = getAvailableCameras()
        if (cameras.isEmpty()) {
            scanCameras()
            return
        }
        closeCamera()

        val currentIndex = cameras.indexOfFirst { it.id == currentCameraId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % cameras.size
        val nextCamera = cameras[nextIndex]
        openCamera(nextCamera.id, surface)
    }

    /**
     * 切换到指定焦段
     */
    fun switchToFocalLength(focalLengthType: String, surface: Surface) {
        val cameras = getAvailableCameras()
        val target = cameras.find { it.displayName.contains(focalLengthType) }
        if (target != null) {
            closeCamera()
            openCamera(target.id, surface)
        }
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}
    }

    fun getCameraDisplayName(cameraId: String): String {
        return getCameraInfo(cameraId)?.displayName ?: "相机 $cameraId"
    }

    fun getCameraCount(): Int {
        return cameraManager.cameraIdList.size
    }
}
