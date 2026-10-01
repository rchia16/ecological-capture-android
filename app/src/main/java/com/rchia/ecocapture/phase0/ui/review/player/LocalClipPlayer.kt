package com.rchia.ecocapture.phase0.ui.review.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun LocalClipPlayer(
    file: File,
    onPlaybackError: () -> Unit,
    modifier: Modifier = Modifier,
    onReleaseReady: ((() -> Unit)?) -> Unit = { },
    onPlaybackStateChanged: (Boolean) -> Unit = { },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val errorCallback = rememberUpdatedState(onPlaybackError)
    val stateCallback = rememberUpdatedState(onPlaybackStateChanged)
    val releaseReady = rememberUpdatedState(onReleaseReady)
    val player = remember(file.absolutePath) { ExoPlayer.Builder(context).build() }
    val playerView = remember(player) { PlayerView(context).apply { useController = false } }
    var released by remember(player) { mutableStateOf(false) }
    var playRequested by remember(player) { mutableStateOf(false) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!released) errorCallback.value()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!released && playRequested != playWhenReady) {
                    playRequested = playWhenReady
                    stateCallback.value(playWhenReady)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!released && playbackState == Player.STATE_ENDED) player.pause()
            }
        }
        player.addListener(listener)
        playerView.player = player
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()

        val releaseOnce: () -> Unit = {
            if (!released) {
                released = true
                playerView.player = null
                player.removeListener(listener)
                player.pause()
                player.release()
            }
        }
        releaseReady.value(releaseOnce)
        val observer = LifecycleEventObserver { _, event ->
            if (!released && (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseOnce()
            releaseReady.value(null)
        }
    }
    LaunchedEffect(player) {
        while (isActive && !released) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    Box(modifier = modifier) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f)).padding(8.dp),
        ) {
            Text(
                "Playback: " + (if (playRequested) "Playing" else "Paused") +
                    " · " + playbackTime(positionMs) + " / " + playbackTime(durationMs),
                color = Color.White,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (playRequested) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                    },
                    enabled = !released,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(if (playRequested) "PAUSE" else "PLAY") }
                Button(
                    onClick = { player.seekTo(0); player.play() },
                    enabled = !released,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("REPLAY") }
            }
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
