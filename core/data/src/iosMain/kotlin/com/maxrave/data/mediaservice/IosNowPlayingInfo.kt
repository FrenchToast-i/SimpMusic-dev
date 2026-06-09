package com.maxrave.data.mediaservice

import com.maxrave.domain.data.player.GenericMediaItem
import platform.AVFoundation.AVPlayer
import platform.Foundation.NSNumber
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueIndex
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueCount
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

internal object IosNowPlayingInfo {
    fun configureRemoteCommands(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
        commandCenter.playCommand.setEnabled(true)
        commandCenter.pauseCommand.setEnabled(true)
        commandCenter.nextTrackCommand.setEnabled(true)
        commandCenter.previousTrackCommand.setEnabled(true)
        commandCenter.playCommand.addTargetWithHandler { _ ->
            onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.pauseCommand.addTargetWithHandler { _ ->
            onPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.nextTrackCommand.addTargetWithHandler { _ ->
            onNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.previousTrackCommand.addTargetWithHandler { _ ->
            onPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    fun updateNowPlaying(
        mediaItem: GenericMediaItem?,
        player: AVPlayer,
        isPlaying: Boolean,
        queueIndex: Int,
        queueCount: Int,
    ) {
        if (mediaItem == null) {
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }
        val metadata = mediaItem.mediaMetadata
        val currentSeconds = player.currentTime().let { time ->
            val seconds = platform.CoreMedia.CMTimeGetSeconds(time)
            if (seconds.isNaN()) 0.0 else seconds
        }
        val info = mutableMapOf<Any?, Any?>(
            platform.MediaPlayer.MPMediaItemPropertyTitle to metadata.title,
            platform.MediaPlayer.MPMediaItemPropertyArtist to metadata.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to NSNumber(double = currentSeconds),
            MPNowPlayingInfoPropertyPlaybackRate to NSNumber(double = if (isPlaying) 1.0 else 0.0),
            MPNowPlayingInfoPropertyPlaybackQueueIndex to NSNumber(int = queueIndex),
            MPNowPlayingInfoPropertyPlaybackQueueCount to NSNumber(int = queueCount),
        )
        metadata.artworkUri?.let { artwork ->
            info[platform.MediaPlayer.MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }
}
