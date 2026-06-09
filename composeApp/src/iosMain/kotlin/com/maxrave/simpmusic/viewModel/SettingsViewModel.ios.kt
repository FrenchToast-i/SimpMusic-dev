package com.maxrave.simpmusic.viewModel

import com.eygraber.uri.Uri
import com.maxrave.data.db.documentDirectory
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults

actual suspend fun calculateDataFraction(cacheRepository: CacheRepository): SettingsStorageSectionFraction? = null

actual suspend fun restoreNative(
    commonRepository: CommonRepository,
    uri: Uri,
    getData: () -> Unit,
) {
    withContext(Dispatchers.Main) {
        showToast("Backup restore is not yet supported on iOS", ToastGravity.Bottom)
    }
}

actual suspend fun backupNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
) {
    withContext(Dispatchers.Main) {
        showToast("Backup export is not yet supported on iOS", ToastGravity.Bottom)
    }
}

actual fun getPackageName(): String = "com.maxrave.simpmusic"

actual fun getFileDir(): String = documentDirectory()

actual fun changeLanguageNative(code: String) {
    val localeCode = if (code == "id-ID") "in-ID" else code
    NSUserDefaults.standardUserDefaults.setObject(listOf(localeCode), forKey = "AppleLanguages")
    NSUserDefaults.standardUserDefaults.synchronize()
    NSLocale.localeWithLocaleIdentifier(localeCode)
}
