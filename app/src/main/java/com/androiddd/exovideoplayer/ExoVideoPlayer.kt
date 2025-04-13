package com.androiddd.exovideoplayer

import android.app.Application
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

class ExoVideoPlayer : Application() {

    companion object {
        lateinit var instance: ExoVideoPlayer

        @OptIn(UnstableApi::class)
        val simpleCache by lazy {
            Log.d("ExoVideoPlayer", "Creating new cache instance")
            val cacheSize = 200 * 1024 * 1024 // 200MB cache
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize.toLong())
            val databaseProvider = StandaloneDatabaseProvider(instance)
            val cacheDir = File(instance.cacheDir, "media")
            cacheDir.mkdirs()
            Log.d("ExoVideoPlayer", "Cache directory: ${cacheDir.absolutePath}")
            SimpleCache(cacheDir, cacheEvictor, databaseProvider)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("ExoVideoPlayer", "Application initialized")
        
        // Инициализируем кэш сразу при запуске приложения
        val keys = simpleCache.keys
        Log.d("ExoVideoPlayer", "Cache initialized with keys: $keys")
    }
}