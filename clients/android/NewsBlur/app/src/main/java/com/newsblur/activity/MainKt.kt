package com.newsblur.activity

import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.service.NBSyncService
import com.newsblur.util.NBScope
import kotlinx.coroutines.launch

object MainKt {

    @JvmStatic
    fun resetReadingSessionAndFlushRecountsAsync(dbHelper: BlurDatabaseHelper) {
        NBScope.launch {
            NBSyncService.resetReadingSession(dbHelper)
            NBSyncService.flushRecounts()
        }
    }
}