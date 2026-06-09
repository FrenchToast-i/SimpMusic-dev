package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import org.koin.compose.koinInject
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MediaPlayerView(
    url: String,
    modifier: Modifier,
) {
    val player = remember(url) { AVPlayer.playerWithURL(NSURL.URLWithString(url)!!) }
    DisposableEffect(player) {
        player.play()
        onDispose {
            player.pause()
        }
    }
    UIKitView(
        factory = {
            val view = UIView(frame = CGRectZero.readValue())
            view.backgroundColor = UIColor.blackColor
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
            playerLayer.frame = view.bounds
            view.layer.addSublayer(playerLayer)
            view
        },
        modifier = modifier,
        update = { view ->
            view.layer.sublayers?.firstOrNull()?.let { layer ->
                (layer as? AVPlayerLayer)?.frame = view.bounds
            }
        },
    )
}

@Composable
actual fun MediaPlayerViewWithSubtitle(
    modifier: Modifier,
    playerName: String,
    shouldPip: Boolean,
    shouldShowSubtitle: Boolean,
    shouldScaleDownSubtitle: Boolean,
    isInPipMode: Boolean,
    timelineState: TimeLine,
    lyricsData: Lyrics?,
    translatedLyricsData: Lyrics?,
    mainTextStyle: TextStyle,
    translatedTextStyle: TextStyle,
) {
    val mediaPlayerHandler: MediaPlayerHandler = koinInject()
    val format by mediaPlayerHandler.format.collectAsStateWithLifecycle()
    val videoUrl = format?.videoUrl ?: format?.audioUrl

    var currentLineIndex by rememberSaveable { mutableIntStateOf(-1) }
    var currentTranslatedLineIndex by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(timelineState, lyricsData, translatedLyricsData) {
        val lines = lyricsData?.lines ?: return@LaunchedEffect
        val translatedLines = translatedLyricsData?.lines
        if (timelineState.current > 0L) {
            lines.indices.forEach { index ->
                val sentence = lines[index]
                val startTimeMs = sentence.startTimeMs.toLong()
                val endTimeMs =
                    if (index < lines.size - 1) {
                        lines[index + 1].startTimeMs.toLong()
                    } else {
                        startTimeMs + 60_000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentLineIndex = index
                }
            }
            translatedLines?.indices?.forEach { index ->
                val sentence = translatedLines[index]
                val startTimeMs = sentence.startTimeMs.toLong()
                val endTimeMs =
                    if (index < translatedLines.size - 1) {
                        translatedLines[index + 1].startTimeMs.toLong()
                    } else {
                        startTimeMs + 60_000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentTranslatedLineIndex = index
                }
            }
        } else {
            currentLineIndex = -1
            currentTranslatedLineIndex = -1
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (!videoUrl.isNullOrEmpty()) {
            MediaPlayerView(url = videoUrl, modifier = Modifier.fillMaxSize())
        }
        if (shouldShowSubtitle) {
            val currentLine = lyricsData?.lines?.getOrNull(currentLineIndex)?.words
            val translatedLine = translatedLyricsData?.lines?.getOrNull(currentTranslatedLineIndex)?.words
            if (!currentLine.isNullOrEmpty()) {
                Text(
                    text = currentLine,
                    style = mainTextStyle,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (!translatedLine.isNullOrEmpty()) {
                Text(
                    text = translatedLine,
                    style = translatedTextStyle,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
