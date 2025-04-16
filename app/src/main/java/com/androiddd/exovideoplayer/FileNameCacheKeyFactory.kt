package com.androiddd.exovideoplayer

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Фабрика ключей кэша, которая использует имя файла вместо URL в качестве ключа кэша.
 * Это позволяет использовать один и тот же кэш для разных URL, если они указывают на один и тот же файл.
 */
@UnstableApi
class FileNameCacheKeyFactory : CacheKeyFactory {
    companion object {
        private const val TAG = "FileNameCacheKeyFactory"
    }

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val uri = dataSpec.uri
        val fileName = extractFileName(uri)
        Log.d(TAG, "Using filename as cache key: $fileName for URI: $uri")
        return fileName
    }

    /**
     * Извлекает имя файла из URI
     */
    private fun extractFileName(uri: Uri): String {
        return try {
            val path = uri.path
            if (path != null) {
                val fileName = path.substringAfterLast('/')
                if (fileName.isNotEmpty()) {
                    return fileName
                }
            }
            // Если не удалось извлечь имя файла, используем хэш URI
            val uriString = uri.toString()
            uriString.hashCode().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting filename from URI", e)
            // В случае ошибки используем хэш URI
            uri.toString().hashCode().toString()
        }
    }
}
