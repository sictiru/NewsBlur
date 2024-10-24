package com.newsblur.activity

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.util.FeedSet
import com.newsblur.util.NBScope
import com.newsblur.util.PrefsUtils
import com.newsblur.util.UIUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ItemsListKt {

    @JvmStatic
    fun autoOpenFirstUnreadAsync(
            context: Context,
            dbHelper: BlurDatabaseHelper,
            fs: FeedSet,
            readingActivityLaunch: ActivityResultLauncher<Intent>,
    ) {
        NBScope.launch {
            val intelState = PrefsUtils.getStateFilter(context)
            if (dbHelper.getUnreadCount(fs, intelState) > 0) {
                withContext(Dispatchers.Main) {
                    UIUtils.startReadingActivity(context, fs, Reading.FIND_FIRST_UNREAD, readingActivityLaunch)
                }
            }
        }
    }
}