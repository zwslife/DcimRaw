package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.tone.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private sealed interface LookRequest {
    data class Preset(val look: AdaptiveLook) : LookRequest
    data class Reference(val uri: Uri) : LookRequest
}

@Composable
internal fun AdaptiveLookPanel(source: Bitmap, geometry: PhotoGeometry, tone: ToneParams,
    busy: Boolean, onTone: (ToneParams) -> Unit, onPick: (EditTool) -> Unit) {
    val context = LocalContext.current
    var portraits by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<LookRequest?>(null) }
    var previousBase by remember { mutableStateOf<ToneParams?>(null) }
    var previousOutput by remember { mutableStateOf<ToneParams?>(null) }
    var appliedLook by remember { mutableStateOf<AdaptiveLook?>(null) }
    var message by remember { mutableStateOf("") }
    val currentTone by rememberUpdatedState(tone)
    val currentBusy by rememberUpdatedState(busy)
    val applyTone by rememberUpdatedState(onTone)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !currentBusy) request = LookRequest.Reference(uri)
    }
    LaunchedEffect(request, source, geometry) {
        val action = request ?: return@LaunchedEffect
        val initial = currentTone
        val base = if (initial == previousOutput) previousBase ?: initial else initial
        try {
            val profile = when (action) {
                is LookRequest.Preset -> action.look.target
                is LookRequest.Reference -> {
                    val result = context.imageLoader.execute(ImageRequest.Builder(context)
                        .data(action.uri).size(256).allowHardware(false)
                        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    withContext(Dispatchers.Default) { bitmap?.let(::bitmapLookProfile) }
                        ?: error("无法读取参考照片")
                }
            }
            val fit = withContext(Dispatchers.Default) {
                val scope = this
                fitPhotoLook(source, geometry, base, profile, (action as? LookRequest.Preset)?.look?.portrait == true) {
                    scope.ensureActive()
                }
            }
            if (currentTone == initial && !currentBusy) {
                applyTone(fit)
                previousBase = base
                previousOutput = fit
                appliedLook = (action as? LookRequest.Preset)?.look
                message = when {
                    fit == initial -> "当前照片已接近目标色调"
                    action is LookRequest.Reference -> "已匹配参考照片"
                    else -> ""
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = if (action is LookRequest.Reference) "无法分析参考照片，请换一张试试" else "暂时无法匹配这张照片"
        } finally {
            if (request === action) request = null
        }
    }
    PorcelainPanel {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorToolPicker(AdaptiveLookToolEntry, onPick)
            Spacer(Modifier.weight(1f))
            EditorAction(StudioSymbol.Images, "从参考照片匹配", { picker.launch("image/*") },
                enabled = !busy && request == null)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("经典", "人像").forEachIndexed { index, label ->
                    val active = portraits == (index == 1)
                    Text(label, Modifier.heightIn(min = 40.dp).studioPressable(selected = active) {
                        portraits = index == 1
                    }.padding(vertical = 10.dp), color = if (active) EditorAccent else StudioColors.Muted, fontSize = 13.sp)
                }
            }
            key(portraits) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(AdaptiveLooks.presets.filter { it.portrait == portraits }, key = { it.label }) { look ->
                        val active = look == appliedLook && tone == previousOutput
                        Column(Modifier.width(78.dp).studioPressable(!busy, selected = active) {
                            request = LookRequest.Preset(look)
                            message = ""
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(look.swatches.map { Color(it) }))) {
                                if (active) StudioIcon(StudioSymbol.Check,
                                    Modifier.align(Alignment.BottomEnd).padding(5.dp).size(14.dp), tint = Color.White)
                            }
                            Text(look.label, Modifier.padding(vertical = 5.dp), maxLines = 1, fontSize = 12.sp,
                                color = if (active) EditorAccent else StudioColors.Ink)
                        }
                    }
                }
            }
            if (request != null || message.isNotEmpty()) Text(
                if (request != null) "正在匹配亮度与颜色…" else message,
                Modifier.padding(top = 6.dp, bottom = 8.dp), color = StudioColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun PortraitLightPanel(frame: Bitmap, geometry: PhotoGeometry, layers: List<MaskLayer>,
    busy: Boolean, onApply: (List<MaskLayer>) -> Unit, onPick: (EditTool) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var addedMaskId by remember { mutableStateOf<String?>(null) }
    val currentLayers by rememberUpdatedState(layers)
    val currentBusy by rememberUpdatedState(busy)
    val applyLayers by rememberUpdatedState(onApply)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val before = currentLayers
        try {
            val result = withContext(Dispatchers.Default) { findFaceLightMasks(frame, geometry, before) }
            if (!currentBusy && currentLayers == before) {
                message = when {
                    result.detected == 0 -> "未检测到清晰人脸，可用径向蒙版手动提亮"
                    result.layers.isEmpty() -> "没有需要新增提亮蒙版的人脸"
                    else -> {
                        applyLayers(before + result.layers)
                        addedMaskId = result.layers.last().id
                        "已提亮 ${result.layers.size} 张脸"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "暂时无法检测人脸，可用径向蒙版手动提亮"
        } finally {
            running = false
        }
    }
    PorcelainPanel {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            EditorToolPicker(PortraitToolEntry, onPick)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioPillButton(if (running) "正在检测…" else "面部提亮", { running = true; message = "" },
                icon = StudioSymbol.Sun, filled = true, enabled = !busy && !running)
            if (message.isNotEmpty()) Text(message, color = StudioColors.Muted, fontSize = 12.sp)
            if (layers.any { it.id == addedMaskId }) {
                StudioChoice("调整蒙版", { onPick(MaskToolEntry) }, symbol = StudioSymbol.Scan)
            }
        }
    }
}
