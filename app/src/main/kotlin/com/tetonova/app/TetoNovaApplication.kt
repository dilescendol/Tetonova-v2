package com.tetonova.app

import android.app.Application
import com.tetonova.app.data.CoverResolver
import com.tetonova.app.data.OmdbResolver
import com.tetonova.app.data.SettingsStore
import com.tetonova.app.data.TnData
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.core.designsystem.CoverProvider

/**
 * App entry. Local-first: keeps working even when the control panel / upstream sources are
 * unreachable. Loads the bundled repo-tn registry on startup (cheap, index only); catalog
 * content is parsed lazily on first use.
 */
class TetoNovaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        TnData.init(this)
        // Offline downloads: build the Media3 DownloadManager + cache and load any persisted downloads.
        DownloadCenter.init(this)
        // Let the design system resolve real poster covers by title (Jikan/MAL).
        CoverProvider.resolve = { CoverResolver.resolve(it) }
        // OMDb (IMDB) lookups for Movie/Drama detail.
        OmdbResolver.apiKey = BuildConfig.TETONOVA_OMDB_KEY
    }
}
