package com.newsblur.database

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.text.TextUtils
import androidx.annotation.WorkerThread
import com.newsblur.domain.Classifier
import com.newsblur.domain.Comment
import com.newsblur.domain.Feed
import com.newsblur.domain.Folder
import com.newsblur.domain.Reply
import com.newsblur.domain.SocialFeed
import com.newsblur.domain.StarredCount
import com.newsblur.domain.Story
import com.newsblur.domain.UserProfile
import com.newsblur.network.domain.CommentResponse
import com.newsblur.network.domain.StoriesResponse
import com.newsblur.util.AppConstants
import com.newsblur.util.CursorFilters
import com.newsblur.util.FeedSet
import com.newsblur.util.Log
import com.newsblur.util.ReadFilter
import com.newsblur.util.ReadingAction
import com.newsblur.util.StateFilter
import com.newsblur.util.StoryOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class BlueDatabaseHelperKt(
        private val dbWrapper: BlurDatabase,
) {

    constructor(context: Context) : this(BlurDatabase(context))

    // Removing the manual synchro will cause ANRs
    // because the db transactions are made on the main thread
    private val RW_MUTEX = Any()

    private val dbRO: SQLiteDatabase
    private val dbRW: SQLiteDatabase

    init {
        Log.d(this.javaClass.name, "new DB conn requested")
        synchronized(RW_MUTEX) {
            dbRO = dbWrapper.ro
            dbRW = dbWrapper.rw
        }
    }

    fun close() {
        // when asked to close, do so via an AsyncTask. This is so that (since becoming serial in android 4.0)
        // the closure will happen after other async tasks are done using the conn
        val executorService = Executors.newSingleThreadExecutor()
        executorService.execute {
            synchronized(RW_MUTEX) {
                dbWrapper.close()
            }
        }
    }

    fun dropAndRecreateTables() {
        Log.i(this.javaClass.name, "dropping and recreating all tables . . .")
        synchronized(RW_MUTEX) { dbWrapper.dropAndRecreateTables() }
        Log.i(this.javaClass.name, ". . . tables recreated.")
    }

    suspend fun getEngineVersion(): String {
        var engineVersion = ""
        try {
            val c = dbRO.rawQuery("SELECT sqlite_version() AS sqlite_version", null)
            if (c.moveToFirst()) {
                engineVersion = c.getString(0)
            }
            c.close()
        } catch (e: Exception) {
            // this is only debug code, do not raise a failure
        }
        return engineVersion
    }

    suspend fun getAllFeeds(): Set<String> = getAllFeeds(false)

    private suspend fun getAllFeeds(activeOnly: Boolean): Set<String> = withContext(Dispatchers.IO) {
        var q1 = "SELECT " + DatabaseConstants.FEED_ID +
                " FROM " + DatabaseConstants.FEED_TABLE
        if (activeOnly) {
            q1 = q1 + " WHERE " + DatabaseConstants.FEED_ACTIVE + " = 1"
        }
        val c = dbRO.rawQuery(q1, null)
        val feedIds = LinkedHashSet<String>(c.count)
        while (c.moveToNext()) {
            feedIds.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.FEED_ID)))
        }
        c.close()
        feedIds
    }

    suspend fun getAllActiveFeeds(): Set<String> = getAllFeeds(true)

    private suspend fun getAllSocialFeeds(): List<String> {
        val q1 = "SELECT " + DatabaseConstants.SOCIAL_FEED_ID +
                " FROM " + DatabaseConstants.SOCIALFEED_TABLE
        val c = dbRO.rawQuery(q1, null)
        val feedIds: MutableList<String> = ArrayList(c.count)
        while (c.moveToNext()) {
            feedIds.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.SOCIAL_FEED_ID)))
        }
        c.close()
        return feedIds
    }

    /**
     * Clean up stories from more than a month ago. This is the oldest an unread can be,
     * and a good cutoff point for what it is sane for us to store for users that ask
     * us to keep a copy of read stories.  This is necessary primarily to catch any
     * stories that get missed by cleanupReadStories() because their read state might
     * not have been correctly resolved and they get orphaned in the DB.
     */
    suspend fun cleanupVeryOldStories() = withContext(Dispatchers.IO) {
        val cutoffDate = Calendar.getInstance()
        cutoffDate.add(Calendar.MONTH, -1)
        synchronized(RW_MUTEX) {
            val count = dbRW.delete(DatabaseConstants.STORY_TABLE,
                    DatabaseConstants.STORY_TIMESTAMP + " < ?" +
                            " AND " + DatabaseConstants.STORY_TEXT_STORY_HASH + " NOT IN " +
                            "( SELECT " + DatabaseConstants.READING_SESSION_STORY_HASH + " FROM " + DatabaseConstants.READING_SESSION_TABLE + ")",
                    arrayOf(cutoffDate.time.time.toString()))
            Log.d(this, "cleaned up ancient stories: $count")
        }
    }

    /**
     * Clean up stories that have already been read, unless they are being actively
     * displayed to the user.
     */
    suspend fun cleanupReadStories() = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            val count = dbRW.delete(DatabaseConstants.STORY_TABLE,
                    DatabaseConstants.STORY_READ + " = 1" +
                            " AND " + DatabaseConstants.STORY_TEXT_STORY_HASH + " NOT IN " +
                            "( SELECT " + DatabaseConstants.READING_SESSION_STORY_HASH + " FROM " + DatabaseConstants.READING_SESSION_TABLE + ")",
                    null)
            Log.d(this, "cleaned up read stories: $count")
        }
    }

    suspend fun cleanupStoryText() = withContext(Dispatchers.IO) {
        val q = "DELETE FROM " + DatabaseConstants.STORY_TEXT_TABLE +
                " WHERE " + DatabaseConstants.STORY_TEXT_STORY_HASH + " NOT IN " +
                "( SELECT " + DatabaseConstants.STORY_HASH + " FROM " + DatabaseConstants.STORY_TABLE +
                ")"
        synchronized(RW_MUTEX) { dbRW.execSQL(q) }
    }

    suspend fun vacuum() = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.execSQL("VACUUM") }
    }

    suspend fun deleteFeed(feedId: String?) = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(feedId)
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.FEED_TABLE, DatabaseConstants.FEED_ID + " = ?", selArgs) }
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.STORY_TABLE, DatabaseConstants.STORY_FEED_ID + " = ?", selArgs) }
    }

    suspend fun deleteSocialFeed(userId: String?) = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(userId)
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.SOCIALFEED_TABLE, DatabaseConstants.SOCIAL_FEED_ID + " = ?", selArgs) }
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.STORY_TABLE, DatabaseConstants.STORY_FEED_ID + " = ?", selArgs) }
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE, DatabaseConstants.SOCIALFEED_STORY_USER_ID + " = ?", selArgs) }
    }

    suspend fun deleteSavedSearch(feedId: String?, query: String?) = withContext(Dispatchers.IO) {
        val q = "DELETE FROM " + DatabaseConstants.SAVED_SEARCH_TABLE +
                " WHERE " + DatabaseConstants.SAVED_SEARCH_FEED_ID + " = '" + feedId + "'" +
                " AND " + DatabaseConstants.SAVED_SEARCH_QUERY + " = '" + query + "'"
        synchronized(RW_MUTEX) { dbRW.execSQL(q) }
    }

    suspend fun deleteStories() = withContext(Dispatchers.IO) {
        vacuum()
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.STORY_TABLE, null, null) }
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.STORY_TEXT_TABLE, null, null) }
    }

    suspend fun getFeed(feedId: String?): Feed? = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.FEED_TABLE, null, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId), null, null, null)
        var result: Feed? = null
        while (c.moveToNext()) {
            result = Feed.fromCursor(c)
        }
        c.close()
        result
    }

    suspend fun updateFeed(feed: Feed) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.insertWithOnConflict(DatabaseConstants.FEED_TABLE, null, feed.values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private suspend fun bulkInsertValues(table: String, valuesList: List<ContentValues>) = withContext(Dispatchers.IO) {
        if (valuesList.isEmpty()) return@withContext
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                for (values in valuesList) {
                    dbRW.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    // not suspended due to suspension point is inside a critical section otherwise.
    // once RW_MUTEX is removed this can become suspended

    // just like bulkInsertValues, but leaves sync/transactioning to the caller
    private fun bulkInsertValuesExtSync(table: String, valuesList: List<ContentValues>) {
        if (valuesList.isEmpty()) return
        for (values in valuesList) {
            dbRW.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun setFeedsFolders(
            folderValues: List<ContentValues>,
            feedValues: List<ContentValues>,
            socialFeedValues: List<ContentValues>,
            starredCountValues: List<ContentValues>,
            savedSearchValues: List<ContentValues>) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                dbRW.delete(DatabaseConstants.FEED_TABLE, null, null)
                dbRW.delete(DatabaseConstants.FOLDER_TABLE, null, null)
                dbRW.delete(DatabaseConstants.SOCIALFEED_TABLE, null, null)
                dbRW.delete(DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE, null, null)
                dbRW.delete(DatabaseConstants.COMMENT_TABLE, null, null)
                dbRW.delete(DatabaseConstants.REPLY_TABLE, null, null)
                dbRW.delete(DatabaseConstants.STARREDCOUNTS_TABLE, null, null)
                dbRW.delete(DatabaseConstants.SAVED_SEARCH_TABLE, null, null)
                bulkInsertValuesExtSync(DatabaseConstants.FOLDER_TABLE, folderValues)
                bulkInsertValuesExtSync(DatabaseConstants.FEED_TABLE, feedValues)
                bulkInsertValuesExtSync(DatabaseConstants.SOCIALFEED_TABLE, socialFeedValues)
                bulkInsertValuesExtSync(DatabaseConstants.STARREDCOUNTS_TABLE, starredCountValues)
                bulkInsertValuesExtSync(DatabaseConstants.SAVED_SEARCH_TABLE, savedSearchValues)
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    // note method name: this gets a set rather than a list, in case the caller wants to
    // spend the up-front cost of hashing for better lookup speed rather than iteration!
    suspend fun getUnreadStoryHashesAsSet(): Set<String> = withContext(Dispatchers.IO) {
        val q = "SELECT " + DatabaseConstants.STORY_HASH +
                " FROM " + DatabaseConstants.STORY_TABLE +
                " WHERE " + DatabaseConstants.STORY_READ + " = 0"
        val c = dbRO.rawQuery(q, null)
        val hashes: MutableSet<String> = HashSet(c.count)
        while (c.moveToNext()) {
            hashes.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_HASH)))
        }
        c.close()
        hashes
    }

    suspend fun getStarredStoryHashes(): Set<String> = withContext(Dispatchers.IO) {
        val q = "SELECT " + DatabaseConstants.STORY_HASH +
                " FROM " + DatabaseConstants.STORY_TABLE +
                " WHERE " + DatabaseConstants.STORY_STARRED + " = 1"
        val c = dbRO.rawQuery(q, null)
        val hashes: MutableSet<String> = java.util.HashSet(c.count)
        while (c.moveToNext()) {
            hashes.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_HASH)))
        }
        c.close()
        hashes
    }

    suspend fun getAllStoryImages(): Set<String> = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.STORY_TABLE, arrayOf(DatabaseConstants.STORY_IMAGE_URLS), null, null, null, null, null)
        val urls: MutableSet<String> = java.util.HashSet(c.count)
        while (c.moveToNext()) {
            urls.addAll(Arrays.asList(*TextUtils.split(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_IMAGE_URLS)), ",")))
        }
        c.close()
        urls
    }

    suspend fun getAllStoryThumbnails(): Set<String> = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.STORY_TABLE, arrayOf(DatabaseConstants.STORY_THUMBNAIL_URL), null, null, null, null, null)
        val urls: MutableSet<String> = java.util.HashSet(c.count)
        while (c.moveToNext()) {
            val url = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_THUMBNAIL_URL))
            if (url != null) {
                urls.add(url)
            }
        }
        c.close()
        urls
    }

    suspend fun insertStories(apiResponse: StoriesResponse, stateFilter: StateFilter, forImmediateReading: Boolean) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            // do not attempt to use beginTransactionNonExclusive() to reduce lock time for this very heavy set
            // of calls. most versions of Android incorrectly implement the underlying SQLite calls and will
            // result in crashes that poison the DB beyond repair
            dbRW.beginTransaction()
            try {
                // to insert classifiers, we need to determine the feed ID of the stories in this
                // response, so sniff one out.

                var impliedFeedId: String? = null

                // handle users
                if (apiResponse.users != null) {
                    val userValues: MutableList<ContentValues> = java.util.ArrayList(apiResponse.users.size)
                    for (user in apiResponse.users) {
                        userValues.add(user.values)
                    }
                    bulkInsertValuesExtSync(DatabaseConstants.USER_TABLE, userValues)
                }

                // handle supplemental feed data that may have been included (usually in social requests)
                if (apiResponse.feeds != null) {
                    val feedValues: MutableList<ContentValues> = java.util.ArrayList(apiResponse.feeds.size)
                    for (feed in apiResponse.feeds) {
                        feedValues.add(feed.values)
                    }
                    bulkInsertValuesExtSync(DatabaseConstants.FEED_TABLE, feedValues)
                }

                // handle story content
                if (apiResponse.stories != null) {
                    storiesloop@ for (story in apiResponse.stories) {
                        if ((story.storyHash == null) || (story.storyHash.length < 1)) {
                            // this is incredibly rare, but has been seen in crash reports at least twice.
                            Log.e(this, "story received without story hash: " + story.id)
                            continue@storiesloop
                        }
                        insertSingleStoryExtSync(story)
                        // if the story is being fetched for the immediate session, also add the hash to the session table
                        if (forImmediateReading && story.isStoryVisibleInState(stateFilter)) {
                            val sessionHashValues = ContentValues()
                            sessionHashValues.put(DatabaseConstants.READING_SESSION_STORY_HASH, story.storyHash)
                            dbRW.insert(DatabaseConstants.READING_SESSION_TABLE, null, sessionHashValues)
                        }
                        impliedFeedId = story.feedId
                    }
                }
                if (apiResponse.story != null) {
                    if ((apiResponse.story.storyHash == null) || (apiResponse.story.storyHash.isEmpty())) {
                        Log.e(this, "story received without story hash: " + apiResponse.story.id)
                        return@withContext
                    }
                    insertSingleStoryExtSync(apiResponse.story)
                    impliedFeedId = apiResponse.story.feedId
                }

                // handle classifiers
                if (apiResponse.classifiers != null) {
                    for (entry in apiResponse.classifiers.entries) {
                        // the API might not have included a feed ID, in which case it deserialized as -1 and must be implied
                        var classifierFeedId = entry.key
                        if (classifierFeedId == "-1") {
                            classifierFeedId = impliedFeedId!!
                        }
                        val classifierValues = entry.value.contentValues
                        for (values in classifierValues) {
                            values.put(DatabaseConstants.CLASSIFIER_ID, classifierFeedId)
                        }
                        dbRW.delete(DatabaseConstants.CLASSIFIER_TABLE, DatabaseConstants.CLASSIFIER_ID + " = ?", arrayOf(classifierFeedId))
                        bulkInsertValuesExtSync(DatabaseConstants.CLASSIFIER_TABLE, classifierValues)
                    }
                }

                if (apiResponse.feedTags != null) {
                    val feedTags: MutableList<String> = java.util.ArrayList(apiResponse.feedTags.size)
                    for (tuple in apiResponse.feedTags) {
                        // the API returns a list of lists, but all we care about is the tag name/id which is the first item in the tuple
                        if (tuple.size > 0) {
                            feedTags.add(tuple[0])
                        }
                    }
                    putFeedTagsExtSync(impliedFeedId, feedTags)
                }

                if (apiResponse.feedAuthors != null) {
                    val feedAuthors: MutableList<String> = java.util.ArrayList(apiResponse.feedAuthors.size)
                    for (tuple in apiResponse.feedAuthors) {
                        // the API returns a list of lists, but all we care about is the author name/id which is the first item in the tuple
                        if (tuple.size > 0) {
                            feedAuthors.add(tuple[0])
                        }
                    }
                    putFeedAuthorsExtSync(impliedFeedId, feedAuthors)
                }

                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    private fun insertSingleStoryExtSync(story: Story) {
        // pick a thumbnail for the story
        story.thumbnailUrl = Story.guessStoryThumbnailURL(story)
        // insert the story data
        val values = story.values
        dbRW.insertWithOnConflict(DatabaseConstants.STORY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        // if a story was shared by a user, also insert it into the social table under their userid, too
        for (sharedUserId in story.sharedUserIds) {
            val socialValues = ContentValues()
            socialValues.put(DatabaseConstants.SOCIALFEED_STORY_USER_ID, sharedUserId)
            socialValues.put(DatabaseConstants.SOCIALFEED_STORY_STORYID, values.getAsString(DatabaseConstants.STORY_ID))
            dbRW.insertWithOnConflict(DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE, null, socialValues, SQLiteDatabase.CONFLICT_REPLACE)
        }
        // handle comments
        for (comment in story.publicComments) {
            comment.storyId = story.id
            insertSingleCommentExtSync(comment)
        }
        for (comment in story.friendsComments) {
            comment.storyId = story.id
            comment.byFriend = true
            insertSingleCommentExtSync(comment)
        }
        for (comment in story.friendsShares) {
            comment.isPseudo = true
            comment.storyId = story.id
            comment.byFriend = true
            insertSingleCommentExtSync(comment)
        }
    }

    @WorkerThread
    private fun insertSingleCommentExtSync(comment: Comment) {
        // real comments replace placeholders
        val count = dbRW.delete(DatabaseConstants.COMMENT_TABLE, DatabaseConstants.COMMENT_ISPLACEHOLDER + " = ?", arrayOf("true"))
        // comments always come with an updated set of replies, so remove old ones first
        dbRW.delete(DatabaseConstants.REPLY_TABLE, DatabaseConstants.REPLY_COMMENTID + " = ?", arrayOf(comment.id))
        dbRW.insertWithOnConflict(DatabaseConstants.COMMENT_TABLE, null, comment.values, SQLiteDatabase.CONFLICT_REPLACE)
        for (reply in comment.replies) {
            reply.commentId = comment.id
            dbRW.insertWithOnConflict(DatabaseConstants.REPLY_TABLE, null, reply.values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * Update an existing story based upon a new copy received from a social API. This handles the fact
     * that some social APIs helpfully vend updated copies of stories with social-related fields updated
     * to reflect a social action, but that the new copy is missing some fields.  Attempt to merge the
     * new story with the old one.
     */
    suspend fun updateStory(apiResponse: StoriesResponse, stateFilter: StateFilter, forImmediateReading: Boolean) = withContext(Dispatchers.IO) {
        if (apiResponse.story == null) {
            Log.e(this, "updateStory called on response with missing single story")
            return@withContext
        }
        val c = dbRO.query(DatabaseConstants.STORY_TABLE,
                null,
                DatabaseConstants.STORY_HASH + " = ?",
                arrayOf(apiResponse.story.storyHash),
                null, null, null)
        if (c.count < 1) {
            Log.w(this, "updateStory can't find old copy; new story may be missing fields.")
        } else {
            val oldStory = Story.fromCursor(c)
            c.close()
            apiResponse.story.starred = oldStory.starred
            apiResponse.story.starredTimestamp = oldStory.starredTimestamp
            apiResponse.story.read = oldStory.read
        }
        insertStories(apiResponse, stateFilter, forImmediateReading)
    }

    /**
     * Update an existing comment and associated replies based upon a new copy received from a social
     * API.  Most social APIs vend an updated view that replaces any old or placeholder records.
     */
    suspend fun updateComment(apiResponse: CommentResponse, storyId: String?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            // comments often contain enclosed replies, so batch them.
            dbRW.beginTransaction()
            try {
                // the API might include new supplemental user metadata if new replies have shown up.
                if (apiResponse.users != null) {
                    val userValues: MutableList<ContentValues> = java.util.ArrayList(apiResponse.users.size)
                    for (user in apiResponse.users) {
                        userValues.add(user.values)
                    }
                    bulkInsertValuesExtSync(DatabaseConstants.USER_TABLE, userValues)
                }

                // we store all comments in the context of the associated story, but the social API doesn't
                // reference the story when responding, so fix that from our context
                apiResponse.comment.storyId = storyId
                insertSingleCommentExtSync(apiResponse.comment)
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    suspend fun fixMissingStoryFeeds(stories: Array<Story>?) = withContext(Dispatchers.IO) {
        // start off with feeds mentioned by the set of stories
        // TODO cleanup
        val feedIds = mutableSetOf<String>()
        stories?.let {
            for (story in stories) {
                feedIds.add(story.feedId)
            }
        }
        // now prune any we already have
        val q1 = "SELECT " + DatabaseConstants.FEED_ID +
                " FROM " + DatabaseConstants.FEED_TABLE
        val c = dbRO.rawQuery(q1, null)
        while (c.moveToNext()) {
            feedIds.remove(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.FEED_ID)))
        }
        c.close()
        // if any feeds are left, they are phantoms and need a fake entry
        if (feedIds.size < 1) return@withContext
        android.util.Log.i(this.javaClass.name, "inserting missing metadata for " + feedIds.size + " feeds used by new stories")
        val feedValues: MutableList<ContentValues> = java.util.ArrayList(feedIds.size)
        for (feedId in feedIds) {
            val missingFeed = Feed.getZeroFeed()
            missingFeed.feedId = feedId
            feedValues.add(missingFeed.values)
        }
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                for (values in feedValues) {
                    dbRW.insertWithOnConflict(DatabaseConstants.FEED_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    suspend fun getFolder(folderName: String): Folder? = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(folderName)
        val selection = DatabaseConstants.FOLDER_NAME + " = ?"
        val c = dbRO.query(DatabaseConstants.FOLDER_TABLE, null, selection, selArgs, null, null, null)
        if (c.count < 1) {
            closeQuietly(c)
            return@withContext null
        }
        val folder = Folder.fromCursor(c)
        closeQuietly(c)
        folder
    }

    suspend fun touchStory(hash: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_LAST_READ_DATE, Date().time)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_LAST_READ_DATE + " < 1 AND " + DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash)) }
    }

    suspend fun markStoryHashesRead(hashes: Collection<String>) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                val values = ContentValues()
                values.put(DatabaseConstants.STORY_READ, true)
                for (hash in hashes) {
                    dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash))
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    suspend fun markStoryHashesStarred(hashes: Collection<String>, isStarred: Boolean) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                val values = ContentValues()
                values.put(DatabaseConstants.STORY_STARRED, isStarred)
                for (hash in hashes) {
                    dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash))
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    suspend fun setFeedsActive(feedIds: Set<String>, active: Boolean) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                val values = ContentValues()
                values.put(DatabaseConstants.FEED_ACTIVE, active)
                for (feedId in feedIds) {
                    dbRW.update(DatabaseConstants.FEED_TABLE, values, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId))
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    suspend fun setFeedFetchPending(feedId: String) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.FEED_FETCH_PENDING, true)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.FEED_TABLE, values, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId)) }
    }

    suspend fun isFeedSetFetchPending(fs: FeedSet): Boolean = withContext(Dispatchers.IO) {
        if (fs.singleFeed != null) {
            val feedId = fs.singleFeed
            val c = dbRO.query(DatabaseConstants.FEED_TABLE,
                    arrayOf(DatabaseConstants.FEED_FETCH_PENDING),
                    DatabaseConstants.FEED_ID + " = ? AND " + DatabaseConstants.FEED_FETCH_PENDING + " = ?",
                    arrayOf(feedId, "1"),
                    null, null, null)
            try {
                if (c.count > 0) return@withContext true
            } finally {
                closeQuietly(c)
            }
        }
        false
    }

    /**
     * Marks a story (un)read but does not adjust counts. Must stay idempotent an time-insensitive.
     */
    suspend fun setStoryReadState(hash: String?, read: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_READ, read)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash)) }
    }

    /**
     * Marks a story (un)read and also adjusts unread counts for it. Non-idempotent by design.
     *
     * @return the set of feed IDs that potentially have counts impacted by the mark.
     */
    suspend fun setStoryReadState(story: Story, read: Boolean): Set<FeedSet> = withContext(Dispatchers.IO) {
        // calculate the impact surface so the caller can re-check counts if needed
        val impactedFeeds: MutableSet<FeedSet> = java.util.HashSet()
        impactedFeeds.add(FeedSet.singleFeed(story.feedId))
        val socialIds: MutableSet<String> = java.util.HashSet()
        if (!TextUtils.isEmpty(story.socialUserId)) {
            socialIds.add(story.socialUserId)
        }
        if (story.friendUserIds != null) {
            socialIds.addAll(Arrays.asList(*story.friendUserIds))
        }
        if (socialIds.size > 0) {
            impactedFeeds.add(FeedSet.multipleSocialFeeds(socialIds))
        }
        // check the story's starting state and the desired state and adjust it as an atom so we
        // know if it truly changed or not
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                // get a fresh copy of the story from the DB so we know if it changed
                val c = dbRW.query(DatabaseConstants.STORY_TABLE,
                        arrayOf(DatabaseConstants.STORY_READ),
                        DatabaseConstants.STORY_HASH + " = ?",
                        arrayOf(story.storyHash),
                        null, null, null)
                if (c.count < 1) {
                    android.util.Log.w(this.javaClass.name, "story removed before finishing mark-read")
                    return@withContext impactedFeeds
                }
                c.moveToFirst()
                val origState = (c.getInt(c.getColumnIndexOrThrow(DatabaseConstants.STORY_READ)) > 0)
                c.close()
                // if there is nothing to be done, halt
                if (origState == read) {
                    dbRW.setTransactionSuccessful()
                    return@withContext impactedFeeds
                }
                // update the story's read state
                val values = ContentValues()
                values.put(DatabaseConstants.STORY_READ, read)
                dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(story.storyHash))
                // which column to inc/dec depends on story intel
                val impactedCol: String
                val impactedSocialCol: String
                if (story.intelligence.calcTotalIntel() < 0) {
                    // negative stories don't affect counts
                    dbRW.setTransactionSuccessful()
                    return@withContext impactedFeeds
                } else if (story.intelligence.calcTotalIntel() == 0) {
                    impactedCol = DatabaseConstants.FEED_NEUTRAL_COUNT
                    impactedSocialCol = DatabaseConstants.SOCIAL_FEED_NEUTRAL_COUNT
                } else {
                    impactedCol = DatabaseConstants.FEED_POSITIVE_COUNT
                    impactedSocialCol = DatabaseConstants.SOCIAL_FEED_POSITIVE_COUNT
                }
                val operator = (if (read) " - 1" else " + 1")
                var q = StringBuilder("UPDATE " + DatabaseConstants.FEED_TABLE)
                q.append(" SET ").append(impactedCol).append(" = ").append(impactedCol).append(operator)
                q.append(" WHERE " + DatabaseConstants.FEED_ID + " = ").append(story.feedId)
                dbRW.execSQL(q.toString())
                for (socialId in socialIds) {
                    q = StringBuilder("UPDATE " + DatabaseConstants.SOCIALFEED_TABLE)
                    q.append(" SET ").append(impactedSocialCol).append(" = ").append(impactedSocialCol).append(operator)
                    q.append(" WHERE " + DatabaseConstants.SOCIAL_FEED_ID + " = ").append(socialId)
                    dbRW.execSQL(q.toString())
                }
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
        impactedFeeds
    }

    /**
     * Marks a range of stories in a subset of feeds as read. Does not update unread counts;
     * the caller must use updateLocalFeedCounts() or the /reader/feed_unread_count API.
     */
    suspend fun markStoriesRead(fs: FeedSet, olderThan: Long?, newerThan: Long?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_READ, true)
        var rangeSelection: String? = null
        if (olderThan != null) rangeSelection = DatabaseConstants.STORY_TIMESTAMP + " <= " + olderThan
        if (newerThan != null) rangeSelection = DatabaseConstants.STORY_TIMESTAMP + " >= " + newerThan
        var feedSelection: java.lang.StringBuilder? = null
        if (fs.isAllNormal) {
            // a null selection is fine for all stories
        } else if (fs.multipleFeeds != null) {
            feedSelection = java.lang.StringBuilder(DatabaseConstants.STORY_FEED_ID + " IN ( ")
            feedSelection.append(TextUtils.join(",", fs.multipleFeeds))
            feedSelection.append(")")
        } else if (fs.singleFeed != null) {
            feedSelection = java.lang.StringBuilder(DatabaseConstants.STORY_FEED_ID + " = ")
            feedSelection.append(fs.singleFeed)
        } else if (fs.singleSocialFeed != null) {
            feedSelection = java.lang.StringBuilder(DatabaseConstants.STORY_SOCIAL_USER_ID + " = ")
            feedSelection.append(fs.singleSocialFeed.key)
        } else {
            throw IllegalStateException("Asked to mark stories for FeedSet of unknown type.")
        }
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.STORY_TABLE, values, conjoinSelections(feedSelection, rangeSelection), null) }
    }

    /**
     * Get the unread count for the given feedset based on the totals in the feeds table.
     */
    suspend fun getUnreadCount(fs: FeedSet, stateFilter: StateFilter): Int = withContext(Dispatchers.IO) {
        // if reading in starred-only mode, there are no unreads, since stories vended as starred are never unread
        if (fs.isFilterSaved) return@withContext 0
        if (fs.isAllNormal) {
            return@withContext getFeedsUnreadCount(stateFilter, null, null)
        } else if (fs.isAllSocial) {
            //return getSocialFeedsUnreadCount(stateFilter, null, null);
            // even though we can count up and total the unreads in social feeds, the API doesn't vend
            // unread status for stories viewed when reading All Shared Stories, so force this to 0.
            return@withContext 0
        } else if (fs.multipleFeeds != null) {
            val selection = java.lang.StringBuilder(DatabaseConstants.FEED_ID + " IN ( ")
            selection.append(TextUtils.join(",", fs.multipleFeeds)).append(")")
            return@withContext getFeedsUnreadCount(stateFilter, selection.toString(), null)
        } else if (fs.multipleSocialFeeds != null) {
            val selection = java.lang.StringBuilder(DatabaseConstants.SOCIAL_FEED_ID + " IN ( ")
            selection.append(TextUtils.join(",", fs.multipleSocialFeeds.keys)).append(")")
            return@withContext getSocialFeedsUnreadCount(stateFilter, selection.toString(), null)
        } else if (fs.singleFeed != null) {
            return@withContext getFeedsUnreadCount(stateFilter, DatabaseConstants.FEED_ID + " = ?", arrayOf<String>(fs.singleFeed))
        } else if (fs.singleSocialFeed != null) {
            return@withContext getSocialFeedsUnreadCount(stateFilter, DatabaseConstants.SOCIAL_FEED_ID + " = ?", arrayOf<String>(fs.singleSocialFeed.key))
        } else {
            // all other types of view don't track unreads correctly
            return@withContext 0
        }
    }

    private suspend fun getFeedsUnreadCount(stateFilter: StateFilter, selection: String?, selArgs: Array<String>?): Int = withContext(Dispatchers.IO) {
        var result = 0
        val c = dbRO.query(DatabaseConstants.FEED_TABLE, null, selection, selArgs, null, null, null)
        while (c.moveToNext()) {
            val f = Feed.fromCursor(c)
            if (!f.active) continue
            result += f.positiveCount
            if ((stateFilter == StateFilter.SOME) || (stateFilter == StateFilter.ALL)) result += f.neutralCount
            if (stateFilter == StateFilter.ALL) result += f.negativeCount
        }
        c.close()
        result
    }

    private suspend fun getSocialFeedsUnreadCount(stateFilter: StateFilter, selection: String, selArgs: Array<String>?): Int = withContext(Dispatchers.IO) {
        var result = 0
        val c = dbRO.query(DatabaseConstants.SOCIALFEED_TABLE, null, selection, selArgs, null, null, null)
        while (c.moveToNext()) {
            val f = SocialFeed.fromCursor(c)
            result += f.positiveCount
            if ((stateFilter == StateFilter.SOME) || (stateFilter == StateFilter.ALL)) result += f.neutralCount
            if (stateFilter == StateFilter.ALL) result += f.negativeCount
        }
        c.close()
        result
    }

    suspend fun updateFeedCounts(feedId: String?, values: ContentValues?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.FEED_TABLE, values, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId)) }
    }

    suspend fun updateSocialFeedCounts(feedId: String?, values: ContentValues?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.SOCIALFEED_TABLE, values, DatabaseConstants.SOCIAL_FEED_ID + " = ?", arrayOf(feedId)) }
    }

    /**
     * Refreshes the counts in the feeds/socialfeeds tables by counting stories in the story table.
     */
    suspend fun updateLocalFeedCounts(fs: FeedSet) = withContext(Dispatchers.IO) {
        // decompose the FeedSet into a list of single feeds that need to be recounted
        val feedIds: MutableList<String> = java.util.ArrayList()
        val socialFeedIds: MutableList<String> = java.util.ArrayList()

        if (fs.isAllNormal) {
            feedIds.addAll(getAllFeeds())
            socialFeedIds.addAll(getAllSocialFeeds())
        } else if (fs.multipleFeeds != null) {
            feedIds.addAll(fs.multipleFeeds)
        } else if (fs.singleFeed != null) {
            feedIds.add(fs.singleFeed)
        } else if (fs.singleSocialFeed != null) {
            socialFeedIds.add(fs.singleSocialFeed.key)
        } else if (fs.multipleSocialFeeds != null) {
            socialFeedIds.addAll(fs.multipleSocialFeeds.keys)
        } else {
            throw IllegalStateException("Asked to refresh story counts for FeedSet of unknown type.")
        }

        // now recount the number of unreads in each feed, one by one
        for (feedId in feedIds) {
            val singleFs = FeedSet.singleFeed(feedId)
            val values = ContentValues()
            values.put(DatabaseConstants.FEED_NEGATIVE_COUNT, getLocalUnreadCount(singleFs, StateFilter.NEG))
            values.put(DatabaseConstants.FEED_NEUTRAL_COUNT, getLocalUnreadCount(singleFs, StateFilter.NEUT))
            values.put(DatabaseConstants.FEED_POSITIVE_COUNT, getLocalUnreadCount(singleFs, StateFilter.BEST))
            synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.FEED_TABLE, values, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId)) }
        }

        for (socialId in socialFeedIds) {
            val singleFs = FeedSet.singleSocialFeed(socialId, "")
            val values = ContentValues()
            values.put(DatabaseConstants.SOCIAL_FEED_NEGATIVE_COUNT, getLocalUnreadCount(singleFs, StateFilter.NEG))
            values.put(DatabaseConstants.SOCIAL_FEED_NEUTRAL_COUNT, getLocalUnreadCount(singleFs, StateFilter.NEUT))
            values.put(DatabaseConstants.SOCIAL_FEED_POSITIVE_COUNT, getLocalUnreadCount(singleFs, StateFilter.BEST))
            synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.SOCIALFEED_TABLE, values, DatabaseConstants.SOCIAL_FEED_ID + " = ?", arrayOf(socialId)) }
        }
    }

    /**
     * Get the unread count for the given feedset based on local story state.
     */
    suspend fun getLocalUnreadCount(fs: FeedSet, stateFilter: StateFilter): Int = withContext(Dispatchers.IO) {
        val sel = java.lang.StringBuilder()
        val selArgs = java.util.ArrayList<String>()
        getLocalStorySelectionAndArgs(sel, selArgs, fs, stateFilter, ReadFilter.UNREAD)

        val c = dbRO.rawQuery(sel.toString(), selArgs.toTypedArray<String>())
        val count = c.count
        c.close()
        count
    }

    suspend fun clearInfrequentSession() = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_INFREQUENT, false)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.STORY_TABLE, values, null, null) }
    }

    suspend fun enqueueAction(ra: ReadingAction) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.insertOrThrow(DatabaseConstants.ACTION_TABLE, null, ra.toContentValues()) }
    }

    suspend fun getActions(): Cursor = withContext(Dispatchers.IO) {
        val q = "SELECT * FROM " + DatabaseConstants.ACTION_TABLE
        dbRO.rawQuery(q, null)
    }

    suspend fun incrementActionTried(actionId: String?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            val q = "UPDATE " + DatabaseConstants.ACTION_TABLE +
                    " SET " + DatabaseConstants.ACTION_TRIED + " = " + DatabaseConstants.ACTION_TRIED + " + 1" +
                    " WHERE " + DatabaseConstants.ACTION_ID + " = ?"
            dbRW.execSQL(q, arrayOf(actionId))
        }
    }

    suspend fun getUntriedActionCount(): Int = withContext(Dispatchers.IO) {
        val q = "SELECT * FROM " + DatabaseConstants.ACTION_TABLE + " WHERE " + DatabaseConstants.ACTION_TRIED + " < 1"
        val c = dbRO.rawQuery(q, null)
        val result = c.count
        c.close()
        result
    }

    suspend fun clearAction(actionId: String?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.ACTION_TABLE, DatabaseConstants.ACTION_ID + " = ?", arrayOf(actionId)) }
    }

    suspend fun setStoryStarred(hash: String, userTags: List<String?>?, starred: Boolean) = withContext(Dispatchers.IO) {
        // check the story's starting state and the desired state and adjust it as an atom so we
        // know if it truly changed or not and thus whether to update counts
        synchronized(RW_MUTEX) {
            dbRW.beginTransaction()
            try {
                // get a fresh copy of the story from the DB so we know if it changed
                val c = dbRW.query(DatabaseConstants.STORY_TABLE,
                        arrayOf(DatabaseConstants.STORY_STARRED),
                        DatabaseConstants.STORY_HASH + " = ?",
                        arrayOf(hash),
                        null, null, null)
                if (c.count < 1) {
                    android.util.Log.w(this.javaClass.name, "story removed before finishing mark-starred")
                    return@withContext
                }
                c.moveToFirst()
                val origState = (c.getInt(c.getColumnIndexOrThrow(DatabaseConstants.STORY_STARRED)) > 0)
                c.close()
                // if already stared, update user tags
                if (origState == starred && starred && userTags != null) {
                    val values = ContentValues()
                    values.put(DatabaseConstants.STORY_USER_TAGS, TextUtils.join(",", userTags))
                    dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash))
                    return@withContext
                } else if (origState == starred) {
                    return@withContext
                }
                // fix the state
                val values = ContentValues()
                values.put(DatabaseConstants.STORY_STARRED, starred)
                dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash))
                // adjust counts
                val operator = (if (starred) " + 1" else " - 1")
                val q = java.lang.StringBuilder("UPDATE " + DatabaseConstants.STARREDCOUNTS_TABLE)
                q.append(" SET " + DatabaseConstants.STARREDCOUNTS_COUNT + " = " + DatabaseConstants.STARREDCOUNTS_COUNT).append(operator)
                q.append(" WHERE " + DatabaseConstants.STARREDCOUNTS_TAG + " = '" + StarredCount.TOTAL_STARRED + "'")
                // TODO: adjust counts per feed (and tags?)
                dbRW.execSQL(q.toString())
                dbRW.setTransactionSuccessful()
            } finally {
                dbRW.endTransaction()
            }
        }
    }

    @SuppressLint("Range")
    suspend fun setStoryShared(hash: String?, currentUserId: String?, shared: Boolean) = withContext(Dispatchers.IO) {
        // get a fresh copy of the story from the DB so we can append to the shared ID set
        val c = dbRO.query(DatabaseConstants.STORY_TABLE,
                arrayOf(DatabaseConstants.STORY_SHARED_USER_IDS),
                DatabaseConstants.STORY_HASH + " = ?",
                arrayOf(hash),
                null, null, null)
        if ((c == null) || (c.count < 1)) {
            android.util.Log.w(this.javaClass.name, "story removed before finishing mark-shared")
            closeQuietly(c)
            return@withContext
        }
        c.moveToFirst()
        val sharedUserIds = TextUtils.split(c.getString(c.getColumnIndex(DatabaseConstants.STORY_SHARED_USER_IDS)), ",")
        closeQuietly(c)

        // append to set and update DB
        val newIds: MutableSet<String?> = java.util.HashSet(listOf(*sharedUserIds))
        // the id to append to or remove from the shared list (the current user)
        if (shared) {
            newIds.add(currentUserId)
        } else {
            newIds.remove(currentUserId)
        }
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_SHARED_USER_IDS, TextUtils.join(",", newIds))
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.STORY_TABLE, values, DatabaseConstants.STORY_HASH + " = ?", arrayOf(hash)) }
    }

    suspend fun getStoryText(hash: String?): String? = withContext(Dispatchers.IO) {
        val q = "SELECT " + DatabaseConstants.STORY_TEXT_STORY_TEXT +
                " FROM " + DatabaseConstants.STORY_TEXT_TABLE +
                " WHERE " + DatabaseConstants.STORY_TEXT_STORY_HASH + " = ?"
        val c = dbRO.rawQuery(q, arrayOf(hash))
        if (c.count < 1) {
            c.close()
            return@withContext null
        } else {
            c.moveToFirst()
            val result = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_TEXT_STORY_TEXT))
            c.close()
            return@withContext result
        }
    }

    suspend fun getStoryContent(hash: String?): String? = withContext(Dispatchers.IO) {
        val q = "SELECT " + DatabaseConstants.STORY_CONTENT +
                " FROM " + DatabaseConstants.STORY_TABLE +
                " WHERE " + DatabaseConstants.STORY_HASH + " = ?"
        val c = dbRO.rawQuery(q, arrayOf(hash))
        if (c.count < 1) {
            c.close()
            return@withContext null
        } else {
            c.moveToFirst()
            // TODO: may not contain col?
            val result = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.STORY_CONTENT))
            c.close()
            return@withContext result
        }
    }

    suspend fun putStoryText(hash: String?, text: String) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.STORY_TEXT_STORY_HASH, hash)
        values.put(DatabaseConstants.STORY_TEXT_STORY_TEXT, text)
        synchronized(RW_MUTEX) { dbRW.insertOrThrow(DatabaseConstants.STORY_TEXT_TABLE, null, values) }
    }

    suspend fun getSocialFeedsCursor(cancellationSignal: CancellationSignal): Cursor = withContext(Dispatchers.IO) {
        query(false, DatabaseConstants.SOCIALFEED_TABLE, null, null, null, null, null, "UPPER(" + DatabaseConstants.SOCIAL_FEED_TITLE + ") ASC", null, cancellationSignal)
    }

    suspend fun getSocialFeed(feedId: String?): SocialFeed? = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.SOCIALFEED_TABLE, null, DatabaseConstants.SOCIAL_FEED_ID + " = ?", arrayOf(feedId), null, null, null)
        var result: SocialFeed? = null
        while (c.moveToNext()) {
            result = SocialFeed.fromCursor(c)
        }
        c.close()
        result
    }

    suspend fun getStarredFeedByTag(tag: String): StarredCount? = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.STARREDCOUNTS_TABLE, null, DatabaseConstants.STARREDCOUNTS_TAG + " = ?", arrayOf(tag), null, null, null)
        var result: StarredCount? = null
        while (c.moveToNext()) {
            result = StarredCount.fromCursor(c)
        }
        c.close()
        result
    }

    suspend fun getFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val c = getFoldersCursor(null)
        val folders: MutableList<Folder> = java.util.ArrayList(c.count)
        while (c.moveToNext()) {
            folders.add(Folder.fromCursor(c))
        }
        c.close()
        folders
    }

    suspend fun getFoldersCursor(cancellationSignal: CancellationSignal?): Cursor = withContext(Dispatchers.IO) {
        query(false, DatabaseConstants.FOLDER_TABLE, null, null, null, null, null, null, null, cancellationSignal)
    }

    suspend fun getFeedsCursor(cancellationSignal: CancellationSignal): Cursor = withContext(Dispatchers.IO) {
        query(false, DatabaseConstants.FEED_TABLE, null, null, null, null, null, "UPPER(" + DatabaseConstants.FEED_TITLE + ") ASC", null, cancellationSignal)
    }

    suspend fun getSavedStoryCountsCursor(cancellationSignal: CancellationSignal): Cursor = withContext(Dispatchers.IO) {
        query(false, DatabaseConstants.STARREDCOUNTS_TABLE, null, null, null, null, null, null, null, cancellationSignal)
    }

    suspend fun getSavedSearchCursor(cancellationSignal: CancellationSignal): Cursor = withContext(Dispatchers.IO) {
        query(false, DatabaseConstants.SAVED_SEARCH_TABLE, null, null, null, null, null, null, null, cancellationSignal)
    }

    suspend fun getNotifyFocusStoriesCursor(): Cursor? = withContext(Dispatchers.IO) {
        rawQuery(DatabaseConstants.NOTIFY_FOCUS_STORY_QUERY, null, null)
    }

    suspend fun getNotifyUnreadStoriesCursor(): Cursor? = withContext(Dispatchers.IO) {
        rawQuery(DatabaseConstants.NOTIFY_UNREAD_STORY_QUERY, null, null)
    }

    suspend fun getNotifyFeeds(): Set<String> = withContext(Dispatchers.IO) {
        val q = "SELECT " + DatabaseConstants.FEED_ID + " FROM " + DatabaseConstants.FEED_TABLE +
                " WHERE " + DatabaseConstants.FEED_NOTIFICATION_FILTER + " = '" + Feed.NOTIFY_FILTER_FOCUS + "'" +
                " OR " + DatabaseConstants.FEED_NOTIFICATION_FILTER + " = '" + Feed.NOTIFY_FILTER_UNREAD + "'"
        val c = dbRO.rawQuery(q, null)
        val feedIds: MutableSet<String> = java.util.HashSet(c.count)
        while (c.moveToNext()) {
            val id = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.FEED_ID))
            if (id != null) {
                feedIds.add(id)
            }
        }
        c.close()
        feedIds
    }

    suspend fun getActiveStoriesCursor(fs: FeedSet, cursorFilters: CursorFilters, cancellationSignal: CancellationSignal): Cursor? = withContext(Dispatchers.IO) {
        // get the stories for this FS
        var result: Cursor? = getActiveStoriesCursorNoPrep(fs, cursorFilters.storyOrder, cancellationSignal)
        // if the result is blank, try to prime the session table with existing stories, in case we
        // are offline, but if a session is started, just use what was there so offsets don't change.
        if (result != null && result.count < 1) {
            if (AppConstants.VERBOSE_LOG) android.util.Log.d(this.javaClass.name, "priming reading session")
            prepareReadingSession(fs, cursorFilters.stateFilter, cursorFilters.readFilter)

            result = getActiveStoriesCursorNoPrep(fs, cursorFilters.storyOrder, cancellationSignal)
        }
        result
    }

    private suspend fun getActiveStoriesCursorNoPrep(fs: FeedSet, order: StoryOrder, cancellationSignal: CancellationSignal): Cursor? = withContext(Dispatchers.IO) {
        // stories aren't actually queried directly via the FeedSet and filters set in the UI. rather,
        // those filters are use to push live or cached story hashes into the reading session table, and
        // those hashes are used to pull story data from the story table
        val q = StringBuilder(DatabaseConstants.SESSION_STORY_QUERY_BASE)

        if (fs.isAllRead) {
            q.append(" ORDER BY ").append(DatabaseConstants.READ_STORY_ORDER)
        } else if (fs.isGlobalShared) {
            q.append(" ORDER BY ").append(DatabaseConstants.SHARED_STORY_ORDER)
        } else if (fs.isAllSaved) {
            q.append(" ORDER BY ").append(DatabaseConstants.getSavedStoriesSortOrder(order))
        } else {
            q.append(" ORDER BY ").append(DatabaseConstants.getStorySortOrder(order))
        }
        rawQuery(q.toString(), null, cancellationSignal)
    }

    suspend fun clearStorySession() = withContext(Dispatchers.IO) {
        Log.i(this, "reading session reset")
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.READING_SESSION_TABLE, null, null) }
    }

    /**
     * Populates the reading session table with hashes of already-fetched stories that meet the
     * criteria for the given FeedSet and filters; these hashes will be supplemented by hashes
     * fetched via the API and used to actually select story data when rendering story lists.
     */
    suspend fun prepareReadingSession(fs: FeedSet, stateFilter: StateFilter, readFilter: ReadFilter) = withContext(Dispatchers.IO) {
        // a selection filter that will be used to pull active story hashes from the stories table into the reading session table
        val sel = java.lang.StringBuilder()
        // any selection args that need to be used within the inner select statement
        val selArgs = java.util.ArrayList<String>()

        getLocalStorySelectionAndArgs(sel, selArgs, fs, stateFilter, readFilter)

        // use the inner select statement to push the active hashes into the session table
        val q = java.lang.StringBuilder("INSERT INTO " + DatabaseConstants.READING_SESSION_TABLE)
        q.append(" (" + DatabaseConstants.READING_SESSION_STORY_HASH + ") ")
        q.append(sel)

        synchronized(RW_MUTEX) { dbRW.execSQL(q.toString(), selArgs.toTypedArray<String>()) }
    }

    /**
     * Gets hashes of already-fetched stories that satisfy the given FeedSet and filters. Can be used
     * both to populate a reading session or to count local unreads.
     */
    private suspend fun getLocalStorySelectionAndArgs(sel: java.lang.StringBuilder, selArgs: MutableList<String>, fs: FeedSet, stateFilter: StateFilter, readFilter: ReadFilter) = withContext(Dispatchers.IO) {
        // if the user has requested saved stories, ignore the unreads filter, as saveds do not have this state
        var stateFilter = stateFilter
        var readFilter = readFilter
        if (fs.isFilterSaved) {
            readFilter = ReadFilter.ALL
        }

        sel.append("SELECT " + DatabaseConstants.STORY_HASH)
        if (fs.singleFeed != null) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE " + DatabaseConstants.STORY_FEED_ID + " = ?")
            selArgs.add(fs.singleFeed)
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.multipleFeeds != null) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE " + DatabaseConstants.STORY_TABLE + "." + DatabaseConstants.STORY_FEED_ID + " IN ( ")
            sel.append(TextUtils.join(",", fs.multipleFeeds)).append(")")
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.singleSocialFeed != null) {
            sel.append(" FROM " + DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE)
            sel.append(DatabaseConstants.JOIN_STORIES_ON_SOCIALFEED_MAP)
            sel.append(" WHERE " + DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE + "." + DatabaseConstants.SOCIALFEED_STORY_USER_ID + " = ? ")
            selArgs.add(fs.singleSocialFeed.key)
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.isAllNormal) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE 1")
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.isAllSocial) {
            sel.append(" FROM " + DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE)
            sel.append(DatabaseConstants.JOIN_STORIES_ON_SOCIALFEED_MAP)
            if (stateFilter == StateFilter.SAVED) stateFilter = StateFilter.SOME
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.isAllRead) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE (" + DatabaseConstants.STORY_LAST_READ_DATE + " > 0)")
        } else if (fs.isAllSaved) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE (" + DatabaseConstants.STORY_STARRED + " = 1)")
            DatabaseConstants.appendStorySelection(sel, selArgs, ReadFilter.ALL, StateFilter.ALL, fs.searchQuery)
        } else if (fs.isInfrequent) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE (" + DatabaseConstants.STORY_INFREQUENT + " = 1)")
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else if (fs.singleSavedTag != null) {
            sel.append(" FROM " + DatabaseConstants.STORY_TABLE)
            sel.append(" WHERE (" + DatabaseConstants.STORY_STARRED + " = 1)")
            sel.append(" AND (" + DatabaseConstants.STORY_USER_TAGS + " LIKE ?)")
            val tagArg = java.lang.StringBuilder("%")
            tagArg.append(fs.singleSavedTag).append("%")
            selArgs.add(tagArg.toString())
            DatabaseConstants.appendStorySelection(sel, selArgs, ReadFilter.ALL, StateFilter.ALL, fs.searchQuery)
        } else if (fs.isGlobalShared) {
            sel.append(" FROM " + DatabaseConstants.SOCIALFEED_STORY_MAP_TABLE)
            sel.append(DatabaseConstants.JOIN_STORIES_ON_SOCIALFEED_MAP)
            if (stateFilter == StateFilter.SAVED) stateFilter = StateFilter.SOME
            DatabaseConstants.appendStorySelection(sel, selArgs, readFilter, stateFilter, fs.searchQuery)
        } else {
            throw IllegalStateException("Asked to get stories for FeedSet of unknown type.")
        }
    }

    suspend fun setSessionFeedSet(fs: FeedSet?) = withContext(Dispatchers.IO) {
        if (fs == null) {
            synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.SYNC_METADATA_TABLE, DatabaseConstants.SYNC_METADATA_KEY + " = ?", arrayOf(DatabaseConstants.SYNC_METADATA_KEY_SESSION_FEED_SET)) }
        } else {
            val values = ContentValues()
            values.put(DatabaseConstants.SYNC_METADATA_KEY, DatabaseConstants.SYNC_METADATA_KEY_SESSION_FEED_SET)
            values.put(DatabaseConstants.SYNC_METADATA_VALUE, fs.toCompactSerial())
            synchronized(RW_MUTEX) { dbRW.insertWithOnConflict(DatabaseConstants.SYNC_METADATA_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }

    suspend fun getSessionFeedSet(): FeedSet? = withContext(Dispatchers.IO) {
        val fs: FeedSet
        val c = dbRO.query(DatabaseConstants.SYNC_METADATA_TABLE, null, DatabaseConstants.SYNC_METADATA_KEY + " = ?", arrayOf(DatabaseConstants.SYNC_METADATA_KEY_SESSION_FEED_SET), null, null, null, null)
        if (c.count < 1) return@withContext null
        c.moveToFirst()
        fs = FeedSet.fromCompactSerial(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.SYNC_METADATA_VALUE)))
        closeQuietly(c)
        fs
    }

    suspend fun isFeedSetReady(fs: FeedSet?): Boolean = withContext(Dispatchers.IO) {
        fs == getSessionFeedSet()
    }

    suspend fun clearClassifiersForFeed(feedId: String?) = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(feedId)
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.CLASSIFIER_TABLE, DatabaseConstants.CLASSIFIER_ID + " = ?", selArgs) }
    }

    suspend fun insertClassifier(classifier: Classifier) = withContext(Dispatchers.IO) {
        bulkInsertValues(DatabaseConstants.CLASSIFIER_TABLE, classifier.contentValues)
    }

    suspend fun getClassifierForFeed(feedId: String?): Classifier = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(feedId)
        val c = dbRO.query(DatabaseConstants.CLASSIFIER_TABLE, null, DatabaseConstants.CLASSIFIER_ID + " = ?", selArgs, null, null, null)
        val classifier = Classifier.fromCursor(c)
        closeQuietly(c)
        classifier.feedId = feedId
        classifier
    }

    suspend fun getComments(storyId: String): List<Comment> = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(storyId)
        val selection = DatabaseConstants.COMMENT_STORYID + " = ?"
        val c = dbRO.query(DatabaseConstants.COMMENT_TABLE, null, selection, selArgs, null, null, null)
        val comments: MutableList<Comment> = java.util.ArrayList(c.count)
        while (c.moveToNext()) {
            comments.add(Comment.fromCursor(c))
        }
        closeQuietly(c)
        comments
    }

    @WorkerThread
    fun getComment(storyId: String?, userId: String?): Comment? {
        val selection = DatabaseConstants.COMMENT_STORYID + " = ? AND " + DatabaseConstants.COMMENT_USERID + " = ?"
        val selArgs = arrayOf(storyId, userId)
        val c = dbRO.query(DatabaseConstants.COMMENT_TABLE, null, selection, selArgs, null, null, null)
        if (c.count < 1) return null
        c.moveToFirst()
        val comment = Comment.fromCursor(c)
        closeQuietly(c)
        return comment
    }

    /**
     * Insert brand new comment for which we do not yet have a server-assigned ID.  This comment
     * will show up in the UI with reduced functionality until the server gets back to us with
     * an ID at which time the placeholder will be removed.
     */
    suspend fun insertCommentPlaceholder(storyId: String?, userId: String?, commentText: String?) = withContext(Dispatchers.IO) {
        val comment = Comment()
        comment.isPlaceholder = true
        comment.id = Comment.PLACEHOLDER_COMMENT_ID + storyId + userId
        comment.storyId = storyId
        comment.userId = userId
        comment.commentText = commentText
        comment.byFriend = true
        if (TextUtils.isEmpty(commentText)) {
            comment.isPseudo = true
        }
        synchronized(RW_MUTEX) {
            // in order to make this method idempotent (so it can be attempted before, during, or after
            // the real comment is done, we have to check for a real one
            if (getComment(storyId, userId) != null) {
                Log.i(this.javaClass.name, "electing not to insert placeholder comment over live one")
                return@withContext
            }
            dbRW.insertWithOnConflict(DatabaseConstants.COMMENT_TABLE, null, comment.values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun editReply(replyId: String?, replyText: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.REPLY_TEXT, replyText)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.REPLY_TABLE, values, DatabaseConstants.REPLY_ID + " = ?", arrayOf(replyId)) }
    }

    suspend fun deleteReply(replyId: String?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) { dbRW.delete(DatabaseConstants.REPLY_TABLE, DatabaseConstants.REPLY_ID + " = ?", arrayOf(replyId)) }
    }

    suspend fun clearSelfComments(storyId: String?, userId: String?) = withContext(Dispatchers.IO) {
        synchronized(RW_MUTEX) {
            dbRW.delete(DatabaseConstants.COMMENT_TABLE,
                    DatabaseConstants.COMMENT_STORYID + " = ? AND " + DatabaseConstants.COMMENT_USERID + " = ?",
                    arrayOf(storyId, userId))
        }
    }

    suspend fun setCommentLiked(storyId: String?, commentUserId: String?, currentUserId: String?, liked: Boolean) = withContext(Dispatchers.IO) {
        // get a fresh copy of the story from the DB so we can append to the shared ID set
        val c = dbRO.query(DatabaseConstants.COMMENT_TABLE,
                null,
                DatabaseConstants.COMMENT_STORYID + " = ? AND " + DatabaseConstants.COMMENT_USERID + " = ?",
                arrayOf(storyId, commentUserId),
                null, null, null)
        if ((c == null) || (c.count < 1)) {
            Log.w(this.javaClass.name, "comment removed before finishing mark-liked")
            closeQuietly(c)
            return@withContext
        }
        c.moveToFirst()
        val comment = Comment.fromCursor(c)
        closeQuietly(c)

        // append to set and update DB
        val newIds: MutableSet<String?> = HashSet(listOf(*comment.likingUsers))
        // the new id to append/remove from the liking list (the current user)
        if (liked) {
            newIds.add(currentUserId)
        } else {
            newIds.remove(currentUserId)
        }
        val values = ContentValues()
        values.put(DatabaseConstants.COMMENT_LIKING_USERS, TextUtils.join(",", newIds))
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.COMMENT_TABLE, values, DatabaseConstants.COMMENT_ID + " = ?", arrayOf(comment.id)) }
    }

    suspend fun getUserProfile(userId: String?): UserProfile? = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(userId)
        val selection = DatabaseConstants.USER_USERID + " = ?"
        val c = dbRO.query(DatabaseConstants.USER_TABLE, null, selection, selArgs, null, null, null)
        val profile = UserProfile.fromCursor(c)
        closeQuietly(c)
        profile
    }

    suspend fun getCommentReplies(commentId: String?): List<Reply> = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(commentId)
        val selection = DatabaseConstants.REPLY_COMMENTID + " = ?"
        val c = dbRO.query(DatabaseConstants.REPLY_TABLE, null, selection, selArgs, null, null, DatabaseConstants.REPLY_DATE + " ASC")
        val replies: MutableList<Reply> = java.util.ArrayList(c.count)
        while (c.moveToNext()) {
            replies.add(Reply.fromCursor(c))
        }
        closeQuietly(c)
        replies
    }

    suspend fun insertReplyPlaceholder(storyId: String?, userId: String?, commentUserId: String?, replyText: String?) = withContext(Dispatchers.IO) {
        // get a fresh copy of the comment so we can discover the ID
        val c = dbRO.query(DatabaseConstants.COMMENT_TABLE,
                null,
                DatabaseConstants.COMMENT_STORYID + " = ? AND " + DatabaseConstants.COMMENT_USERID + " = ?",
                arrayOf(storyId, commentUserId),
                null, null, null)
        if ((c == null) || (c.count < 1)) {
            Log.w(this, "comment removed before reply could be processed")
            closeQuietly(c)
            return@withContext
        }
        c.moveToFirst()
        val comment = Comment.fromCursor(c)
        closeQuietly(c)

        val reply = Reply()
        reply.commentId = comment.id
        reply.text = replyText
        reply.userId = userId
        reply.date = Date()
        reply.id = Reply.PLACEHOLDER_COMMENT_ID + storyId + comment.id + reply.userId
        synchronized(RW_MUTEX) { dbRW.insertWithOnConflict(DatabaseConstants.REPLY_TABLE, null, reply.values, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    suspend fun putStoryDismissed(storyHash: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.NOTIFY_DISMISS_STORY_HASH, storyHash)
        values.put(DatabaseConstants.NOTIFY_DISMISS_TIME, Calendar.getInstance().time.time)
        synchronized(RW_MUTEX) { dbRW.insertOrThrow(DatabaseConstants.NOTIFY_DISMISS_TABLE, null, values) }
    }

    suspend fun isStoryDismissed(storyHash: String?): Boolean = withContext(Dispatchers.IO) {
        val selArgs = arrayOf(storyHash)
        val selection = DatabaseConstants.NOTIFY_DISMISS_STORY_HASH + " = ?"
        val c = dbRO.query(DatabaseConstants.NOTIFY_DISMISS_TABLE, null, selection, selArgs, null, null, null)
        val result = (c.count > 0)
        closeQuietly(c)
        result
    }

    suspend fun cleanupDismissals() = withContext(Dispatchers.IO) {
        val cutoffDate = Calendar.getInstance()
        cutoffDate.add(Calendar.MONTH, -1)
        synchronized(RW_MUTEX) {
            val count = dbRW.delete(DatabaseConstants.NOTIFY_DISMISS_TABLE,
                    DatabaseConstants.NOTIFY_DISMISS_TIME + " < ?",
                    arrayOf(cutoffDate.time.time.toString()))
            Log.d(this.javaClass.name, "cleaned up dismissals: $count")
        }
    }

    private fun putFeedTagsExtSync(feedId: String?, tags: Collection<String>) {
        dbRW.delete(DatabaseConstants.FEED_TAGS_TABLE,
                DatabaseConstants.FEED_TAGS_FEEDID + " = ?",
                arrayOf(feedId)
        )
        val valuesList: MutableList<ContentValues> = java.util.ArrayList(tags.size)
        for (tag in tags) {
            val values = ContentValues()
            values.put(DatabaseConstants.FEED_TAGS_FEEDID, feedId)
            values.put(DatabaseConstants.FEED_TAGS_TAG, tag)
            valuesList.add(values)
        }
        bulkInsertValuesExtSync(DatabaseConstants.FEED_TAGS_TABLE, valuesList)
    }

    suspend fun getTagsForFeed(feedId: String?): List<String> = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.FEED_TAGS_TABLE,
                arrayOf(DatabaseConstants.FEED_TAGS_TAG),
                DatabaseConstants.FEED_TAGS_FEEDID + " = ?",
                arrayOf(feedId),
                null,
                null,
                DatabaseConstants.FEED_TAGS_TAG + " ASC"
        )
        val result: MutableList<String> = java.util.ArrayList(c.count)
        while (c.moveToNext()) {
            result.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.FEED_TAGS_TAG)))
        }
        closeQuietly(c)
        result
    }

    private fun putFeedAuthorsExtSync(feedId: String?, authors: Collection<String>) {
        dbRW.delete(DatabaseConstants.FEED_AUTHORS_TABLE,
                DatabaseConstants.FEED_AUTHORS_FEEDID + " = ?",
                arrayOf(feedId)
        )
        val valuesList: MutableList<ContentValues> = java.util.ArrayList(authors.size)
        for (author in authors) {
            val values = ContentValues()
            values.put(DatabaseConstants.FEED_AUTHORS_FEEDID, feedId)
            values.put(DatabaseConstants.FEED_AUTHORS_AUTHOR, author)
            valuesList.add(values)
        }
        bulkInsertValuesExtSync(DatabaseConstants.FEED_AUTHORS_TABLE, valuesList)
    }

    suspend fun getAuthorsForFeed(feedId: String?): List<String> = withContext(Dispatchers.IO) {
        val c = dbRO.query(DatabaseConstants.FEED_AUTHORS_TABLE,
                arrayOf(DatabaseConstants.FEED_AUTHORS_AUTHOR),
                DatabaseConstants.FEED_AUTHORS_FEEDID + " = ?",
                arrayOf(feedId),
                null,
                null,
                DatabaseConstants.FEED_AUTHORS_AUTHOR + " ASC"
        )
        val result: MutableList<String> = java.util.ArrayList(c.count)
        while (c.moveToNext()) {
            result.add(c.getString(c.getColumnIndexOrThrow(DatabaseConstants.FEED_AUTHORS_AUTHOR)))
        }
        closeQuietly(c)
        result
    }

    suspend fun renameFeed(feedId: String?, newFeedName: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        values.put(DatabaseConstants.FEED_TITLE, newFeedName)
        synchronized(RW_MUTEX) { dbRW.update(DatabaseConstants.FEED_TABLE, values, DatabaseConstants.FEED_ID + " = ?", arrayOf(feedId)) }
    }

    fun closeQuietly(c: Cursor?) {
        if (c == null) return
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    private fun conjoinSelections(vararg args: CharSequence?): String? {
        var s: StringBuilder? = null
        for (c in args) {
            if (c == null) continue
            if (s == null) {
                s = java.lang.StringBuilder(c)
            } else {
                s.append(" AND ")
                s.append(c)
            }
        }
        if (s == null) return null
        return s.toString()
    }

    /**
     * Invoke the rawQuery() method on our read-only SQLiteDatabase memeber using the provided CancellationSignal
     * only if the device's platform provides support.
     */
    private suspend fun rawQuery(sql: String, selectionArgs: Array<String>?, cancellationSignal: CancellationSignal?): Cursor? = withContext(Dispatchers.IO) {
        if (AppConstants.VERBOSE_LOG_DB) {
            android.util.Log.d(this.javaClass.name, String.format("DB rawQuery: '%s' with args: %s", sql, selectionArgs.contentToString()))
        }
        dbRO.rawQuery(sql, selectionArgs, cancellationSignal)
    }

    /**
     * Invoke the query() method on our read-only SQLiteDatabase memeber using the provided CancellationSignal
     * only if the device's platform provides support.
     */
    private suspend fun query(distinct: Boolean, table: String, columns: Array<String>?, selection: String?, selectionArgs: Array<String>?, groupBy: String?, having: String?, orderBy: String?, limit: String?, cancellationSignal: CancellationSignal?): Cursor = withContext(Dispatchers.IO) {
        dbRO.query(distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy, limit, cancellationSignal)
    }

    suspend fun feedSetFromFolderName(folderName: String): FeedSet = withContext(Dispatchers.IO) {
        FeedSet.folder(folderName, getFeedIdsRecursive(folderName))
    }

    private suspend fun getFeedIdsRecursive(folderName: String): Set<String> = withContext(Dispatchers.IO) {
        val folder = getFolder(folderName) ?: return@withContext emptySet()
        val feedIds: MutableSet<String> = java.util.HashSet(folder.feedIds)
        for (child in folder.children) feedIds.addAll(getFeedIdsRecursive(child))
        feedIds
    }
}