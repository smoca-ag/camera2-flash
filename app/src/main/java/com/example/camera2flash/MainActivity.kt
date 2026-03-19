package com.example.camera2flash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.camera2flash.camera.CameraFragment
import com.example.camera2flash.camera.CameraSelector
import com.example.camera2flash.ui.theme.Camera2FlashTheme

class MainActivity : ComponentActivity() {
    private lateinit var cameraFragment: CameraFragment
    private var isCameraOpen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cameraSelector = CameraSelector(this)
        cameraFragment = CameraFragment(this, this, cameraSelector)

        cameraFragment.onPictureTaken = { file ->
            runOnUiThread {
                Toast.makeText(this, "Picture saved: ${file.name}", Toast.LENGTH_SHORT).show()
            }
        }

        enableEdgeToEdge()
        setContent {
            Camera2FlashTheme {
                CameraScreen()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        cameraFragment.closeCamera()
        isCameraOpen.value = false
    }

    @Composable
    private fun CameraScreen() {
        var cameraOpen by isCameraOpen

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                cameraFragment.openCamera()
                cameraOpen = true
            } else {
                Toast.makeText(
                    this@MainActivity, "Camera permission denied", Toast.LENGTH_SHORT
                ).show()
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    factory = { cameraFragment.createTextureView() },
                    modifier = Modifier.fillMaxSize()
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!cameraOpen) {
                        Button(onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                cameraFragment.openCamera()
                                cameraOpen = true
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }) {
                            Text("Open Camera")
                        }
                    } else {
                        Button(onClick = {
                            cameraFragment.takePicture()
                        }) {
                            Text("Take picture with flash")
                        }
                    }
                }
            }
        }
    }
}
