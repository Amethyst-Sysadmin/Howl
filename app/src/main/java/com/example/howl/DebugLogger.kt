package com.example.howl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Log Level ────────────────────────────────────────────────────────────────

enum class LogLevel(val label: String, val priority: Int) {
    VERBOSE("V", Log.VERBOSE),
    DEBUG("D", Log.DEBUG),
    INFO("I", Log.INFO),
    WARN("W", Log.WARN),
    ERROR("E", Log.ERROR);

    /** Subtle tint applied in the UI – readable on both light and dark surfaces. */
    val color: Color
        get() = when (this) {
            VERBOSE -> Color(0xFF9E9E9E) // neutral grey
            DEBUG   -> Color(0xFF7CB342) // muted green
            INFO    -> Color(0xFF42A5F5) // soft blue
            WARN    -> Color(0xFFFFA726) // amber
            ERROR   -> Color(0xFFEF5350) // soft red
        }
}

// ─── Log Entry ────────────────────────────────────────────────────────────────

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val exception: Throwable? = null
)

// ─── HLog ─────────────────────────────────────────────────────────────────────

object HLog {
    const val MAX_LOG_ENTRIES = 100

    private val logBuffer = CircularBuffer<LogEntry>(MAX_LOG_ENTRIES)
    private val _logStateFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logStateFlow: StateFlow<List<LogEntry>> = _logStateFlow.asStateFlow()

    private val mutex = Mutex()
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Public per-level convenience methods ──────────────────────────────

    fun v(tag: String, message: String, exception: Throwable? = null) =
        log(LogLevel.VERBOSE, tag, message, exception)

    fun d(tag: String, message: String, exception: Throwable? = null) =
        log(LogLevel.DEBUG, tag, message, exception)

    fun i(tag: String, message: String, exception: Throwable? = null) =
        log(LogLevel.INFO, tag, message, exception)

    fun w(tag: String, message: String, exception: Throwable? = null) =
        log(LogLevel.WARN, tag, message, exception)

    fun e(tag: String, message: String, exception: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, exception)

    // ── Common internal implementation ────────────────────────────────────

    private fun log(level: LogLevel, tag: String, message: String, exception: Throwable?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            exception = exception
        )

        loggerScope.launch {
            mutex.withLock {
                logBuffer.add(entry, overwrite = true)
                _logStateFlow.value = logBuffer.toList()
            }
        }

        // Forward to Android's system log at the matching priority
        when (level) {
            LogLevel.VERBOSE -> if (exception != null) Log.v(tag, message, exception) else Log.v(tag, message)
            LogLevel.DEBUG   -> if (exception != null) Log.d(tag, message, exception) else Log.d(tag, message)
            LogLevel.INFO    -> if (exception != null) Log.i(tag, message, exception) else Log.i(tag, message)
            LogLevel.WARN    -> if (exception != null) Log.w(tag, message, exception) else Log.w(tag, message)
            LogLevel.ERROR   -> if (exception != null) Log.e(tag, message, exception) else Log.e(tag, message)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    fun clear() {
        loggerScope.launch {
            mutex.withLock {
                logBuffer.clear()
                _logStateFlow.value = emptyList()
            }
        }
    }

    fun formatLogEntry(entry: LogEntry): String {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timeString = dateFormat.format(Date(entry.timestamp))
        val base = "[$timeString] ${entry.tag}: ${entry.message}"
        return if (entry.exception != null) {
            base + "\n" + Log.getStackTraceString(entry.exception)
        } else {
            base
        }
    }

    suspend fun getFormattedLogs(): String {
        return mutex.withLock {
            logBuffer.toList().joinToString(separator = "\n") { formatLogEntry(it) }
        }
    }

    suspend fun copyLogsToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val logs = getFormattedLogs()
        val clip = ClipData.newPlainText("Debug Logs", logs)
        clipboard.setPrimaryClip(clip)
    }
}

// ─── Log Viewer UI ────────────────────────────────────────────────────────────

@Composable
fun LogViewer() {
    val logEntries by HLog.logStateFlow.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(logEntries.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                coroutineScope.launch {
                    HLog.copyLogsToClipboard(context)
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Copy Log")
            }

            Button(onClick = { HLog.clear() }) {
                Text("Clear Log")
            }
        }

        // Scrollable log list
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(8.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.large
                    )
            ) {
                items(logEntries) { entry ->
                    val formattedText = HLog.formatLogEntry(entry)
                    val entryColor = entry.level.color

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        formattedText.lineSequence().forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = entryColor
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}