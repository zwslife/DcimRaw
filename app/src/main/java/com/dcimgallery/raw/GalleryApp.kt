package com.dcimgallery.raw

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.dcimgallery.raw.core.coil.Cr3Fetcher
import com.dcimgallery.raw.core.coil.PreviewCache
import com.dcimgallery.raw.core.raw.DecodedCache
import com.dcimgallery.raw.data.db.AppDatabase

class GalleryApp : Application(), ImageLoaderFactory {

    lateinit var db: AppDatabase
        private set

    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        PreviewCache.init(this)
        DecodedCache.init(this)
        imageLoader = ImageLoader.Builder(this)
            .components { add(Cr3Fetcher.Factory(this@GalleryApp)) }
            .memoryCache {
                MemoryCache.Builder(this@GalleryApp)
                    .maxSizePercent(0.25)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}
