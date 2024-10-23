package com.newsblur.domain

import android.content.ContentValues
import android.database.Cursor
import com.google.gson.annotations.SerializedName
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.DatabaseConstants

class SavedSearch {

    @JvmField
    @SerializedName("query")
    var query: String? = null

    @JvmField
    @SerializedName("feed_id")
    var feedId: String = ""

    @SerializedName("feed_address")
    var feedAddress: String? = null

    @JvmField
    var feedTitle: String? = null

    @JvmField
    var faviconUrl: String? = null

    suspend fun getValues(dbHelper: BlurDatabaseHelper): ContentValues {
        val values = ContentValues()
        val feedTitle = "\"<b>" + query + "</b>\" in <b>" + getFeedTitle(dbHelper) + "</b>"
        values.put(DatabaseConstants.SAVED_SEARCH_FEED_TITLE, feedTitle)
        values.put(DatabaseConstants.SAVED_SEARCH_FAVICON, getFaviconUrl(dbHelper))
        values.put(DatabaseConstants.SAVED_SEARCH_ADDRESS, feedAddress)
        values.put(DatabaseConstants.SAVED_SEARCH_QUERY, query)
        values.put(DatabaseConstants.SAVED_SEARCH_FEED_ID, feedId)
        return values
    }

    private suspend fun getFeedTitle(dbHelper: BlurDatabaseHelper): String? {
        var feedTitle: String? = null

        if (feedId == "river:") {
            feedTitle = "All Site Stories"
        } else if (feedId == "river:infrequent") {
            feedTitle = "Infrequent Site Stories"
        } else if (feedId.startsWith("river:")) {
            val folderName = feedId.replace("river:", "")
            val fs = dbHelper.feedSetFromFolderName(folderName)
            feedTitle = fs.folderName
        } else if (feedId == "read") {
            feedTitle = "Read Stories"
        } else if (feedId.startsWith("starred")) {
            feedTitle = "Saved Stories"
            val tag = feedId.replace("starred:", "")
            val starredFeed = dbHelper.getStarredFeedByTag(tag)
            if (starredFeed != null) {
                val tagSlug = tag.replace(" ", "-")
                if (starredFeed.tag == tag || starredFeed.tag == tagSlug) {
                    feedTitle = feedTitle + " - " + starredFeed.tag
                }
            }
        } else if (feedId.startsWith("feed:")) {
            val feed = dbHelper.getFeed(feedId.replace("feed:", "")) ?: return null
            feedTitle = feed.title
        } else if (feedId.startsWith("social:")) {
            val feed = dbHelper.getFeed(feedId.replace("social:", "")) ?: return null
            feedTitle = feed.title
        }

        return feedTitle
    }

    private suspend fun getFaviconUrl(dbHelper: BlurDatabaseHelper): String {
        var url: String? = null
        if (feedId == "river:" || feedId == "river:infrequent") {
            url = "https://newsblur.com/media/img/icons/circular/ak-icon-allstories.png"
        } else if (feedId.startsWith("river:")) {
            url = "https://newsblur.com/media/img/icons/circular/g_icn_folder.png"
        } else if (feedId == "read") {
            url = "https://newsblur.com/media/img/icons/circular/g_icn_unread.png"
        } else if (feedId == "starred") {
            url = "https://newsblur.com/media/img/icons/circular/clock.png"
        } else if (feedId.startsWith("starred:")) {
            url = "https://newsblur.com/media/img/reader/tag.png"
        } else if (feedId.startsWith("feed:")) {
            val feed = dbHelper.getFeed(feedId.replace("feed:", ""))
            if (feed != null) {
                url = feed.faviconUrl
            }
        } else if (feedId.startsWith("social:")) {
            val feed = dbHelper.getFeed(feedId.replace("social:", ""))
            if (feed != null) {
                url = feed.faviconUrl
            }
        }
        if (url == null) {
            url = "https://newsblur.com/media/img/icons/circular/g_icn_search_black.png"
        }
        return url
    }

    companion object {

        @JvmStatic
        fun fromCursor(cursor: Cursor): SavedSearch {
            if (cursor.isBeforeFirst) {
                cursor.moveToFirst()
            }
            val savedSearch = SavedSearch()
            savedSearch.feedTitle = cursor.getString(cursor.getColumnIndex(DatabaseConstants.SAVED_SEARCH_FEED_TITLE))
            savedSearch.faviconUrl = cursor.getString(cursor.getColumnIndex(DatabaseConstants.SAVED_SEARCH_FAVICON))
            savedSearch.feedAddress = cursor.getString(cursor.getColumnIndex(DatabaseConstants.SAVED_SEARCH_ADDRESS))
            savedSearch.query = cursor.getString(cursor.getColumnIndex(DatabaseConstants.SAVED_SEARCH_QUERY))
            savedSearch.feedId = cursor.getString(cursor.getColumnIndex(DatabaseConstants.SAVED_SEARCH_FEED_ID))
            return savedSearch
        }

        @JvmField
        val SavedSearchComparatorByTitle: Comparator<SavedSearch> = Comparator { ss1, ss2 ->
            String.CASE_INSENSITIVE_ORDER.compare(ss1.feedTitle, ss2.feedTitle)
        }
    }
}
