package com.maxrave.data.di.loader

import com.maxrave.common.Config.SERVICE_SCOPE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val iosServiceModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

actual fun loadMediaService() {
    loadKoinModules(iosServiceModule)
}
