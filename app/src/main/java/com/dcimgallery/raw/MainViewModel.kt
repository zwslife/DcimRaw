package com.dcimgallery.raw

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dcimgallery.raw.core.coil.PreviewCache
import com.dcimgallery.raw.core.raw.DecodedCache
import com.dcimgallery.raw.core.raw.RawDecodeRepository
import com.dcimgallery.raw.core.raw.RawDecoder
import com.dcimgallery.raw.core.saf.SafManager
import com.dcimgallery.raw.core.tone.ToneParams
import com.dcimgallery.raw.core.tone.TonePreviewScheduler
import com.dcimgallery.raw.core.tone.MaskLayer
import com.dcimgallery.raw.core.tone.MaskRenderCache
import com.dcimgallery.raw.core.tone.PhotoEditStore
import com.dcimgallery.raw.core.tone.PhotoEdits
import com.dcimgallery.raw.core.tone.PhotoEditClipboard
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.data.repo.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 网格元素：日期分组头或照片。 */
sealed interface GridItem {
    data class Header(val date: LocalDate, val label: String) : GridItem
    data class PhotoItem(val globalIndex: Int, val photo: PhotoEntity) : GridItem
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app as GalleryApp)

    val photos: StateFlow<List<PhotoEntity>> =
        repository.photos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 按拍摄日期分组后的网格项（保持 photos 的倒序） */
    val gridItems: StateFlow<List<GridItem>> = photos
        .map(::buildGridItems)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _scanFound = MutableStateFlow(0)
    val scanFound: StateFlow<Int> = _scanFound

    /** 已授权的外接盘目录树（可能暂时未挂载） */
    private val _treeUri = MutableStateFlow(SafManager.persistedUri(app))
    val treeUri: StateFlow<Uri?> = _treeUri

    /** 外接盘当前是否可读 */
    private val _driveReadable = MutableStateFlow(false)
    val driveReadable: StateFlow<Boolean> = _driveReadable

    /** 本机相册（MediaStore）读取权限 */
    private val _hasMediaPermission = MutableStateFlow(false)
    val hasMediaPermission: StateFlow<Boolean> = _hasMediaPermission

    /** 全屏查看的照片下标，-1 表示未打开 */
    val selectedIndex = MutableStateFlow(-1)

    // ---- RAW 完整解码 ----

    private val rawRepository = RawDecodeRepository(app as GalleryApp)

    /** uri → 完整解码 Bitmap（只保留最近几张，防止内存膨胀） */
    private val _decodedMap = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val decodedMap: StateFlow<Map<String, Bitmap>> = _decodedMap

    /** 正在解码的 uri 集合（内部去重用） */
    private val _decodingSet = MutableStateFlow<Set<String>>(emptySet())

    /** 每张照片的调色参数（与蒙版一同自动保存） */
    private val _toneParams = MutableStateFlow<Map<String, ToneParams>>(emptyMap())
    val toneParams: StateFlow<Map<String, ToneParams>> = _toneParams

    private val _masks = MutableStateFlow<Map<String, List<MaskLayer>>>(emptyMap())
    val masks: StateFlow<Map<String, List<MaskLayer>>> = _masks
    private val editStore = PhotoEditStore(app)
    private val loadedEdits = mutableSetOf<String>()
    private val loadingEdits = mutableMapOf<String, Job>()
    private val pendingSaves = mutableMapOf<String, Job>()
    private val editSaveMutex = Mutex()
    private val editClipboard = PhotoEditClipboard()
    val canPasteEdits = MutableStateFlow(false)

    fun copyEdits(uri: String) {
        viewModelScope.launch {
            loadEdits(uri)?.join()
            editClipboard.copy(PhotoEdits(_toneParams.value[uri] ?: ToneParams(), _masks.value[uri].orEmpty()))
            canPasteEdits.value = editClipboard.hasValue
            toast.value = "已复制调色参数（含蒙版）"
        }
    }

    fun pasteEdits(uri: String) {
        val edits = editClipboard.paste() ?: return
        applyEdits(uri, edits)
        toast.value = "已粘贴调色参数（含蒙版）"
    }

    fun resetEdits(uri: String) {
        applyEdits(uri, PhotoEdits())
        toast.value = "已复位全部调色及蒙版"
    }

    private fun applyEdits(uri: String, edits: PhotoEdits) {
        _toneParams.update { it + (uri to edits.tone) }
        _masks.update { it + (uri to edits.masks) }
        if (toneFull.value?.first == uri) toneFull.value = null
        if (tonePreview.value?.first == uri) tonePreview.value = null
        renderTonePreview(uri)
        saveEdits(uri)
    }

    fun setMasks(uri: String, layers: List<MaskLayer>) {
        _masks.update { it + (uri to layers) }
        if (toneFull.value?.first == uri) toneFull.value = null
        renderTonePreview(uri)
        saveEdits(uri)
    }

    private fun saveEdits(uri: String, debounceMillis: Long = 250) {
        pendingSaves.remove(uri)?.cancel()
        pendingSaves[uri] = viewModelScope.launch {
            delay(debounceMillis)
            loadingEdits[uri]?.join()
            withContext(Dispatchers.IO + NonCancellable) {
                editSaveMutex.withLock {
                    val snapshot = PhotoEdits(_toneParams.value[uri] ?: ToneParams(), _masks.value[uri].orEmpty())
                    runCatching { editStore.save(uri, snapshot) }
                        .onFailure { toast.value = "编辑保存失败：${it.message}" }
                }
            }
        }
    }

    private fun loadEdits(uri: String): Job? {
        if (!loadedEdits.add(uri)) return loadingEdits[uri]
        return viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { editStore.load(uri) } ?: return@launch
            // 加载期间用户已开始操作时，保留当前编辑。
            _toneParams.update { if (uri in it) it else it + (uri to saved.tone) }
            _masks.update { if (uri in it) it else it + (uri to saved.masks) }
            renderTonePreview(uri)
        }.also { loadingEdits[uri] = it }
    }

    /** 离开编辑页或应用进入后台时立即落盘，取消等待中的防抖。 */
    fun persistEdits() {
        (_toneParams.value.keys + _masks.value.keys).forEach { saveEdits(it, 0) }
    }

    /** 导出进行中 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    /** 一次性提示消息 */
    val toast = MutableStateFlow<String?>(null)

    init {
        checkMediaPermission()
        refreshDriveState()
        rescan()
    }

    fun checkMediaPermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        _hasMediaPermission.value = ContextCompat.checkSelfPermission(
            getApplication(), perm,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onMediaPermissionResult(granted: Boolean) {
        _hasMediaPermission.value = granted
        if (granted) rescan()
    }

    fun onTreeGranted(uri: Uri) {
        SafManager.persist(getApplication(), uri)
        _treeUri.value = uri
        refreshDriveState()
        rescan()
    }

    fun refreshDriveState() {
        val uri = _treeUri.value
        _driveReadable.value = uri != null && SafManager.isReadable(getApplication(), uri)
    }

    fun rescan() {
        val tree = _treeUri.value
        val includeLocal = _hasMediaPermission.value
        if (tree == null && !includeLocal) return
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _scanFound.value = 0
            try {
                repository.rescan(tree, includeLocal) { found -> _scanFound.value = found }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun openAt(index: Int) {
        selectedIndex.value = index
    }

    fun closePager() {
        persistEdits()
        selectedIndex.value = -1
    }

    /** 大图页触发：后台完整解码 RAW（仅外接 CR3，幂等、静默失败）。 */
    fun ensureDecoded(photo: PhotoEntity) {
        if (photo.source != "external") return
        val uri = photo.uri
        loadEdits(uri)
        if (_decodedMap.value.containsKey(uri) || _decodingSet.value.contains(uri)) return
        viewModelScope.launch {
            _decodingSet.update { it + uri }
            try {
                val bitmap = rawRepository.decode(uri)
                if (bitmap != null) {
                    _decodedMap.update { old ->
                        (old + (uri to bitmap)).entries.toList().takeLast(3)
                            .associate { it.key to it.value }
                    }
                }
            } finally {
                _decodingSet.update { it - uri }
            }
        }
    }

    /** 更新某张照片的调色参数（全尺寸定稿随之失效，待重新渲染）。 */
    fun setTone(uri: String, params: ToneParams) {
        _toneParams.update { it + (uri to params) }
        if (toneFull.value?.first == uri) toneFull.value = null
        renderTonePreview(uri)
        saveEdits(uri)
    }

    // ---- 调色渲染引擎（native） ----

    private val toneMutex = Mutex()
    private val toneFullMutex = Mutex()
    private val previewMasks = MaskRenderCache()
    private val fullMasks = MaskRenderCache()
    private var toneHandle = 0L
    private var toneHandleUri: String? = null
    private var previewWidth = 0
    private var previewHeight = 0

    /** uri → 滑杆实时预览小图 */
    val tonePreview = MutableStateFlow<Pair<String, Bitmap>?>(null)

    /** uri → 面板关闭后的全尺寸定稿图 */
    val toneFull = MutableStateFlow<Pair<String, Bitmap>?>(null)

    // 单个渲染循环：一帧进行中只保留最新请求，避免为触摸事件创建/取消协程。
    private val previewScheduler = TonePreviewScheduler(viewModelScope) { uri ->
        toneMutex.withLock { renderTonePreviewLocked(uri) }
    }

    /** 打开调色面板（或照片已有调色）时调用：为当前照片准备预览会话。 */
    fun ensureToneEngine(photo: PhotoEntity) {
        if (photo.source != "external") return
        if (toneHandleUri == photo.uri && toneHandle != 0L) return
        val bitmap = _decodedMap.value[photo.uri] ?: return
        viewModelScope.launch(Dispatchers.Default) {
            toneMutex.withLock {
                if (toneHandleUri == photo.uri && toneHandle != 0L) return@withLock
                destroyToneEngineLocked()
                val handle = RawDecoder.createToneSession(bitmap, 1024)
                if (handle != 0L) {
                    toneHandle = handle
                    toneHandleUri = photo.uri
                    val dims = RawDecoder.tonePreviewSize(handle)
                    if (dims.size == 2 && dims[0] > 0 && dims[1] > 0) {
                        previewWidth = dims[0]
                        previewHeight = dims[1]
                        renderTonePreviewLocked(photo.uri)
                    }
                }
            }
        }
    }

    /** 滑杆拖动：重渲预览小图。 */
    fun renderTonePreview(uri: String) {
        previewScheduler.request(uri)
    }

    private fun renderTonePreviewLocked(uri: String) {
        val handle = toneHandle
        if (handle == 0L || previewWidth <= 0 || previewHeight <= 0 || toneHandleUri != uri) return
        val params = _toneParams.value[uri] ?: ToneParams()
        val data = params.toRenderData()
        // 已发布的 Bitmap 不再修改，确保 StateFlow 发出新帧且 Compose 重绘。
        val out = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        if (RawDecoder.renderTonePreview(handle, data.matrix, data.lut, data.hsl, data.effects, out) &&
            previewMasks.apply(out, _masks.value[uri].orEmpty(), handle)) {
            // 连续拖动也发布已完成帧，下一次任务读取最新参数，避免一直等停手。
            tonePreview.value = uri to out
        } else {
            out.recycle()
        }
    }

    /** 调色面板关闭：全尺寸定稿渲染（大图恢复全画质）。 */
    fun renderToneFullDisplay(photo: PhotoEntity) {
        val uri = photo.uri
        saveEdits(uri, 0)
        val params = _toneParams.value[uri] ?: ToneParams()
        val maskLayers = _masks.value[uri].orEmpty()
        if (params.isNeutral && maskLayers.none { it.enabled && !it.tone.isNeutral }) {
            toneFull.value = null
            return
        }
        val src = _decodedMap.value[uri] ?: return
        viewModelScope.launch(Dispatchers.Default) {
            // 全尺寸渲染只读 src，不占用预览会话锁，避免重新打开面板时被阻塞。
            toneFullMutex.withLock {
                // 独立串行，避免多次完成操作同时分配多张全尺寸图片。
                if ((_toneParams.value[uri] ?: ToneParams()) != params ||
                    _masks.value[uri].orEmpty() != maskLayers || toneHandleUri != uri ||
                    toneFull.value?.first == uri) return@withLock
                val data = params.toRenderData()
                val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                var published = false
                try {
                    val rendered = withContext(Dispatchers.IO) {
                        val threadId = Process.myTid()
                        val previousPriority = Process.getThreadPriority(threadId)
                        try {
                            // 全尺寸补帧让出 CPU；拨盘实时预览保持正常优先级。
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            RawDecoder.renderToneFull(src, data.matrix, data.lut, data.hsl, data.effects, dst) &&
                                fullMasks.apply(dst, maskLayers)
                        } finally {
                            Process.setThreadPriority(previousPriority)
                        }
                    }
                    if (rendered) {
                        withContext(Dispatchers.Main.immediate) {
                            if ((_toneParams.value[uri] ?: ToneParams()) == params &&
                                _masks.value[uri].orEmpty() == maskLayers && toneHandleUri == uri) {
                                toneFull.value = uri to dst
                                published = true
                            }
                        }
                    }
                } finally {
                    if (!published) dst.recycle()
                }
            }
        }
    }

    /** 翻页：释放上一张的调色会话。 */
    fun onPagerPageChanged(uri: String?) {
        if (toneHandleUri != null && toneHandleUri != uri) {
            viewModelScope.launch(Dispatchers.Default) {
                toneMutex.withLock {
                    if (toneHandleUri != uri) {
                        destroyToneEngineLocked()
                        tonePreview.value = null
                        toneFull.value = null
                    }
                }
            }
        }
    }

    private fun destroyToneEngineLocked() {
        if (toneHandle != 0L) RawDecoder.destroyToneSession(toneHandle)
        toneHandle = 0L
        toneHandleUri = null
        previewWidth = 0
        previewHeight = 0
        previewMasks.clear()
    }

    /** 导出当前照片 JPEG 到系统相册（应用当前调色参数）。 */
    fun exportEmbeddedJpeg(photo: PhotoEntity) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val reason = rawRepository.exportEmbeddedJpeg(photo.uri, photo.displayName)
                toast.value = reason?.let { "导出失败：$it" } ?: "内嵌 JPEG 已导出到相册（未应用调色）"
            } finally { _exporting.value = false }
        }
    }

    fun export(photo: PhotoEntity) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                loadEdits(photo.uri)?.join()
                saveEdits(photo.uri, 0)
                pendingSaves[photo.uri]?.join()
                val tone = _toneParams.value[photo.uri] ?: ToneParams()
                val reason = rawRepository.exportJpeg(photo.uri, photo.displayName, tone, _masks.value[photo.uri].orEmpty())
                toast.value = reason?.let { "导出失败：$it" } ?: "已导出到系统相册（Pictures/CR3相册）"
            } finally {
                _exporting.value = false
            }
        }
    }

    /** 从外接盘永久删除照片（SAF 写权限）。 */
    fun delete(photo: PhotoEntity) {
        val app = getApplication<GalleryApp>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentsContract.deleteDocument(
                        app.contentResolver, Uri.parse(photo.uri),
                    )
                }.getOrDefault(false)
            }
            if (ok) {
                app.db.photoDao().deleteByUri(photo.uri)
                PreviewCache.fileFor(photo.uri).delete()
                DecodedCache.fileFor(photo.uri).delete()
                _decodedMap.update { it - photo.uri }
                _decodingSet.update { it - photo.uri }
                _toneParams.update { it - photo.uri }
                _masks.update { it - photo.uri }
                pendingSaves.remove(photo.uri)?.let { it.cancel(); it.join() }
                withContext(Dispatchers.IO) { editStore.delete(photo.uri) }
                toast.value = "已删除 ${photo.displayName}"
            } else {
                toast.value = "删除失败：请确认授权时包含了写权限（可重新授权外接目录）"
            }
        }
    }

    fun toastShown() {
        toast.value = null
    }

    private fun buildGridItems(list: List<PhotoEntity>): List<GridItem> {
        val out = ArrayList<GridItem>(list.size + 16)
        var lastDate: LocalDate? = null
        val zone = ZoneId.systemDefault()
        list.forEachIndexed { index, photo ->
            val date = Instant.ofEpochMilli(photo.takenAt).atZone(zone).toLocalDate()
            if (date != lastDate) {
                out += GridItem.Header(date, formatDateLabel(date))
                lastDate = date
            }
            out += GridItem.PhotoItem(index, photo)
        }
        return out
    }

    private fun formatDateLabel(date: LocalDate): String {
        val weekCn = "一二三四五六日"[date.dayOfWeek.value - 1]
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 周$weekCn"
    }
}
