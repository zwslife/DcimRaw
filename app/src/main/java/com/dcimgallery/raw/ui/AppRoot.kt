package com.dcimgallery.raw.ui

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dcimgallery.raw.MainViewModel
import com.dcimgallery.raw.core.exif.ExifReader
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<List<PhotoEntity>?>(null) }
    var deleteAwaitingPermission by remember { mutableStateOf<List<PhotoEntity>?>(null) }
    val deletePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = deleteAwaitingPermission; deleteAwaitingPermission = null
        if (granted && requested != null) viewModel.deletePhotos(requested)
        else Toast.makeText(context, "未获得照片删除权限，照片未删除", Toast.LENGTH_SHORT).show()
    }
    val deleteAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onDeleteAuthorizationResult(it.resultCode == Activity.RESULT_OK)
    }
    val authorization by viewModel.deleteAuthorization.collectAsState()
    LaunchedEffect(authorization?.id) {
        authorization?.takeIf { !it.launched }?.let { request ->
            viewModel.markDeleteAuthorizationLaunched(request.id)
            try { deleteAuthorizationLauncher.launch(IntentSenderRequest.Builder(request.sender).build()) }
            catch (_: Exception) { viewModel.onDeleteAuthorizationResult(false) }
        }
    }
    fun confirmDeletion(requested: List<PhotoEntity>) {
        if (Build.VERSION.SDK_INT <= 28 && requested.any { it.source == "local" } &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            deleteAwaitingPermission = requested
            deletePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else viewModel.deletePhotos(requested)
    }
    var pendingExport by remember { mutableStateOf<Pair<PhotoEntity, Boolean>?>(null) }
    val exportPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingExport
        pendingExport = null
        if (granted && request != null) {
            if (request.second) viewModel.exportEmbeddedJpeg(request.first) else viewModel.export(request.first)
        } else if (!granted) {
            Toast.makeText(context, "未获得存储写入权限，无法导出到相册", Toast.LENGTH_SHORT).show()
        }
    }
    fun requestExport(photo: PhotoEntity, embedded: Boolean) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = photo to embedded
            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (embedded) viewModel.exportEmbeddedJpeg(photo) else viewModel.export(photo)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.persistEdits()
            if (event == Lifecycle.Event.ON_START) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val treeUri by viewModel.treeUri.collectAsState()
    val photos by viewModel.photos.collectAsState()
    val showCr3Only by viewModel.showCr3Only.collectAsState()
    val gridItems by viewModel.gridItems.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanFound by viewModel.scanFound.collectAsState()
    val driveReadable by viewModel.driveReadable.collectAsState()
    val selected by viewModel.selectedIndex.collectAsState()
    val decodedMap by viewModel.decodedMap.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val hasMediaPermission by viewModel.hasMediaPermission.collectAsState()
    val toneParams by viewModel.toneParams.collectAsState()
    val tonePreview by viewModel.tonePreview.collectAsState()
    val toneFull by viewModel.toneFull.collectAsState()
    val masks by viewModel.masks.collectAsState()
    val canPasteEdits by viewModel.canPasteEdits.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val selecting by viewModel.selecting.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val deleting by viewModel.deleting.collectAsState()

    // 网格滚动状态提升到此处保活：进出大图页不丢失浏览位置
    val gridState = rememberLazyGridState()

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel::onTreeGranted)
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onMediaPermissionResult(grants.values.any { it })
    }

    LaunchedEffect(treeUri) {
        if (treeUri != null) viewModel.refreshDriveState()
    }

    // 一次性提示（导出结果等）
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.toastShown()
        }
    }

    val hasAnySource = treeUri != null || hasMediaPermission

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !hasAnySource -> PermissionScreen(
                onGrantLocal = { mediaPermissionLauncher.launch(requiredMediaPermissions()) },
                onPickDirectory = { treeLauncher.launch(null) },
            )
            selected >= 0 && photos.isNotEmpty() -> PhotoPagerScreen(
                photos = photos,
                initialIndex = selected,
                decodedMap = decodedMap,
                exporting = exporting,
                deleting = deleting,
                toneParams = toneParams,
                tonePreview = tonePreview,
                toneFull = toneFull,
                masks = masks,
                onMasksChange = viewModel::setMasks,
                canPasteEdits = canPasteEdits,
                onCopyEdits = viewModel::copyEdits,
                onPasteEdits = viewModel::pasteEdits,
                onResetEdits = viewModel::resetEdits,
                loadExif = { photo ->
                    withContext(Dispatchers.IO) {
                        ExifReader.read(context, Uri.parse(photo.uri), photo.source)
                    }
                },
                onEnsureDecoded = viewModel::ensureDecoded,
                onExport = { requestExport(it, false) },
                onExportEmbedded = { requestExport(it, true) },
                onDelete = { pendingDelete = listOf(it) },
                onToneChange = viewModel::setTone,
                onEnsureToneEngine = viewModel::ensureToneEngine,
                onTonePanelDone = viewModel::renderToneFullDisplay,
                onPageChanged = viewModel::onPagerPageChanged,
                onClose = viewModel::closePager,
            )
            else -> GridScreen(
                items = gridItems,
                photoCount = photos.size,
                gridState = gridState,
                scanning = scanning,
                scanFound = scanFound,
                driveReadable = driveReadable,
                onRefresh = viewModel::rescan,
                onChangeDir = { treeLauncher.launch(null) },
                onOpen = viewModel::openPhoto,
                showCr3Only = showCr3Only,
                onShowCr3Only = viewModel::setShowCr3Only,
                selecting = selecting, selectedUris = selectedUris, deleting = deleting,
                onBeginSelection = viewModel::enterSelection, onToggleSelection = viewModel::toggleSelection,
                onSelectAll = viewModel::selectAllPhotos, onCancelSelection = viewModel::cancelSelection,
                onDeleteSelected = { pendingDelete = photos.filter { it.uri in selectedUris } },
            )
        }
        pendingDelete?.takeIf { it.isNotEmpty() }?.let { requested ->
            DeleteConfirmationDialog(requested.size, requested.singleOrNull()?.displayName,
                onConfirm = { pendingDelete = null; confirmDeletion(requested) }, onDismiss = { pendingDelete = null })
        }
    }
}

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
