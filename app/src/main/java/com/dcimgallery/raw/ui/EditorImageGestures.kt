package com.dcimgallery.raw.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

internal fun Modifier.editorImageGestures(gestureState: PagerGestureState, maskMode: Boolean): Modifier =
    pointerInput(maskMode, gestureState.viewSize) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gestureState.onTouchStart()
            try {
                val first = awaitPointerEvent(PointerEventPass.Initial)
                if (first.changes.none { it.pressed }) return@awaitEachGesture
                if (!maskMode && first.changes.count { it.pressed } < 2 && !gestureState.isZoomed) {
                    return@awaitEachGesture
                }
                val prevPositions = HashMap<PointerId, Offset>()
                first.changes.forEach { prevPositions[it.id] = it.position }
                var trackedCount = first.changes.count { it.pressed }
                var velocityTracker = VelocityTracker()
                var movedWhileZoomed = false
                velocityTracker.addPosition(first.changes.first().uptimeMillis,
                    first.changes.filter { it.pressed }.map { it.position }.centroid())
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (movedWhileZoomed) {
                            val released = event.changes.filter { it.previousPressed }
                            if (trackedCount == 1 && released.size == 1) {
                                velocityTracker.addPosition(released[0].uptimeMillis, released[0].position)
                            }
                            val velocity = velocityTracker.calculateVelocity()
                            gestureState.fling(Offset(velocity.x, velocity.y))
                        }
                        break
                    }
                    val positions = pressed.map { it.position }
                    val centroid = positions.centroid()
                    if (pressed.size != trackedCount) {
                        trackedCount = pressed.size
                        velocityTracker = VelocityTracker()
                        movedWhileZoomed = false
                        velocityTracker.addPosition(pressed[0].uptimeMillis, centroid)
                    }
                    var zoom = 1f
                    if (pressed.size >= 2) {
                        val prevD0 = prevPositions[pressed[0].id]
                        val prevD1 = prevPositions[pressed[1].id]
                        if (prevD0 != null && prevD1 != null) {
                            val prevDist = (prevD0 - prevD1).getDistance()
                            val curDist =
                                (pressed[0].position - pressed[1].position).getDistance()
                            if (prevDist > 0f && curDist > 0f) {
                                zoom = curDist / prevDist
                            }
                        }
                    }
                    val prevList = pressed.mapNotNull { prevPositions[it.id] }
                    val pan = if (prevList.size == pressed.size) {
                        centroid - prevList.centroid()
                    } else {
                        Offset.Zero
                    }
                    if ((pressed.size >= 2 || !maskMode && gestureState.isZoomed) &&
                        (zoom != 1f || pan != Offset.Zero)) {
                        gestureState.onTransform(centroid, pan, zoom)
                        if (gestureState.isZoomed && pan != Offset.Zero) {
                            velocityTracker.addPosition(pressed[0].uptimeMillis, centroid)
                            movedWhileZoomed = true
                        }
                        pressed.forEach { it.consume() }
                    }
                    prevPositions.clear()
                    pressed.forEach { prevPositions[it.id] = it.position }
                }
            } finally {
                gestureState.onTouchEnd()
            }
        }
    }

private fun List<Offset>.centroid(): Offset =
    fold(Offset.Zero) { sum, point -> sum + point } / size.toFloat()
