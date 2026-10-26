package com.gbook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.data.Book
import com.gbook.data.BookFormat
import com.gbook.ui.components.panel
import com.gbook.ui.screens.BookHighlightsScreen
import com.gbook.ui.screens.BookSheet
import com.gbook.ui.screens.WelcomeFlow
import com.gbook.ui.screens.CollectionScreen
import com.gbook.ui.screens.HomeScreen
import com.gbook.ui.screens.PdfScreen
import com.gbook.ui.screens.ReaderScreen
import com.gbook.reminders.EXTRA_OPEN_BOOK
import com.gbook.ui.theme.GBookTheme
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Route {
    val key: String
    data object Home : Route { override val key = "home" }
    data class Highlights(val id: String) : Route { override val key = "highlights/$id" }
    data class Reader(val id: String, val chapter: Int? = null, val block: Int? = null) : Route { override val key = "reader/$id/$chapter/$block" }
    data class Pdf(val id: String) : Route { override val key = "pdf/$id" }
    data class Collection(val id: String) : Route { override val key = "collection/$id" }
}

/** [details] opens a book's options sheet over whatever screen is showing. */
class Nav(val push: (Route) -> Unit, val pop: () -> Unit, val details: (String) -> Unit) {
    fun open(b: Book) = push(if (b.format == BookFormat.PDF) Route.Pdf(b.id) else Route.Reader(b.id))
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pendingOpen = MutableStateFlow<Uri?>(null)
    /** Book id to open, e.g. from a reading reminder notification. */
    private val pendingBook = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val s by vm.data.collectAsState()
            GBookTheme(s.settings.accent, s.settings.appearance, s.settings.amoled) { App(vm, pendingOpen, pendingBook) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { pendingOpen.value = it }
        i?.getStringExtra(EXTRA_OPEN_BOOK)?.let { pendingBook.value = it }
    }
}

@Composable
fun App(vm: AppViewModel, pendingOpen: MutableStateFlow<Uri?>, pendingBook: MutableStateFlow<String?>) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var detailsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val nav = remember {
        Nav(push = { stack.add(it) }, pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }, details = { detailsFor = it })
    }
    val holder = rememberSaveableStateHolder()
    val pending by pendingOpen.collectAsState()
    val bookToOpen by pendingBook.collectAsState()
    val g = LocalG.current

    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        pendingOpen.value = null
        vm.import(uri) { nav.open(it) }
    }
    LaunchedEffect(bookToOpen) {
        val id = bookToOpen ?: return@LaunchedEffect
        pendingBook.value = null
        vm.store.book(id)?.let { b ->
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            vm.markOpened(b.id); nav.open(b)
        }
    }
    BackHandler(stack.size > 1) { nav.pop() }

    Box(Modifier.fillMaxSize().background(g.bg)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f)) togetherWith fadeOut(tween(160)) },
            label = "nav",
        ) { route ->
            holder.SaveableStateProvider(route.key) {
                when (route) {
                    Route.Home -> HomeScreen(vm, nav)
                    is Route.Highlights -> BookHighlightsScreen(vm, route.id, nav)
                    is Route.Reader -> ReaderScreen(vm, route.id, route.chapter, route.block, onBack = nav.pop)
                    is Route.Pdf -> PdfScreen(vm, route.id, onBack = nav.pop)
                    is Route.Collection -> CollectionScreen(vm, route.id, nav)
                }
            }
        }
        detailsFor?.let { BookSheet(vm, it, nav, onDismiss = { detailsFor = null }) }
        // First run: splash, profile and first books, fading away into the library when done.
        val onboarded = vm.data.collectAsState().value.onboarded
        AnimatedVisibility(!onboarded, enter = fadeIn(tween(0)), exit = fadeOut(tween(450))) { WelcomeFlow(vm) }
        ToastHost(vm)
    }
}

@Composable
private fun ToastHost(vm: AppViewModel) {
    val toast by vm.toast.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(2400); vm.toast.value = null } }
    val msg = busy ?: toast
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(msg != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            Row(
                Modifier.panel(CircleShape).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalG.current.text)
                    Spacer(Modifier.width(10.dp))
                }
                Text(msg.orEmpty(), color = LocalG.current.text, fontSize = 15.sp, maxLines = 2)
            }
        }
    }
}
