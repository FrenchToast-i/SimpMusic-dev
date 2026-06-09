package com.maxrave.data.mediaservice

import com.maxrave.common.ASC
import com.maxrave.common.CUSTOM_ORDER
import com.maxrave.common.Config.ALBUM_CLICK
import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.RADIO_CLICK
import com.maxrave.common.Config.RECOVER_TRACK_QUEUE
import com.maxrave.common.Config.SHARE
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.common.Config.VIDEO_CLICK
import com.maxrave.common.DESC
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.LOCAL_PLAYLIST_ID_SAVED_QUEUE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.TITLE
import com.maxrave.data.db.Converters
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.extension.toSongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

private val TAG = "IosMediaPlayerHandler"

class IosMediaPlayerHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val player: MediaPlayerInterface = IosPlayerAdapter(streamRepository, dataStoreManager, coroutineScope)
    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(player.currentMediaItem)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData =
        MutableStateFlow<QueueData>(
            QueueData(
                queueState = QueueData.StateSource.STATE_CREATED,
                data = QueueData.Data(),
            ),
        )
    override val queueData = _queueData.asStateFlow()

    private val _controlState =
        MutableStateFlow<ControlState>(
            ControlState(
                isPlaying = player.isPlaying,
                isShuffle = player.shuffleModeEnabled,
                repeatState =
                    when (player.repeatMode) {
                        PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
                        PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
                        else -> RepeatState.None
                    },
                isLiked = false,
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
                isCrossfading = false,
                volume = player.volume,
            ),
        )

    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingTrackState>(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState(false, 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _skipSegments: MutableStateFlow<List<SponsorSkipSegments>?> = MutableStateFlow(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex: MutableStateFlow<Int> = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var progressJob: Job? = null
    private var songEntityJob: Job? = null
    private var getSkipSegmentsJob: Job? = null
    private var getFormatJob: Job? = null
    private var getDataOfNowPlayingTrackStateJob: Job? = null

    init {
        player.addListener(this)
        _nowPlaying.value = player.currentMediaItem
        IosNowPlayingInfo.configureRemoteCommands(
            onPlay = { player.play() },
            onPause = { player.pause() },
            onNext = { player.seekToNext() },
            onPrevious = { player.seekToPrevious() },
        )

        coroutineScope.launch {
            // Synchronize control state notifications
            controlState.collectLatest {
                updateNotification()
            }
        }
        
        // Restore volume
        coroutineScope.launch {
            val vol = dataStoreManager.playerVolume.first()
            player.volume = vol
        }
    }

    private fun updateNotification() {
        val id = player.currentMediaItem?.mediaId ?: ""
        coroutineScope.launch {
            val liked = songRepository.getSongById(id).firstOrNull()?.liked ?: false
            _controlState.update { it.copy(isLiked = liked) }
            onUpdateNotification(
                listOf(
                    GenericCommandButton.Like(liked),
                    GenericCommandButton.Repeat(repeatState = _controlState.value.repeatState),
                    GenericCommandButton.Radio,
                    GenericCommandButton.Shuffle(isShuffled = _controlState.value.isShuffle),
                )
            )
        }
    }

    private fun getDataOfNowPlayingState(mediaItem: GenericMediaItem) {
        val videoId = mediaItem.mediaId
        val track = queueData.value.data.listTracks.find { it.videoId == videoId }
        
        _nowPlayingState.update {
            it.copy(
                mediaItem = mediaItem,
                track = track,
            )
        }
        _format.value = null
        _skipSegments.value = null
        
        getDataOfNowPlayingTrackStateJob?.cancel()
        getDataOfNowPlayingTrackStateJob = coroutineScope.launch {
            val songEntity = songRepository.getSongById(videoId).firstOrNull()
            if (songEntity != null) {
                _controlState.update { it.copy(isLiked = songEntity.liked) }
                songRepository.updateListenCount(videoId)
            } else {
                _controlState.update { it.copy(isLiked = false) }
                songRepository.insertSong(track?.toSongEntity() ?: mediaItem.toSongEntity())
            }
            
            val currentSong = songEntity ?: track?.toSongEntity() ?: mediaItem.toSongEntity()
            _nowPlayingState.update {
                it.copy(
                    songEntity = currentSong,
                )
            }
            
            // Start listening to the song as a flow
            songEntityJob?.cancel()
            songEntityJob = launch {
                songRepository.getSongAsFlow(videoId).filterNotNull().collectLatest { updatedSong ->
                    _nowPlayingState.update { it.copy(songEntity = updatedSong) }
                    _controlState.update { it.copy(isLiked = updatedSong.liked) }
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        _simpleMediaState.value = if (playbackState == PlayerConstants.STATE_READY) {
            SimpleMediaState.Ready(player.duration)
        } else {
            SimpleMediaState.Initial
        }
        _currentSongIndex.value = player.currentMediaItemIndex
        updateNextPreviousTrackAvailability()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _controlState.update { it.copy(isPlaying = isPlaying) }
        if (isPlaying) {
            startProgressUpdate()
        } else {
            stopProgressUpdate()
        }
        updateNextPreviousTrackAvailability()
        (player as? IosPlayerAdapter)?.let { adapter ->
            IosNowPlayingInfo.updateNowPlaying(
                mediaItem = adapter.currentMediaItem,
                player = adapter.avPlayer,
                isPlaying = isPlaying,
                queueIndex = adapter.currentMediaItemIndex,
                queueCount = adapter.mediaItemCount,
            )
        }
    }

    override fun onMediaItemTransition(mediaItem: GenericMediaItem?, reason: Int) {
        _nowPlaying.value = mediaItem
        _currentSongIndex.value = player.currentMediaItemIndex
        if (mediaItem != null) {
            getDataOfNowPlayingState(mediaItem)
        }
        updateNextPreviousTrackAvailability()
        (player as? IosPlayerAdapter)?.let { adapter ->
            IosNowPlayingInfo.updateNowPlaying(
                mediaItem = mediaItem,
                player = adapter.avPlayer,
                isPlaying = adapter.isPlaying,
                queueIndex = adapter.currentMediaItemIndex,
                queueCount = adapter.mediaItemCount,
            )
        }
    }

    private fun updateNextPreviousTrackAvailability() {
        _controlState.update {
            it.copy(
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
            )
        }
    }

    override fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                delay(200)
                _simpleMediaState.value = SimpleMediaState.Progress(player.currentPosition)
            }
        }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    override fun startBufferedUpdate() {}
    override fun stopBufferedUpdate() {}

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            is PlayerEvent.UpdateVolume -> {
                player.volume = playerEvent.newVolume
                _controlState.update { it.copy(volume = playerEvent.newVolume) }
            }
            PlayerEvent.Backward -> player.seekBack()
            PlayerEvent.Forward -> player.seekForward()
            PlayerEvent.PlayPause -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            PlayerEvent.Next -> player.seekToNext()
            PlayerEvent.Previous -> player.seekToPrevious()
            PlayerEvent.SkipToPrevious -> player.seekToPreviousMediaItem()
            PlayerEvent.Shuffle -> {
                val shuffle = !player.shuffleModeEnabled
                player.shuffleModeEnabled = shuffle
                _controlState.update { it.copy(isShuffle = shuffle) }
            }
            PlayerEvent.Repeat -> {
                val nextMode = when (player.repeatMode) {
                    PlayerConstants.REPEAT_MODE_OFF -> PlayerConstants.REPEAT_MODE_ALL
                    PlayerConstants.REPEAT_MODE_ALL -> PlayerConstants.REPEAT_MODE_ONE
                    else -> PlayerConstants.REPEAT_MODE_OFF
                }
                player.repeatMode = nextMode
                _controlState.update {
                    it.copy(
                        repeatState = when (nextMode) {
                            PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
                            PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
                            else -> RepeatState.None
                        }
                    )
                }
            }
            is PlayerEvent.UpdateProgress -> {
                val pos = (playerEvent.newProgress * player.duration).toLong()
                player.seekTo(pos)
            }
            PlayerEvent.ToggleLike -> toggleLike()
        }
    }

    override fun toggleRadio() {}

    override fun toggleLike() {
        val currentTrack = nowPlayingState.value.track ?: return
        coroutineScope.launch {
            val id = currentTrack.videoId
            val liked = songRepository.getSongById(id).firstOrNull()?.liked ?: false
            like(!liked)
        }
    }

    override fun like(liked: Boolean) {
        val currentTrack = nowPlayingState.value.track ?: return
        coroutineScope.launch {
            val id = currentTrack.videoId
            songRepository.updateLikeStatus(liked, id)
            _controlState.update { it.copy(isLiked = liked) }
        }
    }

    override fun resetSongAndQueue() {
        player.clearMediaItems()
        _queueData.value = QueueData(QueueData.StateSource.STATE_CREATED, QueueData.Data())
        _nowPlaying.value = null
        _nowPlayingState.value = NowPlayingTrackState.initial()
    }

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(false, minutes * 60)
        sleepTimerJob = coroutineScope.launch {
            var remaining = minutes * 60
            while (remaining > 0) {
                delay(1000)
                remaining--
                _sleepTimerState.value = SleepTimerState(false, remaining)
            }
            _sleepTimerState.value = SleepTimerState(true, 0)
            player.pause()
        }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(false, 0)
    }

    override fun removeMediaItem(position: Int) {
        player.removeMediaItem(position)
    }

    override fun addMediaItem(mediaItem: GenericMediaItem, playWhenReady: Boolean) {
        player.addMediaItem(mediaItem)
        if (playWhenReady) {
            player.play()
        }
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        mediaItemList.forEach { player.addMediaItem(it) }
    }

    override fun playMediaItemInMediaSource(index: Int) {
        player.seekTo(index, 0)
        player.play()
    }

    override fun currentSongIndex(): Int = player.currentMediaItemIndex

    override fun currentOrderIndex(): Int = player.currentMediaItemIndex

    override suspend fun swap(from: Int, to: Int) {
        player.moveMediaItem(from, to)
    }

    override fun resetCrossfade() {}

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val tracks = queueData.value.data.listTracks.toMutableList()
        tracks.shuffle()
        setQueueData(queueData.value.data.copy(listTracks = tracks))
        addQueueToPlayer()
    }

    override fun loadMore() {}

    override fun getRelated(videoId: String) {}

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, queueData)
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) {
        if (position > 0) {
            player.moveMediaItem(position, position - 1)
        }
    }

    override suspend fun moveItemDown(position: Int) {
        if (position < player.mediaItemCount - 1) {
            player.moveMediaItem(position, position + 1)
        }
    }

    override fun addFirstMediaItemToIndex(mediaItem: GenericMediaItem?, index: Int) {
        if (mediaItem != null) {
            player.addMediaItem(index, mediaItem)
        }
    }

    override fun reset() {
        resetSongAndQueue()
    }

    override suspend fun load(downloaded: Int, index: Int?) {
        addQueueToPlayer()
        if (index != null) {
            player.seekTo(index, 0)
        }
        player.play()
    }

    override suspend fun loadMoreCatalog(listTrack: ArrayList<Track>, isAddToQueue: Boolean) {}

    override suspend fun updateCatalog(downloaded: Int, index: Int?): Boolean = true

    override fun addQueueToPlayer() {
        player.clearMediaItems()
        val list = queueData.value.data.listTracks
        list.forEach { track ->
            player.addMediaItem(track.toGenericMediaItem())
        }
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        addQueueToPlayer()
        if (index != null) {
            player.seekTo(index, 0)
        }
        player.play()
    }

    override fun setCurrentSongIndex(index: Int) {
        player.seekTo(index, 0)
    }

    override suspend fun playNext(track: Track) {
        player.addMediaItem(player.currentMediaItemIndex + 1, track.toGenericMediaItem())
    }

    override suspend fun <T> loadMediaItem(anyTrack: T, type: String, index: Int?) {}

    override fun getPlayerDuration(): Long = player.duration

    override fun getProgress(): Long = player.currentPosition

    override fun mayBeNormalizeVolume() {}
    override fun mayBeSaveRecentSong(runBlocking: Boolean) {}
    override fun mayBeSavePlaybackState() {}
    override fun mayBeRestoreQueue() {}

    override fun shouldReleaseOnTaskRemoved(): Boolean = true

    override fun release() {
        player.release()
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        songEntityJob?.cancel()
    }
}
