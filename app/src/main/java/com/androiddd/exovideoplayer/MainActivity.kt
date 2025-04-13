package com.androiddd.exovideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.androiddd.exovideoplayer.ExoVideoPlayer.Companion.simpleCache
import com.androiddd.exovideoplayer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    private var cacheJob: Job? = null
    private val TAG = "VideoPreCaching"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setClickListeners()
        checkCacheStatus()
        
        // Начать качать сразу, если файл еще не загружен
        CoroutineScope(Dispatchers.IO).launch {
            val isCached = isFullyCached(videoUrl)
            if (!isCached) {
                withContext(Dispatchers.Main) {
                    startPreCaching()
                }
            }
        }
    }

    private fun setClickListeners() {
        binding.btnCancel.setOnClickListener {
            cancelPreCaching()
        }

        binding.btnPlayVideo.setOnClickListener {
            val intent = Intent(this, VideoPlayerActivity::class.java)
            intent.putExtra("videoUrl", videoUrl)
            startActivity(intent)
        }
    }

    @OptIn(UnstableApi::class)
    private fun checkCacheStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            val isCached = isFullyCached(videoUrl)
            withContext(Dispatchers.Main) {
                updateUIBasedOnCacheStatus(isCached)
                if (isCached) {
                    Log.d(TAG, "Video is already fully cached")
                } else {
                    Log.d(TAG, "Video is not cached yet")
                }
            }
        }
    }

    private fun updateUIBasedOnCacheStatus(isCached: Boolean) {
        if (isCached) {
            binding.tvStatus.text = "File is fully cached and ready to play"
            binding.progressDownload.progress = 100
            binding.tvProgressPercent.text = "100%"
            binding.btnPlayVideo.isEnabled = true
            binding.btnCancel.isEnabled = false
        } else {
            binding.tvStatus.text = "Download will start automatically"
            binding.progressDownload.progress = 0
            binding.tvProgressPercent.text = "0%"
            binding.btnPlayVideo.isEnabled = false
            binding.btnCancel.isEnabled = false
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPreCaching() {
        if (cacheJob != null) return

        binding.btnCancel.isEnabled = true
        binding.tvStatus.text = "Downloading..."

        cacheJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                preCacheVideo(videoUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Pre-caching failed", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Download failed: ${e.message}"
                    binding.btnCancel.isEnabled = false
                }
            }
        }
    }

    private fun cancelPreCaching() {
        cacheJob?.cancel()
        cacheJob = null
        binding.tvStatus.text = "Download canceled"
        binding.btnCancel.isEnabled = false
    }

    @OptIn(UnstableApi::class)
    private suspend fun preCacheVideo(url: String) {
        Log.d(TAG, "Starting to pre-cache: $url")
        
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0)
            .setLength(Long.MAX_VALUE)
            .build()

        val progressListener = object : CacheWriter.ProgressListener {
            private var reportedProgress = 0
            private val progressUpdateThreshold = 1 // Report every 1% change

            override fun onProgress(requestLength: Long, bytesCached: Long, newBytesCached: Long) {
                val progress = if (requestLength > 0) (bytesCached * 100 / requestLength).toInt() else 0
                val shouldReport = progress >= reportedProgress + progressUpdateThreshold || progress == 100

                if (shouldReport) {
                    reportedProgress = progress
                    CoroutineScope(Dispatchers.Main).launch {
                        updateProgress(progress, bytesCached, requestLength)
                        Log.d(TAG, "Cache progress: $progress%, $bytesCached / $requestLength bytes")
                    }
                }
            }
        }

        // Create cache data source
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)

        val cacheDataSource = cacheDataSourceFactory.createDataSource()

        try {
            Log.d(TAG, "Creating CacheWriter with dataSpec: $dataSpec")
            val cacheWriter = CacheWriter(
                cacheDataSource,
                dataSpec,
                null,
                progressListener
            )

            Log.d(TAG, "Starting cache operation...")
            cacheWriter.cache()
            Log.d(TAG, "Cache completed successfully")

            // Final UI update on completion
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Download completed - Ready to play"
                binding.progressDownload.progress = 100
                binding.tvProgressPercent.text = "100%"
                binding.btnPlayVideo.isEnabled = true
                binding.btnCancel.isEnabled = false
                Log.d(TAG, "Pre-caching completed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache error", e)
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Cache error: ${e.message}"
            }
            throw e
        }
    }

    private fun updateProgress(progress: Int, bytesCached: Long, totalBytes: Long) {
        binding.progressDownload.progress = progress
        binding.tvProgressPercent.text = "$progress%"
        
        val cachedMb = bytesCached / (1024 * 1024)
        val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else "unknown"
        binding.tvStatus.text = "Downloading: $cachedMb MB / $totalMb MB"
        
        Log.d(TAG, "Download progress: $progress%, $cachedMb MB / $totalMb MB")
    }

    @OptIn(UnstableApi::class)
    private suspend fun isFullyCached(url: String): Boolean {
        return try {
            val videoUrl = url
            val cachedBytes = simpleCache.getCachedBytes(videoUrl, 0, Long.MAX_VALUE)
            Log.d(TAG, "Cached bytes: $cachedBytes for URL: $videoUrl")
            
            if (cachedBytes <= 0) return false
            
            // Проверим содержимое кэша
            val keys = simpleCache.keys
            Log.d(TAG, "All cache keys: $keys")
            
            val contentLength = getContentLength(url)
            Log.d(TAG, "Content length: $contentLength")
            
            if (contentLength <= 0) return false
            
            val isCached = cachedBytes >= contentLength
            Log.d(TAG, "Is fully cached: $isCached (cached: $cachedBytes, total: $contentLength)")
            isCached
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache status", e)
            false
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun getContentLength(url: String): Long {
        return try {
            val dataSource = DefaultHttpDataSource.Factory().createDataSource()
            val dataSpec = DataSpec(Uri.parse(url))
            val length = dataSource.open(dataSpec)
            dataSource.close()
            Log.d(TAG, "Determined content length for $url: $length")
            length
        } catch (e: Exception) {
            Log.e(TAG, "Error getting content length", e)
            -1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cacheJob?.cancel()
    }
}