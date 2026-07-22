package com.xiaomi.ai_camera.camera

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

/**
 * 小米14U专用Camera2管理器
 * 支持四摄切换、无级可变光圈控制
 */
class CameraManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentCameraId = CAMERA_MAIN

    companion object {
        const val CAMERA_MAIN = "0"         // 主摄 23mm LYT-900
        const val CAMERA_ULTRA_WIDE = "1"   // 超广角 12mm
        const val CAMERA_TELE_75MM = "2"    // 浮动长焦 75mm
        const val CAMERA_TELE_120MM = "3"   // 潜望长焦 120mm
    }

    interface CameraCallback {
        fun onCameraOpened(cameraId: String)
        fun onCameraClosed()
        fun onCameraError(error: String)
    }

    private var callback: CameraCallback? = null

    fun setCallback(cb: CameraCallback) { callback = cb }

    fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    fun getAvailableCameras(): List<String> = cameraManager.cameraIdList.toList()

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics =
        cameraManager.getCameraCharacteristics(cameraId)

    fun isVariableApertureSupported(cameraId: String): Boolean {
        val chars = getCameraCharacteristics(cameraId)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        return apertures != null && apertures.size > 1
    }

    fun getApertureRange(cameraId: String): FloatArray? {
        return getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
    }

    fun openCamera(cameraId: String, surface: Surface) {
        currentCameraId = cameraId
        try {
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
                    callback?.onCameraError("相机错误: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            callback?.onCameraError("没有相机权限")
        } catch (e: CameraAccessException) {
            callback?.onCameraError("无法访问相机: ${e.message}")
        }
    }

    private fun createPreviewSession(surface: Surface) {
        try {
            cameraDevice?.createCaptureSession(listOf(surface),
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
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("预览失败: ${e.message}")
        }
    }

    fun setAperture(aperture: Float) {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                set(CaptureRequest.LENS_APERTURE, aperture)
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: Exception) {
            callback?.onCameraError("设置光圈失败: ${e.message}")
        }
    }

    fun setISO(iso: Int) {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
        } catch (e: Exception) {
            callback?.onCameraError("设置ISO失败: ${e.message}")
        }
    }

    fun switchCamera(surface: Surface) {
        closeCamera()
        val cameras = getAvailableCameras()
        val idx = cameras.indexOf(currentCameraId)
        val next = cameras[(idx + 1) % cameras.size]
        openCamera(next, surface)
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    fun getCameraName(cameraId: String): String = when (cameraId) {
        CAMERA_MAIN -> "主摄 23mm"
        CAMERA_ULTRA_WIDE -> "超广角 12mm"
        CAMERA_TELE_75MM -> "长焦 75mm"
        CAMERA_TELE_120MM -> "潜望 120mm"
        else -> "相机 $cameraId"
    }
}
