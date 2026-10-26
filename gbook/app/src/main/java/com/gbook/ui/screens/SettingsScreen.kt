package com.gbook.ui.screens

import android.text.format.DateUtils
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gbook.data.Reminder
import com.gbook.ui.theme.DisplaySerif
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import com.gbook.R
import com.gbook.ui.components.Avatar
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.formatDuration
import com.gbook.ui.theme.AppSans
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
import com.gbook.ui.components.GSwitch
import com.gbook.ui.theme.AccentPalettes
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class SettingsPage(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

private val Pages = listOf(
    SettingsPage("appearance", "Appearance", "Mode, accent colour, pure black", Icons.Rounded.Palette),
    SettingsPage("navigation", "Navigation bar", "Bar style and icon style", Icons.Rounded.SpaceDashboard),
    SettingsPage("reading", "Reading", "Font, size, margins, page turning, tracker", Icons.AutoMirrored.Rounded.MenuBook),
    SettingsPage("goals", "Goals & reminders", "Daily goal and reading timetable", Icons.Rounded.NotificationsActive),
    SettingsPage("backup", "Google Drive & backup", "Back up, restore, import books", Icons.Rounded.Cloud),
    SettingsPage("about", "About", "Version and credits", Icons.Rounded.Info),
)

/**
 * Settings root: a profile card and a list of categories, each opening its own screen.
 * [page] is the open sub-screen id ("profile" or one of [Pages]), or null for the root.
 */
@Composable
fun SettingsTab(vm: AppViewModel, page: String?, onPage: (String?) -> Unit, onScrollDir: (Boolean) -> Unit) {
    BackHandler(page != null) { onPage(null) }
    AnimatedContent(
        page,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
        },
        label = "settings",
    ) { p ->
        when (p) {
            null -> SettingsRoot(vm, onPage, onScrollDir)
            else -> SettingsSubPage(
                title = if (p == "profile") "Profile" else Pages.first { it.id == p }.title,
                onBack = { onPage(null) }, onScrollDir = onScrollDir,
            ) {
                when (p) {
                    "profile" -> ProfilePage(vm)
                    "appearance" -> AppearancePage(vm)
                    "navigation" -> NavigationPage(vm)
                    "reading" -> ReadingPage(vm)
                    "goals" -> GoalsPage(vm)
                    "backup" -> BackupPage(vm)
                    else -> AboutPage(vm)
                }
            }
        }
    }
}

@Composable
private fun SettingsRoot(vm: AppViewModel, onPage: (String?) -> Unit, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val s = data.settings
    val list = rememberLazyListState()
    ScrollDirection(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, onScrollDir)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 8.dp)) {
                Text("Settings", style = GType.largeTitle, color = g.text)
            }
        }
        item {
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().clickable { onPage("profile") }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(vm.avatarFile, s.avatarVersion, s.profileName, 60.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.profileName.ifBlank { "Your profile" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.text)
                        Text("Name and profile picture", fontSize = 14.sp, color = g.text2)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = g.text2)
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                Pages.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 66.dp), color = g.separator)
                    Row(
                        Modifier.fillMaxWidth().clickable { onPage(p.id) }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(g.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(p.icon, null, tint = g.accent, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, fontSize = 17.sp, color = g.text)
                            Text(p.subtitle, fontSize = 13.sp, color = g.text2)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = g.text2)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, onScrollDir: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalG.current
    val scroll = rememberScrollState()
    ScrollDirection(0, scroll.value, onScrollDir)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 140.dp)) {
        Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 44.dp)
        }
        Text(title, style = GType.largeTitle, color = g.text, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp))
        content()
    }
}

@Composable
private fun ProfilePage(vm: AppViewModel) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val s = data.settings
    val choose = com.gbook.ui.components.rememberAvatarPicker { vm.saveAvatar(it) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(vm.avatarFile, s.avatarVersion, s.profileName, 120.dp, Modifier.clip(CircleShape).clickable(onClick = choose))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(g.accent).border(3.dp, g.bg, CircleShape).clickable(onClick = choose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PhotoCamera, "Choose photo", tint = g.onAccent, modifier = Modifier.size(18.dp)) }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (s.avatarVersion == 0L) "Add photo" else "Change photo", choose, height = 44.dp)
            if (s.avatarVersion != 0L) PillButton("Remove", vm::removeAvatar, height = 44.dp, contentColor = g.destructive)
        }
    }
    SectionLabel("Name")
    SettingsCard {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (s.profileName.isEmpty()) Text("Your name", fontSize = 17.sp, color = g.text2)
            BasicTextField(
                s.profileName, { name -> vm.updateSettings { it.copy(profileName = name.take(40)) } },
                singleLine = true, textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = AppSans),
                cursorBrush = SolidColor(g.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Text(
        "Your name and photo stay on this device.",
        style = GType.caption, color = g.text2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
    )
}

@Composable
private fun AppearancePage(vm: AppViewModel) {
    val g = LocalG.current
    val s = vm.data.collectAsState().value.settings
    SectionLabel("Mode")
    SettingsCard { AppearanceControls(s, vm) }
    SectionLabel("Accent colour")
    SettingsCard { AccentPicker(s.accent) { id -> vm.updateSettings { it.copy(accent = id) } } }
    Text(
        "The accent tints buttons, the selected tab and, in dark mode, the background.",
        style = GType.caption, color = g.text2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
    )
}

@Composable
private fun NavigationPage(vm: AppViewModel) {
    val g = LocalG.current
    val s = vm.data.collectAsState().value.settings
    SectionLabel("Bar style")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NavStyleCard("Pill", "Floating, springy tabs", s.navStyle == 0, Modifier.weight(1f), { vm.updateSettings { it.copy(navStyle = 0) } }) {
            Box(Modifier.fillMaxWidth().height(26.dp).clip(CircleShape).background(g.fill)) {
                Box(Modifier.fillMaxWidth(0.25f).fillMaxHeight().padding(3.dp).clip(CircleShape).background(g.accent.copy(alpha = 0.35f)))
            }
        }
        NavStyleCard("Classic", "Capsule + settings button", s.navStyle == 1, Modifier.weight(1f), { vm.updateSettings { it.copy(navStyle = 1) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(26.dp).clip(CircleShape).background(g.fill))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(26.dp).clip(CircleShape).background(g.fill))
            }
        }
    }
    SectionLabel("Icons")
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Icon style", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
            Segmented(listOf("Symbols", "3D emoji"), s.navIcons, { i -> vm.updateSettings { it.copy(navIcons = i) } })
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (s.navIcons == 1) {
                listOf(R.drawable.noto3d_library, R.drawable.noto3d_feed, R.drawable.noto3d_highlights, R.drawable.noto3d_settings).forEach {
                    Image(painterResource(it), null, Modifier.size(40.dp))
                }
            } else {
                listOf(Icons.Rounded.LocalLibrary, Icons.Rounded.DynamicFeed, Icons.Rounded.FormatQuote, Icons.Rounded.Settings).forEach {
                    Icon(it, null, tint = g.text2, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
    Text(
        "3D emoji are Google's Noto 3D Emoji (Apache License 2.0).",
        style = GType.caption, color = g.text2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
    )
}

@Composable
private fun NavStyleCard(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val g = LocalG.current
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(g.surface)
            .border(if (selected) 2.dp else 0.75.dp, if (selected) g.accent else g.separator, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        preview()
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = g.accent, modifier = Modifier.size(20.dp))
        }
        Text(subtitle, fontSize = 13.sp, color = g.text2)
    }
}

@Composable
private fun ReadingPage(vm: AppViewModel) {
    val g = LocalG.current
    val s = vm.data.collectAsState().value.settings
    SectionLabel("Text")
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
    }
    SectionLabel("Page")
    Column(Modifier.padding(horizontal = 16.dp)) {
        PageModeControl(s.pageMode) { m -> vm.updateSettings { it.copy(pageMode = m) } }
    }
    SettingsCard {
        Box(Modifier.padding(start = 6.dp, end = 20.dp, bottom = 12.dp)) {
            MarginControl(s.margin) { m -> vm.updateSettings { it.copy(margin = m) } }
        }
    }
    SettingsCard { TrackerOptions(s, vm) }
    SectionLabel("Highlights")
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Style", fontSize = 17.sp, color = g.text)
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

@Composable
private fun GoalsPage(vm: AppViewModel) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val askPermission = rememberNotificationPermission()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Re-checked on resume so it updates after the permission prompt or system settings.
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val goal = data.settings.dailyGoalMin

    SectionLabel("Daily goal")
    SettingsCard {
        Column(Modifier.padding(20.dp)) {
            Text(if (goal == 0) "No daily goal" else "$goal min a day", fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = g.text)
            Text(
                "When you reach it you'll get a small celebration — and you can keep reading as long as you like.",
                fontSize = 14.sp, color = g.text2, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 15, 20, 30, 45, 60, 90).forEach { m ->
                    SelectChip(if (m == 0) "Off" else "$m min", goal == m) { vm.setDailyGoal(m) }
                }
            }
        }
    }
    if (!notificationsOn) {
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NotificationsOff, null, tint = g.destructive)
                Spacer(Modifier.width(12.dp))
                Text("Notifications are off, so reminders can't appear.", fontSize = 15.sp, color = g.text, modifier = Modifier.weight(1f))
                TextButton({
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askPermission()
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("Turn on", color = g.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    SectionLabel("Reading timetable")
    SettingsCard {
        data.reminders.sortedBy { it.hour * 60 + it.minute }.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
            val bookTitle = data.books.firstOrNull { it.id == r.bookId }?.title
            Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(timeLabel(r.hour, r.minute), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = if (r.enabled) g.text else g.text2)
                    Text(daysLabel(r.days) + (bookTitle?.let { " · Plan: $it" } ?: ""), fontSize = 14.sp, color = g.text2, maxLines = 1)
                }
                GSwitch(r.enabled) { on -> vm.saveReminder(r.copy(enabled = on)); if (on) askPermission() }
            }
        }
        if (data.reminders.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
        Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = g.accent)
            Spacer(Modifier.width(12.dp))
            Text("Add reminder", fontSize = 17.sp, color = g.accent, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(
        "Reminders arrive as notifications within about 10 minutes of the set time. Book plans can add their own reminder from the book's page.",
        style = GType.caption, color = g.text2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
    )
    if (adding) ReminderDialog(null, onDismiss = { adding = false }, onSave = { vm.saveReminder(it); askPermission(); adding = false })
    editing?.let { r ->
        ReminderDialog(r, onDismiss = { editing = null }, onSave = { vm.saveReminder(it); editing = null }, onDelete = { vm.deleteReminder(r.id); editing = null })
    }
}

@Composable
private fun BackupPage(vm: AppViewModel) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }
    val importMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importMany(uris) }
    Text(
        "When the file picker opens, choose Drive from the menu (☰) and pick a folder.",
        style = GType.secondary, color = g.text2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
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

@Composable
private fun AboutPage(vm: AppViewModel) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val version = remember(ctx) { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("GBook", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
            Text(version, fontSize = 17.sp, color = g.text2)
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
        Text(
            "${data.books.size} books · ${data.highlights.size} highlights · ${data.posts.size} posts · ${formatDuration(data.books.sumOf { it.readingMs })} read",
            fontSize = 15.sp, color = g.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
    Text(
        "GBook is open source (MIT). Reading fonts: Literata, Charis SIL, Source Serif, Newsreader, Gelasio, Crimson Pro, EB Garamond, Libre Baskerville, Inter and DM Sans — SIL Open Font License. 3D icons: Noto 3D Emoji — Apache License 2.0.",
        style = GType.caption, color = g.text2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 10.dp),
    )
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
