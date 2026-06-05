package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.DownloadHistory
import com.example.downloader.DownloadEngine
import com.example.downloader.DownloadResult
import kotlinx.coroutines.*

class FloatingService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "START_FLOATING"
        const val ACTION_STOP = "STOP_FLOATING"
        
        var isServiceRunning by mutableStateOf(false)
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var parentView: FrameLayout
    private lateinit var lfParams: WindowManager.LayoutParams
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Lifecycle interfaces required for Jetpack Compose inside WindowManager Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private var isExpanded by mutableStateOf(false)
    private var downloadUrl by mutableStateOf("")
    private var downloadProgress by mutableStateOf(0)
    private var downloadStatus by mutableStateOf("")
    private var isDownloading by mutableStateOf(false)

    private var lastDetectedUrl = ""
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        processClipboard()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startNotificationForeground()
        setupOverlayWindow()
        registerClipboardListener()
    }

    private fun registerClipboardListener() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Start proactive main-thread scanner pool to bypass background platform limits
        serviceScope.launch {
            while (isActive) {
                delay(1500)
                processClipboard()
            }
        }
    }

    private fun unregisterClipboardListener() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val clean = clipText.trim()
                if (isValidMediaUrl(clean)) {
                    if (clean != lastDetectedUrl) {
                        lastDetectedUrl = clean
                        downloadUrl = clean
                        
                        // Let's automatically open the download view
                        if (!isExpanded) {
                            isExpanded = true
                            lfParams.flags = lfParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                            try {
                                windowManager.updateViewLayout(parentView, lfParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        vibrateFeedback()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isValidMediaUrl(url: String): Boolean {
        val clean = url.trim().lowercase()
        return (clean.startsWith("http://") || clean.startsWith("https://")) && (
            clean.contains("youtube.com") || clean.contains("youtu.be") ||
            clean.contains("tiktok.com") || clean.contains("instagram.com") ||
            clean.contains("twitter.com") || clean.contains("x.com") ||
            clean.endsWith(".mp3") || clean.endsWith(".mp4")
        )
    }

    private fun vibrateFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startNotificationForeground() {
        val channelId = "solenz_utility_channel"
        val channelName = "Solenz Yardımcı Araçları"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, RandomUtils.nextInt(), openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Solenz Yüzen Baloncuk Aktif")
            .setContentText("Sosyal medyada dolaşırken link yakalamak için hazır.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1999, notification)
    }

    private fun setupOverlayWindow() {
        // Base Layout Containers
        parentView = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeViewModelStoreOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
        }
        
        lfParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 120
            y = 350
        }

        val composeView = ComposeView(this).apply {
            // Register Lifecycle Owner variables
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeViewModelStoreOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            
            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFF005AC1), // High Density Corporate Blue
                        background = Color(0xFFF3F4F9),
                        surface = Color(0xFFFFFFFF),
                        outline = Color(0xFFE1E2EC)
                    )
                ) {
                    FloatingOverlayContent()
                }
            }
        }

        parentView.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Let's implement full layout dragging on Touch
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        parentView.setOnTouchListener { _, event ->
            if (isExpanded) {
                // Drag is disabled during expanded dialogs
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lfParams.x
                    initialY = lfParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lfParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    lfParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(parentView, lfParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (deltaX * deltaX + deltaY * deltaY < 120) {
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(parentView, lfParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        // If expanded, remove NOT_FOCUSABLE flag so dialog inputs can handle typing and keyboard pops up.
        if (isExpanded) {
            lfParams.flags = lfParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // When opening, automatically try to read clipboard URL
            tryToFetchClipboard()
        } else {
            lfParams.flags = lfParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(parentView, lfParams)
    }

    private fun tryToFetchClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (clipText.startsWith("http://") || clipText.startsWith("https://")) {
                    downloadUrl = clipText
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    fun FloatingOverlayContent() {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.wrapContentSize()
        ) {
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut()
                },
                label = "OverlayTransition"
            ) { expandedState ->
                if (expandedState) {
                    ExpandedDownloadPanel()
                } else {
                    CompactBubbleWidget()
                }
            }
        }
    }

    @Composable
    fun CompactBubbleWidget() {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(
                    color = if (isDownloading) Color(0xFF003070) else Color(0xFF005AC1),
                    shape = CircleShape
                )
                .border(2.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            toggleExpanded()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            lfParams.x = lfParams.x + dragAmount.x.toInt()
                            lfParams.y = lfParams.y + dragAmount.y.toInt()
                            try {
                                windowManager.updateViewLayout(parentView, lfParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
            Icon(
                imageVector = if (isDownloading) Icons.Default.Refresh else Icons.Default.PlayArrow,
                contentDescription = "Yüzen Araç",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }

    @Composable
    fun ExpandedDownloadPanel() {
        Column(
            modifier = Modifier
                .width(280.dp)
                .background(Color.White)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Solenz İndirici",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF005AC1),
                    fontSize = 15.sp
                )
                IconButton(
                    onClick = { toggleExpanded() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = Color(0xFF5E5E62),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // URL input box
            OutlinedTextField(
                value = downloadUrl,
                onValueChange = { downloadUrl = it },
                label = { Text("Medya Link (Tiktok, YT, Insta...)") },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color(0xFF1B1B1F)),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF1B1B1F),
                    unfocusedTextColor = Color(0xFF1B1B1F),
                    focusedBorderColor = Color(0xFF005AC1),
                    unfocusedBorderColor = Color(0xFFE1E2EC),
                    focusedLabelColor = Color(0xFF005AC1),
                    unfocusedLabelColor = Color(0xFF5E5E62)
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { tryToFetchClipboard() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Yapıştır",
                            tint = Color(0xFF005AC1)
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isDownloading) {
                // Display download progression
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF005AC1)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "$downloadStatus (%$downloadProgress)",
                        fontSize = 11.sp,
                        color = Color(0xFF5E5E62)
                    )
                }
            } else {
                // Action Download Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startDownloadProcess(isAudioOnly = true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1)),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, contentDescription = "Ses", modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SES MP3", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Button(
                        onClick = { startDownloadProcess(isAudioOnly = false) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF001D35)),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Video", modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("VİDEO MP4", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun startDownloadProcess(isAudioOnly: Boolean) {
        if (downloadUrl.trim().isEmpty()) {
            downloadStatus = "Lütfen geçerli bir Link yapıştırın!"
            return
        }

        isDownloading = true
        downloadProgress = 0
        downloadStatus = "Başlatılıyor..."

        serviceScope.launch {
            val result = DownloadEngine.downloadMedia(
                context = this@FloatingService,
                inputUrl = downloadUrl,
                isAudioOnly = isAudioOnly,
                onProgress = { progress -> downloadProgress = progress },
                onStatusChange = { status -> downloadStatus = status }
            )

            isDownloading = false
            when (result) {
                is DownloadResult.Success -> {
                    downloadStatus = "İndirildi: ${result.title}"
                    // Save item in Local Room Database 
                    try {
                        val db = AppDatabase.getInstance(this@FloatingService)
                        db.downloadDao.insert(
                            DownloadHistory(
                                title = result.title,
                                sourceUrl = downloadUrl,
                                format = if (isAudioOnly) "MP3" else "MP4",
                                fileSize = result.fileSize,
                                filePath = result.filePath
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    toggleExpanded() // Collapse back on complete
                }
                is DownloadResult.Error -> {
                    downloadStatus = "Hata: ${result.message}"
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_STOP -> stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        unregisterClipboardListener()
        // Destroy UI references safely
        try {
            windowManager.removeView(parentView)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        
        serviceJob.cancel()
        super.onDestroy()
    }
}

object RandomUtils {
    private val random = java.util.Random()
    fun nextInt(): Int = random.nextInt(Integer.MAX_VALUE)
}
