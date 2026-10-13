package com.dcimgallery.raw.core.tone

import java.util.UUID

data class PhotoEdits(val tone: ToneParams = ToneParams(), val masks: List<MaskLayer> = emptyList())

/** App-local clipboard owns a detached snapshot, including normalized mask geometry. */
class PhotoEditClipboard {
    private var snapshot: PhotoEdits? = null
    val hasValue: Boolean get() = snapshot != null

    fun copy(edits: PhotoEdits) { snapshot = edits.detached(false) }
    fun paste(): PhotoEdits? = snapshot?.detached(true)

    private fun ToneParams.detached() = copy(
        hsl = hsl.map { it.copy() }, bwMix = bwMix.toList(), grading = grading.map { it.copy() },
    )

    private fun PhotoEdits.detached(newIds: Boolean) = PhotoEdits(tone.detached(), masks.map { layer ->
        layer.copy(id = if (newIds) UUID.randomUUID().toString() else layer.id,
            tone = layer.tone.detached(), components = layer.components.map { part ->
                part.copy(points = part.points.map { it.copy() }, strokeStarts = part.strokeStarts.toList())
            })
    })
}
