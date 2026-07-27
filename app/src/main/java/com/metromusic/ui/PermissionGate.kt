package com.metromusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.metrocompose.MetroButton
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R

/** The permission that lets us read the on-device music library, by platform version. */
val AudioPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * Shows [content] once the library is readable, and an explanation with a request button
 * until then.
 *
 * The notification permission is asked for separately and never blocks: without it playback
 * still works, you just don't get the notification, so there is no reason to hold the app
 * hostage over it.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, AudioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }

    val requestAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        asked = true
    }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional — ignore the answer */ }
    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* only needed to edit tags on Android 9 and below; refusing costs that one page */ }

    // Ask on first launch rather than making the user press a button for the obvious case.
    LaunchedEffect(Unit) {
        if (!granted) requestAudio.launch(AudioPermission)
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifications = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifications) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Everything the app can need, asked for at the start rather than in the middle of the one
        // screen that needs it. On Android 10 and up writing a file the app does not own is granted per
        // file through a system dialog, so there is nothing to ask for here; below that there is no
        // dialog and editing an album's tags needs the blanket write permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val canWrite = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!canWrite) requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    if (granted) {
        content()
    } else {
        Box(Modifier.fillMaxSize()) {
            MetroPage(stringResource(R.string.overline_app), stringResource(R.string.permission_title)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = stringResource(R.string.permission_body),
                        color = MetroTheme.colors.subtle,
                        fontFamily = MetroRegular,
                        fontSize = 19.sp
                    )
                    Spacer(Modifier.height(28.dp))
                    MetroButton(stringResource(R.string.permission_button), filled = true) {
                        requestAudio.launch(AudioPermission)
                    }
                    if (asked) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.permission_manual),
                            color = MetroTheme.colors.dim,
                            fontFamily = MetroRegular,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
