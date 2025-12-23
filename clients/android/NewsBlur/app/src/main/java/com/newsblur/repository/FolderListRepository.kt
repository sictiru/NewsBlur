package com.newsblur.repository

import android.os.CancellationSignal
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.domain.Feed
import com.newsblur.domain.FeedQueryResult
import com.newsblur.domain.Folder
import com.newsblur.domain.FolderQueryResult
import com.newsblur.domain.SavedSearch
import com.newsblur.domain.SavedStoryCountsQueryResult
import com.newsblur.domain.SocialFeed
import com.newsblur.domain.StarredCount
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

interface FolderListRepository {
    suspend fun loadSocialFeeds(signal: CancellationSignal): List<SocialFeed>?

    suspend fun loadFolders(signal: CancellationSignal): FolderQueryResult

    suspend fun loadFeeds(signal: CancellationSignal): FeedQueryResult

    suspend fun loadSavedStoryCounts(signal: CancellationSignal): SavedStoryCountsQueryResult

    suspend fun loadSavedSearches(signal: CancellationSignal): List<SavedSearch>?
}

class FolderListRepositoryImpl
    @Inject
    constructor(
        private val dbHelper: BlurDatabaseHelper,
    ) : FolderListRepository {
        override suspend fun loadSocialFeeds(signal: CancellationSignal): List<SocialFeed>? =
            withContext(Dispatchers.IO) {
                dbHelper.getSocialFeedsCursor(signal).use { cursor ->
                    if (!cursor.isBeforeFirst) return@use null
                    val out = ArrayList<SocialFeed>(cursor.count)
                    while (cursor.moveToNext()) out.add(SocialFeed.fromCursor(cursor))
                    out
                }
            }

        override suspend fun loadFolders(signal: CancellationSignal): FolderQueryResult =
            withContext(Dispatchers.IO) {
                dbHelper.getFoldersCursor(signal).use { cursor ->
                    if (cursor.count < 1 || !cursor.isBeforeFirst) {
                        return@use FolderQueryResult(folders = linkedMapOf(), flatFolders = linkedMapOf())
                    }
                    val folders = LinkedHashMap<String, Folder>(cursor.count)
                    val flatFolders = LinkedHashMap<String, Folder>(cursor.count)
                    while (cursor.moveToNext()) {
                        val folder = Folder.fromCursor(cursor)
                        folders[folder.name] = folder
                        flatFolders[folder.flatName()] = folder
                    }
                    FolderQueryResult(folders = folders, flatFolders = flatFolders)
                }
            }

        override suspend fun loadFeeds(signal: CancellationSignal): FeedQueryResult =
            withContext(Dispatchers.IO) {
                dbHelper.getFeedsCursor(signal).use { cursor ->
                    if (!cursor.isBeforeFirst) {
                        return@use FeedQueryResult(
                            feeds = linkedMapOf(),
                            feedNeutCounts = linkedMapOf(),
                            feedPosCounts = linkedMapOf(),
                            totalNeutCount = 0,
                            totalPosCount = 0,
                            totalActiveFeedCount = 0,
                        )
                    }

                    val feeds = LinkedHashMap<String, Feed>(cursor.count)
                    val feedNeutCounts = mutableMapOf<String, Int>()
                    val feedPosCounts = mutableMapOf<String, Int>()
                    var totalNeutCount = 0
                    var totalPosCount = 0
                    var totalActiveFeedCount = 0

                    while (cursor.moveToNext()) {
                        val f = Feed.fromCursor(cursor)
                        feeds[f.feedId] = f
                        if (f.active && f.positiveCount > 0) {
                            val pos = checkNegativeFeedUnreads(f.positiveCount)
                            feedPosCounts[f.feedId] = pos
                            totalPosCount += pos
                        }
                        if (f.active && f.neutralCount > 0) {
                            val neut = checkNegativeFeedUnreads(f.neutralCount)
                            feedNeutCounts[f.feedId] = neut
                            totalNeutCount += neut
                        }
                        if (f.active) totalActiveFeedCount++
                    }

                    FeedQueryResult(
                        feeds = feeds,
                        feedNeutCounts = feedNeutCounts,
                        feedPosCounts = feedPosCounts,
                        totalNeutCount = totalNeutCount,
                        totalPosCount = totalPosCount,
                        totalActiveFeedCount = totalActiveFeedCount,
                    )
                }
            }

        override suspend fun loadSavedStoryCounts(signal: CancellationSignal): SavedStoryCountsQueryResult =
            withContext(Dispatchers.IO) {
                dbHelper.getSavedStoryCountsCursor(signal).use { cursor ->
                    if (!cursor.isBeforeFirst) {
                        return@use SavedStoryCountsQueryResult(emptyList(), emptyMap(), null)
                    }
                    val starredCountsByTag = mutableListOf<StarredCount>()
                    val feedSavedCounts = mutableMapOf<String, Int>()
                    var savedStoriesTotalCount: Int? = null

                    while (cursor.moveToNext()) {
                        val sc = StarredCount.fromCursor(cursor)
                        if (sc.isTotalCount) {
                            savedStoriesTotalCount = sc.count
                        } else if (sc.tag != null) {
                            starredCountsByTag.add(sc)
                        } else if (sc.feedId != null) {
                            feedSavedCounts[sc.feedId] = sc.count
                        }
                    }

                    Collections.sort(starredCountsByTag, StarredCount.StarredCountComparatorByTag)
                    SavedStoryCountsQueryResult(starredCountsByTag, feedSavedCounts, savedStoriesTotalCount)
                }
            }

        override suspend fun loadSavedSearches(signal: CancellationSignal): List<SavedSearch>? =
            withContext(Dispatchers.IO) {
                dbHelper.getSavedSearchCursor(signal).use { cursor ->
                    if (!cursor.isBeforeFirst) return@use null
                    val out = mutableListOf<SavedSearch>()
                    while (cursor.moveToNext()) out.add(SavedSearch.fromCursor(cursor))
                    Collections.sort(out, SavedSearch.SavedSearchComparatorByTitle)
                    out
                }
            }

        private fun checkNegativeFeedUnreads(count: Int): Int = if (count < 0) 0 else count
    }
