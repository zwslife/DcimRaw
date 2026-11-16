package com.dcimgallery.raw.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.R

object StudioColors {
    val Canvas = Color(0xFFE9EAEC)
    val Ink = Color(0xFF202124)
    val Muted = Color(0xFF656972)
    val Accent = Color(0xFF176753)
    val Tint = Color(0xFFEDF0F3)
    val Line = Color(0xFFE2E5E8)
    val Track = Color(0xFFD8DCDF)
}

internal fun Modifier.studioPressable(enabled: Boolean = true, selected: Boolean? = null,
    onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .95f else 1f, tween(120), label = "button press")
    val input = if (selected == null) Modifier.clickable(interaction, null, enabled, role = Role.Button, onClick = onClick)
    else Modifier.selectable(selected, interaction, null, enabled, role = Role.Tab, onClick = onClick)
    graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .42f }.then(input)
}

@Composable
internal fun StudioPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: StudioSymbol? = null, filled: Boolean = false, enabled: Boolean = true) {
    val foreground = if (filled) Color.White else StudioColors.Accent
    Row(modifier.heightIn(min = 44.dp).studioPressable(enabled, onClick = onClick)
        .clip(CircleShape).background(if (filled) StudioColors.Accent else StudioColors.Tint)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        if (icon != null) StudioIcon(icon, Modifier.size(17.dp), tint = foreground)
        Text(label, color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

enum class StudioSymbol(val resource: Int) {
    ArrowLeft(R.drawable.studio_arrow_left),
    ChartNoAxesColumn(R.drawable.studio_chart_no_axes_column),
    Info(R.drawable.studio_info),
    Settings(R.drawable.studio_sliders_horizontal),
    Ellipsis(R.drawable.studio_ellipsis),
    RefreshCw(R.drawable.studio_refresh_cw),
    FolderOpen(R.drawable.studio_folder_open),
    Sun(R.drawable.studio_sun),
    Palette(R.drawable.studio_palette),
    CircleDot(R.drawable.studio_circle_dot),
    Sparkles(R.drawable.studio_sparkles),
    Aperture(R.drawable.studio_aperture),
    Focus(R.drawable.studio_focus),
    Curve(R.drawable.studio_curve),
    Check(R.drawable.studio_check),
    RotateCcw(R.drawable.studio_rotate_ccw),
    Undo2(R.drawable.studio_undo_2),
    Redo2(R.drawable.studio_redo_2),
    Scan(R.drawable.studio_scan),
    ArrowUpFromLine(R.drawable.studio_arrow_up_from_line),
    Trash2(R.drawable.studio_trash2),
    Plus(R.drawable.studio_plus),
    FlipHorizontal(R.drawable.studio_flip_horizontal),
    Copy(R.drawable.studio_copy),
    Paste(R.drawable.studio_clipboard_paste),
    Pencil(R.drawable.studio_pencil),
    Layers2(R.drawable.studio_layers2),
    Brush(R.drawable.studio_brush),
    RectangleHorizontal(R.drawable.studio_rectangle_horizontal),
    CircleDashed(R.drawable.studio_circle_dashed),
    Minus(R.drawable.studio_minus),
    Blend(R.drawable.studio_blend),
    Droplet(R.drawable.studio_droplet),
    RotateCw(R.drawable.studio_rotate_cw),
    Radius(R.drawable.studio_radius),
    HardDrive(R.drawable.studio_hard_drive),
    Contrast(R.drawable.studio_contrast),
    SunMedium(R.drawable.studio_sun_medium),
    Eclipse(R.drawable.studio_eclipse),
    Circle(R.drawable.studio_circle),
    Thermometer(R.drawable.studio_thermometer),
    Pipette(R.drawable.studio_pipette),
    Droplets(R.drawable.studio_droplets),
    Flower2(R.drawable.studio_flower2),
    Rainbow(R.drawable.studio_rainbow),
    SunDim(R.drawable.studio_sun_dim),
    Frame(R.drawable.studio_frame),
    Grip(R.drawable.studio_grip),
    CircleOff(R.drawable.studio_circle_off),
    Scale(R.drawable.studio_scale),
    X(R.drawable.studio_x),
    ChevronRight(R.drawable.studio_chevron_right),
    SquareCheck(R.drawable.studio_square_check),
    Images(R.drawable.studio_images),
    Compare(R.drawable.studio_compare),
    Share(R.drawable.studio_share),
    Moon(R.drawable.studio_moon),
    MoonActive(R.drawable.studio_moon_active),
    ContrastActive(R.drawable.studio_contrast_active),
    SunActive(R.drawable.studio_sun_active),
    ApertureActive(R.drawable.studio_aperture_active);

}

@Composable
fun StudioIcon(symbol: StudioSymbol, modifier: Modifier = Modifier, label: String? = null, tint: Color = StudioColors.Ink) {
    Icon(painterResource(symbol.resource), label, modifier.size(24.dp), tint = tint)
}

@Composable
fun StudioIconButton(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, filled: Boolean = false, enabled: Boolean = true) {
    Box(modifier.size(44.dp).studioPressable(enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        if (filled || symbol in listOf(StudioSymbol.Undo2, StudioSymbol.Redo2, StudioSymbol.Ellipsis)) {
            Box(Modifier.size(30.dp).background(if (filled) StudioColors.Accent else StudioColors.Tint, CircleShape))
        }
        StudioStateIcon(symbol, label, selected, if (filled) Color.White else StudioColors.Ink)
    }
}

@Composable
internal fun StudioStateIcon(symbol: StudioSymbol, label: String? = null, selected: Boolean = false,
    tint: Color = StudioColors.Ink) {
    Box(Modifier.size(18.dp)) {
        StudioIcon(symbol, Modifier.fillMaxSize(), label, if (selected) StudioColors.Accent else tint)
        if (selected) {
            Box(Modifier.align(Alignment.BottomEnd).size(9.dp).background(Color.White, CircleShape),
                contentAlignment = Alignment.Center) {
                StudioIcon(StudioSymbol.Check, Modifier.size(9.dp), tint = StudioColors.Accent)
            }
        }
    }
}

@Composable
internal fun StudioTool(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = StudioColors.Ink) {
    Column(modifier.heightIn(min = 44.dp).studioPressable(enabled, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        StudioIcon(symbol, Modifier.size(19.dp), tint = tint)
        Text(label, color = tint, maxLines = 1, style = TextStyle(
            fontSize = 11.sp, lineHeight = 13.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ))
    }
}

@Composable
internal fun CompareButton(photoKey: String?, comparing: Boolean, onCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onCompare)
    Box(modifier.size(44.dp).semantics {
        contentDescription = "按住对比未调色原图"
        stateDescription = if (comparing) "原图" else "编辑后"
    }.pointerInput(photoKey) {
        detectTapGestures(onPress = {
            change(true)
            try { tryAwaitRelease() } finally { change(false) }
        })
    }, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(5.dp)
        Box(Modifier.size(28.dp).shadow(1.dp, shape).background(Color.White.copy(alpha = .8f), shape),
            contentAlignment = Alignment.Center) {
            StudioIcon(StudioSymbol.Compare, Modifier.size(18.dp), tint = Color(0xFF34383A))
        }
    }
}

@Composable
internal fun StudioBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = null, onClick = onDismiss))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight * .9f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color.White)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), content = content)
        }
    }
}

@Composable
fun StudioPanelHeader(title: String, onClose: () -> Unit, onReset: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = StudioColors.Ink)
        if (onReset != null) StudioIconButton(StudioSymbol.RotateCcw, "复位", onReset)
        StudioIconButton(StudioSymbol.Check, "完成", onClose, filled = true)
    }
}

/** Inline choices keep a single 44 dp touch target without stacking an icon above its label. */
@Composable
fun StudioChoice(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    symbol: StudioSymbol? = null, selected: Boolean? = null, expandable: Boolean = false) {
    Row(modifier.height(44.dp).studioPressable(selected = selected, onClick = onClick).clip(CircleShape)
        .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (symbol != null) StudioStateIcon(symbol, selected = selected == true, tint = StudioColors.Muted)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected == true) StudioColors.Accent else StudioColors.Muted)
        if (expandable) StudioIcon(StudioSymbol.ChevronRight, Modifier.size(14.dp).rotate(90f), tint = StudioColors.Muted)
    }
}
