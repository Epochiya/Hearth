package com.clockedbit.hearth

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.app.KeyguardManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

    // Android 8 fallback for the app lock (PIN / pattern screen)
    private val legacyUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) AppLock.locked.value = false
    }

    fun unlock() = AppLock.prompt(this) {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        km.createConfirmDeviceCredentialIntent("Unlock Hearth", "Your library is locked")?.let { legacyUnlock.launch(it) }
            ?: run { AppLock.locked.value = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        Store.init(this)
        ReaderAssets.load(this)
        if (savedInstanceState == null && Store.prefs.value.appLock && AppLock.deviceIsSecure(this)) AppLock.locked.value = true
        // Privacy screen: no screenshots, screen recording, or preview in recent apps
        lifecycleScope.launch {
            Store.prefs.collect { p ->
                if (p.privacyScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (savedInstanceState == null) uriFrom(intent)?.let { pendingOpen.value = it }
        setContent { HearthTheme { HearthApp(pendingOpen, ::unlock) } }
    }

    override fun onStop() {
        super.onStop()
        // Lock again whenever Hearth leaves the screen (not for rotation, and not while a picker we opened is showing)
        if (!isChangingConfigurations && Store.prefs.value.appLock && AppLock.deviceIsSecure(this)) {
            if (AppLock.skipNextLock) AppLock.skipNextLock = false else AppLock.locked.value = true
        }
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
fun HearthApp(pendingOpen: MutableStateFlow<Uri?>, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }
    val pending by pendingOpen.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val locked by AppLock.locked.collectAsState()

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

    Box(Modifier.fillMaxSize()) {
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
    if (locked) LockScreen(onUnlock)
    }
}

/** Covers everything until the phone's fingerprint / PIN check passes. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(
        Modifier.fillMaxSize().background(UiColor(0xFF15100E))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Lock, null, tint = UiColor(0xFFFFA45C), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Hearth is locked", style = MaterialTheme.typography.headlineSmall, color = UiColor(0xFFF2E6D6))
            Spacer(Modifier.height(20.dp))
            Button(onUnlock) { Text("Unlock") }
        }
    }
}
