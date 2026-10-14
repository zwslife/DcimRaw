package com.dcimgallery.raw.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dcimgallery.raw.MainViewModel
import com.dcimgallery.raw.core.exif.ExifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.persistEdits()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val treeUri by viewModel.treeUri.collectAsState()
    val photos by viewModel.photos.collectAsState()
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
                onExport = viewModel::export,
                onExportEmbedded = viewModel::exportEmbeddedJpeg,
                onDelete = viewModel::delete,
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
                onOpen = viewModel::openAt,
            )
        }
    }
}

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
