package com.anivault.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.anivault.ui.theme.dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Reads this app's own logcat lines (no permission needed for your own process). */
object AppLogcat {
    suspend fun read(maxLines: Int = 5000): String = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            val pid = android.os.Process.myPid().toString()
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", maxLines.toString())
                .redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text
        } ?: "(logcat did not answer in time)"
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
    }
}

@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun refresh() { scope.launch { busy = true; text = AppLogcat.read(); busy = false } }
    LaunchedEffect(Unit) { refresh() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess
            }
            note = if (ok) "Log saved." else "Couldn't save the log."
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Debug log", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Debug builds only. Shows this app's own logcat. Check it for anything private before sharing it.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.dim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("Refresh") }
                OutlinedButton(onClick = { save.launch("anivault-log-${System.currentTimeMillis() / 1000}.txt") }, enabled = text.isNotEmpty()) { Text("Save") }
                Button(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            File(ctx.cacheDir, "logs").apply { mkdirs() }.let { dir ->
                                File(dir, "anivault-log.txt").also { it.writeText(text) }
                            }
                        }
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }, enabled = text.isNotEmpty()) { Text("Share") }
                TextButton(onClick = { scope.launch { AppLogcat.clear(); text = ""; note = "Log cleared." } }) { Text("Clear") }
            }
            note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    Text(text.ifEmpty { if (busy) "" else "(no log lines)" }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false)
                }
            }
        }
    }
}
