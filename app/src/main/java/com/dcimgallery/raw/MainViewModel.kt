package com.dcimgallery.raw

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.IntentSender
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import com.dcimgallery.raw.core.media.PhotoDeletion
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.dcimgallery.raw.core.raw.PhotoFormatProbe
import com.dcimgallery.raw.core.raw.PhotoFileType

/** 网格元素：日期分组头或照片。 */
sealed interface GridItem {
    data class Header(val date: LocalDate, val label: String) : GridItem
    data class PhotoItem(val globalIndex: Int, val photo: PhotoEntity, val wide: Boolean = false) : GridItem
}

data class DeleteAuthorization(val id: Long, val sender: IntentSender, val launched: Boolean = false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app as GalleryApp)
    private val galleryPreferences = app.getSharedPreferences("gallery-settings", android.content.Context.MODE_PRIVATE)
    private val _showCr3Only = MutableStateFlow(galleryPreferences.getBoolean("cr3-only", false))
    val showCr3Only: StateFlow<Boolean> = _showCr3Only
    private var filterMode: Boolean? = null
    private var filteredSnapshotReady = false
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting
    private val _deleteAuthorization = MutableStateFlow<DeleteAuthorization?>(null)
    val deleteAuthorization: StateFlow<DeleteAuthorization?> = _deleteAuthorization
    private var deleteConsent: CompletableDeferred<Boolean>? = null
    private var consentId = 0L
    private var currentPhotoUri: String? = null

    val photos: StateFlow<List<PhotoEntity>> =
        combine(repository.photos, _showCr3Only) { list, only -> list to only }
            .transformLatest { (list, only) ->
                if (filterMode != only) { filterMode = only; filteredSnapshotReady = false }
                val incremental = !filteredSnapshotReady
                if (!only) { emit(list); filteredSnapshotReady = true } else {
                    val matches = ArrayList<PhotoEntity>()
                    var emittedSize = -1
                    var lastEmission = 0L
                    list.chunked(256).forEach { batch ->
                        val types = PhotoFormatProbe.readBatch(app, batch)
                        batch.forEachIndexed { i, photo -> if (types[i] == PhotoFileType.CR3) matches += photo }
                        val now = android.os.SystemClock.uptimeMillis()
                        if (incremental && matches.size > 0 && matches.size != emittedSize &&
                            (emittedSize < 0 || now - lastEmission >= 100L)) {
                            emit(matches.toList()); emittedSize = matches.size; lastEmission = now
                        }
                    }
                    if (emittedSize != matches.size) emit(matches.toList())
                    filteredSnapshotReady = true
                }
            }.onEach { list ->
                if (_selectedUris.value.isNotEmpty()) {
                    val visible = list.mapTo(HashSet()) { it.uri }
                    _selectedUris.update { it.intersect(visible) }
                }
                val viewed = currentPhotoUri
                if (selectedIndex.value >= 0 && viewed != null && list.none { it.uri == viewed }) {
                    selectedIndex.value = -1; currentPhotoUri = null; onPagerPageChanged(null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setShowCr3Only(enabled: Boolean) {
        if (_showCr3Only.value == enabled) return
        closePager()
        cancelSelection()
        galleryPreferences.edit().putBoolean("cr3-only", enabled).apply()
        _showCr3Only.value = enabled
    }

    /** 按拍摄日期分组后的网格项（保持 photos 的倒序） */
    val gridItems: StateFlow<List<GridItem>> = photos
        .mapLatest { list -> withContext(Dispatchers.Default) { buildGridItems(list) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _scanFound = MutableStateFlow(0)
    val scanFound: StateFlow<Int> = _scanFound
    private var scanJob: Job? = null
    private var scanSource: Pair<Uri?, Boolean>? = null
    private var scanGeneration = 0

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
    private val _geometry = MutableStateFlow<Map<String, PhotoGeometry>>(emptyMap())
    val geometry: StateFlow<Map<String, PhotoGeometry>> = _geometry
    private val editStore = PhotoEditStore(app)
    private val loadedEdits = mutableSetOf<String>()
    private val loadingEdits = mutableMapOf<String, Job>()
    private val pendingSaves = mutableMapOf<String, Job>()
    private val editSaveMutex = Mutex()
    private var editBaseline: Pair<String, PhotoEdits>? = null
    private val _editingUri = MutableStateFlow<String?>(null)
    val editingUri: StateFlow<String?> = _editingUri
    private val _editBusy = MutableStateFlow(false)
    val editBusy: StateFlow<Boolean> = _editBusy

    /** Drafts are rendered in memory; only the editor's Done action writes them to disk. */
    fun beginEditing(uri: String): Job? {
        if (_editBusy.value || editBaseline != null) return null
        _editBusy.value = true
        return viewModelScope.launch {
            try {
                loadEdits(uri)?.join()
                pendingSaves[uri]?.join()
                editSaveMutex.withLock {
                    editBaseline = uri to editSnapshot(uri)
                    photos.value.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.let { selectedIndex.value = it }
                    _editingUri.value = uri
                }
            } finally { _editBusy.value = false }
        }
    }

    fun finishEditing(save: Boolean): Job? {
        val (uri, baseline) = editBaseline ?: return null
        if (_editBusy.value) return null
        _editBusy.value = true
        return viewModelScope.launch {
            try {
                if (save) {
                    val snapshot = editSnapshot(uri)
                    withContext(Dispatchers.IO + NonCancellable) {
                        editSaveMutex.withLock { editStore.save(uri, snapshot) }
                    }
                } else {
                    _toneParams.update { it + (uri to baseline.tone) }
                    _masks.update { it + (uri to baseline.masks) }
                    _geometry.update { it + (uri to baseline.geometry) }
                    if (toneFull.value?.first == uri) toneFull.value = null
                    if (tonePreview.value?.first == uri) tonePreview.value = null
                    renderTonePreview(uri)
                }
                photos.value.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.let { selectedIndex.value = it }
                editBaseline = null
                _editingUri.value = null
            } catch (error: Exception) {
                toast.value = "编辑保存失败：${error.message}"
            } finally { _editBusy.value = false }
        }
    }

    private fun editSnapshot(uri: String) = PhotoEdits(_toneParams.value[uri] ?: ToneParams(),
        _masks.value[uri].orEmpty(), _geometry.value[uri] ?: PhotoGeometry())
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
        if (_editBusy.value) return
        _masks.update { it + (uri to layers) }
        if (toneFull.value?.first == uri) toneFull.value = null
        renderTonePreview(uri)
        saveEdits(uri)
    }

    fun setGeometry(uri: String, geometry: PhotoGeometry) {
        if (_editBusy.value) return
        _geometry.update { it + (uri to geometry.normalized()) }
        saveEdits(uri)
    }

    private fun saveEdits(uri: String, debounceMillis: Long = 250) {
        if (editBaseline?.first == uri) return
        pendingSaves.remove(uri)?.cancel()
        pendingSaves[uri] = viewModelScope.launch {
            delay(debounceMillis)
            loadingEdits[uri]?.join()
            if (editBaseline?.first == uri) return@launch
            withContext(Dispatchers.IO + NonCancellable) {
                editSaveMutex.withLock {
                    val snapshot = PhotoEdits(_toneParams.value[uri] ?: ToneParams(), _masks.value[uri].orEmpty(),
                        _geometry.value[uri] ?: PhotoGeometry())
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
            _geometry.update { if (uri in it) it else it + (uri to saved.geometry) }
            renderTonePreview(uri)
        }.also { loadingEdits[uri] = it }
    }

    /** 离开编辑页或应用进入后台时立即落盘，取消等待中的防抖。 */
    fun persistEdits() {
        (_toneParams.value.keys + _masks.value.keys + _geometry.value.keys).forEach { saveEdits(it, 0) }
    }

    /** 导出进行中 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    /** 一次性提示消息 */
    val toast = MutableStateFlow<String?>(null)

    private var mediaSyncJob: Job? = null
    private var externalSyncJob: Job? = null
    private var mediaObserverRegistered = false
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = requestLocalSync()
    }
    private val externalObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            requestExternalSync()
        }
    }

    init {
        checkMediaPermission()
        refreshDriveState()
        registerMediaObserver()
        observeExternal()
        rescan()
    }

    fun onForeground() {
        checkMediaPermission()
        registerMediaObserver()
        if (_deleting.value) return
        requestLocalSync()
        if (_treeUri.value != null) rescan()
    }

    private fun requestLocalSync() {
        if (!_hasMediaPermission.value) return
        mediaSyncJob?.cancel()
        mediaSyncJob = viewModelScope.launch {
            delay(350)
            if (_deleting.value) return@launch
            try {
                repository.syncLocal()
                // The same file may also be visible through an authorized directory URI.
                if (_treeUri.value != null) requestExternalSync()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep existing rows if the provider is temporarily unavailable. */ }
        }
    }

    private fun registerMediaObserver() {
        if (mediaObserverRegistered) return
        runCatching {
            getApplication<GalleryApp>().contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            mediaObserverRegistered = true
        }
    }

    private fun requestExternalSync() {
        if (_treeUri.value == null) return
        externalSyncJob?.cancel()
        externalSyncJob = viewModelScope.launch {
            delay(500)
            scanJob?.join()
            if (!_deleting.value) rescan()
        }
    }

    private fun observeExternal() {
        val resolver = getApplication<GalleryApp>().contentResolver
        resolver.unregisterContentObserver(externalObserver)
        _treeUri.value?.authority?.let { authority ->
            runCatching { resolver.registerContentObserver(Uri.parse("content://$authority"), true, externalObserver) }
        }
    }

    fun enterSelection(uri: String? = null) {
        if (_deleting.value) return
        _selecting.value = true
        if (uri != null) _selectedUris.update { it + uri }
    }
    fun toggleSelection(uri: String) {
        if (!_deleting.value) _selectedUris.update { if (uri in it) it - uri else it + uri }
    }
    fun setSelectedPhotos(uris: Set<String>) {
        if (!_deleting.value) {
            _selecting.value = true
            _selectedUris.value = uris.intersect(photos.value.mapTo(HashSet()) { it.uri })
        }
    }
    fun selectAllPhotos() { if (!_deleting.value) _selectedUris.value = photos.value.mapTo(LinkedHashSet()) { it.uri } }
    fun cancelSelection() {
        if (_deleting.value) return
        _selecting.value = false; _selectedUris.value = emptySet()
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
        if (granted) { registerMediaObserver(); rescan() }
    }

    fun onTreeGranted(uri: Uri) {
        SafManager.persist(getApplication(), uri)
        _treeUri.value = uri
        observeExternal()
        refreshDriveState()
        rescan()
    }

    fun refreshDriveState() {
        val uri = _treeUri.value
        viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) { uri != null && SafManager.isReadable(getApplication(), uri) }
            if (_treeUri.value == uri) _driveReadable.value = readable
        }
    }

    fun rescan() {
        if (_deleting.value) return
        val tree = _treeUri.value
        val includeLocal = _hasMediaPermission.value
        if (tree == null && !includeLocal) return
        val source = tree to includeLocal
        if (_scanning.value && scanSource == source) return
        val previousScan = scanJob
        previousScan?.cancel()
        scanSource = source
        val generation = ++scanGeneration
        _scanning.value = true
        scanJob = viewModelScope.launch {
            _scanFound.value = 0
            try {
                previousScan?.join()
                repository.rescan(tree, includeLocal) { found ->
                    if (scanGeneration == generation) _scanFound.value = found
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (scanGeneration == generation) toast.value = "照片刷新未完成，已保留现有列表：${error.message.orEmpty()}"
            } finally {
                if (scanGeneration == generation) _scanning.value = false
            }
        }
    }

    fun openAt(index: Int) {
        selectedIndex.value = index
    }

    fun openPhoto(uri: String) {
        val index = photos.value.indexOfFirst { it.uri == uri }
        if (index >= 0) { currentPhotoUri = uri; openAt(index) }
    }

    fun closePager() {
        persistEdits()
        selectedIndex.value = -1
        currentPhotoUri = null
    }

    /** All photo sources use the same decoded bitmap and editing pipeline. */
    fun ensureDecoded(photo: PhotoEntity) {
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
        if (_editBusy.value) return
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
        currentPhotoUri = uri
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
                val reason = rawRepository.exportJpeg(photo.uri, photo.displayName, tone, _masks.value[photo.uri].orEmpty(),
                    _geometry.value[photo.uri] ?: PhotoGeometry())
                toast.value = reason?.let { "导出失败：$it" } ?: "已导出到系统相册（Pictures/CR3相册）"
            } finally {
                _exporting.value = false
            }
        }
    }

    /** Called only after the application's explicit confirmation dialog. */
    fun deletePhotos(requested: List<PhotoEntity>) {
        if (_deleting.value || requested.isEmpty()) return
        val targets = requested.distinctBy { it.uri }
        _deleting.value = true
        viewModelScope.launch {
            try {
                scanJob?.cancelAndJoin()
                mediaSyncJob?.cancelAndJoin()
                externalSyncJob?.cancelAndJoin()
                _scanning.value = false
                val report = PhotoDeletion(getApplication()).delete(targets, ::authorizeDeletion) { deleted ->
                    repository.remove(deleted)
                    if (currentPhotoUri in deleted) { selectedIndex.value = -1; onPagerPageChanged(null) }
                    _selectedUris.update { it - deleted }
                    targets.filter { it.uri in deleted }.forEach { photo ->
                        pendingSaves.remove(photo.uri)?.cancelAndJoin()
                        loadingEdits.remove(photo.uri)?.cancelAndJoin()
                        loadedEdits.remove(photo.uri)
                        withContext(Dispatchers.IO) {
                            runCatching {
                                PreviewCache.fileFor(photo.uri).delete()
                                PreviewCache.fileFor(com.dcimgallery.raw.core.coil.Cr3Image(Uri.parse(photo.uri),
                                    photo.sizeBytes, photo.lastModified).cacheKey).delete()
                                DecodedCache.fileFor(photo.uri).delete()
                                editStore.delete(photo.uri)
                            }
                        }
                    }
                    _decodedMap.update { it - deleted }
                    _decodingSet.update { it - deleted }
                    _toneParams.update { it - deleted }
                    _masks.update { it - deleted }
                    _geometry.update { it - deleted }
                    if (tonePreview.value?.first in deleted) tonePreview.value = null
                    if (toneFull.value?.first in deleted) toneFull.value = null
                }
                if (_selectedUris.value.isEmpty()) _selecting.value = false
                toast.value = when {
                    report.cancelled && report.deleted.isEmpty() -> "已取消删除"
                    report.cancelled -> "已删除 ${report.deleted.size} 张，已取消后续删除"
                    report.failed.isNotEmpty() -> "已删除 ${report.deleted.size} 张，${report.failed.size} 张未能删除，请检查删除授权或目录写入权限"
                    else -> "已删除 ${report.deleted.size} 张照片"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast.value = "删除未完成，请刷新列表后重试" }
            finally {
                _deleteAuthorization.value = null; deleteConsent = null
                _deleting.value = false
                requestLocalSync()
            }
        }
    }

    private suspend fun authorizeDeletion(sender: IntentSender): Boolean {
        val response = CompletableDeferred<Boolean>()
        deleteConsent = response
        _deleteAuthorization.value = DeleteAuthorization(++consentId, sender)
        return try { response.await() } finally {
            if (deleteConsent === response) { deleteConsent = null; _deleteAuthorization.value = null }
        }
    }

    fun markDeleteAuthorizationLaunched(id: Long) {
        _deleteAuthorization.update { if (it?.id == id) it.copy(launched = true) else it }
    }
    fun onDeleteAuthorizationResult(granted: Boolean) { deleteConsent?.complete(granted) }

    override fun onCleared() {
        val resolver = getApplication<GalleryApp>().contentResolver
        resolver.unregisterContentObserver(mediaObserver)
        resolver.unregisterContentObserver(externalObserver)
        deleteConsent?.cancel()
        super.onCleared()
    }

    fun toastShown() {
        toast.value = null
    }

    private suspend fun buildGridItems(list: List<PhotoEntity>): List<GridItem> {
        val out = ArrayList<GridItem>(list.size + 16)
        var lastDate: LocalDate? = null
        val zone = ZoneId.systemDefault()
        list.forEachIndexed { index, photo ->
            if (index % 256 == 0) currentCoroutineContext().ensureActive()
            val date = Instant.ofEpochMilli(photo.takenAt).atZone(zone).toLocalDate()
            val firstInGroup = date != lastDate
            if (firstInGroup) {
                out += GridItem.Header(date, formatDateLabel(date))
                lastDate = date
            }
            out += GridItem.PhotoItem(index, photo, wide = firstInGroup)
        }
        return out
    }

    private fun formatDateLabel(date: LocalDate): String {
        val weekCn = "一二三四五六日"[date.dayOfWeek.value - 1]
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 周$weekCn"
    }
}
