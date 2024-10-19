package com.newsblur.service

import com.newsblur.database.DatabaseConstants
import com.newsblur.service.NbSyncManager.UPDATE_TEXT
import com.newsblur.util.AppConstants
import com.newsblur.util.FeedUtils.Companion.inferFeedId
import com.newsblur.util.Log
import com.newsblur.util.NBScope
import java.util.regex.Pattern

class OriginalTextService(
        parent: NBSyncService,
) : SubService(parent, NBScope) {

    override suspend fun exec() {
        activelyRunning = true
        try {
            while (hashes.size > 0 || priorityHashes.size > 0) {
                if (parent.stopSync()) return
                fetchBatch(priorityHashes)
                fetchBatch(hashes)
            }
        } finally {
            activelyRunning = false
        }
    }

    private suspend fun fetchBatch(hashes: MutableSet<String>) {
        val fetchedHashes: MutableSet<String> = HashSet()
        val batch: MutableSet<String> = HashSet(AppConstants.IMAGE_PREFETCH_BATCH_SIZE)
        batchloop@ for (hash in hashes) {
            batch.add(hash)
            if (batch.size >= AppConstants.IMAGE_PREFETCH_BATCH_SIZE) break@batchloop
        }
        try {
            fetchloop@ for (hash in batch) {
                if (parent.stopSync()) break@fetchloop
                fetchedHashes.add(hash)
                var result: String? = null
                val response = parent.apiManager.getStoryText(inferFeedId(hash), hash)
                if (response != null) {
                    if (response.originalText == null) {
                        // a null value in an otherwise valid response to this call indicates a fatal
                        // failure to extract text and should be recorded so the UI can inform the
                        // user and switch them back to a valid view mode
                        result = NULL_STORY_TEXT
                    } else if (response.originalText.length >= DatabaseConstants.MAX_TEXT_SIZE) {
                        // this API can occasionally return story texts that are much too large to query
                        // from the DB.  stop insertion to prevent poisoning the DB and the cursor lifecycle
                        Log.w(this, "discarding too-large story text. hash " + hash + " size " + response.originalText.length)
                        result = NULL_STORY_TEXT
                    } else {
                        result = response.originalText
                    }
                }
                if (result != null) {
                    // store the fetched text in the DB
                    parent.dbHelper.putStoryText(hash, result)
                    // scan for potentially cache-able images in the extracted 'text'
                    val imgTagMatcher = imgSniff.matcher(result)
                    while (imgTagMatcher.find()) {
                        parent.imagePrefetchService?.addUrl(imgTagMatcher.group(2))
                    }
                }
            }
        } finally {
            parent.sendSyncUpdate(UPDATE_TEXT)
            hashes.removeAll(fetchedHashes)
        }
    }

    companion object {

        @JvmField
        var activelyRunning: Boolean = false

        // special value for when the API responds that it could fatally could not fetch text
        const val NULL_STORY_TEXT: String = "__NULL_STORY_TEXT__"

        private val imgSniff: Pattern = Pattern.compile("<img[^>]*src=(['\"])((?:(?!\\1).)*)\\1[^>]*>", Pattern.CASE_INSENSITIVE)

        /** story hashes we need to fetch (from newly found stories)  */
        private val hashes: MutableSet<String> = HashSet()

        /** story hashes we should fetch ASAP (they are waiting on-screen)  */
        private val priorityHashes: MutableSet<String> = HashSet()

        @JvmStatic
        fun addHash(hash: String) {
            hashes.add(hash)
        }

        fun addPriorityHash(hash: String) {
            priorityHashes.add(hash)
        }

        @JvmStatic
        val pendingCount: Int
            get() = (hashes.size + priorityHashes.size)

        @JvmStatic
        fun clear() {
            hashes.clear()
            priorityHashes.clear()
        }
    }
}

