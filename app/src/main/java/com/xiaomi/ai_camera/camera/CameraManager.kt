package com.xiaomi.ai_camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 小米14U专用Camera2管理器
 * 支持四摄切换、拍照、可变光圈控制
 */
class XiaomiCameraManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var currentCameraId = ""
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null

    // 相机信息
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
                    if (it.isNotEmpty()) {
                        val focal = it[0]
                        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 6.17f
                        (2.0 * Math.toDegrees(Math.atan((sensorWidth / 2).toDouble() / focal.toDouble()))).toFloat()
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
        currentCameraId = cameraId
        previewSurface = surface
        try {
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                callback?.onCameraError("没有相机权限")
                return
            }

            // 创建ImageReader用于拍照
            imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)

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
            val reader = imageReader ?: return

            // 预览 + 拍照两个输出
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
            builder?.build()?.let { captureSession?.setRepeatingRequest(it, null, cameraHandler) }
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

            // 设置拍照回调
            reader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    // 保存到相册
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

            captureBuilder?.build()?.let { captureRequest ->
                session.capture(captureRequest, null, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            callback?.onCameraError("拍照失败: ${e.message}")
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
     * 切换到下一个相机 - 修复Surface复用问题
     */
    fun switchCamera(surface: Surface) {
        val cameras = getAvailableCameras()
        if (cameras.isEmpty()) {
            scanCameras()
            return
        }

        val currentIndex = cameras.indexOfFirst { it.id == currentCameraId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % cameras.size
        val nextCamera = cameras[nextIndex]

        // 先关闭当前相机
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}

        // 稍等一下让相机释放
        cameraHandler?.postDelayed({
            openCamera(nextCamera.id, surface)
        }, 200)
    }

    /**
     * 切换到指定焦段
     */
    fun switchToFocalLength(focalLengthType: String, surface: Surface) {
        val cameras = getAvailableCameras()
        val target = cameras.find { it.displayName.contains(focalLengthType) }
        if (target != null && target.id != currentCameraId) {
            try {
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
            } catch (_: Exception) {}

            cameraHandler?.postDelayed({
                openCamera(target.id, surface)
            }, 200)
        }
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
