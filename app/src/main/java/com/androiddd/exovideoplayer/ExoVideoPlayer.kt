package com.androiddd.exovideoplayer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

class ExoVideoPlayer : Application() {

    companion object {
        lateinit var instance: ExoVideoPlayer

        @get:UnstableApi
        val simpleCache by lazy {
            initCache()
        }

        @OptIn(UnstableApi::class)
        private fun initCache(): SimpleCache {
            val cacheSize = 100 * 1024 * 1024 // 100MB cache
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize.toLong())
            val databaseProvider = StandaloneDatabaseProvider(instance)
            return SimpleCache(File(instance.cacheDir, "media"), cacheEvictor, databaseProvider)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}