package com.ghancaballes.blacksheep

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashVideoActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Uses Theme.BlackSheep.SplashVideo (white background, fullscreen)
        setTheme(R.style.Theme_BlackSheep_SplashVideo)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_video)

        // Optional: preload lightweight data in parallel
        lifecycleScope.launch {
            async(Dispatchers.IO) {
                // Perform quick async prep here if desired
            }.await()
        }

        initPlayer()
    }

    private fun initPlayer() {
        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.alpha = 0f

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            val uri = Uri.parse("android.resource://$packageName/${R.raw.intro}")
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true

            exo.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    // Fade in for polish (avoid sudden flash)
                    playerView.animate().alpha(1f).setDuration(250).start()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        navigateNext()
                    }
                }
            })
        }

        // Safety timeout fallback in case playback stalls or file is corrupted
        lifecycleScope.launch {
            delay(8000) // adjust if your video is longer
            if (!navigated) navigateNext()
        }

        // (Optional) Allow user to tap the screen to skip - uncomment if desired:
        // playerView.setOnClickListener { navigateNext() }
    }

    private fun navigateNext() {
        if (navigated) return
        navigated = true
        player?.run {
            playWhenReady = false
            release()
        }
        player = null

        // Decide landing screen: MainActivity (login) or PlayerManagementActivity if already authenticated.
        // For now we go to MainActivity:
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            player?.release()
            player = null
        }
    }
}