package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.AppDatabase
import com.example.db.DownloadHistory
import com.example.downloader.DownloadEngine
import com.example.downloader.DownloadResult
import com.example.repository.DownloadRepository
import com.example.service.FloatingService
import android.os.Build
import androidx.lifecycle.viewModelScope
import android.media.MediaPlayer
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Architectural Model-View-ViewModel representing Dashboard State managers
class MainViewModel(private val repository: DownloadRepository) : ViewModel() {
    val historyState: StateFlow<List<DownloadHistory>> = repository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    var hasOverlayPermission by mutableStateOf(false)
    var isOverlayServiceRunning by mutableStateOf(false)

    fun checkPermissions(context: Context) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    fun deleteItem(history: DownloadHistory) {
        viewModelScope.launch {
            repository.delete(history)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}

class MainViewModelFactory(private val repository: DownloadRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repository) as T
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getInstance(this)
        val repository = DownloadRepository(database.downloadDao)

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModelFactory(repository)
            )

            // Auto-check overlay permissions on startup and resume
            LaunchedEffect(Unit) {
                mainViewModel.checkPermissions(this@MainActivity)
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981), // Emerald Accent Color
                    secondary = Color(0xFF34D399),
                    background = Color(0xFF0F172A), // Dark Slate
                    surface = Color(0xFF1E293B)
                )
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = mainViewModel,
                        onEnableOverlay = { requestOverlayPermission() },
                        onStartOverlayService = { toggleOverlayService(true) },
                        onStopOverlayService = { toggleOverlayService(false) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-analyze system overlay permissions if user returned from Settings
        val database = AppDatabase.getInstance(this)
        val repository = DownloadRepository(database.downloadDao)
        val vm = ViewModelProvider(this, MainViewModelFactory(repository))[MainViewModel::class.java]
        vm.checkPermissions(this)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Lütfen Solenz Utility Hub için Yüzen Pencere iznini açın.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggleOverlayService(start: Boolean) {
        val serviceIntent = Intent(this, FloatingService::class.java)
        if (start) {
            if (Settings.canDrawOverlays(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Yüzen Baloncuk aktifleşti!", Toast.LENGTH_SHORT).show()
            } else {
                requestOverlayPermission()
            }
        } else {
            stopService(serviceIntent)
            Toast.makeText(this, "Yüzen Baloncuk devredışı.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onEnableOverlay: () -> Unit,
    onStartOverlayService: () -> Unit,
    onStopOverlayService: () -> Unit
) {
    val context = LocalContext.current
    val systemHistory by viewModel.historyState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var manualUrl by remember { mutableStateOf("") }
    var forceFormatAudio by remember { mutableStateOf(true) }
    var directDownloadProgress by remember { mutableStateOf(0) }
    var directDownloadStatus by remember { mutableStateOf("") }
    var isDirectDownloading by remember { mutableStateOf(false) }
    var activePlayingItem by remember { mutableStateOf<DownloadHistory?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var useCustomBackendState by remember { mutableStateOf(DownloadEngine.USE_CUSTOM_BACKEND) }
    var customBackendUrlInput by remember { mutableStateOf(DownloadEngine.CUSTOM_BACKEND_URL) }

    // Double-check active service status
    var activeOverlayState by remember { mutableStateOf(false) }
    
    LaunchedEffect(viewModel.hasOverlayPermission) {
        activeOverlayState = viewModel.hasOverlayPermission
    }

    // Modern M3 Scroll Container - Light High Density Theme Background
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F9))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // SOLENZ STUDIO BRANDING HEADER
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 4.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFE1E2EC), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SOLENZ STUDIO",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF5E5E62),
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Utility Architect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFF001D35)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE0E2EC), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "V2.0",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1B1B1F)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { 
                        useCustomBackendState = DownloadEngine.USE_CUSTOM_BACKEND
                        customBackendUrlInput = DownloadEngine.CUSTOM_BACKEND_URL
                        showSettingsDialog = true 
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFFE0E2EC), CircleShape)
                        .testTag("settings_config_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Config",
                        tint = Color(0xFF1B1B1F),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // BUBBLE CONTROLS & DIAGNOSTICS CARD (System Service Status - Floating Bubble Toggle)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD7E3FF)),
                border = BorderStroke(1.dp, Color(0xFFA8C7FF)),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val statusColor = if (!viewModel.hasOverlayPermission) {
                                Color(0xFFF87171)
                            } else if (FloatingService.isServiceRunning) {
                                Color(0xFF005AC1)
                            } else {
                                Color(0xFF8E9099)
                            }
                            val statusText = if (!viewModel.hasOverlayPermission) {
                                "Yüzen pencere izni eksik"
                            } else if (FloatingService.isServiceRunning) {
                                "Yüzen Baloncuk: AKTİF"
                            } else {
                                "Yüzen Baloncuk: Pasif (Başlatın)"
                            }

                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(
                                            color = statusColor,
                                            shape = CircleShape
                                        )
                                )
                            }
                            Column {
                                Text(
                                    text = "Floating Assistant",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF001D35)
                                )
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    color = Color(0xFF001D35).copy(alpha = 0.7f)
                                )
                            }
                        }
                        
                        // Small overlay active indicator
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (!viewModel.hasOverlayPermission) {
                                    Icons.Default.Info
                                } else if (FloatingService.isServiceRunning) {
                                    Icons.Default.Check
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = "Durum",
                                tint = if (!viewModel.hasOverlayPermission) {
                                    Color(0xFFF87171)
                                } else if (FloatingService.isServiceRunning) {
                                    Color(0xFF005AC1)
                                } else {
                                    Color(0xFF8E9099)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!viewModel.hasOverlayPermission) {
                        Button(
                            onClick = onEnableOverlay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("enable_permission_button")
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "İzin", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pencere İznini Etkinleştir", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Overlay özelliği diğer uygulamaların üstünde sürüklenebilir baloncuk açabilmek için SYSTEM_ALERT_WINDOW iznine ihtiyaç duyar.",
                            fontSize = 10.sp,
                            color = Color(0xFF001D35).copy(alpha = 0.6f),
                            lineHeight = 14.sp
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartOverlayService,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("start_overlay_button")
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Baslat", tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Oynatıcı Başlat", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            }

                            Button(
                                onClick = onStopOverlayService,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stop_overlay_button")
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E2EC))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Durdur", tint = Color(0xFF1B1B1F))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Durdur", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1B1B1F))
                            }
                        }
                    }
                }
            }
        }

        // SOCIAL SHORTCUTS LAUNCHER (TikTok, YouTube, Insta, Chrome)
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    text = "HEDEF KISAYOLLAR",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFF5E5E62),
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShortcutLaunchCard(
                        modifier = Modifier.weight(1.0f),
                        title = "YouTube",
                        packageName = "com.google.android.youtube",
                        fallbackUrl = "https://youtube.com",
                        icon = Icons.Default.PlayArrow,
                        bgColor = Color(0xFFFF0000),
                        context = context
                    )

                    ShortcutLaunchCard(
                        modifier = Modifier.weight(1.0f),
                        title = "TikTok",
                        packageName = "com.zhiliaoapp.musically",
                        fallbackUrl = "https://tiktok.com",
                        icon = Icons.Default.PlayArrow,
                        bgColor = Color(0xFF000000),
                        context = context
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShortcutLaunchCard(
                        modifier = Modifier.weight(1.0f),
                        title = "Instagram",
                        packageName = "com.instagram.android",
                        fallbackUrl = "https://instagram.com",
                        icon = Icons.Default.Share,
                        bgColor = Color(0xFFE1306C),
                        context = context
                    )

                    ShortcutLaunchCard(
                        modifier = Modifier.weight(1.0f),
                        title = "Chrome",
                        packageName = "com.android.chrome",
                        fallbackUrl = "https://google.com",
                        icon = Icons.Default.Search,
                        bgColor = Color(0xFF4285F4),
                        context = context
                    )
                }
            }
        }

        // DIRECT/MANUAL DOWNLOAD PANEL
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE1E2EC)),
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Doğrudan Medya İndirici",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF001D35)
                    )
                    Text(
                        text = "Overlay kullanmadan doğrudan link yapıştırıp ayrıştırın",
                        fontSize = 11.sp,
                        color = Color(0xFF5E5E62)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        label = { Text("Ayrıştırılacak Medya Linki (URL)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF1B1B1F),
                            unfocusedTextColor = Color(0xFF1B1B1F),
                            focusedBorderColor = Color(0xFF005AC1),
                            unfocusedBorderColor = Color(0xFFE1E2EC),
                            focusedLabelColor = Color(0xFF005AC1),
                            unfocusedLabelColor = Color(0xFF5E5E62)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "İndirme Biçimi: ",
                            fontSize = 11.sp,
                            color = Color(0xFF5E5E62),
                            modifier = Modifier.weight(1.0f)
                        )
                        FilterChip(
                            selected = forceFormatAudio,
                            onClick = { forceFormatAudio = true },
                            label = { Text("MP3 Ses") },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        FilterChip(
                            selected = !forceFormatAudio,
                            onClick = { forceFormatAudio = false },
                            label = { Text("MP4 Video") }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isDirectDownloading) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { directDownloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF005AC1)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$directDownloadStatus (%$directDownloadProgress)",
                                fontSize = 11.sp,
                                color = Color(0xFF5E5E62)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (manualUrl.trim().isEmpty()) {
                                    Toast.makeText(context, "Hata: Boş Link!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isDirectDownloading = true
                                directDownloadProgress = 0
                                directDownloadStatus = "Ayrıştırılıyor..."
                                scope.launch {
                                    val result = DownloadEngine.downloadMedia(
                                        context = context,
                                        inputUrl = manualUrl,
                                        isAudioOnly = forceFormatAudio,
                                        onProgress = { progress -> directDownloadProgress = progress },
                                        onStatusChange = { s -> directDownloadStatus = s }
                                    )
                                    isDirectDownloading = false
                                    when (result) {
                                        is DownloadResult.Success -> {
                                            Toast.makeText(context, "İndirme Başarılı: ${result.title}", Toast.LENGTH_LONG).show()
                                            // Write into DB
                                            val database = AppDatabase.getInstance(context)
                                            database.downloadDao.insert(
                                                DownloadHistory(
                                                    title = result.title,
                                                    sourceUrl = manualUrl,
                                                    format = if (forceFormatAudio) "MP3" else "MP4",
                                                    fileSize = result.fileSize,
                                                    filePath = result.filePath
                                                )
                                            )
                                            manualUrl = ""
                                        }
                                        is DownloadResult.Error -> {
                                            Toast.makeText(context, "Hata: ${result.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("direct_download_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Indir", tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ayrıştır ve İndir", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // HISTORICAL DOWNLOADS LIST LEDGER
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AYRIŞTIRMA GEÇMİŞİ (${systemHistory.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFF5E5E62),
                    letterSpacing = 1.2.sp
                )
                if (systemHistory.isNotEmpty()) {
                    Text(
                        text = "Tümünü Temizle",
                        fontSize = 11.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { viewModel.clearAllHistory() }
                            .padding(6.dp)
                    )
                }
            }
        }

        if (systemHistory.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Empty",
                        tint = Color(0xFFE0E2EC),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Henüz ayrıştırılmış medya bulunmuyor.",
                        fontSize = 11.sp,
                        color = Color(0xFF5E5E62),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(systemHistory) { item ->
                HistoryLedgerRow(
                    ledgerItem = item,
                    onPlay = { activePlayingItem = item },
                    onDelete = { viewModel.deleteItem(item) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    activePlayingItem?.let { playingItem ->
        MediaPlayerDialog(
            item = playingItem,
            onDismiss = { activePlayingItem = null }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        DownloadEngine.USE_CUSTOM_BACKEND = useCustomBackendState
                        DownloadEngine.CUSTOM_BACKEND_URL = customBackendUrlInput
                        showSettingsDialog = false
                    },
                    modifier = Modifier.testTag("save_settings_button")
                ) {
                    Text("Kaydet", color = Color(0xFF005AC1), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("İptal", color = Color.Gray)
                }
            },
            title = {
                Text("Entegrasyon Ayarları", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Varsayılan çözücü sunucu yerine kendi canlıya aldığınız (Railway/Render vb.) backend sunucunuzu entegre edebilirsiniz.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Özel Backend Aktif", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Switch(
                            checked = useCustomBackendState,
                            onCheckedChange = { useCustomBackendState = it }
                        )
                    }
                    
                    if (useCustomBackendState) {
                        OutlinedTextField(
                            value = customBackendUrlInput,
                            onValueChange = { customBackendUrlInput = it },
                            label = { Text("API Sunucu URL (Custom REST API Root)") },
                            placeholder = { Text("https://your-api.railway.app") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF005AC1),
                                focusedLabelColor = Color(0xFF005AC1)
                            )
                        )
                        Text(
                            "Not: Sunucu kök dizini girilmeli. Otomatik olarak '/api/extract' eklenir.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            lineHeight = 14.sp
                        )
                    }
                }
            },
            containerColor = Color.White,
            titleContentColor = Color(0xFF001D35),
            textContentColor = Color(0xFF1B1B1F)
        )
    }
}

@Composable
fun ShortcutLaunchCard(
    modifier: Modifier = Modifier,
    title: String,
    packageName: String,
    fallbackUrl: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bgColor: Color,
    context: Context
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE1E2EC)),
        modifier = modifier
            .testTag("app_shortcut_${title.lowercase()}")
            .height(84.dp)
            .clickable {
                launchAppShortcut(context, packageName, fallbackUrl)
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(bgColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = bgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color(0xFF1B1B1F)
            )
        }
    }
}

fun launchAppShortcut(context: Context, packageName: String, fallbackUrl: String) {
    val pm = context.packageManager
    val launchIntent = pm.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
    } else {
        // Fallback standard Browser link launch so that everything always operates beautifully
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
        try {
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Tarayıcı başlatılamadı!", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun HistoryLedgerRow(
    ledgerItem: DownloadHistory,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val formatColor = if (ledgerItem.format == "MP3") Color(0xFF005AC1) else Color(0xFFE1306C)
    
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE1E2EC)),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elegant vertical left bar indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .background(formatColor, RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(12.dp))

            // Icon format indicator
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = formatColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (ledgerItem.format == "MP3") Icons.Default.Notifications else Icons.Default.PlayArrow,
                    contentDescription = ledgerItem.format,
                    tint = formatColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Main details list
            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = ledgerItem.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF001D35),
                    maxLines = 1
                )
                Text(
                    text = ledgerItem.sourceUrl,
                    fontSize = 10.sp,
                    color = Color(0xFF5E5E62),
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Biçim: ${ledgerItem.format}",
                        fontSize = 10.sp,
                        color = formatColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Boyut: ${ledgerItem.fileSize}",
                        fontSize = 10.sp,
                        color = Color(0xFF5E5E62)
                    )
                }
            }

            // Options: Play History Item
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(32.dp).testTag("play_med_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Oynat",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Options: Delete History Item
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Sil",
                    tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun MediaPlayerDialog(
    item: DownloadHistory,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(100f) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_player_button")
            ) {
                Text("Kapat", color = Color(0xFF10B981))
            }
        },
        title = {
            Text(
                text = item.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("media_player_container"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (item.format == "MP4") {
                    // Direct interactive High-Fidelity Video playback
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(Uri.parse(item.filePath))
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                        isPlaying = true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Videonuz yükleniyor ve oynatılıyor. Tam ekran veya yönlendirme desteklidir.",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                } else {
                    // Audio Playback with full interactive seeker duration state updates
                    val mediaPlayer = remember {
                        MediaPlayer().apply {
                            try {
                                setDataSource(context, Uri.parse(item.filePath))
                                prepare()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            try {
                                mediaPlayer.stop()
                                mediaPlayer.release()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    LaunchedEffect(mediaPlayer) {
                        try {
                            mediaPlayer.start()
                            isPlaying = true
                            duration = mediaPlayer.duration.toFloat()
                            while (isPlaying) {
                                currentPos = mediaPlayer.currentPosition.toFloat()
                                delay(500)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(Color(0xFF0F172A), CircleShape)
                            .border(4.dp, Color(0xFF10B981), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Müzik",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(44.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Slider(
                        value = currentPos,
                        onValueChange = { newVal ->
                            try {
                                currentPos = newVal
                                mediaPlayer.seekTo(newVal.toInt())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        valueRange = 0f..duration,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF10B981),
                            inactiveTrackColor = Color(0xFF334155),
                            thumbColor = Color(0xFF10B981)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatPlayTime(currentPos.toInt()), fontSize = 10.sp, color = Color.Gray)
                        Text(formatPlayTime(duration.toInt()), fontSize = 10.sp, color = Color.Gray)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                try {
                                    if (mediaPlayer.isPlaying) {
                                        mediaPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        mediaPlayer.start()
                                        isPlaying = true
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isPlaying) "DURAKLAT" else "OYNAT", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF1E293B),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

fun formatPlayTime(ms: Int): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
