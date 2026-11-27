package com.clockedbit.hearth

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import com.clockedbit.hearth.data.Importer
import com.clockedbit.hearth.data.Store
import com.clockedbit.hearth.reader.ReaderAssets
import com.clockedbit.hearth.reader.ReaderScreen
import com.clockedbit.hearth.ui.HearthTheme
import com.clockedbit.hearth.ui.LibraryScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Volume-key page turning while a book is open. */
object KeyBus {
    @Volatile var enabled = false
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
}

class MainActivity : ComponentActivity() {
    private val pendingOpen = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        Store.init(this)
        ReaderAssets.load(this)
        if (savedInstanceState == null) uriFrom(intent)?.let { pendingOpen.value = it }
        setContent { HearthTheme { HearthApp(pendingOpen) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uriFrom(intent)?.let { pendingOpen.value = it }
    }

    private fun uriFrom(i: Intent?): Uri? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (KeyBus.enabled && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN) KeyBus.events.tryEmit(if (code == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun HearthApp(pendingOpen: MutableStateFlow<Uri?>) {
    val ctx = LocalContext.current
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }
    val pending by pendingOpen.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pending) {
        val u = pending ?: return@LaunchedEffect
        pendingOpen.value = null
        try {
            val b = Importer.import(ctx, u)
            openBook = b.id
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "Couldn't open that file")
        }
    }

    AnimatedContent(
        targetState = openBook,
        transitionSpec = {
            (fadeIn(tween(380)) + scaleIn(tween(380), initialScale = 0.96f)) togetherWith fadeOut(tween(220))
        },
        label = "nav"
    ) { id ->
        if (id == null) LibraryScreen(onOpen = { openBook = it }, snackbar = snackbar)
        else ReaderScreen(id, onClose = { openBook = null })
    }
}
