package com.androiddd.exovideoplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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

    private fun initializePlayer() {
        if (player == null && videoUrl != null) {
            player = ExoPlayer.Builder(this).build().apply {
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
