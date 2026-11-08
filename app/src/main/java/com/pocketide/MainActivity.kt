package com.pocketide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val Night = darkColorScheme(
    background = Color(0xFF1B1D23),
    onBackground = Color(0xFFD7DAE0),
    surface = Color(0xFF23262E),
    onSurface = Color(0xFFD7DAE0),
    surfaceContainerLow = Color(0xFF23262E),
    surfaceContainerHigh = Color(0xFF2C303A),
    onSurfaceVariant = Color(0xFF8A90A0),
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF14161C)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "workspace").apply { mkdirs() }
        setContent { MaterialTheme(colorScheme = Night) { IdeScreen(root) } }
    }
}

private enum class Mode { NEW_FILE, NEW_FOLDER, RENAME, DELETE }

private fun validName(n: String) = n.isNotBlank() && '/' !in n && n != "." && n != ".."

private fun inside(f: File?, dir: File) =
    f != null && (f.path == dir.path || f.path.startsWith(dir.path + File.separator))

private fun flatten(dir: File, depth: Int, expanded: Set<String>, out: MutableList<Pair<File, Int>>) {
    val kids = dir.listFiles()
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
    for (f in kids) {
        out.add(f to depth)
        if (f.isDirectory && f.path in expanded) flatten(f, depth + 1, expanded, out)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeScreen(root: File) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var current by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<Mode?>(null) }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    val rows = remember(tick, expanded) {
        mutableListOf<Pair<File, Int>>().also { flatten(root, 0, expanded, it) }
    }

    fun save() {
        val f = current ?: return
        if (text == loaded) return
        try { f.writeText(text); loaded = text } catch (e: Exception) { }
    }

    fun closeFile() { current = null; text = ""; loaded = "" }

    fun baseDir(): File {
        val t = selected
        return when {
            t == null -> root
            t.isDirectory -> t
            else -> t.parentFile ?: root
        }
    }

    fun commit(m: Mode) {
        save()
        val t = selected
        if (m == Mode.DELETE) {
            if (t != null) {
                if (inside(current, t)) closeFile()
                t.deleteRecursively()
                selected = null
            }
        } else {
            val name = input.trim()
            val dir = if (m == Mode.RENAME) (t?.parentFile ?: root) else baseDir()
            if (!validName(name)) {
                err = "Имя не должно быть пустым и содержать «/»"
                return
            }
            val target = File(dir, name)
            if (target.exists()) {
                err = "Такое имя уже есть"
                return
            }
            try {
                when (m) {
                    Mode.NEW_FILE -> {
                        target.createNewFile()
                        selected = target
                        current = target
                        text = ""
                        loaded = ""
                        scope.launch { drawer.close() }
                    }
                    Mode.NEW_FOLDER -> target.mkdirs()
                    else -> if (t != null) {
                        val affected = inside(current, t)
                        if (!t.renameTo(target)) {
                            err = "Не удалось переименовать"
                            return
                        }
                        if (affected) closeFile()
                        selected = null
                    }
                }
                expanded = expanded + dir.path
            } catch (e: Exception) {
                err = "Не удалось: ${e.message}"
                return
            }
        }
        mode = null
        tick++
    }

    LaunchedEffect(text) { delay(500); save() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Проект",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { input = ""; err = null; mode = Mode.NEW_FILE }) { Text("+ Файл") }
                    TextButton(onClick = { input = ""; err = null; mode = Mode.NEW_FOLDER }) { Text("+ Папка") }
                }
                val sel = selected
                if (sel != null) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { input = sel.name; err = null; mode = Mode.RENAME }) { Text("Переименовать") }
                        TextButton(onClick = { err = null; mode = Mode.DELETE }) { Text("Удалить") }
                    }
                }
                LazyColumn {
                    items(rows, key = { it.first.path }) { (f, depth) ->
                        val mark = if (!f.isDirectory) "·" else if (f.path in expanded) "▾" else "▸"
                        Text(
                            text = "$mark  ${f.name}",
                            color = if (f == selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = f
                                    if (f.isDirectory) {
                                        expanded = if (f.path in expanded) expanded - f.path else expanded + f.path
                                    } else {
                                        save()
                                        current = f
                                        val body = try { f.readText() } catch (e: Exception) { "" }
                                        text = body
                                        loaded = body
                                        scope.launch { drawer.close() }
                                    }
                                }
                                .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text((if (current != null && text != loaded) "● " else "") + (current?.name ?: "PocketIDE"))
                    },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawer.open() } }) { Text("Файлы") }
                    }
                )
            }
        ) { pad ->
            if (current == null) {
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Файл не открыт", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Открой список файлов или создай новый.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(
                        onClick = { scope.launch { drawer.open() } },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("Открыть файлы") }
                }
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }

    val m = mode
    if (m != null) {
        AlertDialog(
            onDismissRequest = { mode = null },
            title = {
                Text(
                    when (m) {
                        Mode.NEW_FILE -> "Новый файл"
                        Mode.NEW_FOLDER -> "Новая папка"
                        Mode.RENAME -> "Переименовать"
                        Mode.DELETE -> "Удалить «${selected?.name}»?"
                    }
                )
            },
            text = {
                if (m == Mode.DELETE) {
                    Text(
                        if (selected?.isDirectory == true) "Папка удалится вместе со всем, что в ней. Отменить нельзя."
                        else "Файл удалится. Отменить нельзя."
                    )
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; err = null },
                        singleLine = true,
                        isError = err != null,
                        supportingText = {
                            Text(err ?: if (m == Mode.RENAME) "Новое имя" else "Создать в: /" + baseDir().relativeTo(root).path)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { commit(m) }) { Text(if (m == Mode.DELETE) "Удалить" else "Готово") }
            },
            dismissButton = { TextButton(onClick = { mode = null }) { Text("Отмена") } }
        )
    }
}
