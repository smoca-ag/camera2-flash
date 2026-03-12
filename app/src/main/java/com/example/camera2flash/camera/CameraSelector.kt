package com.example.camera2flash.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

class CameraSelector(
    var context: Context
) {
    private var activeCameraId: String? = null
    private val manager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun configureCamera(cameraId: String) {
        if (!manager.cameraIdList.contains(cameraId)) {
            throw IllegalArgumentException("Invalid camera ID")
        }
        activeCameraId = cameraId
    }

    fun getCurrentCameraCharacteristics(): CameraCharacteristics {
        activeCameraId?.let {
            return manager.getCameraCharacteristics(it)
        }
        throw IllegalStateException("No camera is currently selected")
    }

    fun getManager(): CameraManager = manager

    fun getBackCameraId(): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    fun getSupportedPreviewSizes(): Array<Size> {
        val configMap = getCurrentCameraCharacteristics()
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val highResSizes = configMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray<Size>()
        val surfaceTextureSizes = configMap?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray<Size>()

        return (highResSizes + surfaceTextureSizes)
    }

}