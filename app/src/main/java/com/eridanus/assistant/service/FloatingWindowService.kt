package com.eridanus.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.eridanus.assistant.R
import com.eridanus.assistant.data.ConfigManager
import com.eridanus.assistant.net.EridanusApiClient
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class FloatingWindowService : Service() {

    private val tag = "FloatingWindowService"

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "eridanus_floating_service"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var configManager: ConfigManager
    private lateinit var apiClient: EridanusApiClient

    private var floatingBallView: View? = null
    private var dialogCardView: View? = null

    private var ballLayoutParams: WindowManager.LayoutParams? = null
    private var dialogLayoutParams: WindowManager.LayoutParams? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isAnalyzing = false
    private var isAutoPilotEnabled = false
    private var autoPilotJob: Job? = null
    private var lastRecognizedHash = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        configManager = ConfigManager(this)
        apiClient = EridanusApiClient(configManager.serverUrl, configManager.authToken)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            try {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                setupVirtualDisplay()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get MediaProjection", e)
            }
        }

        apiClient.updateBaseUrl(configManager.serverUrl)
        initFloatingViews()
        configManager.isFloatingRunning = true
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        try {
            imageReader?.close()
            virtualDisplay?.release()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(tag, "setupVirtualDisplay error", e)
        }
    }

    private fun getLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingViews() {
        val layoutInflater = LayoutInflater.from(this)

        // 1. 初始化悬浮球
        if (floatingBallView == null) {
            floatingBallView = layoutInflater.inflate(R.layout.layout_floating_ball, null)
            val ballSize = (56 * resources.displayMetrics.density).toInt()
            ballLayoutParams = WindowManager.LayoutParams(
                ballSize,
                ballSize,
                getLayoutType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - ballSize - 24
                y = screenHeight / 3
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false

            floatingBallView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = ballLayoutParams?.x ?: 0
                        initialY = ballLayoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true
                        }
                        ballLayoutParams?.x = initialX + dx
                        ballLayoutParams?.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(floatingBallView, ballLayoutParams)
                        } catch (e: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            toggleDialogCard()
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(floatingBallView, ballLayoutParams)
            } catch (e: Exception) {
                Log.e(tag, "addView floatingBallView failed", e)
            }
        }

        // 2. 初始化结果卡片窗口
        if (dialogCardView == null) {
            dialogCardView = layoutInflater.inflate(R.layout.layout_floating_dialog, null)
            dialogLayoutParams = WindowManager.LayoutParams(
                (320 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                getLayoutType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val tvTitle = dialogCardView?.findViewById<TextView>(R.id.tvTitle)
            tvTitle?.text = "${configManager.botName} \u52a9\u7406"

            dialogCardView?.findViewById<ImageView>(R.id.ivClose)?.setOnClickListener {
                hideDialogCard()
            }

            dialogCardView?.findViewById<Button>(R.id.btnReCapture)?.setOnClickListener {
                captureAndAnalyze(isAuto = false)
            }

            val switchAuto = dialogCardView?.findViewById<SwitchMaterial>(R.id.switchAutoPilot)
            switchAuto?.setOnCheckedChangeListener { _, isChecked ->
                isAutoPilotEnabled = isChecked
                if (isChecked) {
                    Toast.makeText(this, "\u5df2\u5f00\u542f\u4e3b\u52a8\u611f\u77e5\u6a21\u5f0f\uff0c\u6bcf\u96944\u79d2\u81ea\u52a8\u611f\u77e5\u5c4f\u5e55\u53d8\u5316", Toast.LENGTH_SHORT).show()
                    startAutoPilotMode()
                } else {
                    stopAutoPilotMode()
                }
            }

            dialogCardView?.visibility = View.GONE
            try {
                windowManager.addView(dialogCardView, dialogLayoutParams)
            } catch (e: Exception) {
                Log.e(tag, "addView dialogCardView failed", e)
            }
        }
    }

    private fun toggleDialogCard() {
        dialogCardView?.let { card ->
            if (card.visibility == View.VISIBLE) {
                hideDialogCard()
            } else {
                showDialogCard()
                captureAndAnalyze(isAuto = false)
            }
        }
    }

    private fun showDialogCard() {
        dialogCardView?.let { card ->
            card.visibility = View.VISIBLE
            try {
                windowManager.updateViewLayout(card, dialogLayoutParams)
            } catch (e: Exception) {}
        }
    }

    private fun hideDialogCard() {
        dialogCardView?.visibility = View.GONE
    }

    private fun startAutoPilotMode() {
        autoPilotJob?.cancel()
        autoPilotJob = serviceScope.launch {
            while (isActive && isAutoPilotEnabled) {
                delay(4000)
                if (!isAnalyzing && dialogCardView?.visibility != View.VISIBLE) {
                    captureAndAnalyze(isAuto = true)
                }
            }
        }
    }

    private fun stopAutoPilotMode() {
        autoPilotJob?.cancel()
        autoPilotJob = null
    }

    private suspend fun captureCurrentBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return@withContext null
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            cropped
        } catch (e: Exception) {
            Log.e(tag, "captureCurrentBitmap error", e)
            null
        } finally {
            image?.close()
        }
    }

    private fun captureAndAnalyze(isAuto: Boolean) {
        if (isAnalyzing) return
        isAnalyzing = true

        serviceScope.launch {
            val tvResult = dialogCardView?.findViewById<TextView>(R.id.tvResult)
            val pbLoading = dialogCardView?.findViewById<ProgressBar>(R.id.pbLoading)
            val ivReplyImage = dialogCardView?.findViewById<ImageView>(R.id.ivReplyImage)

            ivReplyImage?.visibility = View.GONE

            if (!isAuto) {
                showDialogCard()
                tvResult?.text = "\u6b63\u5728\u611f\u77e5\u5c4f\u5e55\u5185\u5bb9\uff0c\u52a9\u7406\u5206\u6790\u4e2d..."
                pbLoading?.visibility = View.VISIBLE
            }

            try {
                val bitmap = captureCurrentBitmap()
                if (bitmap == null) {
                    if (!isAuto) {
                        tvResult?.text = "\u672a\u83b7\u53d6\u5230\u5c4f\u5e55\u753b\u9762\uff0c\u8bf7\u786e\u8ba4\u5f55\u5c4f\u622a\u5c4f\u6743\u9650\u662f\u5426\u5df2\u6388\u4e88"
                        pbLoading?.visibility = View.GONE
                    }
                    isAnalyzing = false
                    return@launch
                }

                // 本地 OCR 初筛
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val recognizedText = withTimeoutOrNull(5000) {
                    val deferred = CompletableDeferred<String>()
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            deferred.complete(visionText.text)
                        }
                        .addOnFailureListener {
                            deferred.complete("")
                        }
                    deferred.await()
                } ?: ""

                val textHash = recognizedText.trim().hashCode()
                if (isAuto && textHash == lastRecognizedHash && recognizedText.isNotBlank()) {
                    bitmap.recycle()
                    isAnalyzing = false
                    return@launch
                }
                lastRecognizedHash = textHash

                if (isAuto) {
                    showDialogCard()
                    tvResult?.text = "\u68c0\u6d4b\u5230\u5c4f\u5e55\u53d8\u5316\uff0c\u52a9\u7406\u5206\u6790\u4e2d..."
                    pbLoading?.visibility = View.VISIBLE
                }

                val base64Image = withContext(Dispatchers.IO) {
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * 0.6).toInt(),
                        (bitmap.height * 0.6).toInt(),
                        true
                    )
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    val bytes = out.toByteArray()
                    scaled.recycle()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }

                val promptBuilder = StringBuilder()
                promptBuilder.append("\u3010\u667a\u80fd\u52a9\u7406\u00b7\u5c4f\u5e55\u7406\u89e3\u4e0e\u5206\u6790\u6307\u5bfc\u3011\n")
                if (recognizedText.isNotBlank()) {
                    promptBuilder.append("\u3010\u5c4f\u5e55OCR\u6587\u672c\u3011: ").append(recognizedText).append("\n\n")
                }
                promptBuilder.append("\u8bf7\u4f5c\u4e3a\u6211\u7684\u8d34\u8eab\u667a\u80fd\u52a9\u7406\uff0c\u4ed4\u7ec6\u67e5\u770b\u5f53\u524d\u5c4f\u5e55\u753b\u9762\uff1a\n")
                promptBuilder.append("1. \u82e5\u753b\u9762\u4e2d\u51fa\u73b0\u975e\u4e2d\u6587\u6216\u5176\u4ed6\u8bed\u8a00\u754c\u9762\uff0c\u8bf7\u7cbe\u51c6\u7ffb\u8bd1\u6838\u5fc3\u6587\u672c\u548c\u529f\u80fd\u6309\u94ae\uff1b\n")
                promptBuilder.append("2. \u7ed3\u5408\u6211\u7684\u4e0a\u4e0b\u6587\uff0c\u6e05\u6670\u6307\u51fa\u8be5\u754c\u9762\u5728\u8fdb\u884c\u4ec0\u4e48\u64cd\u4f5c\uff0c\u5e76\u7ed9\u51fa\u4e0b\u4e00\u6b65\u7b80\u660e\u5efa\u8bae\u3002")

                val result = apiClient.askAssistant(
                    prompt = promptBuilder.toString(),
                    imageBase64 = base64Image,
                    bindQqId = configManager.bindQqId,
                    atBot = true
                )

                pbLoading?.visibility = View.GONE
                if (result.isSuccess) {
                    val resp = result.getOrNull()
                    val reply = resp?.reply ?: resp?.message ?: "(\u65e0\u56de\u590d\u5185\u5bb9)"
                    tvResult?.text = reply
                    ivReplyImage?.visibility = View.GONE
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "\u8fde\u63a5\u5931\u8d25"
                    tvResult?.text = "\u52a9\u7406\u5206\u6790\u5931\u8d25: " + errorMsg
                }
            } catch (e: Exception) {
                pbLoading?.visibility = View.GONE
                tvResult?.text = "\u53d1\u751f\u5f02\u5e38: " + (e.message ?: "")
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eridanus \u60ac\u6d6e\u52a9\u7406\u670d\u52a1",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "\u4fdd\u6301\u60ac\u6d6e\u7403\u524d\u53f0\u8fd0\u884c\uff0c\u6355\u83b7\u5c4f\u5e55\u5e76\u63d0\u4f9b\u667a\u80fd\u5efa\u8bae"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${configManager.botName} \u52a9\u7406\u5df2\u8fd0\u884c")
            .setContentText("\u60ac\u6d6e\u7403\u5df2\u5e38\u9a7b\u5c4f\u5e55\u8fb9\u7f18\uff0c\u70b9\u51fb\u5373\u53ef\u6355\u83b7\u5206\u6790")
            .setSmallIcon(R.drawable.avatar_round)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoPilotMode()
        serviceScope.cancel()
        floatingBallView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        dialogCardView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        configManager.isFloatingRunning = false
    }
}