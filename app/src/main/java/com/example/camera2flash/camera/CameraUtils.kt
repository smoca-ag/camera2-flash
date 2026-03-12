package com.example.camera2flash.camera

import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.TextureView
import kotlin.math.max
import kotlin.math.min

object CameraUtils {

    enum class CropMode {
        CROP_FILL,
        CROP_LETTERBOXED
    }

    /**
     * Configures the transform [Matrix] for a [TextureView] to correctly display the camera preview.
     *
     * This function accounts for device orientation, sensor orientation, and aspect ratio differences
     * between the [containerView] and the [previewSize]. It calculates the necessary scaling and
     * rotation to ensure the preview is centered and fits within the view without being stretched.
     *
     * @param containerView The [TextureView] where the camera preview is being rendered.
     * @param previewSize The dimensions of the camera preview stream.
     * @param characteristics The characteristics of the camera device being used.
     * @param surfaceRotation The current rotation of the display (e.g., Surface.ROTATION_0).
     * @param cropMode The cropping mode to apply to the preview.
     * @return A [Matrix] that should be applied to the [TextureView] via [TextureView.setTransform].
     */
    fun createTransformMatrix(
        containerView: TextureView,
        previewSize: Size,
        characteristics: CameraCharacteristics,
        surfaceRotation: Int,
        cropMode: CropMode
    ): Matrix {
        val surfaceRotationDegrees = surfaceRotation * 90
        val windowSize = Size(containerView.width, containerView.height)
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val isRotationRequired =
            computeRelativeRotation(characteristics, surfaceRotationDegrees) % 180 != 0

        val swapDimensions = (sensorOrientation == 0) == !isRotationRequired
        val scaleX = windowSize.width.toFloat() / if (swapDimensions) previewSize.height else previewSize.width
        val scaleY = windowSize.height.toFloat() / if (swapDimensions) previewSize.width else previewSize.height

        /* Scale factor required to fit the preview to the TextureView size */
        val finalScale = if (cropMode == CropMode.CROP_LETTERBOXED) min(scaleX, scaleY) else max(scaleX, scaleY)
        val halfWidth = windowSize.width / 2f
        val halfHeight = windowSize.height / 2f

        val matrix = Matrix()

        if (isRotationRequired) {
            matrix.setScale(
                1 / scaleX * finalScale,
                1 / scaleY * finalScale,
                halfWidth,
                halfHeight
            )
        } else {
            matrix.setScale(
                windowSize.height / windowSize.width.toFloat() / scaleY * finalScale,
                windowSize.width / windowSize.height.toFloat() / scaleX * finalScale,
                halfWidth,
                halfHeight
            )
        }

        // Rotate to compensate display rotation
        matrix.postRotate(
            -surfaceRotationDegrees.toFloat(),
            halfWidth,
            halfHeight
        )

        return matrix
    }

    private fun computeRelativeRotation(
        characteristics: CameraCharacteristics,
        deviceOrientationDegrees: Int
    ): Int {
        val sensorOrientationDegrees =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Reverse device orientation for front-facing cameras
        val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        ) 1 else -1

        return (sensorOrientationDegrees - (deviceOrientationDegrees * sign) + 360) % 360
    }
}