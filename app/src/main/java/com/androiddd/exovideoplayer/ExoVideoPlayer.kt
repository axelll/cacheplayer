package com.androiddd.exovideoplayer

import android.app.Application
import android.os.Environment
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

        // Переменная для хранения информации о месте хранения кэша
        var cacheStorageType = "internal" // "internal" или "external"

        // Переменная для хранения имени файла последнего кэшированного видео
        var lastCachedFileName: String? = null

        // Переменная для хранения прогресса загрузки последнего файла (0-100)
        var lastCacheProgress = 0

        // Переменная для хранения URL последнего кэшированного файла
        var lastCachedUrl: String? = null

        /**
         * Фабрика ключей кэша, которая использует имя файла вместо URL
         */
        @OptIn(UnstableApi::class)
        val cacheKeyFactory by lazy {
            FileNameCacheKeyFactory()
        }

        @OptIn(UnstableApi::class)
        val simpleCache by lazy {
            Log.d("ExoVideoPlayer", "Creating new cache instance")
            val cacheSize = 20L * 1024 * 1024 * 1024 // 20GB cache
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize)
            val databaseProvider = StandaloneDatabaseProvider(instance)

            // Пытаемся использовать внешнее хранилище (флешку)
            val cacheDir = findBestCacheDir()
            cacheDir.mkdirs()

            Log.d("ExoVideoPlayer", "Cache directory: ${cacheDir.absolutePath}")
            SimpleCache(cacheDir, cacheEvictor, databaseProvider)
        }

        /**
         * Находит наилучшую директорию для кэша, предпочитая внешнее хранилище
         * @return File директория для кэша
         */
        fun findBestCacheDir(): File {
            Log.d("ExoVideoPlayer", "Finding best cache directory...")

            // Получаем информацию о внутреннем хранилище
            val internalDir = instance.cacheDir
            val internalFreeSpace = internalDir.freeSpace
            Log.d("ExoVideoPlayer", "Internal storage: ${internalDir.absolutePath}, free space: ${internalFreeSpace / (1024 * 1024)} MB")

            // Получаем все внешние хранилища
            val externalDirs = instance.getExternalFilesDirs(null)
            Log.d("ExoVideoPlayer", "Found ${externalDirs.size} external storage locations")

            // Ищем самое большое хранилище
            var bestDir: File? = null
            var maxSpace = 0L
            var index = 0

            for (dir in externalDirs) {
                if (dir != null) {
                    Log.d("ExoVideoPlayer", "Checking external storage #${index++}: ${dir.absolutePath}")

                    if (dir.exists()) {
                        Log.d("ExoVideoPlayer", "Directory exists")

                        if (dir.canWrite()) {
                            Log.d("ExoVideoPlayer", "Directory is writable")

                            val freeSpace = dir.freeSpace
                            Log.d("ExoVideoPlayer", "Free space: ${freeSpace / (1024 * 1024)} MB")

                            // Проверяем, является ли это внешним хранилищем (флешкой)
                            val isRemovable = isExternalStorageRemovable(dir)
                            Log.d("ExoVideoPlayer", "Is removable: $isRemovable")

                            // Предпочитаем флешку, если она есть
                            if (isRemovable && freeSpace > 100 * 1024 * 1024) { // Минимум 100 МБ свободного места
                                Log.d("ExoVideoPlayer", "Found removable storage with sufficient space")
                                val cacheDir = File(dir, "media_cache")
                                cacheStorageType = "external"
                                Log.d("ExoVideoPlayer", "Using removable storage for cache: ${cacheDir.absolutePath}")
                                return cacheDir
                            }

                            // Если нет флешки, выбираем хранилище с наибольшим свободным местом
                            if (freeSpace > maxSpace) {
                                maxSpace = freeSpace
                                bestDir = dir
                            }
                        } else {
                            Log.d("ExoVideoPlayer", "Directory is not writable")
                        }
                    } else {
                        Log.d("ExoVideoPlayer", "Directory does not exist")
                    }
                }
            }

            // Если нашли внешнее хранилище с большим свободным местом, чем внутреннее
            if (bestDir != null && maxSpace > internalFreeSpace) {
                val cacheDir = File(bestDir, "media_cache")
                cacheStorageType = "external"
                Log.d("ExoVideoPlayer", "Using external storage for cache: ${cacheDir.absolutePath}")
                return cacheDir
            }

            // Используем внутреннее хранилище
            val internalCacheDir = File(instance.cacheDir, "media")
            cacheStorageType = "internal"
            Log.d("ExoVideoPlayer", "Using internal storage for cache: ${internalCacheDir.absolutePath}")
            return internalCacheDir
        }

        /**
         * Пересоздает кэш приложения
         */
        @OptIn(UnstableApi::class)
        fun recreateCache() {
            try {
                // Закрываем текущий кэш
                simpleCache.release()
                Log.d("ExoVideoPlayer", "Existing cache released")

                // Сбрасываем информацию о последнем файле
                lastCachedFileName = null
                lastCachedUrl = null
                lastCacheProgress = 0

                // Создаем новый кэш через переинициализацию приложения
                val cacheDir = findBestCacheDir()
                cacheDir.mkdirs()

                // Создаем новый кэш через доступ к lazy-переменной
                val keys = simpleCache.keys
                Log.d("ExoVideoPlayer", "Cache recreated successfully with keys: $keys")
            } catch (e: Exception) {
                Log.e("ExoVideoPlayer", "Error recreating cache", e)
            }
        }

        /**
         * Проверяет, является ли хранилище съемным (флешкой)
         */
        private fun isExternalStorageRemovable(path: File): Boolean {
            try {
                // На Android TV флешка обычно содержит в пути слова "usb" или "sdcard"
                val pathStr = path.absolutePath.lowercase()
                val isUSB = pathStr.contains("usb") || pathStr.contains("sdcard") || pathStr.contains("emulated/0")

                // Также проверяем, что это не первый элемент в списке внешних хранилищ (обычно первый - это внутренняя память)
                val externalDirs = instance.getExternalFilesDirs(null)
                val isNotFirstStorage = externalDirs.isNotEmpty() && externalDirs[0] != path

                // Дополнительная проверка - если свободного места больше, чем во внутренней памяти
                val hasMoreSpace = path.freeSpace > instance.cacheDir.freeSpace * 2 // В 2 раза больше места

                return (isUSB || isNotFirstStorage) && hasMoreSpace
            } catch (e: Exception) {
                Log.e("ExoVideoPlayer", "Error checking if storage is removable", e)
                return false
            }
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