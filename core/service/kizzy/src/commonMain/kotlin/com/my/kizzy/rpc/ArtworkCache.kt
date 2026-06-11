package com.my.kizzy.rpc

internal object ArtworkCache {
    private val cache = mutableMapOf<String, String>()

    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        return fetch()?.also { result ->
            synchronized(cache) {
                cache[key] = result
            }
        }
    }
}
