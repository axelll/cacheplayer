package com.androiddd.exovideoplayer

import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import com.androiddd.exovideoplayer.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra("videoUrl") ?: return

        initializePlayer(videoUrl)
    }

    private fun initializePlayer(videoUrl: String) {
        // Initialize Media3 ExoPlayer
        player = ExoPlayer.Builder(this).build()

        // Bind the PlayerView to the ExoPlayer
        binding.playerView.player = player

        // Create and prepare the media item
        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        player?.setMediaItem(mediaItem)

        // Prepare the player
        player?.prepare()

        // Start playback when ready
        player?.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            val videoUrl = intent.getStringExtra("VIDEO_URL") ?: return
            initializePlayer(videoUrl)
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
        player?.release()
        player = null
    }

}