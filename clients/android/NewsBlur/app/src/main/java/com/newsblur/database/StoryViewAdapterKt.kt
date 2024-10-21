package com.newsblur.database

import android.content.Context
import com.newsblur.activity.FeedItemsList
import com.newsblur.domain.Story
import com.newsblur.util.FeedSet
import com.newsblur.util.FeedUtils
import com.newsblur.util.NBScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StoryViewAdapterKt {

    @JvmStatic
    fun markReadAsync(
            feedUtils: FeedUtils,
            context: Context,
            fs: FeedSet,
            olderThan: Long?,
            newerThan: Long?,
            choicesRid: Int,
    ) {
        NBScope.launch(Dispatchers.Main) {
            markReadAsync(
                    feedUtils = feedUtils,
                    context = context,
                    fs = fs,
                    olderThan = olderThan,
                    newerThan = newerThan,
                    choicesRid = choicesRid,
            )
        }
    }

    @JvmStatic
    fun goToFeedAsync(
            feedUtils: FeedUtils,
            context: Context,
            story: Story,
    ) {
        NBScope.launch {
            val fs = FeedSet.singleFeed(story.feedId)
            val feed = feedUtils.getFeed(story.feedId)
            withContext(Dispatchers.Main) {
                FeedItemsList.startActivity(context, fs, feed, null, null)
            }
        }
    }
}