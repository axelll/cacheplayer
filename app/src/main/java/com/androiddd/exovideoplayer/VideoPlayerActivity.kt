package com.androiddd.exovideoplayer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.androiddd.exovideoplayer.ExoVideoPlayer.Companion.simpleCache
import com.androiddd.exovideoplayer.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var videoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUrl = intent.getStringExtra("videoUrl")
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player == null && videoUrl != null) {
            Log.d("VideoPlayerActivity", "Initializing player for URL: $videoUrl")
            
            // Check if video is in cache first
            val isCached = isVideoCached(videoUrl!!)
            Log.d("VideoPlayerActivity", "Is video in cache: $isCached")
            
            if (!isCached) {
                Log.e("VideoPlayerActivity", "Video not found in cache. Playback aborted.")
                Toast.makeText(this, "Видео не найдено в кэше. Необходимо сначала скачать.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            
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
                
                Log.d("VideoPlayerActivity", "Created MediaItem for URL: $videoUrl")
                
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
                                Log.e("VideoPlayerActivity", "Player error: ${error.message}", error)
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
                                Log.d("VideoPlayerActivity", "Playback state changed: $stateStr")
                            }
                        })
                    }
                binding.playerView.player = player
                Log.d("VideoPlayerActivity", "Player initialized and prepared")
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error initializing player", e)
                Toast.makeText(this, "Ошибка инициализации плеера: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    @OptIn(UnstableApi::class)
    private fun isVideoCached(url: String): Boolean {
        val cachedBytes = simpleCache.getCachedBytes(url, 0, Long.MAX_VALUE)
        Log.d("VideoPlayerActivity", "Cached bytes: $cachedBytes for URL: $url")
        
        // Выводим все ключи для отладки
        val keys = simpleCache.keys
        Log.d("VideoPlayerActivity", "All cache keys: $keys")
        
        return cachedBytes > 0
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
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