package fr.poptv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import fr.poptv.player.data.Http

/**
 * Chargeur d'images partagé : gros cache mémoire et disque, et les images des fournisseurs IPTV
 * (souvent servies sans en-têtes de cache) sont quand même conservées, donc rechargées sans réseau.
 */
class ITvApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory(Http.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build()
        }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()
}
