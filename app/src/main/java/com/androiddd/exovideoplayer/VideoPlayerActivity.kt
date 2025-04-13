package com.androiddd.exovideoplayer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.androiddd.exovideoplayer.ExoVideoPlayer.Companion.simpleCache
import com.androiddd.exovideoplayer.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var videoUrl: String? = null
    private var cacheJob: Job? = null
    private val TAG = "VideoPlayerActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        processIntent()
    }

    private fun processIntent() {
        // Обработка интента - получение URL видео
        val intentData = intent.data
        videoUrl = when {
            // URL из VIEW интента
            intentData != null -> {
                Log.d(TAG, "URL from intent data: ${intentData.toString()}")
                intentData.toString()
            }
            // URL из экстра (из нашего приложения)
            intent.hasExtra("videoUrl") -> {
                val url = intent.getStringExtra("videoUrl")
                Log.d(TAG, "URL from extra: $url")
                url
            }
            else -> null
        }

        if (videoUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Ошибка: не указан URL видео", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // После получения URL, проверяем наличие в кэше и начинаем кэширование если необходимо
        checkCacheAndStart()
    }

    @OptIn(UnstableApi::class)
    private fun checkCacheAndStart() {
        if (videoUrl == null) return

        // Показываем интерфейс кэширования
        binding.preCacheLayout.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val isCached = isFullyCached(videoUrl!!)
            withContext(Dispatchers.Main) {
                if (isCached) {
                    // Видео уже скачано, активируем кнопку воспроизведения
                    binding.tvStatus.text = "Видео загружено и готово к воспроизведению"
                    binding.progressDownload.progress = 100
                    binding.tvProgressPercent.text = "100%"
                    enablePlayButton()
                } else {
                    startPreCaching()
                }
            }
        }
    }

    private fun startPlayback() {
        // Скрываем интерфейс кэширования и показываем плеер
        binding.preCacheLayout.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        
        initializePlayer()
    }
    
    private fun enablePlayButton() {
        binding.btnPlay.isEnabled = true
        binding.btnPlay.setOnClickListener {
            startPlayback()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPreCaching() {
        if (cacheJob != null || videoUrl == null) return

        binding.btnCancel.setOnClickListener {
            cancelPreCaching()
        }

        binding.tvStatus.text = "Загрузка видео..."
        binding.progressDownload.progress = 0
        binding.tvProgressPercent.text = "0%"
        binding.btnCancel.isEnabled = true
        binding.btnPlay.isEnabled = false

        cacheJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                preCacheVideo(videoUrl!!)
                // Не запускаем воспроизведение автоматически - это сделает кнопка Play
            } catch (e: Exception) {
                Log.e(TAG, "Pre-caching failed", e)
                withContext(Dispatchers.Main) {
                    // Показываем подробную ошибку и причину
                    binding.tvStatus.text = "Ошибка загрузки: ${e.message}"
                    binding.tvProgressPercent.text = "Загрузка не удалась"
                    binding.btnCancel.isEnabled = false
                    binding.btnPlay.isEnabled = false

                    // Изменяем кнопку отмены на закрытие активности
                    binding.btnCancel.text = "Закрыть"
                    binding.btnCancel.isEnabled = true
                    binding.btnCancel.setOnClickListener { 
                        finish() 
                    }
                }
            }
        }
    }

    private fun cancelPreCaching() {
        cacheJob?.cancel()
        cacheJob = null
        binding.tvStatus.text = "Загрузка отменена"
        binding.btnCancel.isEnabled = false
        binding.btnPlay.isEnabled = false
        
        // Проверяем, полностью ли загружен файл
        CoroutineScope(Dispatchers.IO).launch {
            val isCached = videoUrl?.let { isFullyCached(it) } ?: false
            withContext(Dispatchers.Main) {
                if (isCached) {
                    // Если файл полностью скачан, разрешаем воспроизведение
                    binding.tvStatus.text = "Видео загружено и готово к воспроизведению"
                    enablePlayButton()
                } else {
                    // Если файл не полностью скачан, показываем сообщение и кнопку закрытия
                    binding.tvStatus.text = "Загрузка отменена. Файл загружен не полностью."
                    binding.btnCancel.text = "Закрыть"
                    binding.btnCancel.isEnabled = true
                    binding.btnCancel.setOnClickListener { 
                        finish() 
                    }
                }
            }
        }
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
                binding.tvStatus.text = "Видео загружено и готово к воспроизведению"
                binding.progressDownload.progress = 100
                binding.tvProgressPercent.text = "100%"
                binding.btnCancel.isEnabled = false
                enablePlayButton()
                Log.d(TAG, "Pre-caching completed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache error", e)
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Ошибка: ${e.message}"
            }
            throw e
        }
    }

    private fun updateProgress(progress: Int, bytesCached: Long, totalBytes: Long) {
        binding.progressDownload.progress = progress
        binding.tvProgressPercent.text = "$progress%"
        
        val cachedMb = bytesCached / (1024 * 1024)
        val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else "unknown"
        binding.tvStatus.text = "Загрузка: $cachedMb МБ / $totalMb МБ"
        
        Log.d(TAG, "Download progress: $progress%, $cachedMb MB / $totalMb MB")
    }

    @OptIn(UnstableApi::class)
    private suspend fun isFullyCached(url: String): Boolean {
        return try {
            val cachedBytes = simpleCache.getCachedBytes(url, 0, Long.MAX_VALUE)
            Log.d(TAG, "Cached bytes: $cachedBytes for URL: $url")
            
            // Если ничего не кэшировано, сразу возвращаем false
            if (cachedBytes <= 0) {
                Log.d(TAG, "No cached bytes found for URL: $url")
                return false
            }
            
            // Выводим все ключи кэша для отладки
            val keys = simpleCache.keys
            Log.d(TAG, "All cache keys: $keys")
            
            // Получаем полный размер файла
            val contentLength = getContentLength(url)
            Log.d(TAG, "Content length: $contentLength")
            
            if (contentLength <= 0) {
                Log.d(TAG, "Unable to determine content length")
                return false
            }
            
            // Определяем процент заполнения кэша
            val cachePercentage = (cachedBytes * 100.0 / contentLength).toInt()
            Log.d(TAG, "Cache percentage: $cachePercentage% ($cachedBytes / $contentLength bytes)")
            
            // Считаем, что файл полностью кэширован только если кэш содержит как минимум 99% файла
            // для случаев, когда есть небольшие расхождения в расчете размера
            val isCached = cachePercentage >= 99
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

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player == null && videoUrl != null) {
            Log.d(TAG, "Initializing player for URL: $videoUrl")
            
            try {
                // Create a data source factory with cache support
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(5000)
                
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

                // Create a media source using the cache data source factory
                val mediaSourceFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory)

                // Create MediaItem
                val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
                
                Log.d(TAG, "Created MediaItem for URL: $videoUrl")
                
                player = ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .apply {
                        setMediaItem(mediaItem)
                        playWhenReady = this@VideoPlayerActivity.playWhenReady
                        seekTo(playbackPosition)
                        prepare()
                        
                        // Log playback errors and other events
                        addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                Log.e(TAG, "Player error: ${error.message}", error)
                                Toast.makeText(this@VideoPlayerActivity, 
                                    "Ошибка воспроизведения: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                            
                            override fun onPlaybackStateChanged(state: Int) {
                                val stateStr = when(state) {
                                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                                    androidx.media3.common.Player.STATE_READY -> "READY"
                                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                    else -> "UNKNOWN"
                                }
                                Log.d(TAG, "Playback state changed: $stateStr")
                            }
                        })
                    }
                binding.playerView.player = player
                Log.d(TAG, "Player initialized and prepared")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing player", e)
                Toast.makeText(this, "Ошибка инициализации плеера: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    @OptIn(UnstableApi::class)
    override fun onStop() {
        super.onStop()
        releasePlayer()
        cacheJob?.cancel()
    }
    
    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        super.onDestroy()
        
        // Очищаем кэш перед закрытием активности
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Clearing cache on app close")
                simpleCache.keys.forEach { key ->
                    Log.d(TAG, "Removing cache for key: $key")
                    simpleCache.removeResource(key)
                }
                
                // Закрытие и освобождение кэша
                simpleCache.release()
                Log.d(TAG, "Cache cleared and released")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing cache", e)
            }
        }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
            player = null
        }
    }
}