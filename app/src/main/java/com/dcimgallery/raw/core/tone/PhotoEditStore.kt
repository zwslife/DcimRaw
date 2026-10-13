package com.dcimgallery.raw.core.tone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 非破坏性编辑保存在应用私有目录，原始 CR3 不会被修改。 */
class PhotoEditStore(context: Context) {
    private val directory = File(context.filesDir, "photo-edits").apply { mkdirs() }
    private fun file(uri: String): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory, "$key.json"))
    }

    @Synchronized
    fun load(uri: String): PhotoEdits? = runCatching {
        val root = JSONObject(file(uri).readFully().toString(Charsets.UTF_8))
        val masks = root.optJSONArray("masks") ?: JSONArray()
        PhotoEdits(readTone(root.getJSONObject("tone")), List(masks.length()) { i ->
            val mask = masks.getJSONObject(i)
            val parts = mask.getJSONArray("parts")
            MaskLayer(id = mask.getString("id"), name = mask.getString("name"),
                inverted = mask.optBoolean("inverted"), enabled = mask.optBoolean("enabled", true),
                tone = readTone(mask.getJSONObject("tone")), components = List(parts.length()) { j ->
                    val part = parts.getJSONObject(j)
                    val points = part.getJSONArray("points")
                    MaskComponent(tool = MaskTool.valueOf(part.getString("tool")),
                        points = List(points.length()) { k ->
                            val point = points.getJSONArray(k)
                            MaskPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                        }, subtract = part.optBoolean("subtract"), radius = part.getDouble("radius").toFloat(),
                        feather = part.getDouble("feather").toFloat(), opacity = part.getDouble("opacity").toFloat(),
                        rotation = part.optDouble("rotation", 0.0).toFloat(),
                        strokeStarts = part.optJSONArray("strokeStarts")?.let { starts ->
                            List(starts.length()) { starts.getInt(it) }.filter { it in 1 until points.length() }
                        }.orEmpty())
                })
        })
    }.getOrNull()

    @Synchronized
    fun save(uri: String, edits: PhotoEdits) {
        val root = JSONObject().put("version", 3).put("tone", toneJson(edits.tone))
        root.put("masks", JSONArray().apply {
            edits.masks.forEach { mask -> put(JSONObject().put("id", mask.id).put("name", mask.name)
                .put("inverted", mask.inverted).put("enabled", mask.enabled).put("tone", toneJson(mask.tone))
                .put("parts", JSONArray().apply {
                    mask.components.forEach { part -> put(JSONObject().put("tool", part.tool.name)
                        .put("subtract", part.subtract).put("radius", part.radius.toDouble())
                        .put("feather", part.feather.toDouble()).put("opacity", part.opacity.toDouble())
                        .put("rotation", part.rotation.toDouble())
                        .put("strokeStarts", JSONArray(part.strokeStarts))
                        .put("points", JSONArray().apply {
                            part.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
                        })) }
                })) }
        })
        val target = file(uri)
        val stream = target.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun delete(uri: String) = file(uri).delete()

    private fun toneJson(t: ToneParams) = JSONObject().apply {
        put("exposure", t.exposure.toDouble()); put("contrast", t.contrast.toDouble())
        put("temperature", t.temperature.toDouble()); put("tint", t.tint.toDouble())
        put("saturation", t.saturation.toDouble()); put("highlights", t.highlights.toDouble())
        put("shadows", t.shadows.toDouble()); put("whites", t.whites.toDouble()); put("blacks", t.blacks.toDouble())
        put("hsl", JSONArray().apply { t.hsl.forEach { put(JSONArray().put(it.hue.toDouble()).put(it.sat.toDouble()).put(it.lum.toDouble())) } })
        put("effects", JSONArray().apply { t.effectsArray().forEach { put(it.toDouble()) } })
    }

    private fun readTone(t: JSONObject): ToneParams {
        fun f(key: String) = t.optDouble(key, 0.0).toFloat()
        val hsl = t.optJSONArray("hsl")
        val base = ToneParams(f("exposure"), f("contrast"), f("temperature"), f("tint"), f("saturation"),
            f("highlights"), f("shadows"), f("whites"), f("blacks"), List(ToneParams.HSL_COUNT) { i ->
                val a = hsl?.optJSONArray(i)
                HslAdjust(a?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                    a?.optDouble(1, 0.0)?.toFloat() ?: 0f, a?.optDouble(2, 0.0)?.toFloat() ?: 0f)
            })
        return base.withEffects(t.optJSONArray("effects")?.let { values ->
            val defaults = ToneParams().effectsArray()
            FloatArray(ToneParams.EFFECT_COUNT) { values.optDouble(it, defaults[it].toDouble()).toFloat() }
        } ?: ToneParams().effectsArray())
    }
}
