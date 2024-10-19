package com.newsblur.service

import com.newsblur.util.Log
import com.newsblur.util.NBScope
import com.newsblur.util.PrefConstants
import com.newsblur.util.PrefsUtils

class CleanupService(
        parent: NBSyncService,
) : SubService(parent, NBScope) {

    override suspend fun exec() {
        if (!PrefsUtils.isTimeToCleanup(parent)) return

        activelyRunning = true

        Log.d(this.javaClass.name, "cleaning up old stories")
        parent.dbHelper.cleanupVeryOldStories()
        if (!PrefsUtils.isKeepOldStories(parent)) {
            parent.dbHelper.cleanupReadStories()
        }
        PrefsUtils.updateLastCleanupTime(parent)

        Log.d(this.javaClass.name, "cleaning up old story texts")
        parent.dbHelper.cleanupStoryText()

        Log.d(this.javaClass.name, "cleaning up notification dismissals")
        parent.dbHelper.cleanupDismissals()

        Log.d(this.javaClass.name, "cleaning up story image cache")
        parent.storyImageCache.cleanupUnusedAndOld(parent.dbHelper.getAllStoryImages(), PrefsUtils.getMaxCachedAgeMillis(parent))

        Log.d(this.javaClass.name, "cleaning up icon cache")
        parent.iconCache.cleanupOld(PrefConstants.CACHE_AGE_VALUE_30D)

        Log.d(this.javaClass.name, "cleaning up thumbnail cache")
        parent.thumbnailCache.cleanupUnusedAndOld(parent.dbHelper.getAllStoryThumbnails(), PrefsUtils.getMaxCachedAgeMillis(parent))

        activelyRunning = false
    }

    companion object {

        @JvmField
        var activelyRunning: Boolean = false
    }
}

