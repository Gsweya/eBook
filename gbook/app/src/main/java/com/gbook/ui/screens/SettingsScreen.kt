package com.gbook.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.data.ReaderSettings
import com.gbook.ui.components.GSheet
import com.gbook.ui.theme.AccentPalettes
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsTab(vm: AppViewModel, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val s = data.settings
    val list = rememberLazyListState()
    ScrollDirection(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, onScrollDir)
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }
    val importMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { vm.import(it) } }
    val version = remember(ctx) { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 4.dp)) {
                Text("Settings", style = GType.largeTitle, color = g.text)
            }
        }
        item {
            SectionLabel("Appearance")
            SettingsCard {
                AppearanceControls(s, vm)
            }
            SettingsCard {
                Text("Accent", fontSize = 17.sp, color = g.text, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
                AccentPicker(s.accent) { id -> vm.updateSettings { it.copy(accent = id) } }
            }
        }
        item {
            SectionLabel("Reading")
            Box(Modifier.padding(horizontal = 16.dp)) {
                FontPicker(s.font) { id -> vm.updateSettings { it.copy(font = id) } }
            }
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Text size", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
                    Segmented(listOf("A−", "${s.fontSize.toInt()}", "A+"), selected = 1, onSelect = { i ->
                        val d = if (i == 0) -1f else if (i == 2) 1f else 0f
                        vm.updateSettings { it.copy(fontSize = (it.fontSize + d).coerceIn(MIN_FONT, MAX_FONT)) }
                    }, highlightSelected = false)
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Highlight style", fontSize = 17.sp, color = g.text)
                        HighlightPreview(s.highlightStyle)
                    }
                    Segmented(listOf("Marker", "Underline"), s.highlightStyle, { i -> vm.updateSettings { it.copy(highlightStyle = i) } })
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
                OptionRow("Post every new highlight", "Highlights appear in your Feed straight away", s.autoPostHighlights) { on ->
                    vm.updateSettings { it.copy(autoPostHighlights = on) }
                }
            }
        }
        item {
            SectionLabel("Google Drive")
            Text(
                "When the file picker opens, choose Drive from the menu (☰) and pick a folder.",
                style = GType.caption, color = g.text2, modifier = Modifier.padding(start = 36.dp, end = 36.dp, bottom = 4.dp),
            )
            SettingsCard {
                SyncRow(
                    Icons.Rounded.CloudUpload, "Back up library",
                    if (data.lastBackupAt > 0) "Last backup " + DateUtils.getRelativeTimeSpanString(data.lastBackupAt) else "Books, highlights and posts in one file",
                ) { backup.launch("GBook-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip") }
                HorizontalDivider(Modifier.padding(start = 62.dp), color = g.separator)
                SyncRow(Icons.Rounded.CloudDownload, "Restore from a backup", "Merges into this library — nothing is deleted") {
                    restore.launch(arrayOf("application/zip", "application/octet-stream"))
                }
                HorizontalDivider(Modifier.padding(start = 62.dp), color = g.separator)
                SyncRow(Icons.Rounded.FileOpen, "Import books", "EPUB and PDF, several at once") {
                    importMany.launch(arrayOf("application/epub+zip", "application/pdf"))
                }
            }
        }
        item {
            SectionLabel("About")
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text("GBook", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
                    Text(version, fontSize = 17.sp, color = g.text2)
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
                Text(
                    "${data.books.size} books · ${data.highlights.size} highlights · ${data.posts.size} posts",
                    fontSize = 15.sp, color = g.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            Text(
                "Reading fonts: Literata, Charis SIL, Source Serif, Newsreader, Gelasio, Crimson Pro, EB Garamond, Inter and DM Sans — SIL Open Font License.",
                style = GType.caption, color = g.text2, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 8.dp),
            )
        }
    }
}

/** Quick theme switcher opened from the Library's palette button. */
@Composable
fun ThemeSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val data by vm.data.collectAsState()
    GSheet(onDismiss, "Themes") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SettingsCard { AppearanceControls(data.settings, vm) }
            SettingsCard { AccentPicker(data.settings.accent) { id -> vm.updateSettings { it.copy(accent = id) } } }
        }
    }
}

@Composable
private fun AppearanceControls(s: ReaderSettings, vm: AppViewModel) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Mode", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
        Segmented(listOf("System", "Light", "Dark"), s.appearance, { i -> vm.updateSettings { it.copy(appearance = i) } })
    }
    HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
    OptionRow("Pure black", if (g.dark) "AMOLED backgrounds in dark mode" else "Applies in dark mode", s.amoled) { on ->
        vm.updateSettings { it.copy(amoled = on) }
    }
}

/** Grid of accent swatches: gradient discs, ringed and checked when selected (after Nuvio's theme chips). */
@Composable
fun AccentPicker(selected: String, onSelect: (String) -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentPalettes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    val on = p.id == selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(p.id) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(54.dp).then(if (on) Modifier.border(2.dp, p.gradient.last(), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(androidx.compose.ui.graphics.Brush.linearGradient(p.gradient.ifEmpty { listOf(p.accent, p.accent) })),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.Rounded.Check, "Selected", tint = p.onAccent, modifier = Modifier.size(22.dp)) }
                        }
                        Text(
                            p.name, fontSize = 12.sp, color = if (on) g.text else g.text2,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Compact segmented control; the selected segment gets the accent-tinted pill used across the app. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, highlightSelected: Boolean = true) {
    val g = LocalG.current
    Row(Modifier.height(36.dp).clip(CircleShape).background(g.fill).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = highlightSelected && i == selected
            val bg by animateColorAsState(if (on) g.accent.copy(alpha = 0.18f) else Color.Transparent, label = "seg")
            Box(
                Modifier.fillMaxHeight().clip(CircleShape).background(bg).clickable { onSelect(i) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) g.accent else g.text)
            }
        }
    }
}

@Composable
private fun HighlightPreview(style: Int) {
    val g = LocalG.current
    val c = HighlightColors[0]
    Text(
        "a remembered line", fontFamily = QuoteSerif, fontSize = 15.sp, color = g.text,
        modifier = Modifier.padding(top = 4.dp).drawBehind {
            if (style == 1) drawLine(c, Offset(0f, size.height - 2.dp.toPx()), Offset(size.width, size.height - 2.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
            else drawRect(c.copy(alpha = 0.38f))
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), style = GType.eyebrow, color = LocalG.current.text2,
        modifier = Modifier.padding(start = 36.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val g = LocalG.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(g.surface)
            .border(0.75.dp, g.separator.copy(alpha = if (g.dark) 0.55f else 1f), RoundedCornerShape(24.dp)),
        content = content,
    )
}

@Composable
private fun SyncRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = g.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, fontSize = 17.sp, color = g.text)
            Text(subtitle, fontSize = 14.sp, color = g.text2)
        }
    }
}
