package com.androiddd.exovideoplayer

import android.net.Uri
import android.os.Bundle
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
            // Create a data source factory with cache support
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            val cacheDataSourceFactory = CacheDataSource.Factory().setCache(simpleCache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // Create a media source using the cache data source factory
            val mediaSourceFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory)

            player =
                ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                    playWhenReady = this@VideoPlayerActivity.playWhenReady
                    seekTo(playbackPosition)
                    prepare()
                }
            binding.playerView.player = player
        }
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
