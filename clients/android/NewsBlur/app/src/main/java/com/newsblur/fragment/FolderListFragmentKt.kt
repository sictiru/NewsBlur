package com.newsblur.fragment

import android.content.Context
import android.content.Intent
import com.newsblur.R
import com.newsblur.activity.AllSharedStoriesItemsList
import com.newsblur.activity.AllStoriesItemsList
import com.newsblur.activity.FeedItemsList
import com.newsblur.activity.FolderItemsList
import com.newsblur.activity.GlobalSharedStoriesItemsList
import com.newsblur.activity.InfrequentItemsList
import com.newsblur.activity.ItemsList
import com.newsblur.activity.NbActivity
import com.newsblur.activity.ReadStoriesItemsList
import com.newsblur.activity.SavedStoriesItemsList
import com.newsblur.activity.SocialFeedItemsList
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.FolderListAdapter
import com.newsblur.domain.Feed
import com.newsblur.domain.SavedSearch
import com.newsblur.util.FeedSet
import com.newsblur.util.FeedUtils
import com.newsblur.util.NBScope
import com.newsblur.util.PrefsUtils
import com.newsblur.util.Session
import com.newsblur.util.SessionDataSource
import com.newsblur.util.StateFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object FolderListFragmentKt {

    @JvmStatic
    fun markFeedAsReadAsync(
            activity: NbActivity,
            feedUtils: FeedUtils,
            adapter: FolderListAdapter,
            groupPosition: Int,
            childPosition: Int,
    ) {
        NBScope.launch {
            val fs = adapter.getChild(groupPosition, childPosition)
            feedUtils.markRead(activity, fs, null, null, R.array.mark_all_read_options)
            withContext(Dispatchers.Main) {
                adapter.lastFeedViewedId = fs.singleFeed
                adapter.lastFolderViewed = fs.folderName

            }
        }
    }

    @JvmStatic
    fun markFolderAsReadAsync(
            activity: NbActivity,
            feedUtils: FeedUtils,
            adapter: FolderListAdapter,
            groupPosition: Int,
    ) {
        NBScope.launch {
            val fs = adapter.getGroup(groupPosition)
            feedUtils.markRead(activity, fs, null, null, R.array.mark_all_read_options)
            withContext(Dispatchers.Main) {
                adapter.lastFeedViewedId = fs.singleFeed
                adapter.lastFolderViewed = fs.folderName

            }
        }
    }

    @JvmStatic
    fun openSavedSearchAsync(
            activity: NbActivity,
            dbHelper: BlurDatabaseHelper,
            feedUtils: FeedUtils,
            savedSearch: SavedSearch,
    ) {
        NBScope.launch {
            var intent: Intent? = null
            var fs: FeedSet? = null
            val feedId = savedSearch.feedId
            if (feedId == "river:") {
                // all site stories
                intent = Intent(activity, AllStoriesItemsList::class.java)
                fs = FeedSet.allFeeds()
            } else if (feedId == "river:infrequent") {
                // infrequent stories
                intent = Intent(activity, InfrequentItemsList::class.java)
                fs = FeedSet.infrequentFeeds()
            } else if (feedId.startsWith("river:")) {
                intent = Intent(activity, FolderItemsList::class.java)
                val folderName = feedId.replace("river:", "")
                fs = dbHelper.feedSetFromFolderName(folderName)
                intent.putExtra(FolderItemsList.EXTRA_FOLDER_NAME, folderName)
            } else if (feedId == "read") {
                intent = Intent(activity, ReadStoriesItemsList::class.java)
                fs = FeedSet.allRead()
            } else if (feedId == "starred") {
                intent = Intent(activity, SavedStoriesItemsList::class.java)
                fs = FeedSet.allSaved()
            } else if (feedId.startsWith("starred:")) {
                intent = Intent(activity, SavedStoriesItemsList::class.java)
                fs = FeedSet.singleSavedTag(feedId.replace("starred:", ""))
            } else if (feedId.startsWith("feed:")) {
                intent = Intent(activity, FeedItemsList::class.java)
                val cleanFeedId = feedId.replace("feed:", "")
                val feed = feedUtils.getFeed(cleanFeedId)
                fs = FeedSet.singleFeed(cleanFeedId)
                intent.putExtra(FeedItemsList.EXTRA_FEED, feed)
            } else if (feedId.startsWith("social:")) {
                intent = Intent(activity, SocialFeedItemsList::class.java)
                val cleanFeedId = feedId.replace("social:", "")
                fs = FeedSet.singleFeed(cleanFeedId)
                val feed = feedUtils.getFeed(cleanFeedId)
                intent.putExtra(FeedItemsList.EXTRA_FEED, feed)
            }

            if (intent != null) {
                withContext(Dispatchers.Main) {
                    fs?.searchQuery = savedSearch.query
                    intent.putExtra(ItemsList.EXTRA_FEED_SET, fs)
                    activity.startActivity(intent)
                }
            }
        }
    }

    @JvmStatic
    fun getGroupBlocking(
            adapter: FolderListAdapter,
            dbHelper: BlurDatabaseHelper,
            currentState: StateFilter,
            groupPosition: Int,
    ): FeedSet = runBlocking {
        if (adapter.isRowGlobalSharedStories(groupPosition)) {
            FeedSet.globalShared()
        } else if (adapter.isRowAllSharedStories(groupPosition)) {
            FeedSet.allSocialFeeds()
        } else if (adapter.isRowAllStories(groupPosition)) {
            if (currentState == StateFilter.SAVED) FeedSet.allSaved()
            else FeedSet.allFeeds()
        } else if (adapter.isRowInfrequentStories(groupPosition)) {
            FeedSet.infrequentFeeds()
        } else if (adapter.isRowReadStories(groupPosition)) {
            FeedSet.allRead()
        } else if (adapter.isRowSavedStories(groupPosition)) {
            FeedSet.allSaved()
        } else {
            val folderName: String = adapter.getGroupFolderName(groupPosition)
            val fs: FeedSet = dbHelper.feedSetFromFolderName(folderName)
            if (currentState == StateFilter.SAVED) fs.isFilterSaved = true
            fs
        }
    }

    @JvmStatic
    fun onGroupClickAsync(
            activity: NbActivity,
            adapter: FolderListAdapter,
            currentState: StateFilter,
            groupPosition: Int,
    ) {
        NBScope.launch {
            val fs: FeedSet = adapter.getGroup(groupPosition)
            val i: Intent
            if (adapter.isRowAllStories(groupPosition)) {
                i = if (currentState == StateFilter.SAVED) {
                    // the existence of this row in saved mode is something of a framework artifact and may
                    // confuse users. redirect them to the activity corresponding to what they will actually see
                    Intent(activity, SavedStoriesItemsList::class.java)
                } else {
                    Intent(activity, AllStoriesItemsList::class.java)
                }
            } else if (adapter.isRowGlobalSharedStories(groupPosition)) {
                i = Intent(activity, GlobalSharedStoriesItemsList::class.java)
            } else if (adapter.isRowAllSharedStories(groupPosition)) {
                i = Intent(activity, AllSharedStoriesItemsList::class.java)
            } else if (adapter.isRowInfrequentStories(groupPosition)) {
                i = Intent(activity, InfrequentItemsList::class.java)
            } else if (adapter.isRowReadStories(groupPosition)) {
                i = Intent(activity, ReadStoriesItemsList::class.java)
            } else if (adapter.isRowSavedStories(groupPosition)) {
                i = Intent(activity, SavedStoriesItemsList::class.java)
            } else {
                i = Intent(activity, FolderItemsList::class.java)
                val canonicalFolderName: String = adapter.getGroupFolderName(groupPosition)
                val sessionDataSource: SessionDataSource? = getSessionData(
                        activity,
                        adapter,
                        fs,
                        canonicalFolderName,
                        null,
                )
                i.putExtra(FolderItemsList.EXTRA_FOLDER_NAME, canonicalFolderName)
                i.putExtra(ItemsList.EXTRA_SESSION_DATA, sessionDataSource)
                withContext(Dispatchers.Main) {
                    adapter.lastFeedViewedId = null
                    adapter.lastFolderViewed = canonicalFolderName
                }
            }
            i.putExtra(ItemsList.EXTRA_FEED_SET, fs)
            withContext(Dispatchers.Main) {
                activity.startActivity(i)
            }
        }
    }

    @JvmStatic
    fun getSessionData(
            context: Context,
            adapter: FolderListAdapter,
            fs: FeedSet,
            folderName: String?,
            feed: Feed?,
    ): SessionDataSource? {
        if (PrefsUtils.loadNextOnMarkRead(context)) {
            val activeSession = Session(fs, folderName, feed)
            return adapter.buildSessionDataSource(activeSession)
        }
        return null
    }
}