package com.zariapps.ringtonemaker

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

// ── Colors ──────────────────────────────────────────────────────────────────
private val BgColor       = Color(0xFF0D0B1A)
private val SurfaceColor  = Color(0xFF1A1730)
private val CardColor     = Color(0xFF221F3A)
private val AccentColor   = Color(0xFF7C6FFF)
private val AccentDark    = Color(0xFF4535B8)
private val TextPrimary   = Color(0xFFEAE8FF)
private val TextSecondary = Color(0xFF8B87B8)
private val WaveInactive  = Color(0xFF2D2B4A)
private val HandleColor   = Color(0xFFFFFFFF)
private val PlaylineColor = Color(0xFFFFD700)
private val SuccessColor  = Color(0xFF4CAF50)
private val ErrorColor    = Color(0xFFE53935)

// ── Data ─────────────────────────────────────────────────────────────────────
data class AudioInfo(val uri: Uri, val name: String, val duration: Long)

enum class SaveType(val label: String, val icon: String, val directory: String) {
    RINGTONE    ("Ringtone",     "📞", Environment.DIRECTORY_RINGTONES),
    NOTIFICATION("Notification", "🔔", Environment.DIRECTORY_NOTIFICATIONS),
    ALARM       ("Alarm",        "⏰", Environment.DIRECTORY_ALARMS),
    MUSIC       ("Music",        "🎵", Environment.DIRECTORY_MUSIC)
}

// ── Helpers ───────────────────────────────────────────────────────────────────
fun getAudioInfo(context: Context, uri: Uri): AudioInfo? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            else null
        } ?: "audio.mp3"
        AudioInfo(uri, name, duration)
    } catch (_: Exception) { null }
    finally { retriever.release() }
}

suspend fun generateWaveform(context: Context, uri: Uri, bars: Int = 120): FloatArray {
    return withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val sizes = mutableListOf<Int>()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i); break
                }
            }
            val buf = ByteBuffer.allocate(256 * 1024)
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                sizes.add(n); extractor.advance()
            }
        } catch (_: Exception) {
        } finally { extractor.release() }

        if (sizes.isEmpty()) return@withContext FloatArray(bars) { i ->
            (0.35f + kotlin.math.sin(i * 0.4f) * 0.3f).coerceIn(0.1f, 0.95f)
        }
        val maxSize = sizes.max().toFloat().coerceAtLeast(1f)
        val step = sizes.size.toFloat() / bars
        FloatArray(bars) { i ->
            val from = (i * step).toInt()
            val to   = min(((i + 1) * step).toInt(), sizes.size)
            if (from >= to) 0.2f
            else sizes.subList(from, to).average().toFloat() / maxSize
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
suspend fun trimAudio(context: Context, inputUri: Uri, startMs: Long, endMs: Long, outputFile: File): Boolean {
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context).build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (!cont.isCompleted) cont.resume(true)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (!cont.isCompleted) cont.resume(false)
                }
            })

            cont.invokeOnCancellation { transformer.cancel() }

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            try {
                transformer.start(mediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                if (!cont.isCompleted) cont.resume(false)
            }
        }
    }
}

suspend fun saveToMediaStore(
    context: Context,
    file: File,
    displayName: String,
    saveType: SaveType
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.m4a")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.IS_RINGTONE,     if (saveType == SaveType.RINGTONE)     1 else 0)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, if (saveType == SaveType.NOTIFICATION) 1 else 0)
                put(MediaStore.Audio.Media.IS_ALARM,        if (saveType == SaveType.ALARM)        1 else 0)
                put(MediaStore.Audio.Media.IS_MUSIC,        if (saveType == SaveType.MUSIC)        1 else 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, saveType.directory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val mediaUri = context.contentResolver
                .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return@withContext null
            context.contentResolver.openOutputStream(mediaUri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    mediaUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
            }
            mediaUri
        } catch (_: Exception) { null }
    }
}

fun setAsSystemRingtone(context: Context, uri: Uri, saveType: SaveType): Boolean {
    if (saveType == SaveType.MUSIC) return false
    if (!Settings.System.canWrite(context)) return false
    return try {
        val type = when (saveType) {
            SaveType.RINGTONE     -> RingtoneManager.TYPE_RINGTONE
            SaveType.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
            SaveType.ALARM        -> RingtoneManager.TYPE_ALARM
            else -> return false
        }
        RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
        true
    } catch (_: Exception) { false }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min    = totalSec / 60
    val sec    = totalSec % 60
    val centis = (ms % 1000) / 10
    return "%d:%02d.%02d".format(min, sec, centis)
}

// ── Activity ──────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RingtoneApp() }
    }
}

// ── Root composable ───────────────────────────────────────────────────────────
@Composable
fun RingtoneApp() {
    val context = LocalContext.current
    var audioInfo by remember { mutableStateOf<AudioInfo?>(null) }

    val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by remember {
        mutableStateOf(context.checkSelfPermission(readPermission) == PackageManager.PERMISSION_GRANTED)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            audioInfo = getAudioInfo(context, it)
        }
    }

    fun openPicker() {
        if (hasPermission) filePicker.launch(arrayOf("audio/*"))
        else permLauncher.launch(readPermission)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AnimatedContent(
            targetState = audioInfo,
            transitionSpec = { fadeIn() togetherWith fadeOut() }
        ) { info ->
            if (info == null) EmptyState(onPickFile = ::openPicker)
            else AudioEditorScreen(audioInfo = info, onPickNew = ::openPicker)
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────
@Composable
fun EmptyState(onPickFile: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(SurfaceColor),
                contentAlignment = Alignment.Center
            ) { Text("✂️", fontSize = 44.sp) }

            Text("Ringtone Maker", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)

            Text(
                "Trim any audio file into the perfect\nringtone, alarm, or notification",
                color = TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(AccentDark, AccentColor)))
                    .clickable(onClick = onPickFile)
                    .padding(horizontal = 40.dp, vertical = 16.dp)
            ) {
                Text("Select Audio File", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Text("MP3 · M4A · WAV · OGG · FLAC and more", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

// ── Editor screen ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditorScreen(audioInfo: AudioInfo, onPickNew: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var waveform      by remember { mutableStateOf<FloatArray?>(null) }
    var startFraction by remember { mutableFloatStateOf(0f) }
    var endFraction   by remember { mutableFloatStateOf(1f) }

    var isPlaying    by remember { mutableStateOf(false) }
    var playFraction by remember { mutableFloatStateOf(0f) }
    var player       by remember { mutableStateOf<MediaPlayer?>(null) }

    var outputName       by remember { mutableStateOf(audioInfo.name.substringBeforeLast('.') + "_trim") }
    var selectedSaveType by remember { mutableStateOf(SaveType.RINGTONE) }

    var isSaving                by remember { mutableStateOf(false) }
    var saveStatus              by remember { mutableStateOf<String?>(null) }
    var showWriteSettingsDialog by remember { mutableStateOf(false) }
    var pendingSavedUri         by remember { mutableStateOf<Uri?>(null) }

    val startMs   = (startFraction * audioInfo.duration).toLong()
    val endMs     = (endFraction   * audioInfo.duration).toLong()
    val trimDurMs = endMs - startMs

    LaunchedEffect(audioInfo.uri) { waveform = generateWaveform(context, audioInfo.uri) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val p = player
            if (p != null && p.isPlaying) {
                val cur = p.currentPosition.toLong()
                playFraction = cur.toFloat() / audioInfo.duration.coerceAtLeast(1)
                if (cur >= endMs) {
                    p.pause(); p.seekTo(startMs.toInt())
                    isPlaying = false; playFraction = startFraction
                }
            }
            delay(40)
        }
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    fun togglePlayback() {
        val p = player ?: MediaPlayer().also { mp ->
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            try { mp.setDataSource(context, audioInfo.uri); mp.prepare() } catch (_: Exception) {}
            player = mp
        }
        if (isPlaying) { p.pause(); isPlaying = false }
        else {
            try { p.seekTo(startMs.toInt()); p.start(); isPlaying = true } catch (_: Exception) {}
        }
    }

    suspend fun doCut() {
        isSaving = true; saveStatus = "Trimming audio…"
        val outputFile = File(context.cacheDir, "$outputName.m4a")
        if (!trimAudio(context, audioInfo.uri, startMs, endMs, outputFile)) {
            saveStatus = "Trim failed — try a different file"; isSaving = false; return
        }
        saveStatus = "Saving to device…"
        val mediaUri = saveToMediaStore(context, outputFile, outputName, selectedSaveType)
        outputFile.delete()
        if (mediaUri == null) { saveStatus = "Save failed"; isSaving = false; return }
        if (selectedSaveType != SaveType.MUSIC) {
            if (!Settings.System.canWrite(context)) {
                pendingSavedUri = mediaUri; showWriteSettingsDialog = true
            } else {
                setAsSystemRingtone(context, mediaUri, selectedSaveType)
            }
        }
        saveStatus = "✓  Saved as ${selectedSaveType.label}"
        isSaving = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("✂️  Ringtone Maker", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceColor)
                        .clickable(onClick = onPickNew).padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Change File", color = AccentColor, fontSize = 13.sp) }
            }

            // File info
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceColor).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(audioInfo.name, color = TextPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Total duration: ${formatTime(audioInfo.duration)}", color = TextSecondary, fontSize = 13.sp)
                }
            }

            // Waveform + trim
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceColor).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Trim", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                    if (waveform == null) {
                        Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f), color = AccentColor, trackColor = WaveInactive)
                        }
                    } else {
                        WaveformView(
                            waveform      = waveform!!,
                            startFraction = startFraction,
                            endFraction   = endFraction,
                            playFraction  = if (isPlaying) playFraction else -1f,
                            onStartChange = { startFraction = it },
                            onEndChange   = { endFraction   = it }
                        )
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Column {
                            Text("Start", color = TextSecondary, fontSize = 11.sp)
                            Text(formatTime(startMs), color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Duration", color = TextSecondary, fontSize = 11.sp)
                            Text(formatTime(trimDurMs), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("End", color = TextSecondary, fontSize = 11.sp)
                            Text(formatTime(endMs), color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Playback controls
            Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                Box(
                    modifier = Modifier.size(60.dp).clip(CircleShape)
                        .background(if (isPlaying) AccentColor else SurfaceColor)
                        .clickable { togglePlayback() },
                    contentAlignment = Alignment.Center
                ) { Text(if (isPlaying) "⏸" else "▶", fontSize = 24.sp) }
            }

            // Output name
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceColor).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Output Name", color = TextSecondary, fontSize = 12.sp)
                    TextField(
                        value         = outputName,
                        onValueChange = { outputName = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        textStyle     = TextStyle(fontSize = 14.sp, color = TextPrimary),
                        colors        = TextFieldDefaults.colors(
                            focusedContainerColor   = CardColor,
                            unfocusedContainerColor = CardColor,
                            focusedIndicatorColor   = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor        = TextPrimary,
                            unfocusedTextColor      = TextPrimary,
                            cursorColor             = AccentColor
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(".m4a", color = TextSecondary, fontSize = 11.sp)
                }
            }

            // Save type selector
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceColor).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save As", color = TextSecondary, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        SaveType.entries.forEach { type ->
                            val selected = type == selectedSaveType
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) AccentColor else CardColor)
                                    .clickable { selectedSaveType = type }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(type.icon, fontSize = 18.sp)
                                    Text(type.label,
                                        color = if (selected) Color.White else TextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }

            // Status banner
            saveStatus?.let { status ->
                val isError = status.contains("fail", ignoreCase = true) ||
                              status.contains("Could not", ignoreCase = true)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (isError) ErrorColor.copy(alpha = 0.15f) else SuccessColor.copy(alpha = 0.15f))
                        .padding(14.dp)
                ) {
                    Text(status, color = if (isError) ErrorColor else SuccessColor,
                        fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            // Cut & Save button
            Box(
                modifier = Modifier
                    .fillMaxWidth().height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSaving) SolidColor(SurfaceColor)
                        else Brush.linearGradient(listOf(AccentDark, AccentColor))
                    )
                    .clickable(enabled = !isSaving) { scope.launch { doCut() } },
                contentAlignment = Alignment.Center
            ) {
                if (isSaving) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.width(100.dp), color = AccentColor, trackColor = WaveInactive)
                        Text(saveStatus ?: "Processing…", color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    Text("✂  Cut & Save", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Write settings dialog
    if (showWriteSettingsDialog) {
        BasicAlertDialog(onDismissRequest = { showWriteSettingsDialog = false }) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(SurfaceColor).padding(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Set as ${selectedSaveType.label}?",
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "File saved! To also set it as your default ${selectedSaveType.label.lowercase()}, " +
                        "grant \"Modify system settings\" permission.",
                        color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CardColor)
                            .clickable { showWriteSettingsDialog = false }.padding(vertical = 12.dp),
                            Alignment.Center
                        ) { Text("Skip", color = TextSecondary, fontSize = 14.sp) }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AccentColor)
                            .clickable {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                                showWriteSettingsDialog = false
                            }.padding(vertical = 12.dp),
                            Alignment.Center
                        ) { Text("Grant", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

// ── Waveform canvas ───────────────────────────────────────────────────────────
@Composable
fun WaveformView(
    waveform: FloatArray,
    startFraction: Float,
    endFraction: Float,
    playFraction: Float,
    onStartChange: (Float) -> Unit,
    onEndChange: (Float) -> Unit
) {
    var totalWidth by remember { mutableFloatStateOf(1f) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .pointerInput(startFraction, endFraction) {
                detectDragGestures { change, _ ->
                    val x = (change.position.x / totalWidth).coerceIn(0f, 1f)
                    if (abs(x - startFraction) <= abs(x - endFraction))
                        onStartChange(x.coerceIn(0f, endFraction - 0.01f))
                    else
                        onEndChange(x.coerceIn(startFraction + 0.01f, 1f))
                }
            }
    ) {
        totalWidth = size.width
        val h       = size.height
        val centerY = h / 2f
        val bars    = waveform.size
        val barW    = size.width / bars

        waveform.forEachIndexed { i, amp ->
            val x        = i * barW + barW / 2f
            val fraction = i.toFloat() / bars
            val inRange  = fraction in startFraction..endFraction
            val barH     = amp * h * 0.85f
            drawLine(
                color       = if (inRange) AccentColor else WaveInactive,
                start       = Offset(x, centerY - barH / 2f),
                end         = Offset(x, centerY + barH / 2f),
                strokeWidth = (barW - 1f).coerceAtLeast(1.5f),
                cap         = StrokeCap.Round
            )
        }

        val sx = startFraction * size.width
        drawLine(HandleColor, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2.5f)
        drawCircle(HandleColor, radius = 7f, center = Offset(sx, centerY))

        val ex = endFraction * size.width
        drawLine(HandleColor, Offset(ex, 0f), Offset(ex, h), strokeWidth = 2.5f)
        drawCircle(HandleColor, radius = 7f, center = Offset(ex, centerY))

        if (playFraction >= 0f) {
            val px = playFraction * size.width
            drawLine(PlaylineColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
        }
    }
}
