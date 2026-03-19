package com.example.camera2flash.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

class CameraFragment(
    val lifecycle: LifecycleOwner,
    val context: Context,
    val cameraSelector: CameraSelector
) {
    companion object {
        private const val TAG = "CameraFragment"
    }

    private var mPreviewSize: Size? = null
    private var mTextureView: TextureView? = null
    private var mPreviewSurface: Surface? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mImageReader: ImageReader? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    var onSurfaceReady: (() -> Unit)? = null
    var onPictureTaken: ((File) -> Unit)? = null

    /**
     * Creates and initializes a [TextureView] instance to be used for the camera preview.
     *
     * This method configures the view for hardware acceleration, attaches the
     * [surfaceTextureListener] to handle lifecycle events, and updates the internal
     * [mTextureView] reference.
     *
     * @return A configured [TextureView] instance.
     */
    fun createTextureView(): TextureView {
        return TextureView(context).also {
            it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            it.surfaceTextureListener = surfaceTextureListener
            mTextureView = it
        }
    }

    /**
     * This listener would be used to react to the lifecycle events of the [TextureView].
     * In this example resizing is ignored, but we are listening to the surface texture being
     * ready to use. Once it's ready, the streaming to the underlying [Surface] is started.
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            onSurfaceReady?.invoke()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    /**
     * Camera2 requires a [HandlerThread] or an [Executor] to perform operations. As they can take
     * long it is important to not perform this on the main thread. For this example app we choose
     * to go with Handlers because all other requests require a Handler and not an executor.
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground").also { it.start() }
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted", e)
        }
        mBackgroundThread = null
        mBackgroundHandler = null
    }

    /**
     * Selects the most appropriate [Size] for the camera preview.
     *
     * This method attempts to find a 1080p (1920x1080) resolution first. If not available,
     * it falls back to the largest supported size provided by the [cameraSelector].
     * If no sizes are returned, it defaults to 1920x1080.
     *
     * @return The selected [Size] for the camera preview.
     */
    private fun choosePreviewSize(): Size {
        val sizes = cameraSelector.getSupportedPreviewSizes()
        return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    /**
     * Initializes and opens the back-facing camera device.
     *
     * This function performs several setup steps:
     * 1. Verifies that the [TextureView] is ready; if not, it defers the call until the surface is available.
     * 2. Identifies and configures the back camera ID.
     * 3. Starts a background [HandlerThread] for camera operations to avoid blocking the main thread.
     * 4. Determines the optimal preview size and initializes an [ImageReader] to handle still image captures.
     * 5. Sets up the [mPreviewSurface] and requests the [android.hardware.camera2.CameraManager] to open the device.
     *
     * When the camera is successfully opened, the [cameraStateCallback] is triggered to start the preview.
     *
     * @throws IllegalStateException if the [mTextureView] is null or if no back-facing camera is found.
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        val textureView = mTextureView ?: throw IllegalStateException("No texture view created")
        if (!textureView.isAvailable) {
            onSurfaceReady = { openCamera() }
            return
        }

        val cameraId = cameraSelector.getBackCameraId()
            ?: throw IllegalStateException("No back-facing camera found")

        cameraSelector.configureCamera(cameraId)
        startBackgroundThread()
        mPreviewSize = choosePreviewSize()

        // Set up ImageReader for still capture
        val captureSize = mPreviewSize!!
        mImageReader = ImageReader.newInstance(
            captureSize.width, captureSize.height,
            ImageFormat.JPEG, 1
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                onPictureTaken?.invoke(file)
            }, mBackgroundHandler)
        }

        initPreviewSurface()

        cameraSelector.getManager().openCamera(
            cameraId,
            cameraStateCallback,
            mBackgroundHandler
        )
    }

    /**
     * [CameraDevice.StateCallback] is called when the [CameraDevice] changes its state.
     *
     * It handles the lifecycle of the camera device, including successfully opening the camera
     * (triggering the preview start), handling disconnection, and managing error states by
     * closing the device and cleaning up references.
     */
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mCameraDevice = camera
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            mCameraDevice = null
            Log.e(TAG, "Camera device error: $error")
        }
    }

    /**
     * Configures the camera capture session and starts the camera preview stream.
     *
     * This function sets up the [CameraCaptureSession] with the necessary output surfaces
     * (the preview surface and the image reader for photo capture). It creates a
     * [CaptureRequest] using the [CameraDevice.TEMPLATE_PREVIEW] template and starts
     * a repeating request to ensure a continuous stream of frames is displayed on the UI.
     *
     * The preview utilizes the "preview" stream use case to optimize for low latency
     * and power efficiency on supported devices.
     */
    private fun startPreview() {
        val camera = mCameraDevice ?: return
        val imageReader = mImageReader ?: return
        val backgroundHandler = mBackgroundHandler ?: return
        val previewSurface = mPreviewSurface ?: return

        // Specify the targets where the camera should output frames to
        val targets = listOf<Surface>(previewSurface, imageReader.surface)

        // Configure the outputs and how they should be treated
        val configs = mutableListOf<OutputConfiguration>()
        val streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT

        targets.forEach {
            val config = OutputConfiguration(it)
            config.streamUseCase = streamUseCase.toLong()
            configs.add(config)
        }

        val executor = Executor { command -> backgroundHandler.post(command)}

        // Create a request to display preview to the user using the request builder
        mPreviewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
        }

        val previewRequest = mPreviewRequestBuilder!!.build()

        // Setup session and start repeating requests to display preview to user
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            configs,
            executor,
            object  : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                mCaptureSession = session
                session.setRepeatingRequest(previewRequest, repeatingCaptureCallback, mBackgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Capture session configuration failed")
            }
        })

        camera.createCaptureSession(sessionConfig)
    }

    /**
     * Initiates the process of capturing a still image with a forced flash sequence.
     *
     * This method performs the following steps:
     * 1. Updates the repeating preview request to enable [CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH].
     * 2. Launches a coroutine on [Dispatchers.IO] to handle the capture sequence asynchronously.
     * 3. Waits for the Auto-Exposure (AE) mode to update and executes a precapture sequence
     *    (AE/AWB convergence) to ensure the flash and exposure are ready.
     * 4. Dispatches a single [CameraDevice.TEMPLATE_STILL_CAPTURE] request to the [ImageReader] surface.
     * 5. Resets the repeating preview request to the standard AE mode once the capture is complete.
     *
     * The results of the capture are handled by the [mImageReader]'s listener, which saves the
     * image to the cache directory and triggers [onPictureTaken].
     */
    fun takePicture() {
        val camera = mCameraDevice ?: return
        val session = mCaptureSession ?: return
        val builder = mPreviewRequestBuilder ?: return

        /*
        To avoid having to have the flash mode enabled all the time during preview, we can store a reference
        to the builder and update its repeating request before performing the precapture sequence.
         */
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
        session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, mBackgroundHandler)

        /*
         The following is the core of the flash procedure. It is important that it is performed asynchronously
         using a different coroutine scope to allow the main thread to continue updating the preview.
         */
        lifecycle.lifecycleScope.launch(Dispatchers.IO) {
            /*
             We wait to continue until the flash mode has been switched
             */
            repeatingCaptureCallback.awaitAeModeUpdate(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            runPrecaptureSequence()

            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(mImageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }.build()

            session.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Picture taken with flash")
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, mBackgroundHandler)
                }
            }, mBackgroundHandler)
        }
    }

    /**
     * Executes the pre-capture sequence to prepare the camera for a still image capture with flash.
     *
     * This method triggers the Auto-Exposure (AE) precapture metering sequence by sending a
     * specific [CaptureRequest] with [CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START].
     * It then suspends execution until two conditions are met:
     * 1. The precapture trigger request itself is completed.
     * 2. The AE and Auto-White Balance (AWB) algorithms have converged to stable states,
     *    ensuring optimal exposure and color balance for the subsequent high-quality capture.
     *
     * @throws IllegalArgumentException if [mCaptureSession] or [mPreviewSurface] is null.
     */
    private suspend fun runPrecaptureSequence() {
        val session = mCaptureSession ?: throw IllegalArgumentException("No capture session set")
        val previewSurface = mPreviewSurface ?: throw IllegalArgumentException("No preview surface set")

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        /**
         * The [CompletableDeferred] is used to wait for the camera to acknowledge that the
         * AE precapture trigger request has been captured/processed. This does NOT mean the
         * precapture sequence is complete - it just confirms the trigger was successfully sent.
         */
        val precaptureDeferred = CompletableDeferred<Unit>()
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                precaptureDeferred.complete(Unit)
            }
        }, mBackgroundHandler)

        precaptureDeferred.await()

        /**
         * Now that the precapture trigger has been sent and acknowledged, we wait for the actual
         * precapture sequence to complete. This means waiting for AE (auto-exposure) and AWB
         * (auto white balance) to converge to stable values before taking the picture.
         */
        repeatingCaptureCallback.awaitAeAwbConvergence()
    }

    /**
     * Closes the current [CameraDevice], [CameraCaptureSession], and [ImageReader] to release
     * resources. This function also stops the background thread used for camera operations.
     *
     * It is essential to call this method when the camera is no longer needed (e.g., when
     * the activity or fragment is paused or destroyed) to ensure that the camera hardware
     * is available for other applications and to prevent memory leaks.
     */
    fun closeCamera() {
        mCaptureSession?.close()
        mCaptureSession = null
        mCameraDevice?.close()
        mCameraDevice = null
        mImageReader?.close()
        mImageReader = null
        stopBackgroundThread()
    }

    /**
     * Initializes the camera preview surface by configuring the [TextureView] transformation matrix
     * and setting the default buffer size for the [SurfaceTexture]. The [TextureView] is the
     * part used to display the preview. The incoming frames of the physical camera are
     * send to the underlying [SurfaceTexture].
     *
     *
     * This function calculates the correct orientation and scaling (letterboxed) based on the
     * current display rotation and camera characteristics to ensure the preview is not stretched
     * or incorrectly rotated.
     *
     * @throws IllegalArgumentException if [mTextureView] or [mPreviewSize] has not been initialized.
     */
    fun initPreviewSurface() {
        val textureView = mTextureView ?: throw IllegalArgumentException("No texture view set")
        val previewSize = mPreviewSize ?: throw IllegalArgumentException("No preview size set")
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val previewTransform = CameraUtils.createTransformMatrix(
            containerView = textureView,
            previewSize = previewSize,
            characteristics = cameraSelector.getCurrentCameraCharacteristics(),
            surfaceRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY).rotation,
            cropMode = CameraUtils.CropMode.CROP_LETTERBOXED
        )

        textureView.setTransform(previewTransform)
        val texture = textureView.surfaceTexture?.apply {
            setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        mPreviewSurface = Surface(texture)

    }

    /**
     * A [CameraCaptureSession.CaptureCallback] used to monitor the state of the repeating
     * preview request.
     *
     * This callback tracks [CaptureResult] metadata to provide synchronization primitives
     * for asynchronous camera operations. It allows the application to:
     * 1. Suspend execution until a specific Auto-Exposure (AE) mode has been applied by
     *    the camera hardware ([awaitAeModeUpdate]).
     * 2. Suspend execution until both AE and Auto-White Balance (AWB) have reached a
     *    converged state ([awaitAeAwbConvergence]), which is critical for consistent
     *    image quality before a still capture.
     */
    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private var targetAeMode: Int? = null
        private var aeModeUpdateDeferred: CompletableDeferred<Unit>? = null
        private var convergenceDeferred: CompletableDeferred<Unit>? = null

        suspend fun awaitAeModeUpdate(targetAeMode: Int) {
            this.targetAeMode = targetAeMode
            aeModeUpdateDeferred = CompletableDeferred()
            aeModeUpdateDeferred?.await()
        }

        suspend fun awaitAeAwbConvergence() {
            convergenceDeferred = CompletableDeferred()
            convergenceDeferred?.await()
        }

        private fun process(result: CaptureResult) {
            // Checks if AE mode is updated and completes any awaiting Deferred
            aeModeUpdateDeferred?.let {
                val aeMode = result[CaptureResult.CONTROL_AE_MODE]
                if (aeMode == targetAeMode) {
                    it.complete(Unit)
                    aeModeUpdateDeferred = null
                }
            }

            // Checks for convergence and completes any awaiting Deferred
            convergenceDeferred?.let {
                val aeState = result[CaptureResult.CONTROL_AE_STATE]
                val awbState = result[CaptureResult.CONTROL_AWB_STATE]

                val isAeReady = (
                        aeState == null
                                || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        )

                val isAwbReady = (
                        awbState == null
                                || awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
                        )

                if (isAeReady && isAwbReady) {
                    it.complete(Unit)
                    convergenceDeferred = null
                }
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }
}
