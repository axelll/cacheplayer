package com.androiddd.exovideoplayer

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.os.StatFs
import android.util.Log
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.androiddd.exovideoplayer.ExoVideoPlayer.Companion.simpleCache
import com.androiddd.exovideoplayer.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var videoUrl: String? = null
    private var cacheJob: Job? = null
    private var cacheWriter: CacheWriter? = null
    private var cacheDataSource: CacheDataSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val TAG = "VideoPlayerActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализируем оверлей перемотки
        initSeekOverlay()

        processIntent()
    }

    // Переменная для хранения формата видео
    private var videoFormat = "Неизвестный формат"

    // Переменная для хранения имени файла
    private var currentFileName: String? = null

    // Флаг, указывающий, является ли текущий файл тем же, что и предыдущий
    private var isSameFile = false

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

        // Определяем формат видео по URL
        detectVideoFormat(videoUrl!!)

        // Извлекаем имя файла из URL
        currentFileName = extractFileName(videoUrl!!)

        // Проверяем, является ли этот файл тем же, что и предыдущий
        isSameFile = currentFileName == ExoVideoPlayer.lastCachedFileName

        // Добавляем отладочную информацию
        val debugInfo = "URL: ${videoUrl?.substring(0, minOf(30, videoUrl?.length ?: 0))}...\n" +
                      "File: $currentFileName\n" +
                      "Last file: ${ExoVideoPlayer.lastCachedFileName}\n" +
                      "Same file: $isSameFile\n" +
                      "Last progress: ${ExoVideoPlayer.lastCacheProgress}%\n" +
                      "Storage: ${ExoVideoPlayer.cacheStorageType}"
        // Логируем информацию о файле
        Log.d(TAG, "File info: $debugInfo")

        if (isSameFile) {
            Log.d(TAG, "Same file detected: $currentFileName, continuing with existing cache")
            // Сохраняем URL текущего файла для использования в кэше
            ExoVideoPlayer.lastCachedUrl = videoUrl
        } else {
            Log.d(TAG, "New file detected: $currentFileName, previous was: ${ExoVideoPlayer.lastCachedFileName}")
            // Обновляем имя последнего кэшированного файла
            ExoVideoPlayer.lastCachedFileName = currentFileName
            ExoVideoPlayer.lastCachedUrl = videoUrl
            ExoVideoPlayer.lastCacheProgress = 0
        }

        // После получения URL, проверяем наличие в кэше и начинаем кэширование если необходимо
        checkCacheAndStart()
    }

    private fun detectVideoFormat(url: String) {
        videoFormat = when {
            url.contains(".mp4", ignoreCase = true) -> "MP4"
            url.contains(".webm", ignoreCase = true) -> "WebM"
            url.contains(".mkv", ignoreCase = true) -> "MKV"
            url.contains(".avi", ignoreCase = true) -> "AVI"
            url.contains(".mov", ignoreCase = true) -> "MOV"
            url.contains(".m3u8", ignoreCase = true) -> "HLS"
            url.contains(".mpd", ignoreCase = true) -> "DASH"
            url.contains(".3gp", ignoreCase = true) -> "3GP"
            url.contains("/dash/", ignoreCase = true) -> "DASH"
            url.contains("/hls/", ignoreCase = true) -> "HLS"
            else -> "Неизвестный формат"
        }
        Log.d(TAG, "Detected video format: $videoFormat for URL: $url")
    }

    /**
     * Извлекает имя файла из URL
     * @param url URL видео
     * @return Имя файла или хэш URL, если имя не может быть извлечено
     */
    private fun extractFileName(url: String): String {
        try {
            // Попытка извлечь имя файла из URL
            val uri = Uri.parse(url)
            val path = uri.path

            if (path != null) {
                // Извлекаем имя файла из пути
                val fileName = path.substringAfterLast('/')
                if (fileName.isNotEmpty()) {
                    Log.d(TAG, "Extracted filename: $fileName from URL: $url")
                    return fileName
                }
            }

            // Если не удалось извлечь имя файла, используем хэш URL
            val urlHash = url.hashCode().toString()
            Log.d(TAG, "Using URL hash as filename: $urlHash for URL: $url")
            return urlHash
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting filename from URL", e)
            // В случае ошибки используем хэш URL
            val urlHash = url.hashCode().toString()
            return urlHash
        }
    }

    @OptIn(UnstableApi::class)
    private fun checkCacheAndStart() {
        if (videoUrl == null) return

        Log.d(TAG, "Checking cache for file: $currentFileName, isSameFile: $isSameFile, lastProgress: ${ExoVideoPlayer.lastCacheProgress}%")

        // Добавляем базовую информацию о файле
        Log.d(TAG, "URL: ${videoUrl?.substring(0, minOf(30, videoUrl?.length ?: 0))}...")
        Log.d(TAG, "File: $currentFileName, Last file: ${ExoVideoPlayer.lastCachedFileName}")
        Log.d(TAG, "Same file: $isSameFile, Last progress: ${ExoVideoPlayer.lastCacheProgress}%")
        Log.d(TAG, "Storage: ${ExoVideoPlayer.cacheStorageType}")

        // Показываем интерфейс кэширования
        binding.preCacheLayout.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE

        // Инициализируем текстовые поля для дополнительной информации
        binding.tvDownloadSpeed.text = "Скорость: 0 МБ/с"
        binding.tvTimeRemaining.text = "Осталось: --:--"

        // Настраиваем кнопку очистки кэша
        binding.btnClearCache.setOnClickListener {
            // Изменяем стиль кнопки при нажатии
            resetButtonStyles()
            setSelectedButtonStyle(binding.btnClearCache)
            clearAllCache()
        }

        // Проверяем реальный прогресс кэширования из кэша
        var actualCacheProgress = 0
        if (isSameFile && videoUrl != null) {
            try {
                // Получаем размер файла и закэшированные байты
                CoroutineScope(Dispatchers.IO).launch {
                    val contentLength = getContentLength(videoUrl!!)
                    if (contentLength > 0) {
                        val cachedBytes = simpleCache.getCachedBytes(videoUrl!!, 0, contentLength)
                        if (cachedBytes > 0) {
                            actualCacheProgress = (cachedBytes * 100 / contentLength).toInt()
                            Log.d(TAG, "Actual cache progress from cache: $actualCacheProgress%")

                            // Обновляем сохраненный прогресс, если реальный прогресс больше
                            if (actualCacheProgress > ExoVideoPlayer.lastCacheProgress) {
                                ExoVideoPlayer.lastCacheProgress = actualCacheProgress
                                withContext(Dispatchers.Main) {
                                    updateCacheProgressUI(actualCacheProgress)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking actual cache progress", e)
            }
        }

        // Отображаем имя файла всегда
        binding.tvFilename.text = currentFileName

        // Показываем информацию о продолжении загрузки, если это тот же файл
        if (isSameFile && ExoVideoPlayer.lastCacheProgress > 0) {
            updateCacheProgressUI(ExoVideoPlayer.lastCacheProgress)
        } else {
            binding.tvStatus.text = if (isSameFile) "Загрузка файла" else "Загрузка нового файла"
            binding.progressDownload.progress = 0
            binding.tvProgressPercent.text = "0%"

            val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"
            binding.tvCacheInfo.text = "$videoFormat | $storageTypeText | Загрузка..."
        }

        // Проверяем, полностью ли закэширован файл
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

    /**
     * Обновляет UI с информацией о прогрессе кэширования
     */
    private fun updateCacheProgressUI(progress: Int) {
        // Отображаем имя файла
        binding.tvFilename.text = currentFileName

        binding.tvStatus.text = "Продолжение загрузки файла"
        binding.progressDownload.progress = progress
        binding.tvProgressPercent.text = "${progress}%"

        val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"
        binding.tvCacheInfo.text = "$videoFormat | $storageTypeText | Дозагрузка с ${progress}%"
    }

    private fun startPlayback() {
        // Скрываем интерфейс кэширования и показываем плеер
        binding.preCacheLayout.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE

        // Устанавливаем черный фон для плеера
        binding.playerView.setBackgroundColor(Color.BLACK)
        window.decorView.setBackgroundColor(Color.BLACK)

        initializePlayer()

        // Активируем WakeLock и флаг KEEP_SCREEN_ON для предотвращения перехода в режим скринсейвера
        // во время воспроизведения видео
        acquireWakeLock()
    }

    private fun enablePlayButton() {
        binding.btnPlay.isEnabled = true

        // Сбрасываем стили и устанавливаем активный стиль для кнопки Начать
        resetButtonStyles()

        // Делаем кнопку Начать заметной и активной
        binding.btnPlay.setBackgroundColor(resources.getColor(android.R.color.holo_green_light))
        binding.btnPlay.strokeColor = resources.getColorStateList(android.R.color.white)
        binding.btnPlay.strokeWidth = 3
        binding.btnPlay.textSize = 16f
        binding.btnPlay.setTextColor(resources.getColor(android.R.color.white))

        // Делаем кнопку Отмена менее заметной
        binding.btnCancel.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))
        binding.btnCancel.strokeWidth = 1

        binding.btnPlay.setOnClickListener {
            // Изменяем стиль кнопки при нажатии
            resetButtonStyles()
            setSelectedButtonStyle(binding.btnPlay)
            startPlayback()
        }

        // Отображаем имя файла
        binding.tvFilename.text = currentFileName

        // Обновляем информацию о формате видео и месте хранения
        val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"
        binding.tvCacheInfo.text = "$videoFormat | Сохранено на: $storageTypeText | Готово к воспроизведению"
    }

    @OptIn(UnstableApi::class)
    private fun startPreCaching() {
        if (cacheJob != null || videoUrl == null) {
            Log.d(TAG, "Not starting pre-caching: cacheJob=${cacheJob != null}, videoUrl=${videoUrl != null}")
            return
        }

        Log.d(TAG, "Starting pre-caching for file: $currentFileName, isSameFile: $isSameFile, lastProgress: ${ExoVideoPlayer.lastCacheProgress}%")

        // Логируем дополнительную информацию
        Log.d(TAG, "Starting pre-caching with isSameFile: $isSameFile, lastProgress: ${ExoVideoPlayer.lastCacheProgress}%")

        binding.btnCancel.setOnClickListener {
            cancelPreCaching()
        }

        // Отображаем имя файла всегда
        binding.tvFilename.text = currentFileName

        // Показываем информацию о продолжении загрузки, если это тот же файл
        if (isSameFile && ExoVideoPlayer.lastCacheProgress > 0) {
            binding.tvStatus.text = "Продолжение загрузки файла"
            binding.progressDownload.progress = ExoVideoPlayer.lastCacheProgress
            binding.tvProgressPercent.text = "${ExoVideoPlayer.lastCacheProgress}%"
        } else {
            binding.tvStatus.text = if (isSameFile) "Загрузка файла" else "Загрузка нового файла"
            binding.progressDownload.progress = 0
            binding.tvProgressPercent.text = "0%"
        }

        binding.btnCancel.isEnabled = true
        binding.btnPlay.isEnabled = false

        // Сбрасываем стили и делаем кнопку Начать явно неактивной
        resetButtonStyles()
        setDisabledButtonStyle(binding.btnPlay)

        // Делаем кнопку Отмена заметной
        binding.btnCancel.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))
        binding.btnCancel.strokeColor = resources.getColorStateList(android.R.color.white)
        binding.btnCancel.strokeWidth = 3
        binding.btnCancel.textSize = 14f

        cacheJob = CoroutineScope(Dispatchers.IO).launch {
            val isCached = isFullyCached(videoUrl!!)
            if (isCached) {
                // Видео уже скачано, активируем кнопку воспроизведения
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Видео загружено и готово к воспроизведению"
                    binding.progressDownload.progress = 100
                    binding.tvProgressPercent.text = "100%"
                    enablePlayButton()
                }
            } else {
                // Начинаем кэширование видео
                try {
                    // Вызываем preCacheVideo в фоновом потоке
                    preCacheVideo(videoUrl!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pre-caching video", e)
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "Ошибка загрузки: ${e.message}"
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)


    private fun cancelPreCaching() {
        // Изменяем стиль кнопки при нажатии
        resetButtonStyles()
        setSelectedButtonStyle(binding.btnCancel)

        // Отменяем задачу кэширования с полным завершением
        if (cacheJob != null) {
            Log.d(TAG, "Cancelling cache job with immediate cancellation")
            cacheJob?.cancel(CancellationException("User cancelled caching"))

            // Дополнительно убеждаемся, что задача отменена
            runBlocking {
                try {
                    // Ждем небольшое время, чтобы задача успела отмениться
                    withTimeout(1000) {
                        cacheJob?.join()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cache job cancellation timeout or exception: ${e.message}")
                }
            }

            cacheJob = null
            Log.d(TAG, "Cache job cancelled and set to null")
        }

        // Прямая отмена CacheWriter и закрытие CacheDataSource
        try {
            // Закрываем CacheWriter
            if (cacheWriter != null) {
                Log.d(TAG, "Closing CacheWriter")
                // В CacheWriter нет метода close(), но мы можем освободить ссылку
                cacheWriter = null
            }

            // Закрываем CacheDataSource
            if (cacheDataSource != null) {
                Log.d(TAG, "Closing CacheDataSource")
                try {
                    cacheDataSource?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing CacheDataSource", e)
                } finally {
                    cacheDataSource = null
                }
            }

            Log.d(TAG, "Cache resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing cache resources", e)
        }

        // Сохраняем прогресс загрузки текущего файла перед закрытием
        if (isSameFile && binding.progressDownload.progress > 0) {
            ExoVideoPlayer.lastCacheProgress = binding.progressDownload.progress
            Log.d(TAG, "Saving cache progress before cancel: ${ExoVideoPlayer.lastCacheProgress}% for file: $currentFileName")
        }

        // Принудительно синхронизируем файлы кэша перед выходом
        try {
            // Вызываем fsync для файлов кэша
            forceSyncCacheFiles()
            Log.d(TAG, "Cache files synced before exit")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing cache files before exit", e)
        }

        // Закрываем активность
        Log.d(TAG, "Cancelling pre-caching and closing player")
        finish()
    }

    @OptIn(UnstableApi::class)
    private suspend fun preCacheVideo(url: String) {
        Log.d(TAG, "Starting to pre-cache: $url")

        // Получаем полный размер файла
        val contentLength = getContentLength(url)
        if (contentLength <= 0) {
            Log.w(TAG, "Could not determine content length, using default caching approach")
        }

        // Получаем имя файла для использования в качестве ключа кэша
        val cacheKey = ExoVideoPlayer.cacheKeyFactory.buildCacheKey(DataSpec(Uri.parse(url)))
        Log.d(TAG, "Using cache key: $cacheKey for URL: $url")

        // Выводим все ключи кэша для отладки
        val cacheKeys = simpleCache.keys
        Log.d(TAG, "All cache keys before caching: $cacheKeys")

        // Логируем дополнительную информацию
        Log.d(TAG, "Cache details - key: $cacheKey, all keys: $cacheKeys, content length: $contentLength")

        // Получаем информацию о уже закэшированных частях
        val cachedBytes = if (contentLength > 0) {
            simpleCache.getCachedBytes(cacheKey, 0, contentLength)
        } else {
            0L
        }

        // Рассчитываем процент уже закэшированных данных
        val cachedPercent = if (contentLength > 0) {
            (cachedBytes * 100 / contentLength).toInt()
        } else {
            0
        }

        // Обновляем сохраненный прогресс для более точного отображения
        if (isSameFile && cachedPercent > 0) {
            ExoVideoPlayer.lastCacheProgress = cachedPercent
            Log.d(TAG, "Updated cache progress from actual cache: $cachedPercent%")
        }

        Log.d(TAG, "Already cached: $cachedBytes bytes of $contentLength bytes total (${cachedPercent}%)")

        // Создаем DataSpec для кэширования
        // Media3 автоматически пропустит уже закэшированные части
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0) // Начинаем с начала, CacheDataSource автоматически пропустит уже закэшированные части
            .setLength(if (contentLength > 0) contentLength else Long.MAX_VALUE) // Устанавливаем точный размер, если известен
            .build()

        // Логируем информацию о запросе
        val isResumingDownload = isSameFile && ExoVideoPlayer.lastCacheProgress > 0
        val downloadType = if (isResumingDownload) "RESUME" else "INITIAL"
        Log.d(TAG, "HTTP_ANALYSIS [$downloadType]: URL=$url, ContentLength=${contentLength.format()}, CachedBytes=${cachedBytes.format()}, CachedPercent=$cachedPercent%")

        val progressListener = object : CacheWriter.ProgressListener {
            private var reportedProgress = 0
            private val progressUpdateThreshold = 1 // Report every 1% change

            // Переменные для анализа размера блоков данных
            private var lastBytesCached = 0L
            private var lastProgressTime = 0L
            private var blockSizes = mutableListOf<Long>()

            override fun onProgress(requestLength: Long, bytesCached: Long, newBytesCached: Long) {
                val progress = if (requestLength > 0) (bytesCached * 100 / requestLength).toInt() else 0
                val shouldReport = progress >= reportedProgress + progressUpdateThreshold || progress == 100

                // Анализ размера блоков данных
                val currentTime = System.currentTimeMillis()
                if (lastBytesCached > 0 && newBytesCached > 0) {
                    val blockSize = bytesCached - lastBytesCached
                    val timeDiff = currentTime - lastProgressTime

                    if (blockSize > 0) {
                        blockSizes.add(blockSize)
                        val isResumingDownload = isSameFile && ExoVideoPlayer.lastCacheProgress > 0 && progress > ExoVideoPlayer.lastCacheProgress
                        val downloadType = if (isResumingDownload) "RESUME" else "INITIAL"
                        Log.d(TAG, "BLOCK_ANALYSIS [$downloadType]: Size=${blockSize.format()}, Time=${(timeDiff/1000.0).format(2)}s, Total=${bytesCached.format()}, New=${newBytesCached.format()}")
                    }
                }
                lastBytesCached = bytesCached
                lastProgressTime = currentTime

                if (shouldReport) {
                    reportedProgress = progress

                    // Периодически вызываем принудительную синхронизацию файлов кэша
                    // Делаем это каждые 10% прогресса
                    if (progress % 10 == 0) {
                        try {
                            // Вызываем fsync для файлов кэша
                            forceSyncCacheFiles()
                            Log.d(TAG, "Cache files synced at $progress%")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing cache files at $progress%", e)
                        }
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        updateProgress(progress, bytesCached, requestLength)
                        Log.d(TAG, "Cache progress: $progress%, $bytesCached / $requestLength bytes")
                    }
                }
            }
        }

        // Создаем оптимизированный HTTP источник данных
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)  // Увеличиваем таймаут соединения
            .setReadTimeoutMs(15000)     // Увеличиваем таймаут чтения
            .setAllowCrossProtocolRedirects(true) // Разрешаем перенаправления между протоколами

        // Создаем оптимизированный источник данных с кэшем
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(ExoVideoPlayer.cacheKeyFactory) // Используем кастомный CacheKeyFactory
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) // Игнорируем кэш при ошибках

        // Сохраняем ссылку на cacheDataSource, чтобы иметь возможность закрыть его при отмене
        cacheDataSource = cacheDataSourceFactory.createDataSource()

        try {
            Log.d(TAG, "Creating CacheWriter with dataSpec: $dataSpec")
            // Сохраняем ссылку на cacheWriter, чтобы иметь возможность отменить его при необходимости
            cacheWriter = CacheWriter(
                cacheDataSource!!,
                dataSpec,
                null,
                progressListener
            )

            Log.d(TAG, "Starting cache operation...")
            cacheWriter?.cache()
            Log.d(TAG, "Cache completed successfully")

            // Очищаем ссылки после успешного завершения
            cacheWriter = null
            cacheDataSource = null

            // Final UI update on completion
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Видео загружено и готово к воспроизведению"
                binding.progressDownload.progress = 100
                binding.tvProgressPercent.text = "100%"
                binding.tvDownloadSpeed.text = "Скорость: 0 МБ/с"
                binding.tvTimeRemaining.text = "Загрузка завершена"

                // Обновляем информацию о кэше с указанием места хранения
                val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"
                binding.tvCacheInfo.text = "$videoFormat | Сохранено на: $storageTypeText | Загрузка завершена"
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

    // Переменные для расчета скорости загрузки
    private var lastUpdateTime = 0L
    private var lastCachedBytes = 0L
    private var downloadSpeed = 0.0 // МБ/с

    private fun updateProgress(progress: Int, bytesCached: Long, totalBytes: Long) {
        val currentTime = System.currentTimeMillis()

        // Обновляем прогресс
        binding.progressDownload.progress = progress
        binding.tvProgressPercent.text = "$progress%"

        // Расчет размеров в МБ
        val cachedMb = bytesCached / (1024.0 * 1024.0)
        val totalMb = if (totalBytes > 0) totalBytes / (1024.0 * 1024.0) else 0.0

        // Всегда отображаем имя файла
        binding.tvFilename.text = currentFileName

        // Обновляем информацию о загрузке
        val statusText = if (isSameFile && ExoVideoPlayer.lastCacheProgress > 0 && progress > ExoVideoPlayer.lastCacheProgress) {
            // Если это дозагрузка и прогресс увеличился
            String.format("Дозагрузка: %.1f МБ / %.1f МБ", cachedMb, totalMb)
        } else {
            String.format("Загрузка: %.1f МБ / %.1f МБ", cachedMb, totalMb)
        }
        binding.tvStatus.text = statusText

        // Расчет скорости загрузки
        if (lastUpdateTime > 0 && currentTime > lastUpdateTime) {
            val timeDiffSeconds = (currentTime - lastUpdateTime) / 1000.0
            val bytesDiff = bytesCached - lastCachedBytes

            if (timeDiffSeconds > 0 && bytesDiff > 0) {
                // Сглаживание скорости (средняя между текущей и предыдущей)
                val currentSpeed = bytesDiff / timeDiffSeconds / (1024.0 * 1024.0) // МБ/с
                downloadSpeed = if (downloadSpeed > 0) (downloadSpeed + currentSpeed) / 2 else currentSpeed

                // Подробное логирование для анализа скорости
                val isResumingDownload = isSameFile && ExoVideoPlayer.lastCacheProgress > 0 && progress > ExoVideoPlayer.lastCacheProgress
                val downloadType = if (isResumingDownload) "RESUME" else "INITIAL"
                Log.d(TAG, "SPEED_ANALYSIS [$downloadType]: Current=${currentSpeed.format(1)} MB/s, Avg=${downloadSpeed.format(1)} MB/s, Bytes=${bytesDiff.format()}, Time=${timeDiffSeconds.format(2)}s")

                // Обновляем информацию о скорости
                binding.tvDownloadSpeed.text = String.format("Скорость: %.1f МБ/с", downloadSpeed)

                // Расчет оставшегося времени
                if (downloadSpeed > 0 && totalBytes > bytesCached) {
                    val remainingBytes = totalBytes - bytesCached
                    val remainingSeconds = remainingBytes / (downloadSpeed * 1024 * 1024)

                    // Форматируем время в минуты:секунды
                    val minutes = (remainingSeconds / 60).toInt()
                    val seconds = (remainingSeconds % 60).toInt()
                    binding.tvTimeRemaining.text = String.format("Осталось: %02d:%02d", minutes, seconds)
                }
            }
        }

        // Информация о кэше устройства
        updateCacheInfo()

        // Сохраняем значения для следующего расчета
        lastUpdateTime = currentTime
        lastCachedBytes = bytesCached

        Log.d(TAG, "Download progress: $progress%, $cachedMb MB / $totalMb MB, Speed: $downloadSpeed MB/s")
    }

    @OptIn(UnstableApi::class)
    private fun updateCacheInfo() {
        try {
            // Получаем информацию о занятом месте в кэше
            val cacheSize = simpleCache.cacheSpace
            val cacheSizeMb = cacheSize / (1024.0 * 1024.0)

            // Получаем информацию о свободном месте на устройстве
            val statFs = if (ExoVideoPlayer.cacheStorageType == "external") {
                // Если используется внешнее хранилище, получаем путь к нему
                val externalDirs = applicationContext.getExternalFilesDirs(null)
                val bestDir = externalDirs.filterNotNull().maxByOrNull { it.freeSpace }
                StatFs(bestDir?.path ?: applicationContext.cacheDir.path)
            } else {
                // Иначе используем внутреннее хранилище
                StatFs(applicationContext.cacheDir.path)
            }

            val availableBytes = statFs.availableBytes
            val availableMb = availableBytes / (1024.0 * 1024.0)
            val availableGb = availableMb / 1024.0

            // Определяем тип хранилища для отображения
            val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"

            // Форматируем размер в ГБ, если он больше 1 ГБ
            val availableText = if (availableGb >= 1.0) {
                String.format("%.1f ГБ", availableGb)
            } else {
                String.format("%.0f МБ", availableMb)
            }

            binding.tvCacheInfo.text = String.format(
                "%s | %s: %s | Кэш: %.1f МБ",
                videoFormat, storageTypeText, availableText, cacheSizeMb
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cache info", e)
            binding.tvCacheInfo.text = "Информация о кэше недоступна"
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun isFullyCached(url: String): Boolean {
        return try {
            // Получаем имя файла для использования в качестве ключа кэша
            val cacheKey = ExoVideoPlayer.cacheKeyFactory.buildCacheKey(DataSpec(Uri.parse(url)))
            Log.d(TAG, "Using cache key: $cacheKey for URL: $url")

            val cachedBytes = simpleCache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE)
            Log.d(TAG, "Cached bytes: $cachedBytes for cache key: $cacheKey")

            // Если ничего не кэшировано, сразу возвращаем false
            if (cachedBytes <= 0) {
                Log.d(TAG, "No cached bytes found for cache key: $cacheKey")
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

            // Проверяем валидность кэша
            val isValid = isCacheValid(cacheKey, cachedBytes)
            if (!isValid) {
                Log.d(TAG, "Cache is not valid for key: $cacheKey")
                return false
            }

            // Считаем, что файл полностью кэширован только если кэш содержит как минимум 99% файла
            // для случаев, когда есть небольшие расхождения в расчете размера
            val isCached = cachePercentage >= 99 && isValid
            Log.d(TAG, "Is fully cached: $isCached (cached: $cachedBytes, total: $contentLength, valid: $isValid)")
            isCached
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache status", e)
            false
        }
    }

    /**
     * Проверяет валидность кэша
     * Пытается прочитать начало и конец файла из кэша, чтобы убедиться, что файл не поврежден
     */
    @OptIn(UnstableApi::class)
    private fun isCacheValid(cacheKey: String, cachedBytes: Long): Boolean {
        try {
            // Проверяем, что в кэше есть спаны (части файла)
            val cachedSpans = simpleCache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) {
                Log.d(TAG, "No cached spans found for key: $cacheKey")
                return false
            }

            Log.d(TAG, "Found ${cachedSpans.size} cached spans for key: $cacheKey")

            // Проверяем, что размер кэша соответствует размеру спанов
            var totalSpanSize = 0L
            for (span in cachedSpans) {
                totalSpanSize += span.length
            }

            // Если размер спанов сильно отличается от размера кэша, то кэш может быть поврежден
            val sizeDifference = Math.abs(totalSpanSize - cachedBytes)
            val isValidSize = sizeDifference < 1024 * 1024 // Допускаем разницу в 1 МБ

            Log.d(TAG, "Cache validation: totalSpanSize=$totalSpanSize, cachedBytes=$cachedBytes, difference=$sizeDifference, isValidSize=$isValidSize")

            // Проверяем, что есть спаны для начала и конца файла
            var hasStartSpan = false
            var hasEndSpan = false

            for (span in cachedSpans) {
                if (span.position == 0L) {
                    hasStartSpan = true
                }

                // Если спан заканчивается в конце файла или близко к концу
                if (span.position + span.length >= cachedBytes - 1024L * 1024L) { // Допускаем погрешность в 1 МБ
                    hasEndSpan = true
                }
            }

            Log.d(TAG, "Cache validation: hasStartSpan=$hasStartSpan, hasEndSpan=$hasEndSpan")

            // Файл считается валидным, если есть спаны для начала и конца файла и размер соответствует
            return hasStartSpan && hasEndSpan && isValidSize

        } catch (e: Exception) {
            Log.e(TAG, "Error validating cache", e)
            return false
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
                    .setCacheKeyFactory(ExoVideoPlayer.cacheKeyFactory) // Используем кастомный CacheKeyFactory
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

                // Создаем трек-селектор с настройками для автоматического выбора русской аудиодорожки
                trackSelector = DefaultTrackSelector(this).apply {
                    // Устанавливаем предпочтительный язык аудио - русский
                    setParameters(
                        buildUponParameters().setPreferredAudioLanguage("ru")
                    )
                    Log.d(TAG, "Track selector configured with preferred audio language: ru")
                }

                // Create a media source using the cache data source factory
                val mediaSourceFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory)

                // Create MediaItem
                val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))

                Log.d(TAG, "Created MediaItem for URL: $videoUrl")

                player = ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setTrackSelector(trackSelector!!) // Используем трек-селектор с настройками
                    .build()
                    .apply {
                        setMediaItem(mediaItem)
                        playWhenReady = this@VideoPlayerActivity.playWhenReady
                        seekTo(playbackPosition)
                        prepare()

                        // Log playback errors and other events
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                Log.e(TAG, "Player error: ${error.message}", error)
                                Toast.makeText(this@VideoPlayerActivity,
                                    "Ошибка воспроизведения: ${error.message}", Toast.LENGTH_LONG).show()
                            }

                            override fun onPlaybackStateChanged(state: Int) {
                                val stateStr = when(state) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    Player.STATE_ENDED -> "ENDED"
                                    else -> "UNKNOWN"
                                }
                                Log.d(TAG, "Playback state changed: $stateStr")
                            }

                            @OptIn(UnstableApi::class)
                            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                // Логируем информацию о доступных аудиодорожках
                                val audioTracks = mutableListOf<String>()

                                for (trackGroup in tracks.groups) {
                                    // Проверяем только аудиодорожки
                                    if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                                        for (i in 0 until trackGroup.length) {
                                            val format = trackGroup.getTrackFormat(i)
                                            val language = format.language ?: "unknown"
                                            val label = format.label ?: "no label"
                                            val isSelected = trackGroup.isTrackSelected(i)

                                            audioTracks.add("[$i] Language: $language, Label: $label, Selected: $isSelected")

                                            // Если это русская дорожка и она не выбрана, выбираем её
                                            if (language.equals("ru", ignoreCase = true) && !isSelected) {
                                                Log.d(TAG, "Found Russian audio track, selecting it")
                                                val trackSelectionParameters = player?.trackSelectionParameters
                                                    ?.buildUpon()
                                                    ?.setPreferredAudioLanguage("ru")
                                                    ?.build()

                                                trackSelectionParameters?.let {
                                                    player?.trackSelectionParameters = it
                                                }
                                            }
                                        }
                                    }
                                }

                                if (audioTracks.isNotEmpty()) {
                                    Log.d(TAG, "Available audio tracks: ${audioTracks.joinToString("\n")}")
                                } else {
                                    Log.d(TAG, "No audio tracks found")
                                }
                            }
                        })
                    }
                binding.playerView.player = player

                // Настраиваем поведение контроллера плеера
                binding.playerView.controllerShowTimeoutMs = 3000 // Автоматически скрывать контроллер через 3 секунды
                binding.playerView.controllerHideOnTouch = true // Скрывать контроллер при касании
                binding.playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING) // Показывать буферизацию только при воспроизведении

                // Отключаем автоматическое показывание контроллера при перемотке
                binding.playerView.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                    // Если контроллер появляется во время перемотки, скрываем его
                    if (visibility == View.VISIBLE && seekOverlayView?.visibility == View.VISIBLE) {
                        binding.playerView.hideController()
                    }
                })

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

        // Восстанавливаем обычный фон
        window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Отменяем задачу кэширования
        cacheJob?.cancel(CancellationException("Activity stopped"))
        cacheJob = null

        // Закрываем ресурсы кэширования
        try {
            // Закрываем CacheWriter
            cacheWriter = null

            // Закрываем CacheDataSource
            if (cacheDataSource != null) {
                try {
                    cacheDataSource?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing CacheDataSource in onStop", e)
                } finally {
                    cacheDataSource = null
                }
            }

            Log.d(TAG, "Cache resources released in onStop")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing cache resources in onStop", e)
        }
    }

    @OptIn(UnstableApi::class)
    /**
     * Сканирует все доступные хранилища и возвращает информацию о них
     */
    private fun scanAvailableStorage(): String {
        val result = StringBuilder()

        try {
            // Получаем информацию о внутреннем хранилище
            val internalDir = applicationContext.filesDir
            val internalCacheDir = applicationContext.cacheDir
            val internalFreeSpace = internalDir.freeSpace
            val internalTotalSpace = internalDir.totalSpace
            val internalFreeMB = internalFreeSpace / (1024 * 1024)
            val internalTotalMB = internalTotalSpace / (1024 * 1024)

            result.append("1. Внутренняя память:\n")
            result.append("   Путь: ${internalDir.absolutePath}\n")
            result.append("   Кэш: ${internalCacheDir.absolutePath}\n")
            result.append("   Свободно: $internalFreeMB MB / $internalTotalMB MB\n")

            // Получаем все внешние хранилища
            val externalDirs = applicationContext.getExternalFilesDirs(null)
            result.append("\nВнешние хранилища (${externalDirs.size}):\n")

            externalDirs.forEachIndexed { index, dir ->
                if (dir != null) {
                    val freeSpace = dir.freeSpace
                    val totalSpace = dir.totalSpace
                    val freeMB = freeSpace / (1024 * 1024)
                    val totalMB = totalSpace / (1024 * 1024)
                    val isRemovable = isExternalStorageRemovable(dir)

                    result.append("${index + 2}. Внешнее хранилище ${if (isRemovable) "(съемное)" else ""}:\n")
                    result.append("   Путь: ${dir.absolutePath}\n")
                    result.append("   Свободно: $freeMB MB / $totalMB MB\n")
                    result.append("   Съемное: $isRemovable\n")

                    // Проверяем наличие ключевых слов в пути
                    val path = dir.absolutePath.lowercase()
                    val hasUSB = path.contains("usb")
                    val hasSDCard = path.contains("sdcard")
                    val hasEmulated = path.contains("emulated")
                    result.append("   Ключевые слова: USB=$hasUSB, SDCard=$hasSDCard, Emulated=$hasEmulated\n")
                }
            }

            // Проверяем дополнительные пути для Android TV
            val additionalPaths = listOf(
                "/storage/emulated/0",
                "/storage/self/primary",
                "/mnt/media_rw",
                "/mnt/usb",
                "/storage/usb",
                "/storage/sdcard"
            )

            result.append("\nДополнительные пути:\n")
            additionalPaths.forEach { path ->
                val file = File(path)
                val exists = file.exists()
                val isDir = file.isDirectory
                val canRead = file.canRead()
                val canWrite = file.canWrite()

                result.append("- $path:\n")
                result.append("  Существует: $exists, Директория: $isDir, Чтение: $canRead, Запись: $canWrite\n")

                if (exists && isDir && canRead) {
                    val subDirs = file.listFiles()
                    if (subDirs != null && subDirs.isNotEmpty()) {
                        result.append("  Содержимое: ${subDirs.take(5).joinToString { file -> file.name }}${if (subDirs.size > 5) "..." else ""}\n")
                    }
                }
            }

            // Информация о текущем кэше
            result.append("\nТекущий кэш:\n")
            result.append("- Тип хранилища: ${ExoVideoPlayer.cacheStorageType}\n")

            // Получаем директорию кэша, которая используется в настоящий момент
            val bestCacheDir = ExoVideoPlayer.findBestCacheDir()
            val cacheExists = bestCacheDir.exists()
            val cacheIsDir = bestCacheDir.isDirectory
            val cacheCanWrite = bestCacheDir.canWrite()
            result.append("- Путь кэша: ${bestCacheDir.absolutePath}\n")
            result.append("- Существует: $cacheExists, Директория: $cacheIsDir, Запись: $cacheCanWrite\n")

            // Проверяем размер кэша
            val cacheSize = simpleCache.cacheSpace
            val cacheSizeMB = cacheSize / (1024.0 * 1024.0)
            result.append("- Размер кэша: ${cacheSizeMB.toInt()} MB\n")

            // Проверяем ключи в кэше
            val cacheKeys = simpleCache.keys
            result.append("- Ключи в кэше: ${cacheKeys.joinToString()}\n")

        } catch (e: Exception) {
            result.append("\nОшибка при сканировании хранилищ: ${e.message}")
            Log.e(TAG, "Error scanning storage", e)
        }

        return result.toString()
    }

    /**
     * Очищает весь кэш приложения
     */
    @OptIn(UnstableApi::class)
    private fun clearAllCache() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Отменяем текущую задачу кэширования, если она запущена
                cacheJob?.cancel()
                cacheJob = null

                // Получаем все ключи в кэше
                val cacheKeys = simpleCache.keys
                Log.d(TAG, "Clearing all cache. Keys before: $cacheKeys")

                // Удаляем все ключи из кэша
                cacheKeys.forEach { key ->
                    simpleCache.removeResource(key)
                    Log.d(TAG, "Removed cache for key: $key")
                }

                // Сбрасываем информацию о последнем файле
                ExoVideoPlayer.lastCachedFileName = ""
                ExoVideoPlayer.lastCachedUrl = null
                ExoVideoPlayer.lastCacheProgress = 0

                // Обновляем UI
                withContext(Dispatchers.Main) {
                    binding.progressDownload.progress = 0
                    binding.tvProgressPercent.text = "0%"
                    binding.tvStatus.text = "Кэш полностью очищен"

                    // Обновляем информацию о кэше
                    val storageTypeText = if (ExoVideoPlayer.cacheStorageType == "external") "Флешка" else "Внутр. память"
                    binding.tvCacheInfo.text = "$videoFormat | $storageTypeText | Кэш очищен"

                    // Логируем успешную очистку кэша
                    Log.d(TAG, "Cache successfully cleared")

                    Toast.makeText(this@VideoPlayerActivity, "Кэш полностью очищен", Toast.LENGTH_SHORT).show()
                }

                Log.d(TAG, "Cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing cache", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VideoPlayerActivity, "Ошибка при очистке кэша: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isExternalStorageRemovable(path: File): Boolean {
        try {
            // На Android TV флешка обычно содержит в пути слова "usb" или "sdcard"
            val pathStr = path.absolutePath.lowercase()
            val isUSB = pathStr.contains("usb") || pathStr.contains("sdcard") || !pathStr.contains("emulated/0")

            // Также проверяем, что это не первый элемент в списке внешних хранилищ (обычно первый - это внутренняя память)
            val externalDirs = applicationContext.getExternalFilesDirs(null)
            val isNotFirstStorage = externalDirs.isNotEmpty() && externalDirs[0] != path

            // Дополнительная проверка - если свободного места больше, чем во внутренней памяти
            val hasMoreSpace = path.freeSpace > applicationContext.cacheDir.freeSpace * 1.5 // В 1.5 раза больше места

            return (isUSB || isNotFirstStorage) && hasMoreSpace
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if storage is removable", e)
            return false
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Освобождаем WakeLock при закрытии активности
        releaseWakeLock()

        // Сохраняем прогресс загрузки текущего файла
        if (isSameFile && binding.progressDownload.progress > 0) {
            ExoVideoPlayer.lastCacheProgress = binding.progressDownload.progress
            Log.d(TAG, "Saving cache progress: ${ExoVideoPlayer.lastCacheProgress}% for file: $currentFileName")
        }

        // Принудительно синхронизируем файлы кэша перед выходом
        try {
            // Вызываем fsync для файлов кэша
            forceSyncCacheFiles()
            Log.d(TAG, "Cache files synced in onDestroy")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing cache files in onDestroy", e)
        }

        // Очищаем кэш только если это новый файл
        if (!isSameFile) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Clearing cache for previous files")
                    // Получаем имя файла для использования в качестве ключа кэша
                    val currentCacheKey = if (videoUrl != null) {
                        ExoVideoPlayer.cacheKeyFactory.buildCacheKey(DataSpec(Uri.parse(videoUrl!!)))
                    } else {
                        null
                    }

                    // Удаляем все ключи, кроме текущего файла
                    simpleCache.keys.forEach { key ->
                        if (currentCacheKey == null || key != currentCacheKey) {
                            Log.d(TAG, "Removing cache for key: $key")
                            simpleCache.removeResource(key)
                        } else {
                            Log.d(TAG, "Keeping cache for current file: $key")
                        }
                    }

                    // НЕ вызываем release() чтобы избежать проблем при повторном использовании
                    Log.d(TAG, "Previous cache cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing cache", e)
                }
            }
        } else {
            Log.d(TAG, "Keeping cache for same file: $currentFileName")
        }
    }

    /**
     * Форматирует число с заданным количеством знаков после запятой
     */
    private fun Double.format(digits: Int): String {
        return String.format("%.${digits}f", this)
    }

    /**
     * Форматирует число байт в человекочитаемый формат
     */
    private fun Long.format(): String {
        return when {
            this < 1024 -> "$this B"
            this < 1024 * 1024 -> "${this / 1024} KB"
            else -> "${this / (1024 * 1024)} MB"
        }
    }

    /**
     * Сбрасывает стили всех кнопок к обычному
     */
    private fun resetButtonStyles() {
        // Устанавливаем темный стиль для кнопок
        binding.btnPlay.setBackgroundColor(resources.getColor(R.color.dark_button_background))
        binding.btnClearCache.setBackgroundColor(resources.getColor(R.color.dark_button_background))

        // Для кнопки отмены используем приглушенный темно-синий
        binding.btnCancel.setBackgroundColor(resources.getColor(R.color.dark_button_cancel))

        // Устанавливаем обводку в темном стиле
        binding.btnPlay.strokeColor = resources.getColorStateList(R.color.dark_button_stroke)
        binding.btnCancel.strokeColor = resources.getColorStateList(R.color.dark_button_stroke)
        binding.btnClearCache.strokeColor = resources.getColorStateList(R.color.dark_button_stroke)

        // Устанавливаем тонкую обводку для всех кнопок
        binding.btnPlay.strokeWidth = 1
        binding.btnCancel.strokeWidth = 1
        binding.btnClearCache.strokeWidth = 1

        // Устанавливаем нормальный размер текста
        binding.btnPlay.textSize = 14f
        binding.btnCancel.textSize = 14f
        binding.btnClearCache.textSize = 14f

        // Устанавливаем цвет текста в темном стиле
        binding.btnPlay.setTextColor(resources.getColor(R.color.dark_text_high_emphasis))
        binding.btnCancel.setTextColor(resources.getColor(R.color.dark_text_high_emphasis))
        binding.btnClearCache.setTextColor(resources.getColor(R.color.dark_text_high_emphasis))

        // Делаем кнопку Начать неактивной, если она не включена
        if (!binding.btnPlay.isEnabled) {
            setDisabledButtonStyle(binding.btnPlay)
        }
    }

    /**
     * Устанавливает стиль выбранной кнопки
     */
    private fun setSelectedButtonStyle(button: com.google.android.material.button.MaterialButton) {
        // Устанавливаем цвет фона в темном стиле
        button.setBackgroundColor(resources.getColor(R.color.dark_button_selected))

        // Устанавливаем обводку в темном стиле
        button.strokeColor = resources.getColorStateList(R.color.dark_primary)

        // Устанавливаем толстую обводку
        button.strokeWidth = 2

        // Увеличиваем размер текста для выделения
        button.textSize = 15f

        // Устанавливаем цвет текста в темном стиле
        button.setTextColor(resources.getColor(R.color.dark_text_high_emphasis))
    }

    /**
     * Устанавливает стиль неактивной кнопки
     */
    private fun setDisabledButtonStyle(button: com.google.android.material.button.MaterialButton) {
        // Устанавливаем темно-серый цвет фона
        button.setBackgroundColor(resources.getColor(R.color.dark_button_disabled))

        // Устанавливаем темно-серую обводку
        button.strokeColor = resources.getColorStateList(R.color.dark_surface_overlay_1)

        // Устанавливаем тонкую обводку
        button.strokeWidth = 1

        // Уменьшаем размер текста
        button.textSize = 13f

        // Делаем текст серым
        button.setTextColor(resources.getColor(R.color.dark_text_disabled))
    }

    /**
     * Принудительно синхронизирует файлы кэша с диском
     */
    private fun forceSyncCacheFiles() {
        try {
            // Получаем директорию кэша
            val cacheDir = ExoVideoPlayer.findBestCacheDir()
            if (!cacheDir.exists() || !cacheDir.isDirectory) {
                Log.w(TAG, "Cache directory does not exist: ${cacheDir.absolutePath}")
                return
            }

            // Получаем все файлы в директории кэша
            val cacheFiles = cacheDir.listFiles()
            if (cacheFiles == null || cacheFiles.isEmpty()) {
                Log.w(TAG, "No cache files found in ${cacheDir.absolutePath}")
                return
            }

            // Проходим по всем файлам и вызываем fsync
            var syncedFiles = 0
            cacheFiles.forEach { file ->
                if (file.isFile) {
                    try {
                        // Открываем файл в режиме "rwd" для синхронной записи
                        val fileChannel = java.io.RandomAccessFile(file, "rwd").channel
                        // Вызываем force(true) для сброса данных и метаданных на диск
                        fileChannel.force(true)
                        fileChannel.close()
                        syncedFiles++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing file ${file.absolutePath}", e)
                    }
                } else if (file.isDirectory) {
                    // Рекурсивно обрабатываем вложенные директории
                    val subFiles = file.listFiles()
                    if (subFiles != null && subFiles.isNotEmpty()) {
                        subFiles.forEach { subFile ->
                            if (subFile.isFile) {
                                try {
                                    val fileChannel = java.io.RandomAccessFile(subFile, "rwd").channel
                                    fileChannel.force(true)
                                    fileChannel.close()
                                    syncedFiles++
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error syncing subfile ${subFile.absolutePath}", e)
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Synced $syncedFiles cache files to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cache files sync", e)
        }
    }

    /**
     * Переопределяем метод onBackPressed, чтобы он вызывал cancelPreCaching()
     * Это обеспечит одинаковое поведение при нажатии кнопки "Назад" и кнопки "Отмена"
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Вместо стандартного завершения активности вызываем cancelPreCaching()
        Log.d(TAG, "Back button pressed, calling cancelPreCaching()")
        cancelPreCaching()
        // Не вызываем super.onBackPressed(), так как cancelPreCaching() уже вызывает finish()
    }

    // Переменные для отслеживания последовательных нажатий
    private var lastSeekDirection = 0 // 0 - нет, 1 - вперед, -1 - назад
    private var consecutiveSeekCount = 0 // Счетчик последовательных нажатий
    private var lastSeekTime = 0L // Время последней перемотки
    private val seekTimeoutMs = 2000L // Таймаут между нажатиями для сброса счетчика

    /**
     * Обрабатывает нажатия клавиш на пульте
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Обрабатываем только нажатия кнопок, а не отпускания
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        // Обрабатываем кнопки только в режиме воспроизведения
        if (binding.playerView.visibility != View.VISIBLE || player == null) {
            return super.dispatchKeyEvent(event)
        }

        val currentTime = System.currentTimeMillis()

        // Если прошло больше 2 секунд с последней перемотки, сбрасываем счетчик
        if (currentTime - lastSeekTime > seekTimeoutMs) {
            consecutiveSeekCount = 0
            lastSeekDirection = 0
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                // Если предыдущее нажатие было в том же направлении, увеличиваем счетчик
                if (lastSeekDirection == 1) {
                    consecutiveSeekCount++
                } else {
                    consecutiveSeekCount = 0
                    lastSeekDirection = 1
                }

                // Рассчитываем шаг перемотки в зависимости от количества нажатий
                val seekStep = when {
                    consecutiveSeekCount > 10 -> 60000 // 60 секунд после 10 нажатий
                    consecutiveSeekCount > 5 -> 30000  // 30 секунд после 5 нажатий
                    else -> 15000                     // 15 секунд по умолчанию
                }

                val currentPosition = player?.currentPosition ?: 0
                val duration = player?.duration ?: 0
                val newPosition = Math.min(duration, currentPosition + seekStep)
                player?.seekTo(newPosition)

                // Показываем информацию о перемотке
                showSeekOverlay("+${seekStep / 1000} сек", newPosition, duration)

                Log.d(TAG, "Seeking forward ${seekStep / 1000} seconds (consecutive: $consecutiveSeekCount)")
                lastSeekTime = currentTime
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                // Если предыдущее нажатие было в том же направлении, увеличиваем счетчик
                if (lastSeekDirection == -1) {
                    consecutiveSeekCount++
                } else {
                    consecutiveSeekCount = 0
                    lastSeekDirection = -1
                }

                // Рассчитываем шаг перемотки в зависимости от количества нажатий
                val seekStep = when {
                    consecutiveSeekCount > 10 -> 30000 // 30 секунд после 10 нажатий
                    consecutiveSeekCount > 5 -> 15000  // 15 секунд после 5 нажатий
                    else -> 5000                      // 5 секунд по умолчанию
                }

                val currentPosition = player?.currentPosition ?: 0
                val duration = player?.duration ?: 0
                val newPosition = Math.max(0, currentPosition - seekStep)
                player?.seekTo(newPosition)

                // Показываем информацию о перемотке
                showSeekOverlay("-${seekStep / 1000} сек", newPosition, duration)

                Log.d(TAG, "Seeking backward ${seekStep / 1000} seconds (consecutive: $consecutiveSeekCount)")
                lastSeekTime = currentTime
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Переключаем воспроизведение/паузу
                if (player?.isPlaying == true) {
                    player?.pause()
                    // Показываем стандартный контроллер плеера
                    binding.playerView.showController()
                } else {
                    player?.play()
                    // Скрываем стандартный контроллер плеера через некоторое время
                    binding.playerView.postDelayed({
                        binding.playerView.hideController()
                    }, 1000)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Показываем стандартный контроллер плеера
                binding.playerView.showController()

                // Перемещаем фокус на кнопку настроек
                moveToSettingsButton()

                Log.d(TAG, "Down button pressed, showing controller and moving focus to settings")
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // Переменные для оверлея перемотки
    private var seekOverlayView: View? = null
    private var seekActionTextView: TextView? = null
    private var seekPositionTextView: TextView? = null
    private var hideSeekOverlayRunnable: Runnable? = null
    private val seekOverlayTimeoutMs = 1500L // Время показа оверлея в миллисекундах

    /**
     * Инициализирует оверлей перемотки
     */
    private fun initSeekOverlay() {
        // Инициализируем оверлей только если он еще не создан
        if (seekOverlayView == null) {
            // Загружаем макет оверлея
            seekOverlayView = layoutInflater.inflate(R.layout.seek_overlay, null)
            seekActionTextView = seekOverlayView?.findViewById(R.id.tv_seek_action)
            seekPositionTextView = seekOverlayView?.findViewById(R.id.tv_seek_position)

            // Добавляем оверлей в корневой макет
            val rootView = window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(seekOverlayView)

            // Скрываем оверлей по умолчанию
            seekOverlayView?.visibility = View.GONE

            // Создаем Runnable для скрытия оверлея
            hideSeekOverlayRunnable = Runnable {
                seekOverlayView?.visibility = View.GONE
                // Восстанавливаем контроллер плеера после скрытия оверлея
                binding.playerView.useController = true
            }
        }
    }

    /**
     * Показывает полупрозрачный оверлей с информацией о перемотке
     */
    private fun showSeekOverlay(message: String, position: Long, duration: Long) {
        // Инициализируем оверлей, если он еще не создан
        if (seekOverlayView == null) {
            initSeekOverlay()
        }

        // Форматируем время в формате MM:SS
        val positionStr = formatTime(position)
        val durationStr = formatTime(duration)

        // Обновляем текст в оверлее
        seekActionTextView?.text = message
        seekPositionTextView?.text = "$positionStr / $durationStr"

        // Показываем оверлей
        seekOverlayView?.visibility = View.VISIBLE

        // Скрываем стандартный контроллер плеера и отключаем его появление при перемотке
        binding.playerView.hideController()

        // Дополнительно блокируем появление контроллера на время показа оверлея
        binding.playerView.useController = false

        // Удаляем предыдущий отложенный вызов скрытия
        seekOverlayView?.handler?.removeCallbacks(hideSeekOverlayRunnable!!)

        // Скрываем оверлей через заданное время
        seekOverlayView?.handler?.postDelayed(hideSeekOverlayRunnable!!, seekOverlayTimeoutMs)
    }

    /**
     * Форматирует время в миллисекундах в формат MM:SS
     */
    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * Перемещает фокус на кнопку настроек в контроллере плеера
     */
    private fun moveToSettingsButton() {
        try {
            // Находим кнопку настроек в контроллере плеера
            val settingsButton = findSettingsButton(binding.playerView)

            if (settingsButton != null) {
                // Устанавливаем фокус на кнопку настроек
                settingsButton.requestFocus()
                Log.d(TAG, "Focus moved to settings button")
            } else {
                Log.d(TAG, "Settings button not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving focus to settings button", e)
        }
    }

    /**
     * Рекурсивно ищет кнопку настроек в иерархии представления
     */
    private fun findSettingsButton(view: View): View? {
        // Если это ViewGroup, проходим по всем дочерним элементам
        if (view is ViewGroup) {
            // Проверяем, есть ли в дочерних элементах кнопка настроек
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)

                // Проверяем, является ли этот элемент кнопкой настроек
                if (isSettingsButton(child)) {
                    return child
                }

                // Рекурсивно ищем в дочерних элементах
                val settingsButton = findSettingsButton(child)
                if (settingsButton != null) {
                    return settingsButton
                }
            }
        }

        // Проверяем, является ли текущий элемент кнопкой настроек
        return if (isSettingsButton(view)) view else null
    }

    /**
     * Проверяет, является ли элемент кнопкой настроек
     */
    private fun isSettingsButton(view: View): Boolean {
        // Проверяем по ID
        if (view.id == androidx.media3.ui.R.id.exo_settings) {
            return true
        }

        // Проверяем по тегу (content description)
        val contentDescription = view.contentDescription
        if (contentDescription != null && (
                contentDescription.toString().contains("settings", ignoreCase = true) ||
                contentDescription.toString().contains("настрой", ignoreCase = true)
            )) {
            return true
        }

        return false
    }

    /**
     * Активирует WakeLock и флаг KEEP_SCREEN_ON для предотвращения перехода в режим скринсейвера
     */
    private fun acquireWakeLock() {
        try {
            // Устанавливаем флаг KEEP_SCREEN_ON для предотвращения перехода в режим скринсейвера
            binding.playerView.keepScreenOn = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Создаем WakeLock для предотвращения перехода в режим сна
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "YuraVision:VideoPlayerWakeLock"
                )
                wakeLock?.acquire()
                Log.d(TAG, "WakeLock acquired to prevent screensaver during playback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    /**
     * Освобождает WakeLock и снимает флаг KEEP_SCREEN_ON
     */
    private fun releaseWakeLock() {
        try {
            // Снимаем флаг KEEP_SCREEN_ON
            binding.playerView.keepScreenOn = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Освобождаем WakeLock
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
            player = null

            // Освобождаем трек-селектор
            trackSelector = null

            // Освобождаем WakeLock и снимаем флаг KEEP_SCREEN_ON
            releaseWakeLock()
        }
    }
}