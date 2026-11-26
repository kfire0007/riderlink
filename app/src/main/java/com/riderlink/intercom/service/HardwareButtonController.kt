package com.riderlink.intercom.service

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Handles hardware Volume Down hold-to-talk (in PTT mode) and Bluetooth/wired
 * earphone media button clicks via MediaSession (toggle PTT or toggle Mute).
 */
class HardwareButtonController(
    private val context: Context,
    private val getTalkMode: () -> TalkMode,
    private val onPttPressedChanged: (Boolean) -> Unit,
    private val onTogglePtt: () -> Unit,
    private val onToggleMute: () -> Unit
) {
    private var mediaSession: MediaSession? = null
    private var volumePttDown = false

    fun start() {
        if (mediaSession != null) return
        val session = MediaSession(context.applicationContext, "RiderLinkMediaSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return super.onMediaButtonEvent(mediaButtonIntent)

                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            when (getTalkMode()) {
                                TalkMode.PUSH_TO_TALK -> onTogglePtt()
                                TalkMode.HANDS_FREE -> onToggleMute()
                            }
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        }, Handler(Looper.getMainLooper()))
        val playbackState = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
            .build()
        session.setPlaybackState(playbackState)
        session.isActive = true
        mediaSession = session
    }

    fun stop() {
        volumePttDown = false
        val session = mediaSession ?: return
        mediaSession = null
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
    }

    fun onKeyDown(keyCode: Int, repeatCount: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && getTalkMode() == TalkMode.PUSH_TO_TALK) {
            if (repeatCount == 0 && !volumePttDown) {
                volumePttDown = true
                onPttPressedChanged(true)
            }
            return true
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && getTalkMode() == TalkMode.PUSH_TO_TALK) {
            if (volumePttDown) {
                volumePttDown = false
                onPttPressedChanged(false)
            }
            return true
        }
        return false
    }
}
