package com.metromusic.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.metrocompose.ListRow
import com.metrocompose.MetroListBox
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSubheader
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.media.FolderScanner
import com.metromusic.ui.formatTrackCount
import java.io.File

/**
 * The library folders on the library settings page: what is read besides MediaStore, how each one
 * fared on the last scan, and the two ways of adding one — see [FolderScanner].
 *
 * Laid out after foobar2000's media library folders, which is what issue #9 asked for by name: a list
 * of folders, a row to add one with the system's picker, a connected drive offered in one tap, and
 * the All Files Access switch that decides whether a drive is read by path or through the picker.
 */
@Composable
internal fun LibraryFoldersSection() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val context = LocalContext.current
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val folders by services.library.folders.collectAsStateWithLifecycle()
    val scanning by services.library.scanning.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<String?>(null) }

    // Re-read whenever the app comes back to the front: All Files Access is granted on a settings
    // screen of the system's, and a drive is plugged in while this page is open. Coming back is the
    // event that covers both, the way the album editor notices media management.
    var resumed by remember { mutableIntStateOf(0) }
    var direct by remember { mutableStateOf(FolderScanner.canReadByPath(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed++
                val now = FolderScanner.canReadByPath(context)
                // A different route to the same folders: read them again by the new one.
                if (now != direct && settings.musicFolders.isNotEmpty()) services.library.rescan()
                direct = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) services.library.addFolder(uri) }
    // A drive's own picker, opened at its root: the same grant as the row above, one step shorter.
    val pickDrive = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) services.library.addFolder(uri)
    }

    Spacer(Modifier.height(18.dp))
    MetroSubheader(top = 0.dp, text = stringResource(R.string.folders_heading))
    Text(
        text = stringResource(R.string.folders_explainer),
        color = colors.dim,
        fontFamily = MetroRegular,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
    Spacer(Modifier.height(6.dp))

    for (entry in settings.musicFolders) {
        val folder = folders.firstOrNull { it.entry == entry }
        ListRow(
            primary = folder?.label ?: remember(entry) { FolderScanner.labelOf(context, entry) },
            secondary = when (folder?.state) {
                null -> stringResource(R.string.folders_looking)
                FolderScanner.State.Ready -> stringResource(
                    if (folder.direct) R.string.folders_ready_by_path else R.string.folders_ready,
                    formatTrackCount(folder.songs)
                )
                FolderScanner.State.Unavailable -> stringResource(R.string.folders_unavailable)
                FolderScanner.State.NoAccess -> stringResource(
                    if (entry.startsWith("/")) R.string.folders_no_access_path else R.string.folders_no_access
                )
            },
            onClick = { menuFor = entry }
        )
    }
    ListRow(
        primary = stringResource(R.string.folders_add),
        secondary = stringResource(R.string.folders_add_hint),
        onClick = { pickFolder.launch(null) }
    )

    // Drives that are plugged in and not in the list yet, the way foobar2000 finds mass storage:
    // through the system's own list of volumes. A drive that list does not include is still one
    // tap further away, through the row above.
    val drives = remember(resumed, scanning, settings.musicFolders) {
        connectedDrives(context, settings.musicFolders)
    }
    for (drive in drives) {
        ListRow(
            primary = stringResource(R.string.folders_add_drive, drive.name),
            // Through the picker the drive's root can be refused — Android 11 does that for a volume
            // it considers permanent, an SD card usually — so the hint does not promise all of it.
            secondary = stringResource(
                if (direct && drive.path != null) {
                    R.string.folders_drive_hint
                } else {
                    R.string.folders_drive_hint_picker
                }
            ),
            onClick = {
                val path = drive.path
                if (direct && path != null) {
                    services.library.addFolder(path)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && drive.picker != null) {
                    runCatching { pickDrive.launch(drive.picker) }
                        .onFailure { pickFolder.launch(null) }
                } else {
                    pickFolder.launch(null)
                }
            }
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ListRow(
            primary = stringResource(R.string.folders_full_access),
            secondary = stringResource(if (direct) R.string.folders_on else R.string.folders_off),
            onClick = { openAllFilesAccess(context) }
        )
        Text(
            text = stringResource(R.string.folders_full_access_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    val menuEntry = menuFor
    MetroListBox(
        visible = menuEntry != null,
        title = menuEntry?.let { entry ->
            folders.firstOrNull { it.entry == entry }?.label ?: FolderScanner.labelOf(context, entry)
        }.orEmpty(),
        items = listOf(stringResource(R.string.folders_remove)),
        onSelect = {
            menuEntry?.let(services.library::removeFolder)
            menuFor = null
        },
        onDismiss = { menuFor = null }
    )
}

private class Drive(val name: String, val path: String?, val picker: Intent?)

/** Removable volumes that are mounted and that no library folder is on yet. */
private fun connectedDrives(context: Context, entries: List<String>): List<Drive> {
    val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    val taken = entries.mapNotNull { entry ->
        if (entry.startsWith("/")) {
            entry
        } else {
            val uri = Uri.parse(entry)
            runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?.let { FolderScanner.pathOfDocument(uri.authority, it) }
        }
    }
    return runCatching { manager.storageVolumes }.getOrDefault(emptyList())
        .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { volume ->
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.path
            } else {
                volume.uuid?.let { "/storage/$it" }
            }
            if (path != null && taken.any { it == path || it.startsWith("$path/") }) return@mapNotNull null
            Drive(
                name = volume.getDescription(context),
                path = path?.takeIf { File(it).canRead() },
                picker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    volume.createOpenDocumentTreeIntent()
                } else {
                    null
                }
            )
        }
}

/**
 * This app's own row on the All Files Access page, or the page with every app on it where a device
 * has no such row. Not for a result — that screen belongs to Settings' task; the change is noticed on
 * resume.
 */
private fun openAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
        )
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
