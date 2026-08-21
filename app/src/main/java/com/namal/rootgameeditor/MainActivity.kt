package com.namal.rootgameeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import java.io.File

// Configure libsu once, before any Shell.cmd() calls happen anywhere in the app.
object ShellConfig {
    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }
}

sealed class Screen {
    data object AppList : Screen()
    data class FileBrowser(val path: String) : Screen()
    data class DbEdit(val remotePath: String) : Screen()
    data class TextEdit(val remotePath: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellConfig.hashCode() // ensure init block ran
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootEditorApp()
                }
            }
        }
    }
}

@Composable
fun RootEditorApp() {
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        rootGranted = RootFs.requestRoot()
    }

    when (rootGranted) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        false -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Root access denied or unavailable.\nGrant root in Magisk and reopen the app.")
        }
        true -> {
            var screen by remember { mutableStateOf<Screen>(Screen.AppList) }
            var history by remember { mutableStateOf(listOf<Screen>()) }

            fun navigateTo(next: Screen) {
                history = history + screen
                screen = next
            }

            fun goBack() {
                if (history.isNotEmpty()) {
                    screen = history.last()
                    history = history.dropLast(1)
                }
            }

            BackHandlerCompat(enabled = history.isNotEmpty()) { goBack() }

            when (val s = screen) {
                is Screen.AppList -> FileBrowserScreen(
                    path = "/data/data",
                    title = "Installed apps (/data/data)",
                    onOpenDir = { navigateTo(Screen.FileBrowser(it)) },
                    onOpenFile = { path -> handleFileOpen(path) { navigateTo(it) } },
                    onBack = null
                )
                is Screen.FileBrowser -> FileBrowserScreen(
                    path = s.path,
                    title = s.path,
                    onOpenDir = { navigateTo(Screen.FileBrowser(it)) },
                    onOpenFile = { path -> handleFileOpen(path) { navigateTo(it) } },
                    onBack = { goBack() }
                )
                is Screen.DbEdit -> DbEditScreen(remotePath = s.remotePath, onBack = { goBack() })
                is Screen.TextEdit -> TextEditScreen(remotePath = s.remotePath, onBack = { goBack() })
            }
        }
    }
}

private fun handleFileOpen(path: String, navigate: (Screen) -> Unit) {
    when {
        path.endsWith(".db") || path.endsWith(".sqlite") || path.endsWith(".sqlite3") ->
            navigate(Screen.DbEdit(path))
        path.endsWith(".xml") || path.endsWith(".json") || path.endsWith(".txt") || path.endsWith(".pref") ->
            navigate(Screen.TextEdit(path))
        else -> { /* unsupported file type: ignore for now */ }
    }
}

@Composable
fun FileBrowserScreen(
    path: String,
    title: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onBack: (() -> Unit)?
) {
    var entries by remember(path) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember(path) { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        entries = RootFs.listDir(path)
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
        )
    }) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Empty or inaccessible directory")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(entries) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { if (entry.isDir) Text("folder") },
                        modifier = Modifier.clickable {
                            if (entry.isDir) onOpenDir(entry.path) else onOpenFile(entry.path)
                        }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
fun DbEditScreen(remotePath: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editor by remember { mutableStateOf<SqliteEditor?>(null) }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<TableRow>>(emptyList()) }
    var status by remember { mutableStateOf("Loading...") }

    LaunchedEffect(remotePath) {
        val local = RootFs.copyToCache(remotePath, context.cacheDir)
        if (local == null) {
            status = "Failed to copy DB out of root storage."
            return@LaunchedEffect
        }
        val ed = SqliteEditor(local)
        editor = ed
        tables = ed.listTables()
        status = if (tables.isEmpty()) "No tables found." else ""
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(File(remotePath).name) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (status.isNotEmpty()) Text(status, Modifier.padding(12.dp))

            if (selectedTable == null) {
                LazyColumn {
                    items(tables) { t ->
                        ListItem(
                            headlineContent = { Text(t) },
                            modifier = Modifier.clickable {
                                selectedTable = t
                                rows = editor?.readRows(t) ?: emptyList()
                            }
                        )
                        Divider()
                    }
                }
            } else {
                val table = selectedTable!!
                Row(Modifier.padding(8.dp)) {
                    TextButton(onClick = { selectedTable = null }) { Text("< Tables") }
                    Spacer(Modifier.width(8.dp))
                    Text(table, style = MaterialTheme.typography.titleMedium)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(rows) { row ->
                        RowEditor(row = row, onSave = { col, newVal ->
                            editor?.updateCell(table, row.rowId, col, newVal)
                            row.values[col] = newVal
                        })
                        Divider()
                    }
                }
                Button(
                    onClick = {
                        val local = editor?.let { File(context.cacheDir, File(remotePath).name) }
                        if (local != null) {
                            val owner = RootFs.getOwner(remotePath)
                            val ok = RootFs.writeBack(local, remotePath, owner)
                            status = if (ok) "Saved back to $remotePath" else "Save failed."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Text("Save changes to game") }
            }
        }
    }
}

@Composable
fun RowEditor(row: TableRow, onSave: (column: String, newValue: String) -> Unit) {
    Column(Modifier.padding(8.dp)) {
        for ((col, value) in row.values) {
            var text by remember(row.rowId, col) { mutableStateOf(value ?: "") }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onSave(col, it)
                },
                label = { Text(col) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
fun TextEditScreen(remotePath: String, onBack: () -> Unit) {
    var content by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading...") }

    LaunchedEffect(remotePath) {
        val text = RootFs.readTextFile(remotePath)
        if (text == null) {
            status = "Failed to read file."
        } else {
            content = text
            status = ""
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(File(remotePath).name) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (status.isNotEmpty()) Text(status, Modifier.padding(12.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
            )
            Button(
                onClick = {
                    status = if (RootFs.writeTextFile(remotePath, content))
                        "Saved back to $remotePath" else "Save failed."
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Text("Save changes to game") }
        }
    }
}

// Minimal back-press handler without extra nav dependencies.
@Composable
fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
