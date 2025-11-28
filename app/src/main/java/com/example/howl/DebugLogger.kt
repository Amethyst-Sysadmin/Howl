package com.example.howl

import android.content.ClipData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipboardManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val exception: Throwable? = null
)

object HLog {
    const val MAX_LOG_ENTRIES = 100
    private val logQueue = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)
    private val _logStateFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logStateFlow: StateFlow<List<LogEntry>> = _logStateFlow.asStateFlow()

    private val mutex = Mutex()
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun d(tag: String, message: String, exception: Throwable? = null) {
        val newEntry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            exception = exception
        )

        loggerScope.launch {
            mutex.withLock {
                while (logQueue.size >= MAX_LOG_ENTRIES) {
                    logQueue.removeFirst()
                }
                logQueue.add(newEntry)
                _logStateFlow.value = logQueue.toList()
            }
        }
        if (exception != null) {
            Log.d(tag, message, exception)
        } else {
            Log.d(tag, message)
        }
    }

    fun clear() {
        loggerScope.launch {
            mutex.withLock {
                logQueue.clear()
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
            logQueue.joinToString(separator = "\n") { formatLogEntry(it) }
        }
    }

    suspend fun copyLogsToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val logs = getFormattedLogs()
        val clip = ClipData.newPlainText("Debug Logs", logs)
        clipboard.setPrimaryClip(clip)
    }
}

@Composable
fun LogViewer() {
    val logEntries by HLog.logStateFlow.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom when new entries are added
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(logEntries.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Copy and Clear buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                coroutineScope.launch {
                    HLog.copyLogsToClipboard(context)
                    Toast.makeText(context, context.getString(R.string.debug_logs_copied), Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(context.getString(R.string.debug_copy_log))
            }

            Button(onClick = { HLog.clear() }) {
                Text(context.getString(R.string.debug_clear_log))
            }
        }

        // Log entries display
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
                    // Display each line of the formatted entry separately
                    val formattedText = HLog.formatLogEntry(entry)
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}