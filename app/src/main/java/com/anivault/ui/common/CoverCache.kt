package com.anivault.ui.common

import android.content.Context
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest

/** Warms and clears the cover image cache. */
object CoverCache {
    /**
     * Queues background downloads (and decodes) so covers are already in memory when the user scrolls to them.
     * When [allowDiskCache] is false (H-Vault), images are decoded into memory only and never written to disk.
     */
    fun preload(ctx: Context, urls: Collection<String>, allowDiskCache: Boolean = true) {
        val loader = Coil.imageLoader(ctx)
        val diskPolicy = if (allowDiskCache) CachePolicy.ENABLED else CachePolicy.DISABLED
        urls.asSequence().filter { it.isNotBlank() }.distinct().forEach { u ->
            loader.enqueue(
                ImageRequest.Builder(ctx).data(u).size(300, 430)
                    .diskCachePolicy(diskPolicy)
                    .build()
            )
        }
    }

    /** Deletes every cached cover (memory and disk) and downloads them again once. */
    fun refresh(ctx: Context, urls: Collection<String>) {
        val loader = Coil.imageLoader(ctx)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        urls.asSequence().filter { it.isNotBlank() }.distinct().forEach { u ->
            loader.enqueue(
                ImageRequest.Builder(ctx).data(u).size(300, 430)
                    .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).networkCachePolicy(CachePolicy.ENABLED).build()
            )
        }
    }
}
