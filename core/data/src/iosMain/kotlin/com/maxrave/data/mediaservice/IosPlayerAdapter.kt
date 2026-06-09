package com.maxrave.data.mediaservice

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import platform.AVFoundation.*
import platform.Foundation.*
import platform.CoreMedia.*

class IosPlayerAdapter(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val coroutineScope: CoroutineScope
) : MediaPlayerInterface {
    private val TAG = "IosPlayerAdapter"
    internal val avPlayer = AVPlayer()
    private val listeners = mutableListOf<MediaPlayerListener>()
    private val playlist = mutableListOf<GenericMediaItem>()
    private var currentItemIndex = 0
    private var _isPlaying = false
    private var resolveJob: Job? = null

    init {
        // Observe item end to auto-advance
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = null,
            usingBlock = { _ ->
                coroutineScope.launch(Dispatchers.Main) {
                    onTrackEnded()
                }
            }
        )
    }

    private fun onTrackEnded() {
        if (repeatMode == PlayerConstants.REPEAT_MODE_ONE) {
            seekTo(0)
            play()
        } else if (hasNextMediaItem()) {
            seekToNext()
        } else {
            stop()
            listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
        }
    }

    override fun play() {
        _isPlaying = true
        if (avPlayer.currentItem != null) {
            avPlayer.play()
            listeners.forEach { it.onIsPlayingChanged(true) }
        } else {
            playCurrentItem()
        }
    }

    override fun pause() {
        _isPlaying = false
        avPlayer.pause()
        listeners.forEach { it.onIsPlayingChanged(false) }
    }

    override fun stop() {
        _isPlaying = false
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        listeners.forEach { it.onIsPlayingChanged(false) }
    }

    private fun playCurrentItem() {
        val mediaItem = getMediaItemAt(currentItemIndex) ?: return
        resolveJob?.cancel()
        listeners.forEach { it.onIsLoadingChanged(true) }
        resolveJob = coroutineScope.launch {
            try {
                Logger.d(TAG, "Resolving URL for videoId: ${mediaItem.mediaId}")
                val streamUrl = streamRepository.getStream(
                    dataStoreManager = dataStoreManager,
                    videoId = mediaItem.mediaId,
                    isDownloading = false,
                    isVideo = false
                ).first()
                
                if (streamUrl != null) {
                    withContext(Dispatchers.Main) {
                        Logger.d(TAG, "Playing URL: $streamUrl")
                        val url = NSURL.URLWithString(streamUrl) ?: return@withContext
                        val playerItem = AVPlayerItem.playerItemWithURL(url)
                        avPlayer.replaceCurrentItemWithPlayerItem(playerItem)
                        if (_isPlaying) {
                            avPlayer.play()
                        }
                        listeners.forEach { 
                            it.onMediaItemTransition(mediaItem, 0)
                            it.onIsPlayingChanged(_isPlaying)
                            it.onIsLoadingChanged(false)
                        }
                    }
                } else {
                    Logger.e(TAG, "Failed to resolve stream URL")
                    listeners.forEach { 
                        it.onIsLoadingChanged(false)
                        it.onPlayerError(PlayerError(0, "Failed to resolve stream URL"))
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error resolving stream URL: ${e.message}")
                listeners.forEach { 
                    it.onIsLoadingChanged(false)
                    it.onPlayerError(PlayerError(0, e.message ?: "Unknown error"))
                }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val time = CMTimeMakeWithSeconds(positionMs / 1000.0, 1000)
        avPlayer.seekToTime(time)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex in playlist.indices) {
            currentItemIndex = mediaItemIndex
            playCurrentItem()
            if (positionMs > 0) {
                seekTo(positionMs)
            }
        }
    }

    override fun seekBack() {
        val current = currentPosition
        seekTo((current - 10000).coerceAtLeast(0))
    }

    override fun seekForward() {
        val current = currentPosition
        val dur = duration
        seekTo((current + 10000).coerceAtMost(dur))
    }

    override fun seekToNext() {
        if (hasNextMediaItem()) {
            currentItemIndex++
            playCurrentItem()
        }
    }

    override fun seekToPrevious() {
        if (currentPosition > 3000 || !hasPreviousMediaItem()) {
            seekTo(0)
        } else {
            currentItemIndex--
            playCurrentItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) {
            currentItemIndex--
            playCurrentItem()
        }
    }

    override fun prepare() {
        // AVPlayer prepares automatically on setItem
    }

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        playlist.clear()
        playlist.add(mediaItem)
        currentItemIndex = 0
        listeners.forEach { it.onTimelineChanged(playlist, "setMediaItem") }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)
        listeners.forEach { it.onTimelineChanged(playlist, "addMediaItem") }
    }

    override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) {
        playlist.add(index, mediaItem)
        listeners.forEach { it.onTimelineChanged(playlist, "addMediaItem") }
    }

    override fun removeMediaItem(index: Int) {
        if (index in playlist.indices) {
            playlist.removeAt(index)
            listeners.forEach { it.onTimelineChanged(playlist, "removeMediaItem") }
        }
    }

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex in playlist.indices && toIndex in playlist.indices) {
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)
            listeners.forEach { it.onTimelineChanged(playlist, "moveMediaItem") }
        }
    }

    override fun clearMediaItems() {
        playlist.clear()
        listeners.forEach { it.onTimelineChanged(playlist, "clearMediaItems") }
    }

    override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) {
        if (index in playlist.indices) {
            playlist[index] = mediaItem
            listeners.forEach { it.onTimelineChanged(playlist, "replaceMediaItem") }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = playlist.toList()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex

    override val isPlaying: Boolean
        get() = _isPlaying

    override val currentPosition: Long
        get() {
            val time = avPlayer.currentTime()
            val sec = CMTimeGetSeconds(time)
            return if (sec.isNaN()) 0L else (sec * 1000).toLong()
        }

    override val duration: Long
        get() {
            val item = avPlayer.currentItem ?: return 0L
            val time = item.duration
            val sec = CMTimeGetSeconds(time)
            return if (sec.isNaN()) 0L else (sec * 1000).toLong()
        }

    override val bufferedPosition: Long
        get() = currentPosition // fallback

    override val bufferedPercentage: Int
        get() = 100

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(currentItemIndex)

    override val currentMediaItemIndex: Int
        get() = currentItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = currentPosition

    override val playbackState: Int
        get() = if (isPlaying) PlayerConstants.STATE_READY else PlayerConstants.STATE_IDLE

    override fun hasNextMediaItem(): Boolean = currentItemIndex < playlist.size - 1

    override fun hasPreviousMediaItem(): Boolean = currentItemIndex > 0

    override var shuffleModeEnabled = false
    override var repeatMode = PlayerConstants.REPEAT_MODE_OFF
    override var playWhenReady = true
    override var playbackParameters = GenericPlaybackParameters(1f, 1f)
    override val audioSessionId = 0
    
    override var volume: Float
        get() = avPlayer.volume
        set(value) {
            avPlayer.volume = value
        }
        
    override var skipSilenceEnabled = false

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        stop()
    }
}
