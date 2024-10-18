package com.newsblur.activity

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.newsblur.util.UIUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedReading : Reading() {

    override fun onCreate(savedInstanceBundle: Bundle?) {
        super.onCreate(savedInstanceBundle)

        if (fs == null) {
            // if the activity got launch with a missing FeedSet, it will be in the process of cancelling
            return
        }
        lifecycleScope.launch {
            val feed = dbHelper.getFeed(fs!!.singleFeed)
            withContext(Dispatchers.Main) {
                if (feed == null) {
                    // if this is somehow an intent so stale that the feed no longer exists, bail.
                    finish()
                    return@withContext
                }

                UIUtils.setupToolbar(this@FeedReading, feed.faviconUrl, feed.title, iconLoader, false)
            }
        }
    }
}
