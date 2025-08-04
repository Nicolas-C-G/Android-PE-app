package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.framework.image.MPImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var landmarker: PoseLandmarker
    private lateinit var cameraExecutor: ExecutorService
    private val landmarksState = mutableStateListOf<NormalizedLandmark>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result: PoseLandmarkerResult, _: MPImage ->
                val landmarks = result.landmarks()
                if (landmarks.isNotEmpty()) {
                    val poseLandmarks = landmarks[0]
                    runOnUiThread {
                        landmarksState.clear()
                        landmarksState.addAll(poseLandmarks)
                    }
                }
            }
            .build()

        landmarker = PoseLandmarker.createFromOptions(this, options)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    PoseCameraScreen(landmarker, cameraExecutor, landmarksState)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        landmarker.close()
        cameraExecutor.shutdown()
    }
}

@Composable
fun PoseCameraScreen(
    poseLandmarker: PoseLandmarker,
    cameraExecutor: ExecutorService,
    landmarksState: SnapshotStateList<NormalizedLandmark>
) {
    val context = LocalContext.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && previewView != null) {
            startCamera(context, poseLandmarker, cameraExecutor, previewView!!)
        }
    }

    LaunchedEffect(previewView) {
        if (previewView == null) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera(context, poseLandmarker, cameraExecutor, previewView!!)
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    //scaleType = PreviewView.ScaleType.FILL_CENTER
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        //PoseOverlay(landmarksState)
        if (previewView != null) {
            PoseOverlay(landmarksState, previewView!!)
        }

        if (landmarksState.isEmpty()) {
            Text(
                "Point the camera at a person…",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun PoseOverlay(landmarks: List<NormalizedLandmark>, previewView: PreviewView) {
    Canvas(Modifier.fillMaxSize()) {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()

        val scaleX = size.width / viewWidth
        val scaleY = size.height / viewHeight

        val w = size.width
        val h = size.height

        val segments = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 7,
            0 to 4, 4 to 5, 5 to 6, 6 to 8,
            9 to 10, 11 to 12,
            11 to 13, 13 to 15, 15 to 17,
            12 to 14, 14 to 16, 16 to 18
        )

        segments.forEach { (s, e) ->
            if (s < landmarks.size && e < landmarks.size) {
                drawLine(
                    color = Color.Yellow,
                    start = Offset(landmarks[s].x() * w, landmarks[s].y() * h),
                    end = Offset(landmarks[e].x() * w, landmarks[e].y() * h),
                    strokeWidth = 4f
                )
            }
        }

        landmarks.forEach {
            drawCircle(
                color = Color.Red,
                center = Offset(it.x() * w, it.y() * h),
                radius = 6f
            )
        }
    }
}

fun startCamera(
    context: Context,
    landmarker: PoseLandmarker,
    executor: ExecutorService,
    previewView: PreviewView
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val provider = providerFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { proxy ->
            proxy.toBitmap()?.let { bitmap ->
                val mpImage = BitmapImageBuilder(bitmap).build()
                val options = ImageProcessingOptions.builder()
                    .setRotationDegrees(proxy.imageInfo.rotationDegrees)
                    .build()

                landmarker.detectAsync(mpImage, options, proxy.imageInfo.timestamp)
            }
            proxy.close()
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            context as LifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }, ContextCompat.getMainExecutor(context))
}
