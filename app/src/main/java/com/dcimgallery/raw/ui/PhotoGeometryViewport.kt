package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import kotlin.math.min

/** Transform the photo and mask overlay together; Compose inversely maps mask touch coordinates. */
@Composable
internal fun PhotoGeometryViewport(bitmap: Bitmap, geometry: PhotoGeometry, description: String?,
    modifier: Modifier = Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val frame = geometry.frame(bitmap.width, bitmap.height)
        val scale = min(maxWidth.value / (frame.width * geometry.crop.width),
            maxHeight.value / (frame.height * geometry.crop.height))
        Box(Modifier.size((frame.width * geometry.crop.width * scale).dp,
            (frame.height * geometry.crop.height * scale).dp)
            .clipToBounds(), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize((bitmap.width * scale).dp,
                (bitmap.height * scale).dp).graphicsLayer {
                rotationZ = geometry.rotation
                translationX = -(geometry.crop.centerX - .5f) * frame.width * scale * density
                translationY = -(geometry.crop.centerY - .5f) * frame.height * scale * density
            }) {
                Image(bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                overlay()
            }
        }
    }
}
