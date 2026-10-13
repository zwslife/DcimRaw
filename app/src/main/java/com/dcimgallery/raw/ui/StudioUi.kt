package com.dcimgallery.raw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.R

object StudioColors {
    val Canvas = Color(0xFFF1F4EF)
    val Ink = Color(0xFF202923)
    val Muted = Color(0xFF68736B)
    val Pine = Color(0xFF28745C)
    val Tint = Color(0xFFE8F1EB)
    val Line = Color(0xFFE4E9E3)
}

enum class StudioSymbol(val resource: Int) {
    ArrowLeft(R.drawable.studio_arrow_left),
    ChartNoAxesColumn(R.drawable.studio_chart_no_axes_column),
    Info(R.drawable.studio_info),
    Ellipsis(R.drawable.studio_ellipsis),
    RefreshCw(R.drawable.studio_refresh_cw),
    FolderOpen(R.drawable.studio_folder_open),
    Sun(R.drawable.studio_sun),
    Palette(R.drawable.studio_palette),
    CircleDot(R.drawable.studio_circle_dot),
    Sparkles(R.drawable.studio_sparkles),
    Aperture(R.drawable.studio_aperture),
    Focus(R.drawable.studio_focus),
    Check(R.drawable.studio_check),
    RotateCcw(R.drawable.studio_rotate_ccw),
    Scan(R.drawable.studio_scan),
    ArrowUpFromLine(R.drawable.studio_arrow_up_from_line),
    Trash2(R.drawable.studio_trash2),
    Plus(R.drawable.studio_plus),
    FlipHorizontal(R.drawable.studio_flip_horizontal),
    Copy(R.drawable.studio_copy),
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
    Images(R.drawable.studio_images);

}

@Composable
fun StudioIcon(symbol: StudioSymbol, modifier: Modifier = Modifier, label: String? = null, tint: Color = StudioColors.Ink) {
    Icon(painterResource(symbol.resource), label, modifier.size(24.dp), tint = tint)
}

@Composable
fun StudioIconButton(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, filled: Boolean = false, enabled: Boolean = true) {
    Box(modifier.size(44.dp).clip(CircleShape)
        .background(if (filled) StudioColors.Pine else if (selected) StudioColors.Tint else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        StudioIcon(symbol, label = label, tint = if (!enabled) StudioColors.Muted.copy(alpha = .35f)
            else if (filled) Color.White else if (selected) StudioColors.Pine else StudioColors.Ink)
    }
}

@Composable
fun StudioTool(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, primary: Boolean = false, enabled: Boolean = true) {
    Column(modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (selected && primary) StudioColors.Pine
            else if (selected) StudioColors.Tint else Color.Transparent), contentAlignment = Alignment.Center) {
            StudioIcon(symbol, modifier = Modifier.size(22.dp), tint = if (!enabled) StudioColors.Muted.copy(alpha = .35f)
                else if (selected && primary) Color.White else if (selected) StudioColors.Pine else StudioColors.Muted)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (selected) StudioColors.Pine else StudioColors.Muted)
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
    symbol: StudioSymbol? = null, selected: Boolean = false, expandable: Boolean = false) {
    Row(modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) StudioColors.Tint else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (symbol != null) StudioIcon(symbol, Modifier.size(18.dp), tint = if (selected) StudioColors.Pine else StudioColors.Muted)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected) StudioColors.Pine else StudioColors.Muted)
        if (expandable) StudioIcon(StudioSymbol.ChevronRight, Modifier.size(14.dp).rotate(90f), tint = StudioColors.Muted)
    }
}
