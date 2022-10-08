package com.newsblur.util

import com.newsblur.domain.Feed
import java.io.Serializable

private val invalidMarkAllReadFolderKeys by lazy {
    setOf(
            AppConstants.GLOBAL_SHARED_STORIES_GROUP_KEY,
            AppConstants.ALL_SHARED_STORIES_GROUP_KEY,
            AppConstants.ALL_STORIES_GROUP_KEY,
            AppConstants.INFREQUENT_SITE_STORIES_GROUP_KEY,
            AppConstants.READ_STORIES_GROUP_KEY,
            AppConstants.SAVED_STORIES_GROUP_KEY,
            AppConstants.SAVED_SEARCHES_GROUP_KEY,
    )
}

// document assumption
private fun List<String>.zipFilteredFolderFeed(foldersChildren: List<List<Feed>>): Map<String, List<Feed>> {
    val first = this.iterator()
    val second = foldersChildren.iterator()
    return buildMap {
        while (first.hasNext() && second.hasNext()) {
            val folderName = first.next()
//            if (!invalidMarkAllReadFolderKeys.contains(folderName)) {
            this[folderName] = second.next()
//            }
        }
    }
}

private fun Feed.toFeedSet() = FeedSet.singleFeed(this.feedId).apply {
    isMuted = !this@toFeedSet.active
}

class ReadingSession(
        private val folders: List<String>,
        private val foldersChildrenMap: Map<String, List<Feed>>
) : Serializable {

    private lateinit var session: Session

    constructor(
            activeSession: Session,
            folders: List<String>,
            foldersChildren: List<List<Feed>>,
    ) : this(
            folders = folders,
            foldersChildrenMap = folders.zipFilteredFolderFeed(foldersChildren)) {
        this.session = activeSession
    }

//    constructor(
//            activeSession: Session,
//            folders: List<String>,
//            foldersChildren: List<List<Feed>>,
//    ) : this(folderFeedMap = folders.zipFilteredFolderFeed(foldersChildren)) {
//        this.session = activeSession
//    }
//
//    constructor(
//            activeFeedSet: FeedSet,
//            activeFolderName: String?,
//            activeFeed: Feed?,
//            folders: List<String>,
//            foldersChildren: List<List<Feed>>,
//    ) : this(folderFeedMap = folders.zipFilteredFolderFeed(foldersChildren)) {
//        session = Session(
//                feedSet = activeFeedSet,
//                feed = activeFeed,
//                folderName = activeFolderName,
//        )
//    }

    // when folder
    // get next folder -> get folder name. get value from map. map value into FeedSet

    // when non folder
    // get next feed, create feedSet and set the session

    private fun getNextFolderFeed(feed: Feed, folderName: String): Feed? {
        val cleanFolderName =
                // ROOT FOLDER maps to ALL_STORIES_GROUP_KEY
                if (folderName == AppConstants.ROOT_FOLDER)
                    AppConstants.ALL_STORIES_GROUP_KEY
                else folderName
        val folderFeeds = foldersChildrenMap[cleanFolderName]
        return folderFeeds?.let { feeds ->
            val feedIndex = feeds.indexOf(feed)
            if (feedIndex == -1) return null // invalid feed

            val nextFeedIndex = when (feedIndex) {
                feeds.size - 1 -> null // null feed if EOL
                in feeds.indices -> feedIndex + 1 // next feed
                else -> null // no valid feed found
            }

            nextFeedIndex?.let { feeds[it] }
        }
    }

    private fun getNextNonEmptyFolder(folderName: String): Pair<String, List<Feed>>? = with(folders.indexOf(folderName)) {
        val nextIndex = if (this == folders.size - 1) {
            0 // first folder if EOL
        } else if (this in folders.indices) {
            this + 1 // next folder
        } else this // no folder found

        val nextFolderName = if (nextIndex in folders.indices) {
            folders[nextIndex]
        } else null

        if (nextFolderName == null || nextFolderName == folderName)
            return null

        val feeds = foldersChildrenMap[nextFolderName]
        if (feeds == null || feeds.isEmpty())
        // try and get the next non empty folder name
            getNextNonEmptyFolder(nextFolderName)
        else nextFolderName to feeds
    }

    fun getNextSession(): Session? {
        if (session.feedSet.isFolder) {
            val folderName = session.feedSet.folderName
            return getNextNonEmptyFolder(folderName)?.let { (nextFolderName, nextFolderFeeds) ->
                val nextFeedSet = FeedSet.folder(nextFolderName, nextFolderFeeds.map { it.feedId }.toSet())
                Session(feedSet = nextFeedSet)
            }
        } else {
            if (session.feed == null || session.folderName == null) return null

            val nextFeed = getNextFolderFeed(feed = session.feed!!, folderName = session.folderName!!)
            return nextFeed?.let {
                Session(feedSet = FeedSet.singleFeed(it.feedId), session.folderName, it)
            }
        }
    }

// feed item list
// folder name
// feed

// item set fragment
// feed set


// item list
// feed set

// how to construct feed sets from feeds
//    var feed: Feed? = activeFolderChildren.get(groupPosition).get(childPosition)
//    var fs = FeedSet.singleFeed(feed!!.feedId)
//    if (!feed.active)fs.setMuted(true)
//    if (currentState == StateFilter.SAVED)fs.setFilterSaved(true)
//    return fs
}

data class Session(
        val feedSet: FeedSet,
        val folderName: String? = null,
        val feed: Feed? = null,
) : Serializable