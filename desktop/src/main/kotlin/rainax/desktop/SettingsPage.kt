package rainax.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsPage(theme: Int, onTheme: (Int) -> Unit) {
    val p = LocalPal.current
    var folder by remember { mutableStateOf(Prefs.folder) }
    var parallel by remember { mutableIntStateOf(Prefs.parallel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            Text("Settings", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Group("Appearance") {
                SettingRow("Theme", "Follow Windows, or always light or dark") {
                    Seg(listOf("System", "Light", "Dark"), theme, onTheme, Modifier.width(270.dp))
                }
            }
            Group("Downloads") {
                SettingRow("Save to", folder.path) {
                    SoftButton("Change", Ic.Folder) {
                        chooseFolder(folder)?.let { folder = it; Prefs.folder = it }
                    }
                }
                Line()
                SettingRow("At the same time", "How many downloads run together") {
                    Seg(listOf("1", "2", "3", "4"), parallel - 1, { parallel = it + 1; Downloads.setParallel(it + 1) }, Modifier.width(200.dp))
                }
            }
            Group("About") {
                SettingRow("RAINAX for Windows", "Version 1.0 (preview)")
                Line()
                SettingRow("ffmpeg", if (Ffmpeg.available()) "Ready: HD videos and MP3 work" else "Not found: only 360p videos and M4A sound") {
                    Tag(if (Ffmpeg.available()) "ON" else "OFF", red = Ffmpeg.available())
                }
            }
        }
    }
}
