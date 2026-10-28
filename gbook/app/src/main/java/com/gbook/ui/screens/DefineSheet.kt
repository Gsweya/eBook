package com.gbook.ui.screens

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.data.Definition
import com.gbook.data.Dictionary
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.pressable
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif

private sealed interface Lookup {
    data object Loading : Lookup
    class Found(val d: Definition) : Lookup
    data object Missing : Lookup
    class Failed(val message: String) : Lookup
}

/**
 * The meaning of a selected word. The first time, the reader chooses between downloading the dictionary (offline,
 * private) and looking words up online; after that it opens straight on the definition.
 */
@Composable
fun DefineSheet(vm: AppViewModel, query: String, onDismiss: () -> Unit, onPost: ((Definition) -> Unit)? = null) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val dict = vm.dictionary
    val source by dict.source.collectAsState()
    val downloading by dict.progress.collectAsState()
    val word = remember(query) { Dictionary.clean(query) }
    // A one-off online lookup, e.g. while the dictionary downloads or for a word it doesn't have.
    var online by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val from = if (online) Dictionary.Source.ONLINE else source
    val ready = from == Dictionary.Source.ONLINE || (from == Dictionary.Source.OFFLINE && dict.installed)
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Loading) }
    LaunchedEffect(word, from, ready, attempt) {
        if (!ready) return@LaunchedEffect
        lookup = Lookup.Loading
        lookup = runCatching { dict.define(word, from) }.fold(
            { if (it == null) Lookup.Missing else Lookup.Found(it) },
            { Lookup.Failed(if (from == Dictionary.Source.ONLINE) "Couldn't reach the dictionary. Check your connection." else it.message ?: "Something went wrong") },
        )
    }
    fun searchWeb() {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "define $word")
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode("define $word")))) }
        }
    }

    GSheet(onDismiss, "Dictionary", full = true, heightFraction = 0.72f) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            val shown = (lookup as? Lookup.Found)?.d?.takeIf { ready }
            Text(shown?.word ?: word, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, color = g.text)
            shown?.phonetic?.let { Text(it, fontSize = 16.sp, color = g.text2) }
            when {
                !ready && downloading != null -> {
                    val left by dict.secondsLeft.collectAsState()
                    val got by dict.bytes.collectAsState()
                    Downloading(downloading ?: 0f, Dictionary.sizeText(got.first, got.second) + (Dictionary.timeLeft(left)?.let { " · $it" } ?: "")) { online = true }
                }
                !ready -> ChooseSource(
                    onDownload = { vm.downloadDictionary() },
                    onOnline = { dict.choose(Dictionary.Source.ONLINE) },
                )
                else -> when (val l = lookup) {
                    Lookup.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = g.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    }
                    is Lookup.Found -> Senses(l.d)
                    Lookup.Missing -> Text(
                        "No entry for “$word”." + if (from == Dictionary.Source.OFFLINE) " Names and rare words may only be online." else "",
                        style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 16.dp),
                    )
                    is Lookup.Failed -> Text(l.message, style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 16.dp))
                }
            }
            if (ready && lookup != Lookup.Loading) Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val found = (lookup as? Lookup.Found)?.d
                when {
                    found != null && onPost != null -> PillButton("Post to Feed", { onPost(found) }, icon = Icons.Rounded.AutoAwesome, height = 42.dp)
                    lookup is Lookup.Failed -> PillButton("Try again", { attempt++ }, icon = Icons.Rounded.Refresh, height = 42.dp)
                    lookup == Lookup.Missing && from == Dictionary.Source.OFFLINE -> PillButton("Look up online", { online = true }, icon = Icons.Rounded.Language, height = 42.dp)
                }
                PillButton("Search the web", ::searchWeb, icon = Icons.Rounded.Search, height = 42.dp)
            }
            (lookup as? Lookup.Found)?.takeIf { ready }?.let {
                Text(it.d.source, style = GType.caption, color = g.text2.copy(alpha = 0.8f), modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}

/** Definitions grouped by part of speech, each group numbered, with examples and similar words. */
@Composable
private fun Senses(d: Definition) {
    val g = LocalG.current
    val groups = remember(d) { d.senses.groupBy { it.pos to it.lemma }.entries.toList() }
    groups.forEach { (key, senses) ->
        val (pos, lemma) = key
        val label = if (lemma.isNotBlank() && !lemma.equals(d.word, ignoreCase = true)) "$pos · $lemma" else pos
        Text(label.uppercase(), style = GType.eyebrow, color = g.accent, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        senses.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.text2, modifier = Modifier.width(24.dp).padding(top = 1.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.meaning.replaceFirstChar { it.uppercase() }, fontSize = 17.sp, lineHeight = 23.sp, color = g.text)
                    s.examples.take(2).forEach { ex ->
                        Text("“$ex”", fontFamily = QuoteSerif, fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 22.sp, color = g.text2, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (s.synonyms.isNotEmpty()) Text(
                        "Similar: " + s.synonyms.take(6).joinToString(", "), fontSize = 14.sp, color = g.text2, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/** First lookup: download the dictionary once, or look words up online. */
@Composable
private fun ChooseSource(onDownload: () -> Unit, onOnline: () -> Unit) {
    val g = LocalG.current
    Text("How should GBook look words up?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.text, modifier = Modifier.padding(top = 18.dp))
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SourceCard(
            Icons.Rounded.CloudDownload, "Download the dictionary",
            "About 10 MB, once (28 MB on the phone). Works without a connection, and nothing you look up leaves your phone.",
            onDownload,
        )
        SourceCard(
            Icons.Rounded.Language, "Look words up online",
            "Nothing to download. Each word is sent to the Free Dictionary API (dictionaryapi.dev), so you need a connection.",
            onOnline,
        )
    }
    Text("You can change this later in Settings › Reading.", style = GType.caption, color = g.text2, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SourceCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    val g = LocalG.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().pressable(onClick).clip(shape).background(g.surface).border(0.7.dp, g.separator, shape).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(g.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.text)
            Text(body, fontSize = 14.sp, lineHeight = 19.sp, color = g.text2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Downloading(progress: Float, detail: String, onOnline: () -> Unit) {
    val g = LocalG.current
    Text("Downloading the dictionary… ${(progress * 100).toInt()}%", fontSize = 16.sp, color = g.text, modifier = Modifier.padding(top = 18.dp))
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(g.fill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(g.accent))
    }
    Text(detail, fontSize = 14.sp, color = g.text2, modifier = Modifier.padding(top = 8.dp))
    Text("It carries on if you close this, or GBook.", style = GType.caption, color = g.text2, modifier = Modifier.padding(top = 2.dp))
    PillButton("Look this word up online", onOnline, Modifier.padding(top = 16.dp), icon = Icons.Rounded.Language, height = 42.dp)
}
