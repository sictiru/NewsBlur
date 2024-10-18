package com.newsblur.service

import com.newsblur.util.AppConstants
import com.newsblur.util.Log
import com.newsblur.util.NBScope
import com.newsblur.util.PrefsUtils
import java.util.Collections

class ImagePrefetchService(
        parent: NBSyncService,
) : SubService(parent, NBScope) {

    override suspend fun exec() {
        activelyRunning = true
        try {
            if (!PrefsUtils.isImagePrefetchEnabled(parent)) return
            if (!PrefsUtils.isBackgroundNetworkAllowed(parent)) return

            while (storyImageQueue.size > 0) {
                if (!PrefsUtils.isImagePrefetchEnabled(parent)) return
                if (!PrefsUtils.isBackgroundNetworkAllowed(parent)) return

                Log.d(this, "story images to prefetch: " + storyImageQueue.size)
                // on each batch, re-query the DB for images associated with yet-unread stories
                // this is a bit expensive, but we are running totally async at a really low priority
                val unreadImages = parent.dbHelper.getAllStoryImages()
                val fetchedImages: MutableSet<String> = HashSet()
                val batch: MutableSet<String> = HashSet(AppConstants.IMAGE_PREFETCH_BATCH_SIZE)
                batchloop@ for (url in storyImageQueue) {
                    batch.add(url)
                    if (batch.size >= AppConstants.IMAGE_PREFETCH_BATCH_SIZE) break@batchloop
                }
                try {
                    fetchloop@ for (url in batch) {
                        if (parent.stopSync()) break@fetchloop
                        // dont fetch the image if the associated story was marked read before we got to it
                        if (unreadImages.contains(url)) {
                            if (AppConstants.VERBOSE_LOG) android.util.Log.d(this.javaClass.name, "prefetching image: $url")
                            parent.storyImageCache.cacheFile(url)
                        }
                        fetchedImages.add(url)
                    }
                } finally {
                    storyImageQueue.removeAll(fetchedImages)
                    Log.d(this, "story images fetched: " + fetchedImages.size)
                }
            }

            if (parent.stopSync()) return

            while (thumbnailQueue.size > 0) {
                if (!PrefsUtils.isImagePrefetchEnabled(parent)) return
                if (!PrefsUtils.isBackgroundNetworkAllowed(parent)) return

                Log.d(this, "story thumbs to prefetch: " + storyImageQueue.size)
                // on each batch, re-query the DB for images associated with yet-unread stories
                // this is a bit expensive, but we are running totally async at a really low priority
                val unreadImages = parent.dbHelper.getAllStoryThumbnails()
                val fetchedImages: MutableSet<String> = HashSet()
                val batch: MutableSet<String> = HashSet(AppConstants.IMAGE_PREFETCH_BATCH_SIZE)
                batchloop@ for (url in thumbnailQueue) {
                    batch.add(url)
                    if (batch.size >= AppConstants.IMAGE_PREFETCH_BATCH_SIZE) break@batchloop
                }
                try {
                    fetchloop@ for (url in batch) {
                        if (parent.stopSync()) break@fetchloop
                        // dont fetch the image if the associated story was marked read before we got to it
                        if (unreadImages.contains(url)) {
                            if (AppConstants.VERBOSE_LOG) android.util.Log.d(this.javaClass.name, "prefetching thumbnail: $url")
                            parent.thumbnailCache.cacheFile(url)
                        }
                        fetchedImages.add(url)
                    }
                } finally {
                    thumbnailQueue.removeAll(fetchedImages)
                    Log.d(this, "story thumbs fetched: " + fetchedImages.size)
                }
            }
        } finally {
            activelyRunning = false
        }
    }

    fun addUrl(url: String) {
        storyImageQueue.add(url)
    }

    fun addThumbnailUrl(url: String) {
        thumbnailQueue.add(url)
    }

    companion object {

        @JvmField
        var activelyRunning: Boolean = false

        /** URLs of images contained in recently fetched stories that are candidates for prefetch.  */
        private val storyImageQueue: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** URLs of thumbnails for recently fetched stories that are candidates for prefetch.  */
        private val thumbnailQueue: MutableSet<String> = Collections.synchronizedSet(HashSet())

        @JvmStatic
        val pendingCount: Int
            get() = (storyImageQueue.size + thumbnailQueue.size)

        @JvmStatic
        fun clear() {
            storyImageQueue.clear()
            thumbnailQueue.clear()
        }
    }
}

