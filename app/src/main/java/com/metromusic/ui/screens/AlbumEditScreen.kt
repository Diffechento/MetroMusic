package com.metromusic.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroButton
import com.metrocompose.MetroEmptyNote
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroSuggestBox
import com.metrocompose.MetroTextBox
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.media.MetadataWriter
import com.metromusic.data.media.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editing what the library says about an album — by writing the tags inside its files.
 *
 * It wrote MediaStore's own columns until it turned out that Android simply ignores those writes for
 * metadata (see [MetadataWriter]), which is why saving used to do nothing at all. Tags are the real
 * thing: every app reads them, and a rescan now confirms an edit instead of undoing it.
 *
 * Android will not let an app write media it does not own without asking the user once. That arrives
 * as a [MetadataWriter.Result.NeedsConsent] with a system dialog to launch, and the save is retried as
 * soon as the dialog comes back with a yes.
 */
@Composable
fun AlbumEditScreen(albumId: Long, onDone: () -> Unit) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val album = library.album(albumId)

    if (album == null) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.album_title)) {
            MetroEmptyNote(stringResource(R.string.album_gone))
        }
        return
    }

    val tracks = remember(library, albumId) { library.tracksOf(album) }
    val writer = remember { MetadataWriter(services.appContext) }

    var title by remember(album.id) { mutableStateOf(album.title) }
    var artist by remember(album.id) { mutableStateOf(album.artist) }
    var year by remember(album.id) {
        mutableStateOf(album.year.takeIf { it > 0 }?.toString().orEmpty())
    }
    var genre by remember(album.id) {
        mutableStateOf(tracks.firstNotNullOfOrNull { it.genre }.orEmpty())
    }
    var failure by remember { mutableStateOf<String?>(null) }

    // Whether Android is still going to ask about every album separately. Re-read whenever something
    // that could change the answer comes back — the settings screen, or the runtime prompt.
    val context = LocalContext.current
    var asksEveryTime by remember { mutableStateOf(asksEveryTime(context)) }
    // On resume rather than through an activity result: that settings screen belongs to Settings'
    // own task, so `startActivityForResult` against it is answered CANCELLED the instant it is
    // launched and the screen never even comes forward. Coming back is the event that matters
    // anyway, and this way it is noticed however the user got there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) asksEveryTime = asksEveryTime(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openMediaManagement() {
        // Straight to this app's own row on that page. A device whose settings have no such screen
        // is left alone rather than crashed at.
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_MANAGE_MEDIA,
                    Uri.parse("package:" + context.packageName)
                )
            )
        }
    }
    // Media management is only half of it: the platform also wants the app to hold
    // ACCESS_MEDIA_LOCATION before it will skip the dialog — the reasoning being that an app allowed
    // to rewrite a file silently should already be able to see everything in it. Measured, because
    // it is the kind of requirement documentation states and platforms forget: with the special
    // access alone the dialog still came up, and with both it did not. Asked for here and nowhere
    // else, so nobody who never edits a tag is ever prompted for something that reads like location.
    val mediaLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        asksEveryTime = asksEveryTime(context)
        // Refused, so the special access would buy nothing; the row stays, which is the truth.
        if (granted) openMediaManagement()
    }

    // Built fresh on every attempt: what the user typed can change between the first try and the
    // retry that follows the consent dialog. Only the fields that differ are sent — an untouched
    // field should not be rewritten into the file, and a blank year should not blank the tag.
    fun tags(): Tags = Tags(
        album = title.trim().takeIf { it != album.title },
        artist = artist.trim().takeIf { it != album.artist },
        year = year.trim().takeIf { it.isNotEmpty() && it != album.year.toString() },
        genre = genre.trim().takeIf {
            it.isNotEmpty() && it != tracks.firstNotNullOfOrNull { track -> track.genre }
        }
    )

    // Tagging a whole album is file I/O — a copy out, a rewrite and a copy back per track — so it
    // runs off the frame thread. The page says what it is doing while it happens; without that a
    // twenty-track album looks frozen.
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    fun finish(result: MetadataWriter.Result.Done) {
        // The library reads MediaStore, and the scanner has just been asked to re-read the files;
        // a rescan here picks up whatever it has already committed, and the content observer picks
        // up the rest.
        services.library.rescan()
        if (result.failures.isEmpty()) onDone() else failure = result.failures.joinToString("\n")
    }

    val consentRefused = stringResource(R.string.album_edit_no_consent)
    // The save and the consent dialog each need the other: the dialog's answer restarts the save, and
    // the save is what raises the dialog. One box holds the attempt so neither has to be declared
    // after the other.
    val attempts = remember { Attempts() }
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) attempts.run(false)
    }

    // The retry after the dialog passes false, so a platform that still refuses is reported instead
    // of raising the dialog again for ever.
    attempts.run = { allowConsent ->
        failure = null
        saving = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { writer.write(tracks.map { it.uri }, tags()) }
            saving = false
            when (outcome) {
                is MetadataWriter.Result.Done -> finish(outcome)
                is MetadataWriter.Result.NeedsConsent ->
                    if (allowConsent) {
                        consent.launch(IntentSenderRequest.Builder(outcome.request).build())
                    } else {
                        failure = consentRefused
                    }
                is MetadataWriter.Result.Failed -> failure = outcome.reason
            }
        }
    }

    fun save() = attempts.run(true)

    MetroPage(stringResource(R.string.overline_app), stringResource(R.string.album_edit)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Field(stringResource(R.string.album_edit_title)) { MetroTextBox(title, { title = it }) }
            Field(stringResource(R.string.album_edit_artist)) { MetroTextBox(artist, { artist = it }) }
            Field(stringResource(R.string.album_edit_year)) { MetroTextBox(year, { year = it }) }
            // The genre the library already knows about, offered as you type. Genres are free text in
            // every tagger there is, and this is the field where that costs something: "Electro" typed
            // again as "electro" is a second section in the panorama for the same music. The app folds
            // those together after the fact (settings → library → fix genre doubling); offering what
            // the other tracks say stops them being written in the first place.
            Field(stringResource(R.string.album_edit_genre)) {
                MetroSuggestBox(
                    value = genre,
                    onValueChange = { genre = it },
                    suggestions = library.genres
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Says what it is doing while it does it: rewriting the tags of every track of an
                // album is real file work, and a button that just sits there reads as a dead button.
                MetroButton(
                    text = stringResource(
                        if (saving) R.string.album_edit_saving else R.string.action_save
                    ),
                    filled = true
                ) { if (!saving) save() }
                MetroButton(stringResource(R.string.action_cancel), onClick = onDone)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = failure ?: stringResource(R.string.album_edit_explainer),
                color = if (failure != null) colors.accent else colors.dim,
                fontFamily = MetroRegular,
                fontSize = 13.sp
            )

            // The consent dialog is per set of files, so editing ten albums means answering it ten
            // times. Since Android 12 that can be settled once, as a special app access the user
            // grants in system settings — which is where this goes, because it is the platform's
            // switch and not one of ours. Offered here rather than on a settings page: this is the
            // one screen where being asked again is the thing that just happened. It disappears once
            // granted, and it is not shown at all below Android 12, where there is nothing to offer.
            if (asksEveryTime) {
                Spacer(Modifier.height(16.dp))
                MetroButton(stringResource(R.string.album_edit_manage)) {
                    if (hasMediaLocation(context)) {
                        openMediaManagement()
                    } else {
                        mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.album_edit_manage_explainer),
                    color = colors.dim,
                    fontFamily = MetroRegular,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Whether Android is still going to ask about every album, and there is something the user can do.
 *
 * False below Android 12 as well — not because it does not ask there, but because there is no such
 * setting to send anyone to, and a button that leads nowhere is worse than the dialog it offers to
 * silence. Both halves have to be in place: the special access *and* [Manifest.permission.
 * ACCESS_MEDIA_LOCATION], or the dialog comes up regardless.
 */
private fun asksEveryTime(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !(MediaStore.canManageMedia(context) && hasMediaLocation(context))

private fun hasMediaLocation(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Holds the save attempt, so it and the consent dialog can each reach the other. */
private class Attempts {
    var run: (Boolean) -> Unit = {}
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            text = label,
            color = MetroTheme.colors.subtle,
            fontFamily = MetroSemilight,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}
