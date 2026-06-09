package com.maxrave.simpmusic

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.simpmusic.di.viewModelModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import multiplatform.network.cmptoast.ToastHost
import okio.FileSystem
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.loadKoinModules

private var isKoinInitialized = false

private fun initKoin() {
    if (isKoinInitialized || GlobalContext.getOrNull() != null) {
        isKoinInitialized = true
        return
    }
    startKoin {
        loadAllModules()
        loadKoinModules(viewModelModule)
    }
    isKoinInitialized = true
}

@Composable
private fun IosRoot() {
    val context = LocalPlatformContext.current
    val httpClient = HttpClient(Darwin.create())
    setSingletonImageLoaderFactory {
        ImageLoader
            .Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
            }.diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCache(
                DiskCache
                    .Builder()
                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build(),
            ).crossfade(true)
            .build()
    }
    App()
    ToastHost()
}

fun MainViewController() =
    ComposeUIViewController {
        initKoin()
        IosRoot()
    }
