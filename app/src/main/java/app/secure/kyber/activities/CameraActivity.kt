package app.secure.kyber.activities

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.app.Activity
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import app.secure.kyber.R
import app.secure.kyber.dataClasses.SelectedMediaParcelable
import kotlin.math.abs

/**
 * Blocks free finger-scrolling but detects intentional left/right swipes and
 * fires [onSwipeListener]. Taps still fall through to child TextViews.
 */
class SwipeableModeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    var onSwipeListener: ((goRight: Boolean) -> Unit)? = null

    private val swipeSlop = (resources.displayMetrics.density * 24).toInt()
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > swipeSlop && dx > dy * 1.5f) return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP   -> {
                val dx = ev.x - downX
                if (abs(dx) > swipeSlop) onSwipeListener?.invoke(dx < 0)
            }
        }
        return true // consume — never actually scrolls
    }
}

class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnFlipCamera: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var tvZoom: TextView
    private lateinit var mode0: TextView
    private lateinit var mode1: TextView
    private lateinit var mode2: TextView
    private lateinit var modeScrollView: SwipeableModeScrollView
    private lateinit var modeContainer: LinearLayout
    private lateinit var selectedOption: TextView
    private lateinit var pauseIcon: ImageView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    private var currentMode = "photo"
    private var isTorchOn = false
    private var isFlipping = false

    private val modeOrder = listOf("video", "photo", "videonote")

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) setResult(Activity.RESULT_OK, result.data)
        else setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )

        bindViews()
        setupModeSelector()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnClose.setOnClickListener { finish() }

        btnCapture.setOnClickListener {
            if (currentMode == "photo") takePhoto()
            else if (recording != null) stopVideo() else startVideo()
        }

        // ── Flip camera — 3-D card-flip ───────────────────────────────────────
        btnFlipCamera.setOnClickListener {
            if (isFlipping) return@setOnClickListener
            isFlipping = true

            val rotateOut = ObjectAnimator.ofFloat(btnFlipCamera, "rotationY", 0f, 90f).apply {
                duration = 160; interpolator = AccelerateDecelerateInterpolator()
            }
            val rotateIn = ObjectAnimator.ofFloat(btnFlipCamera, "rotationY", -90f, 0f).apply {
                duration = 160; interpolator = AccelerateDecelerateInterpolator()
            }
            val sxOut = ObjectAnimator.ofFloat(btnFlipCamera, "scaleX", 1f, 1.18f).apply { duration = 160 }
            val syOut = ObjectAnimator.ofFloat(btnFlipCamera, "scaleY", 1f, 1.18f).apply { duration = 160 }
            val sxIn  = ObjectAnimator.ofFloat(btnFlipCamera, "scaleX", 1.18f, 1f).apply {
                duration = 160; interpolator = OvershootInterpolator(2.5f)
            }
            val syIn  = ObjectAnimator.ofFloat(btnFlipCamera, "scaleY", 1.18f, 1f).apply {
                duration = 160; interpolator = OvershootInterpolator(2.5f)
            }
            val firstHalf  = AnimatorSet().apply { playTogether(rotateOut, sxOut, syOut) }
            val secondHalf = AnimatorSet().apply { playTogether(rotateIn,  sxIn,  syIn) }
            AnimatorSet().also { it.playSequentially(firstHalf, secondHalf); it.start() }

            firstHalf.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    startCamera()
                    isFlipping = false
                }
            })
        }

        // ── Torch toggle ──────────────────────────────────────────────────────
        btnFlash.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            btnFlash.animate().alpha(if (isTorchOn) 1f else 0.5f).setDuration(200).start()
        }

        btnGallery.setOnClickListener {
            startActivity(Intent(Intent.ACTION_PICK).apply { type = "image/* video/*" })
        }

        // ── Pinch-to-zoom ─────────────────────────────────────────────────────
        val scaleDetector = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    camera?.cameraControl?.setZoomRatio(currentZoom * detector.scaleFactor)
                    return true
                }
            })
        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    private fun bindViews() {
        previewView    = findViewById(R.id.cameraPreviewView)
        btnCapture     = findViewById(R.id.btnCapture)
        btnClose       = findViewById(R.id.btnClose)
        btnFlash       = findViewById(R.id.btnFlash)
        btnFlipCamera  = findViewById(R.id.btnFlipCamera)
        btnGallery     = findViewById(R.id.btnGallery)
        tvZoom         = findViewById(R.id.tvZoom)
        mode0          = findViewById(R.id.mode0)
        mode1          = findViewById(R.id.mode1)
        mode2          = findViewById(R.id.mode2)
        modeScrollView = findViewById(R.id.modeScrollView)
        modeContainer  = findViewById(R.id.modeContainer)
        pauseIcon = findViewById(R.id.pause_Image)
        selectedOption = mode1
    }

    // ── Mode selector ─────────────────────────────────────────────────────────

    private fun setupModeSelector() {
        // Add padding = half the scroll-view width on both sides.
        // This gives every pill (including first and last) enough room to
        // be scrolled to the exact horizontal centre of the screen.
        // view.left already INCLUDES this padding, so the scrollToCenter
        // formula is simply:  view.left - halfScreen + view.width/2
        modeScrollView.viewTreeObserver.addOnGlobalLayoutListener {
            val half = modeScrollView.width / 2
            if (modeContainer.paddingStart != half) {
                modeContainer.setPadding(half, 0, half, 0)
                // Centre the default (Photo) pill without animation
                mode1.post { scrollToCenter(mode1, animate = false) }
            }
        }

        // Tap to select
        mode0.setOnClickListener { selectMode("video") }
        mode1.setOnClickListener { selectMode("photo") }
        mode2.setOnClickListener { selectMode("videonote") }

        // Swipe left/right to step through modes
        modeScrollView.onSwipeListener = { goRight ->
            val next = modeOrder.indexOf(currentMode) + (if (goRight) 1 else -1)
            if (next in modeOrder.indices) selectMode(modeOrder[next])
        }

        applySelectionStyle(mode1)
        currentMode = "photo"
        selectedOption = mode1
    }

    private fun selectMode(mode: String) {
        if (recording != null || currentMode == mode) return

        val previousIndex = modeOrder.indexOf(currentMode)
        val newIndex      = modeOrder.indexOf(mode)
        val goingRight    = newIndex > previousIndex

        currentMode = mode

        // Old pill: shrink + dim out
        val oldPill = selectedOption
        clearSelectionStyle(oldPill)
        oldPill.animate()
            .alpha(0.55f).scaleX(0.90f).scaleY(0.90f)
            .setDuration(160).setInterpolator(DecelerateInterpolator())
            .start()

        // New pill: spring in
        selectedOption = when (mode) {
            "video"     -> { btnCapture.background = ContextCompat.getDrawable(this, R.drawable.button_video);  mode0 }
            "photo"     -> { btnCapture.background = ContextCompat.getDrawable(this, R.drawable.button_camera); mode1 }
            "videonote" -> { btnCapture.background = ContextCompat.getDrawable(this, R.drawable.button_video);  mode2 }
            else        -> mode1
        }
        applySelectionStyle(selectedOption)
        selectedOption.alpha = 0.5f; selectedOption.scaleX = 0.82f; selectedOption.scaleY = 0.82f
        selectedOption.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(280).setInterpolator(OvershootInterpolator(1.8f))
            .start()

        // Container nudge
        val nudge = 20f * resources.displayMetrics.density * (if (goingRight) -1f else 1f)
        modeContainer.animate().cancel()
        modeContainer.translationX = 0f
        modeContainer.animate()
            .translationX(nudge).setDuration(120).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                modeContainer.animate()
                    .translationX(0f).setDuration(220).setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }.start()

        // Scroll selected pill to the centre of the screen
        scrollToCenter(selectedOption, animate = true)
    }

    /**
     * Scrolls [view] to the horizontal centre of [modeScrollView].
     *
     * Because [modeContainer] has paddingStart = scrollViewWidth/2, every
     * pill's [view.left] already incorporates that offset.  The correct
     * target is therefore:
     *
     *   scrollX = view.left  -  (scrollViewWidth / 2)  +  (view.width / 2)
     *
     * For any pill this value is always ≥ 0 (the padding guarantees it),
     * so HorizontalScrollView never clamps to 0 and right-shift cannot occur.
     */
    private fun scrollToCenter(view: TextView, animate: Boolean) {
        view.post {
            val target = view.left - (modeScrollView.width / 2) + (view.width / 2)
            if (animate) modeScrollView.smoothScrollTo(target, 0)
            else         modeScrollView.scrollTo(target, 0)
        }
    }

    private fun applySelectionStyle(view: TextView) {
        view.background = ContextCompat.getDrawable(this, R.drawable.camera_mode_selected)
        view.typeface   = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun clearSelectionStyle(view: TextView) {
        view.background = null
        view.typeface   = android.graphics.Typeface.DEFAULT
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
                if (isTorchOn) camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {
                Log.e("CameraActivity", "Use case binding failed", e)
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(
            externalMediaDirs.firstOrNull(),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    goToPreview((output.savedUri ?: Uri.fromFile(photoFile)).toString(), "IMAGE")
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startVideo() {
        val videoCapture = videoCapture ?: return
        val videoFile = File(
            externalMediaDirs.firstOrNull(),
            "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        )
        recording = videoCapture.output
            .prepareRecording(this, FileOutputOptions.Builder(videoFile).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start    -> pauseIcon.visibility = View.VISIBLE
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) goToPreview(event.outputResults.outputUri.toString(), "VIDEO")
                        else { recording?.close(); recording = null; Log.e("CameraActivity", "Video error: ${event.error}") }
                        btnCapture.setImageResource(0)
                    }
                }
            }
    }

    private fun stopVideo() {
        pauseIcon.visibility = View.GONE
        recording?.stop(); recording = null
    }

    private fun goToPreview(uriString: String, type: String) {
        val intent = Intent(this, MediaPreviewActivity::class.java).apply {
            putParcelableArrayListExtra(
                MediaPreviewActivity.EXTRA_ITEMS,
                arrayListOf(SelectedMediaParcelable(uriString, type, ""))
            )
        }
        previewLauncher.launch(intent)
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}