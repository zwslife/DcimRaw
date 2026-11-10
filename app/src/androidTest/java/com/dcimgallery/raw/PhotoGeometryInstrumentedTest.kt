package com.dcimgallery.raw

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dcimgallery.raw.core.geometry.CropRect
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.geometry.PhotoGeometryRenderer
import com.dcimgallery.raw.core.tone.PhotoEdits
import com.dcimgallery.raw.core.tone.PhotoEditStore
import com.dcimgallery.raw.core.tone.ToneParams
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.dcimgallery.raw.core.tone.MaskLayer

@RunWith(AndroidJUnit4::class)
class PhotoGeometryInstrumentedTest {
    @Test fun editDraftCommitsOnlyOnDone() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GalleryApp
        val store = PhotoEditStore(app)
        val uri = "content://ruotu-instrumentation/draft-session"
        val baseline = PhotoEdits(ToneParams(exposure = .25f), geometry = PhotoGeometry(1))
        val draft = PhotoEdits(ToneParams(exposure = 1.5f), listOf(MaskLayer(name = "test mask")),
            PhotoGeometry(2, 12f, CropRect(.1f, .2f, .8f, .9f)))
        store.save(uri, baseline)
        val lifecycle = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(lifecycle, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        }
        suspend fun start() { withContext(Dispatchers.Main) { vm.beginEditing(uri) }!!.join() }
        suspend fun change(edits: PhotoEdits) = withContext(Dispatchers.Main) {
            vm.setTone(uri, edits.tone); vm.setMasks(uri, edits.masks); vm.setGeometry(uri, edits.geometry)
            vm.persistEdits()
        }
        suspend fun finish(save: Boolean) { withContext(Dispatchers.Main) { vm.finishEditing(save) }!!.join() }
        try {
            start(); change(draft); delay(400)
            assertEquals("Background persistence must not save a draft", baseline, store.load(uri))
            finish(false)
            assertEquals(baseline.tone, vm.toneParams.value[uri])
            assertEquals(baseline.masks, vm.masks.value[uri])
            assertEquals(baseline.geometry, vm.geometry.value[uri])
            assertEquals(baseline, store.load(uri))
            assertNull(vm.editingUri.value)
            start(); change(draft); finish(true)
            assertEquals(draft, store.load(uri))
            start(); change(PhotoEdits()); finish(false)
            assertEquals(draft.tone, vm.toneParams.value[uri])
            assertEquals(draft.masks, vm.masks.value[uri])
            assertEquals(draft.geometry, vm.geometry.value[uri])
            assertEquals(draft, store.load(uri))
        } finally {
            withContext(Dispatchers.Main) { lifecycle.clear() }
            store.delete(uri)
        }
    }
    private fun fixture(): Bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
        for (y in 0..1) for (x in 0..3) setPixel(x, y, Color.rgb(40 + x * 50, 60 + y * 120, 90))
    }

    @Test fun quarterTurnsAndCropUseExactSourcePixels() {
        val source = fixture()
        val right = PhotoGeometryRenderer.render(source, PhotoGeometry(quarterTurns = 1))
        assertEquals(2, right.width); assertEquals(4, right.height)
        for (y in 0..3) for (x in 0..1) assertEquals(source.getPixel(y, 1 - x), right.getPixel(x, y))
        val left = PhotoGeometryRenderer.render(source, PhotoGeometry(quarterTurns = 3))
        for (y in 0..3) for (x in 0..1) assertEquals(source.getPixel(3 - y, x), left.getPixel(x, y))
        val crop = PhotoGeometryRenderer.render(source, PhotoGeometry(crop = CropRect(.25f, 0f, .75f, 1f)))
        assertEquals(2, crop.width); assertEquals(2, crop.height)
        for (y in 0..1) for (x in 0..1) assertEquals(source.getPixel(x + 1, y), crop.getPixel(x, y))
        assertSame(source, PhotoGeometryRenderer.render(source, PhotoGeometry()))
        source.recycle(); right.recycle(); left.recycle(); crop.recycle()
    }

    @Test fun arbitraryRotationDoesNotExposeEmptyCorners() {
        val source = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        for (turn in 0..3) for (angle in listOf(-45f, -27.5f, 0f, 17.3f, 45f)) {
            val geometry = PhotoGeometry(turn, angle, CropRect(.1f, .1f, .9f, .9f))
            val output = PhotoGeometryRenderer.render(source, geometry)
            val expected = geometry.outputSize(source.width, source.height)
            assertEquals(expected.first, output.width); assertEquals(expected.second, output.height)
            for (y in 1 until output.height - 1) for (x in 1 until output.width - 1) {
                assertEquals("turn=$turn angle=$angle at $x,$y", Color.RED, output.getPixel(x, y))
            }
            output.recycle()
        }
        source.recycle()
    }

    @Test fun geometryPersistsAndLegacyRecordsRemainReadable() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(target.cacheDir, "geometry-store-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir(): File = folder }
        try {
            val store = PhotoEditStore(context)
            val expected = PhotoEdits(ToneParams(exposure = .7f), geometry =
                PhotoGeometry(3, 12.5f, CropRect(.1f, .2f, .8f, .9f)))
            store.save("content://test/photo", expected)
            assertEquals(expected, store.load("content://test/photo"))
            val saved = File(folder, "photo-edits").listFiles()!!.single { it.extension == "json" }
            val legacy = JSONObject(saved.readText()).apply { remove("geometry"); put("version", 4) }
            saved.writeText(legacy.toString())
            val loaded = store.load("content://test/photo")!!
            assertEquals(expected.tone, loaded.tone)
            assertEquals(PhotoGeometry(), loaded.geometry)
        } finally { folder.deleteRecursively() }
    }
}
