package com.newsblur.service

import com.newsblur.network.domain.StoriesResponse
import com.newsblur.util.AppConstants
import com.newsblur.util.FeedUtils.Companion.inferFeedId
import com.newsblur.util.Log
import com.newsblur.util.NBScope
import com.newsblur.util.PrefsUtils
import com.newsblur.util.StoryOrder
import java.util.Collections
import kotlin.concurrent.Volatile

class UnreadsService(
        parent: NBSyncService,
) : SubService(parent, NBScope) {

    override suspend fun exec() {
        activelyRunning = true
        try {
            if (isDoMetadata) {
                syncUnreadList()
                isDoMetadata = false
            }

            if (storyHashQueue.size > 0) {
                getNewUnreadStories()
                parent.pushNotifications()
            }
        } finally {
            activelyRunning = false
        }
    }

    private suspend fun syncUnreadList() {
        if (parent.stopSync()) return

        // get unread hashes and dates from the API
        val unreadHashes = parent.apiManager.unreadStoryHashes

        if (parent.stopSync()) return

        // get all the stories we thought were unread before. we should not enqueue a fetch of
        // stories we already have.  also, if any existing unreads fail to appear in
        // the set of unreads from the API, we will mark them as read. note that this collection
        // will be searched many times for new unreads, so it should be a Set, not a List.
        val oldUnreadHashes = parent.dbHelper.getUnreadStoryHashesAsSet().toMutableSet()
        Log.i(this, "starting unread count: " + oldUnreadHashes.size)

        // a place to store and then sort unread hashes we aim to fetch. note the member format
        // is made to match the format of the API response (a list of [hash, date] tuples). it
        // is crucial that we re-use objects as much as possible to avoid memory churn
        val sortationList: MutableList<Array<String>> = ArrayList()

        // process the api response, both bookkeeping no-longer-unread stories and populating
        // the sortation list we will use to create the fetch list for step two
        var count = 0
        feedloop@ for ((feedId, value) in unreadHashes.unreadHashes) {
            // the API gives us a list of unreads, split up by feed ID. the unreads are tuples of
            // story hash and date
            // ignore unreads from orphaned feeds
            if (parent.orphanFeedIds.contains(feedId)) continue@feedloop
            // ignore unreads from disabled feeds
            if (parent.disabledFeedIds.contains(feedId)) continue@feedloop
            for (newUnread in value) {
                // only fetch the reported unreads if we don't already have them
                if (!oldUnreadHashes.contains(newUnread[0])) {
                    sortationList.add(newUnread)
                } else {
                    oldUnreadHashes.remove(newUnread[0])
                }
                count++
            }
        }
        Log.i(this, "new unread count:      $count")
        Log.i(this, "new unreads found:     " + sortationList.size)
        Log.i(this, "unreads to retire:     " + oldUnreadHashes.size)

        // any stories that we previously thought to be unread but were not found in the
        // list, mark them read now
        parent.dbHelper.markStoryHashesRead(oldUnreadHashes)

        if (parent.stopSync()) return

        // now sort the unreads we need to fetch so they are fetched roughly in the order
        // the user is likely to read them.  if the user reads newest first, those come first.
        val sortNewest = (PrefsUtils.getDefaultStoryOrder(parent) == StoryOrder.NEWEST)
        // custom comparator that understands to sort tuples by the value of the second element
        val hashSorter: Comparator<Array<String>> = object : Comparator<Array<String>> {
            override fun compare(lhs: Array<String>, rhs: Array<String>): Int {
                // element [1] of the unread tuple is the date in epoch seconds
                return if (sortNewest) {
                    rhs[1].compareTo(lhs[1])
                } else {
                    lhs[1].compareTo(rhs[1])
                }
            }

            override fun equals(other: Any?): Boolean {
                return false
            }
        }
        Collections.sort(sortationList, hashSorter)

        // now that we have the sorted set of hashes, turn them into a list over which we 
        // can iterate to fetch them
        storyHashQueue.clear()
        for (tuple in sortationList) {
            // element [0] of the tuple is the story hash, the rest can safely be thown out
            storyHashQueue.add(tuple[0])
        }
    }

    private suspend fun getNewUnreadStories() {
        val notifyFeeds: Set<String?> = parent.dbHelper.getNotifyFeeds()
        unreadsyncloop@ while (storyHashQueue.size > 0) {
            if (parent.stopSync()) break@unreadsyncloop

            val isOfflineEnabled = PrefsUtils.isOfflineEnabled(parent)
            val isEnableNotifications = PrefsUtils.isEnableNotifications(parent)
            val isTextPrefetchEnabled = PrefsUtils.isTextPrefetchEnabled(parent)
            if (!(isOfflineEnabled || isEnableNotifications)) return

            val hashBatch: MutableList<String> = ArrayList(AppConstants.UNREAD_FETCH_BATCH_SIZE)
            val hashSkips: MutableList<String> = ArrayList(AppConstants.UNREAD_FETCH_BATCH_SIZE)
            batchloop@ for (hash in storyHashQueue) {
                if (isOfflineEnabled ||
                        (isEnableNotifications && notifyFeeds.contains(inferFeedId(hash)))) {
                    hashBatch.add(hash)
                } else {
                    hashSkips.add(hash)
                }
                if (hashBatch.size >= AppConstants.UNREAD_FETCH_BATCH_SIZE) break@batchloop
            }
            val response = parent.apiManager.getStoriesByHash(hashBatch)
            if (!isStoryResponseGood(response)) {
                Log.e(this, "error fetching unreads batch, abandoning sync.")
                break@unreadsyncloop
            }

            val stateFilter = PrefsUtils.getStateFilter(parent)
            parent.insertStories(response, stateFilter)
            for (hash in hashBatch) {
                storyHashQueue.remove(hash)
            }
            for (hash in hashSkips) {
                storyHashQueue.remove(hash)
            }

            if (isTextPrefetchEnabled) {
                parent.prefetchOriginalText(response)
            }
            parent.prefetchImages(response)
        }
    }

    private fun isStoryResponseGood(response: StoriesResponse?): Boolean {
        if (response == null) {
            Log.e(this, "Null response received while loading stories.")
            return false
        }
        if (response.stories == null) {
            Log.e(this, "Null stories member received while loading stories.")
            return false
        }
        return true
    }

    companion object {

        @JvmField
        var activelyRunning: Boolean = false

        @Volatile
        var isDoMetadata: Boolean = false
            private set

        /** Unread story hashes the API listed that we do not appear to have locally yet.  */
        private val storyHashQueue: MutableList<String> = ArrayList()

        @JvmStatic
        fun clear() {
            storyHashQueue.clear()
        }

        @JvmStatic
        val pendingCount: String
            /**
             * Describe the number of unreads left to be synced or return an empty message (space padded).
             */
            get() {
                val c = storyHashQueue.size
                return if (c < 1) {
                    " "
                } else {
                    " $c "
                }
            }

        @JvmStatic
        fun doMetadata() {
            isDoMetadata = true
        }
    }
}

