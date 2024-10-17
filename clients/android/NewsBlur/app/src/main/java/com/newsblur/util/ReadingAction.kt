package com.newsblur.util

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.DatabaseConstants
import com.newsblur.domain.Classifier
import com.newsblur.network.APIManager
import com.newsblur.network.domain.CommentResponse
import com.newsblur.network.domain.NewsBlurResponse
import com.newsblur.network.domain.StoriesResponse
import com.newsblur.service.NBSyncService
import com.newsblur.service.NbSyncManager
import com.newsblur.service.NbSyncManager.UPDATE_INTEL
import com.newsblur.service.NbSyncManager.UPDATE_METADATA
import com.newsblur.service.NbSyncManager.UPDATE_SOCIAL
import com.newsblur.service.NbSyncManager.UPDATE_STORY
import java.io.Serializable

class ReadingAction private constructor(
        private val type: ActionType,
        private var time: Long = System.currentTimeMillis(),
        private var tried: Int = 0,
) : Serializable {

    private enum class ActionType {
        MARK_READ,
        MARK_UNREAD,
        SAVE,
        UNSAVE,
        SHARE,
        UNSHARE,
        REPLY,
        EDIT_REPLY,
        DELETE_REPLY,
        LIKE_COMMENT,
        UNLIKE_COMMENT,
        MUTE_FEEDS,
        UNMUTE_FEEDS,
        SET_NOTIFY,
        INSTA_FETCH,
        UPDATE_INTEL,
        RENAME_FEED
    }

    private var storyHash: String? = null
    private var feedSet: FeedSet? = null
    private var olderThan: Long? = null
    private var newerThan: Long? = null
    private var storyId: String? = null
    private var feedId: String? = null
    private var sourceUserId: String? = null
    private var commentReplyText: String? = null // used for both comments and replies
    private var commentUserId: String? = null
    private var replyId: String? = null
    private var notifyFilter: String? = null
    private var notifyTypes: List<String>? = null
    private var userTags: List<String>? = null
    private var classifier: Classifier? = null
    private var newFeedName: String? = null

    // For mute/unmute the API call is always the active feed IDs.
    // We need the feed Ids being modified for the local call.
    private var activeFeedIds: Set<String>? = null
    private var modifiedFeedIds: Set<String>? = null

    fun getTries() = tried

    fun toContentValues(): ContentValues {
        val values = ContentValues()
        values.put(DatabaseConstants.ACTION_TIME, time)
        values.put(DatabaseConstants.ACTION_TRIED, tried)
        // because ReadingActions will have to represent a wide and ever-growing variety of interactions,
        // the number of parameters will continue growing unbounded.  to avoid having to frequently modify the
        // database and support a table with dozens or hundreds of columns that are only ever used at a low
        // cardinality, only the ACTION_TIME and ACTION_TRIED values are stored in columns of their own, and
        // all remaining fields are frozen as JSON, since they are never queried upon.
        values.put(DatabaseConstants.ACTION_PARAMS, DatabaseConstants.JsonHelper.toJson(this))
        return values
    }

    /**
     * Execute this action remotely via the API.
     */
    fun doRemote(apiManager: APIManager, dbHelper: BlurDatabaseHelper, stateFilter: StateFilter): NewsBlurResponse? {
        // generic response to return
        var result: NewsBlurResponse? = null
        // optional specific responses that are locally actionable
        var storiesResponse: StoriesResponse? = null
        var commentResponse: CommentResponse? = null
        var impact = 0
        when (type) {
            ActionType.MARK_READ -> if (storyHash != null) {
                result = apiManager.markStoryAsRead(storyHash)
            } else if (feedSet != null) {
                result = apiManager.markFeedsAsRead(feedSet, olderThan, newerThan)
            }

            ActionType.MARK_UNREAD -> result = apiManager.markStoryHashUnread(storyHash)
            ActionType.SAVE -> result = apiManager.markStoryAsStarred(storyHash, userTags)
            ActionType.UNSAVE -> result = apiManager.markStoryAsUnstarred(storyHash)
            ActionType.SHARE -> storiesResponse = apiManager.shareStory(storyId, feedId, commentReplyText, sourceUserId)
            ActionType.UNSHARE -> storiesResponse = apiManager.unshareStory(storyId, feedId)
            ActionType.LIKE_COMMENT -> result = apiManager.favouriteComment(storyId, commentUserId, feedId)
            ActionType.UNLIKE_COMMENT -> result = apiManager.unFavouriteComment(storyId, commentUserId, feedId)
            ActionType.REPLY -> commentResponse = apiManager.replyToComment(storyId, feedId, commentUserId, commentReplyText)
            ActionType.EDIT_REPLY -> commentResponse = apiManager.editReply(storyId, feedId, commentUserId, replyId, commentReplyText)
            ActionType.DELETE_REPLY -> commentResponse = apiManager.deleteReply(storyId, feedId, commentUserId, replyId)
            ActionType.MUTE_FEEDS, ActionType.UNMUTE_FEEDS -> result = apiManager.saveFeedChooser(activeFeedIds)
            ActionType.SET_NOTIFY -> result = apiManager.updateFeedNotifications(feedId, notifyTypes, notifyFilter)
            ActionType.INSTA_FETCH -> {
                result = apiManager.instaFetch(feedId)
                // also trigger a recount, which will unflag the feed as pending
                NBSyncService.addRecountCandidates(FeedSet.singleFeed(feedId))
                NBSyncService.flushRecounts()
            }

            ActionType.UPDATE_INTEL -> {
                result = apiManager.updateFeedIntel(feedId, classifier)
                // also reset stories for the calling view so they get new scores
                NBSyncService.resetFetchState(feedSet)
                // and recount unreads to get new focus counts
                NBSyncService.addRecountCandidates(feedSet)
            }

            ActionType.RENAME_FEED -> result = apiManager.renameFeed(feedId, newFeedName)
            else -> throw IllegalStateException("cannot execute uknown type of action.")

        }
        if (storiesResponse != null) {
            result = storiesResponse
            if (storiesResponse.story != null) {
                dbHelper.updateStory(storiesResponse, stateFilter, true)
            } else {
                Log.w(this, "failed to refresh story data after action")
            }
            impact = impact or NbSyncManager.UPDATE_SOCIAL
        }
        if (commentResponse != null) {
            result = commentResponse
            if (commentResponse.comment != null) {
                dbHelper.updateComment(commentResponse, storyId)
            } else {
                Log.w(this, "failed to refresh comment data after action")
            }
            impact = impact or NbSyncManager.UPDATE_SOCIAL
        }
        if (result != null && impact != 0) {
            result.impactCode = impact
        }
        return result
    }

    /**
     * Excecute this action on the local DB. These *must* be idempotent.
     *
     * @param isFollowup flag that this is a double-check invocation and is noncritical
     *
     * @return the union of update impact flags that resulted from this action.
     */
    fun doLocal(context: Context, dbHelper: BlurDatabaseHelper, isFollowup: Boolean = false): Int {
        val userId = PrefsUtils.getUserId(context)
        var impact = 0
        when (type) {
            ActionType.MARK_READ -> {
                storyHash?.let {
                    dbHelper.setStoryReadState(it, true)
                } ?: feedSet?.let {
                    dbHelper.markStoriesRead(it, olderThan, newerThan)
                    dbHelper.updateLocalFeedCounts(it)
                }
                impact = impact or UPDATE_METADATA
                impact = impact or UPDATE_STORY
            }

            ActionType.MARK_UNREAD -> {
                dbHelper.setStoryReadState(storyHash, false)
                impact = impact or UPDATE_METADATA
            }

            ActionType.SAVE -> {
                dbHelper.setStoryStarred(storyHash, userTags, true)
                impact = impact or UPDATE_METADATA
            }

            ActionType.UNSAVE -> {
                dbHelper.setStoryStarred(storyHash, null, false)
                impact = impact or UPDATE_METADATA
            }

            ActionType.SHARE -> {
                if (!isFollowup) { // shares are only placeholders
                    dbHelper.setStoryShared(storyHash, userId, true)
                    dbHelper.insertCommentPlaceholder(storyId, userId, commentReplyText)
                    impact = impact or UPDATE_SOCIAL
                    impact = impact or UPDATE_STORY
                }
            }

            ActionType.UNSHARE -> {
                dbHelper.setStoryShared(storyHash, userId, false)
                dbHelper.clearSelfComments(storyId, userId)
                impact = impact or UPDATE_SOCIAL
                impact = impact or UPDATE_STORY
            }

            ActionType.LIKE_COMMENT -> {
                dbHelper.setCommentLiked(storyId, commentUserId, userId, true)
                impact = impact or UPDATE_SOCIAL
            }

            ActionType.UNLIKE_COMMENT -> {
                dbHelper.setCommentLiked(storyId, commentUserId, userId, false)
                impact = impact or UPDATE_SOCIAL
            }

            ActionType.REPLY -> {
                if (!isFollowup) { // replies are only placeholders
                    dbHelper.insertReplyPlaceholder(storyId, userId, commentUserId, commentReplyText)
                }
            }

            ActionType.EDIT_REPLY -> {
                dbHelper.editReply(replyId, commentReplyText)
                impact = impact or UPDATE_SOCIAL
            }

            ActionType.DELETE_REPLY -> {
                dbHelper.deleteReply(replyId)
                impact = impact or UPDATE_SOCIAL
            }

            ActionType.MUTE_FEEDS, ActionType.UNMUTE_FEEDS -> {
                modifiedFeedIds?.let {
                    dbHelper.setFeedsActive(it, type == ActionType.UNMUTE_FEEDS)
                }
                impact = impact or UPDATE_METADATA
            }

            ActionType.SET_NOTIFY -> impact = impact or UPDATE_METADATA
            ActionType.INSTA_FETCH -> {
                if (!isFollowup) { // non-idempotent and purely graphical
                    feedId?.let {
                        dbHelper.setFeedFetchPending(it)
                    }
                }
            }

            ActionType.UPDATE_INTEL -> {
                // TODO: because intel is always calculated on the server, we can change the disposition of
                // individual tags and authors etc in the UI, but story scores won't be updated until a refresh.
                // for best offline operation, we could try to duplicate that business logic locally
                dbHelper.clearClassifiersForFeed(feedId)
                classifier?.let {
                    it.feedId = feedId
                    dbHelper.insertClassifier(it)
                }
                impact = impact or UPDATE_INTEL
            }

            ActionType.RENAME_FEED -> {
                dbHelper.renameFeed(feedId, newFeedName)
                impact = impact or UPDATE_METADATA
            }
        }
        return impact
    }

    companion object {

        val serialVersionUID: Long = 0L

        fun markStoryRead(hash: String?): ReadingAction {
            val ra = ReadingAction(ActionType.MARK_READ)
            ra.storyHash = hash
            return ra
        }

        fun markStoryUnread(hash: String?): ReadingAction {
            val ra = ReadingAction(ActionType.MARK_UNREAD)
            ra.storyHash = hash
            return ra
        }

        fun saveStory(hash: String?, userTags: List<String>?): ReadingAction {
            val ra = ReadingAction(ActionType.SAVE)
            ra.storyHash = hash
            if (userTags == null) {
                ra.userTags = ArrayList()
            } else {
                ra.userTags = userTags
            }
            return ra
        }

        fun unsaveStory(hash: String?): ReadingAction {
            val ra = ReadingAction(ActionType.UNSAVE)
            ra.storyHash = hash
            return ra
        }

        fun markFeedRead(fs: FeedSet, olderThan: Long?, newerThan: Long?): ReadingAction {
            val ra = ReadingAction(ActionType.MARK_READ)
            ra.feedSet = fs
            ra.olderThan = olderThan
            ra.newerThan = newerThan
            return ra
        }

        fun shareStory(hash: String?, storyId: String?, feedId: String?, sourceUserId: String?, commentReplyText: String?): ReadingAction {
            val ra = ReadingAction(ActionType.SHARE)
            ra.storyHash = hash
            ra.storyId = storyId
            ra.feedId = feedId
            ra.sourceUserId = sourceUserId
            ra.commentReplyText = commentReplyText
            return ra
        }

        fun unshareStory(hash: String?, storyId: String?, feedId: String?): ReadingAction {
            val ra = ReadingAction(ActionType.UNSHARE)
            ra.storyHash = hash
            ra.storyId = storyId
            ra.feedId = feedId
            return ra
        }

        fun likeComment(storyId: String?, commentUserId: String?, feedId: String?): ReadingAction {
            val ra = ReadingAction(ActionType.LIKE_COMMENT)
            ra.storyId = storyId
            ra.commentUserId = commentUserId
            ra.feedId = feedId
            return ra
        }

        fun unlikeComment(storyId: String?, commentUserId: String?, feedId: String?): ReadingAction {
            val ra = ReadingAction(ActionType.UNLIKE_COMMENT)
            ra.storyId = storyId
            ra.commentUserId = commentUserId
            ra.feedId = feedId
            return ra
        }

        fun replyToComment(storyId: String?, feedId: String?, commentUserId: String?, commentReplyText: String?): ReadingAction {
            val ra = ReadingAction(ActionType.REPLY)
            ra.storyId = storyId
            ra.commentUserId = commentUserId
            ra.feedId = feedId
            ra.commentReplyText = commentReplyText
            return ra
        }

        fun updateReply(storyId: String?, feedId: String?, commentUserId: String?, replyId: String?, commentReplyText: String?): ReadingAction {
            val ra = ReadingAction(ActionType.EDIT_REPLY)
            ra.storyId = storyId
            ra.commentUserId = commentUserId
            ra.feedId = feedId
            ra.commentReplyText = commentReplyText
            ra.replyId = replyId
            return ra
        }

        fun deleteReply(storyId: String?, feedId: String?, commentUserId: String?, replyId: String?): ReadingAction {
            val ra = ReadingAction(ActionType.DELETE_REPLY)
            ra.storyId = storyId
            ra.commentUserId = commentUserId
            ra.feedId = feedId
            ra.replyId = replyId
            return ra
        }

        fun muteFeeds(activeFeedIds: Set<String>, modifiedFeedIds: Set<String>): ReadingAction {
            val ra = ReadingAction(ActionType.MUTE_FEEDS)
            ra.activeFeedIds = activeFeedIds
            ra.modifiedFeedIds = modifiedFeedIds
            return ra
        }

        fun unmuteFeeds(activeFeedIds: Set<String>, modifiedFeedIds: Set<String>): ReadingAction {
            val ra = ReadingAction(ActionType.UNMUTE_FEEDS)
            ra.activeFeedIds = activeFeedIds
            ra.modifiedFeedIds = modifiedFeedIds
            return ra
        }

        fun setNotify(feedId: String?, notifyTypes: List<String>?, notifyFilter: String?): ReadingAction {
            val ra = ReadingAction(ActionType.SET_NOTIFY)
            ra.feedId = feedId
            if (notifyTypes == null) {
                ra.notifyTypes = ArrayList()
            } else {
                ra.notifyTypes = notifyTypes
            }
            ra.notifyFilter = notifyFilter
            return ra
        }

        fun instaFetch(feedId: String?): ReadingAction {
            val ra = ReadingAction(ActionType.INSTA_FETCH)
            ra.feedId = feedId
            return ra
        }

        fun updateIntel(feedId: String?, classifier: Classifier?, fs: FeedSet?): ReadingAction {
            val ra = ReadingAction(ActionType.UPDATE_INTEL)
            ra.feedId = feedId
            ra.classifier = classifier
            ra.feedSet = fs
            return ra
        }

        fun renameFeed(feedId: String?, newFeedName: String?): ReadingAction {
            val ra = ReadingAction(ActionType.RENAME_FEED)
            ra.feedId = feedId
            ra.newFeedName = newFeedName
            return ra
        }

        @JvmStatic
        fun fromCursor(c: Cursor): ReadingAction {
            val time = c.getLong(c.getColumnIndexOrThrow(DatabaseConstants.ACTION_TIME))
            val tried = c.getInt(c.getColumnIndexOrThrow(DatabaseConstants.ACTION_TRIED))
            val params = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.ACTION_PARAMS))
            val ra = DatabaseConstants.JsonHelper.fromJson(params, ReadingAction::class.java)
            ra.time = time
            ra.tried = tried
            return ra
        }
    }
}
