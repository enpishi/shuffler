package com.ghancaballes.blacksheep

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashVideoActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use a splash / fullscreen theme (see styles snippet)
        setTheme(R.style.Theme_BlackSheep_SplashVideo)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash_video)

        // (Optional) Kick off async preload while video runs
        lifecycleScope.launch {
            val preloadJob = async(Dispatchers.IO) {
                // preload lightweight data; DON'T block forever
                // Example: warm up Firebase or load small cached prefs
                // FirebaseApp.initializeApp(applicationContext) // if not already
            }
            preloadJob.await()
        }

        initPlayer()

        findViewById<ImageButton>(R.id.buttonSkip).setOnClickListener {
            navigateNext()
        }
    }

    private fun initPlayer() {
        val view = findViewById<PlayerView>(R.id.playerView)
        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            // Raw resource URI
            val uri = Uri.parse("android.resource://$packageName/${R.raw.intro}")
            val mediaItem = MediaItem.fromUri(uri)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true

            exo.addListener(object : com.google.android.exoplayer2.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == com.google.android.exoplayer2.Player.STATE_ENDED) {
                        navigateNext()
                    }
                }
            })
        }

        // Safety timeout if video fails
        lifecycleScope.launch {
            delay(8000) // fallback limit
            if (!navigated) navigateNext()
        }
    }

    private fun navigateNext() {
        if (navigated) return
        navigated = true
        player?.playWhenReady = false
        player?.release()
        player = null

        // Optionally store a preference to skip video on subsequent launches
        // getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("video_seen", true).apply()

        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Release early if user backgrounds app
        if (isFinishing) {
            player?.release()
            player = null
        }
    }
}