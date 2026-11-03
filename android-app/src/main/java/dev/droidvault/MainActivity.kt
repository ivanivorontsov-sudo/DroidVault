package dev.droidvault

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val apkPaths: List<String>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidVaultScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DroidVaultScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var destination by remember { mutableStateOf<android.net.Uri?>(null) }
    var status by remember { mutableStateOf("Готов к работе. Выберите приложения для копирования APK.") }
    var busy by remember { mutableStateOf(false) }

    fun refreshApps() {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps = installed
                .filter { it.packageName != context.packageName }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { info ->
                    val pkg = pm.getPackageInfo(info.packageName, 0)
                    val paths = buildList {
                        if (info.sourceDir != null) add(info.sourceDir)
                        info.splitSourceDirs?.forEach { add(it) }
                    }
                    InstalledApp(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        versionName = pkg.versionName,
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong(),
                        apkPaths = paths
                    )
                }
                .filter { it.apkPaths.isNotEmpty() }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
            selected = emptySet()
            status = "Найдено приложений: ${apps.size}"
        } catch (e: Exception) {
            status = "Не удалось получить список: ${e.localizedMessage}"
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destination = uri
            status = "Папка для резервных копий выбрана."
        }
    }

    LaunchedEffect(Unit) { refreshApps() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF80CBC4),
        background = androidx.compose.ui.graphics.Color(0xFF101717),
        surface = androidx.compose.ui.graphics.Color(0xFF182222)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("DroidVault", fontWeight = FontWeight.Bold)
                            Text("Android backup • no-root", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = { refreshApps() }, enabled = !busy) { Text("Обновить") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Резервирование APK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Сохраняет установочные APK, включая split APK. Приватные данные приложений без специальных привилегий недоступны.")
                        Text("Выбрано: ${selected.size} из ${apps.size}", color = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (destination == null) "Выбрать папку" else "Папка выбрана")
                    }
                    Button(
                        onClick = {
                            val uri = destination
                            val chosen = apps.filter { it.packageName in selected }
                            if (uri == null) {
                                status = "Сначала выберите папку назначения."
                            } else if (chosen.isEmpty()) {
                                status = "Выберите хотя бы одно приложение."
                            } else {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        exportApps(context, uri, chosen)
                                    }
                                    status = result
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (busy) "Копирование…" else "Создать копию") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Приложения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selected = if (selected.size == apps.size) emptySet() else apps.map { it.packageName }.toSet() }) {
                        Text(if (selected.size == apps.size && apps.isNotEmpty()) "Снять все" else "Выбрать все")
                    }
                }

                if (apps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = app.packageName in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + app.packageName else selected - app.packageName
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.Medium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                    Text("Версия ${app.versionName ?: "неизвестна"} • ${app.apkPaths.size} APK", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        status,
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun exportApps(
    context: android.content.Context,
    treeUri: android.net.Uri,
    apps: List<InstalledApp>
): String {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: return "Не удалось открыть выбранную папку."
    val vault = root.findFile("DroidVault") ?: root.createDirectory("DroidVault")
        ?: return "Не удалось создать папку DroidVault."
    var success = 0
    val errors = mutableListOf<String>()

    for (app in apps) {
        try {
            val appDir = vault.findFile(app.packageName) ?: vault.createDirectory(app.packageName)
                ?: throw IllegalStateException("Не удалось создать папку приложения")
            val entries = JSONArray()
            app.apkPaths.forEachIndexed { index, source ->
                val name = if (index == 0) "base.apk" else "split_%02d.apk".format(index)
                val file = appDir.findFile(name) ?: appDir.createFile("application/vnd.android.package-archive", name)
                    ?: throw IllegalStateException("Не удалось создать $name")
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openOutputStream(file.uri, "wt").use { output ->
                    requireNotNull(output) { "Не удалось открыть файл для записи" }
                    java.io.File(source).inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                entries.put(JSONObject().apply {
                    put("file", name)
                    put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                })
            }
            val manifest = JSONObject().apply {
                put("format", "droidvault-apk-backup")
                put("format_version", 1)
                put("package", app.packageName)
                put("version_name", app.versionName ?: JSONObject.NULL)
                put("version_code", app.versionCode)
                put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                put("backup_scope", "apk_only_no_root")
                put("contains_private_app_data", false)
                put("apks", entries)
            }
            val manifestFile = appDir.findFile("manifest.json") ?: appDir.createFile("application/json", "manifest.json")
                ?: throw IllegalStateException("Не удалось создать manifest.json")
            context.contentResolver.openOutputStream(manifestFile.uri, "wt").use {
                requireNotNull(it).write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            }
            success++
        } catch (e: Exception) {
            errors += "${app.packageName}: ${e.localizedMessage ?: "ошибка"}"
        }
    }
    return buildString {
        append("Готово: ${success} из ${apps.size} приложений.")
        if (errors.isNotEmpty()) append("\nОшибки:\n").append(errors.take(5).joinToString("\n"))
        append("\nСохранены APK, не приватные данные приложений.")
    }
}
