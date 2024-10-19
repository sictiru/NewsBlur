package com.newsblur.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Process
import com.newsblur.NbApplication.Companion.isAppForeground
import com.newsblur.R
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.BlurDatabaseHelper.Companion.closeQuietly
import com.newsblur.database.DatabaseConstants
import com.newsblur.di.IconFileCache
import com.newsblur.di.StoryImageCache
import com.newsblur.di.ThumbnailCache
import com.newsblur.domain.Feed
import com.newsblur.domain.StarredCount
import com.newsblur.network.APIConstants
import com.newsblur.network.APIManager
import com.newsblur.network.domain.StoriesResponse
import com.newsblur.service.NbSyncManager.UPDATE_DB_READY
import com.newsblur.service.NbSyncManager.UPDATE_METADATA
import com.newsblur.service.NbSyncManager.UPDATE_REBUILD
import com.newsblur.service.NbSyncManager.UPDATE_STATUS
import com.newsblur.service.NbSyncManager.UPDATE_STORY
import com.newsblur.service.NbSyncManager.submitError
import com.newsblur.service.NbSyncManager.submitUpdate
import com.newsblur.service.OriginalTextService.Companion.addHash
import com.newsblur.service.UnreadsService.Companion.doMetadata
import com.newsblur.service.UnreadsService.Companion.storyHashQueue
import com.newsblur.util.AppConstants
import com.newsblur.util.CursorFilters
import com.newsblur.util.DefaultFeedView
import com.newsblur.util.FeedSet
import com.newsblur.util.FileCache
import com.newsblur.util.Log
import com.newsblur.util.NetworkUtils
import com.newsblur.util.NotificationUtils
import com.newsblur.util.PrefsUtils
import com.newsblur.util.ReadingAction
import com.newsblur.util.ReadingAction.Companion.fromCursor
import com.newsblur.util.StateFilter
import com.newsblur.widget.WidgetUtils.hasActiveAppWidgets
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.Volatile

/**
 * A background service to handle synchronisation with the NB servers.
 *
 * It is the design goal of this service to handle all communication with the API.
 * Activities and fragments should enqueue actions in the DB or use the methods
 * provided herein to request an action and let the service handle things.
 *
 * Per the contract of the Service class, at most one instance shall be created. It
 * will be preserved and re-used where possible.  Additionally, regularly scheduled
 * invocations are requested via the Main activity and BootReceiver.
 *
 * The service will notify all running activities of an update before, during, and
 * after sync operations are performed.  Activities can then refresh views and
 * query this class to see if progress indicators should be active.
 */
@AndroidEntryPoint
class NBSyncService : JobService() {

    val orphanFeedIds: MutableSet<String> = HashSet()
    val disabledFeedIds: MutableSet<String> = HashSet()

    // replace with Coro
    private var primaryExecutor: ExecutorService? = null
    private val outstandingStartIds: MutableList<Int> = ArrayList()
    private val outstandingStartParams: MutableList<JobParameters> = ArrayList()
    private var mainSyncRunning = false
    private var cleanupService: CleanupService? = null
    private var starredService: StarredService? = null
    private var originalTextService: OriginalTextService? = null
    private var unreadsService: UnreadsService? = null

    var imagePrefetchService: ImagePrefetchService? = null

    @Inject
    lateinit var apiManager: APIManager

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    @IconFileCache
    @Inject
    lateinit var iconCache: FileCache

    @StoryImageCache
    @Inject
    lateinit var storyImageCache: FileCache

    @ThumbnailCache
    @Inject
    lateinit var thumbnailCache: FileCache

    override fun onCreate() {
        super.onCreate()
        Log.d(this, "onCreate")
        HaltNow = false
        primaryExecutor = Executors.newFixedThreadPool(1)
    }

    /**
     * Services can be constructed synchrnously by the Main thread, so don't do expensive
     * parts of construction in onCreate, but save them for when we are in our own thread.
     */
    private fun finishConstruction() {
        if (cleanupService == null || imagePrefetchService == null) {
            cleanupService = CleanupService(this)
            starredService = StarredService(this)
            originalTextService = OriginalTextService(this)
            unreadsService = UnreadsService(this)
            imagePrefetchService = ImagePrefetchService(this)
            Log.offerContext(this)
        }
    }

    /**
     * Kickoff hook for when we are started via Context.startService()
     */
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(this, "onStartCommand")
        // only perform a sync if the app is actually running or background syncs are enabled
        if (isAppForeground || PrefsUtils.isBackgroundNeeded(this)) {
            HaltNow = false
            // Services actually get invoked on the main system thread, and are not
            // allowed to do tangible work.  We spawn a thread to do so.
            val r = Runnable {
                runBlocking(Dispatchers.IO) {
                    mainSyncRunning = true
                    doSync()
                    mainSyncRunning = false
                    // record the startId so when the sync thread and all sub-service threads finish,
                    // we can report that this invocation completed.
                    synchronized(COMPLETION_CALLBACKS_MUTEX) { outstandingStartIds.add(startId) }
                    checkCompletion()
                }
            }
            primaryExecutor?.execute(r)
        } else {
            Log.i(this, "Skipping sync: app not active and background sync not enabled.")
            synchronized(COMPLETION_CALLBACKS_MUTEX) { outstandingStartIds.add(startId) }
            checkCompletion()
        }

        // indicate to the system that the service should be alive when started, but
        // needn't necessarily persist under memory pressure
        return START_NOT_STICKY
    }

    /**
     * Kickoff hook for when we are started via a JobScheduler
     */
    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(this, "onStartJob")
        // only perform a sync if the app is actually running or background syncs are enabled
        if (isAppForeground || PrefsUtils.isBackgroundNeeded(this)) {
            HaltNow = false
            // Services actually get invoked on the main system thread, and are not
            // allowed to do tangible work.  We spawn a thread to do so.
            val r = Runnable {
                runBlocking(Dispatchers.IO) {
                    mainSyncRunning = true
                    doSync()
                    mainSyncRunning = false
                    // record the JobParams so when the sync thread and all sub-service threads finish,
                    // we can report that this invocation completed.
                    synchronized(COMPLETION_CALLBACKS_MUTEX) { outstandingStartParams.add(params) }
                    checkCompletion()
                }
            }
            primaryExecutor?.execute(r)
        } else {
            Log.d(this, "Skipping sync: app not active and background sync not enabled.")
            synchronized(COMPLETION_CALLBACKS_MUTEX) { outstandingStartParams.add(params) }
            checkCompletion()
        }
        return true // indicate that we are async
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(this, "onStopJob")
        HaltNow = true
        // return false to indicate that we don't necessarily need re-invocation ahead of schedule.
        // background syncs can pick up where the last one left off and forground syncs aren't
        // run via cancellable JobScheduler invocations.
        return false
    }

    override fun onNetworkChanged(params: JobParameters) {
        super.onNetworkChanged(params)
        Log.d(this, "onNetworkChanged")
    }

    /**
     * Do the actual work of syncing.
     */
    private suspend fun doSync() {
        try {
            if (HaltNow) return

            finishConstruction()

            Log.d(this, "starting primary sync")

            if (!isAppForeground) {
                // if the UI isn't running, politely run at background priority
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } else {
                // if the UI is running, run just one step below normal priority so we don't step on async tasks that are updating the UI
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            }

            Thread.currentThread().name = this.javaClass.name

            if (OfflineNow) {
                if (NetworkUtils.isOnline(this)) {
                    OfflineNow = false
                    sendSyncUpdate(UPDATE_STATUS)
                } else {
                    Log.d(this, "Abandoning sync: network still offline")
                    return
                }
            }

            // do this even if background syncs aren't enabled, because it absolutely must happen
            // on all devices
            housekeeping()

            // check to see if we are on an allowable network only after ensuring we have CPU
            if (!(isAppForeground ||
                            PrefsUtils.isEnableNotifications(this) ||
                            PrefsUtils.isBackgroundNetworkAllowed(this) ||
                            hasActiveAppWidgets(this))) {
                Log.d(this.javaClass.name, "Abandoning sync: app not active and network type not appropriate for background sync.")
                return
            }

            // ping activities to indicate that housekeeping is done, and the DB is safe to use
            sendSyncUpdate(UPDATE_DB_READY)

            // async text requests might have been queued up and are being waiting on by the live UI. give them priority
            originalTextService?.start()

            // first: catch up
            syncActions()

            // if MD is stale, sync it first so unreads don't get backwards with story unread state
            syncMetadata()


            // handle fetching of stories that are actively being requested by the live UI
            syncPendingFeedStories()

            // re-apply the local state of any actions executed before local UI interaction
            finishActions()

            // after all actions, double-check local state vs remote state consistency
            checkRecounts()

            // async story and image prefetch are lower priority and don't affect active reading, do them last
            unreadsService?.start()
            imagePrefetchService?.start()

            // almost all notifications will be pushed after the unreadsService gets new stories, but double-check
            // here in case some made it through the feed sync loop first
            pushNotifications()

            Log.d(this, "finishing primary sync")
        } catch (e: Exception) {
            Log.e(this.javaClass.name, "Sync error.", e)
        }
    }

    /**
     * Check for upgrades and wipe the DB if necessary, and do DB maintenance
     */
    private suspend fun housekeeping() {
        try {
            val upgraded = PrefsUtils.checkForUpgrade(this)
            if (upgraded) {
                isHousekeepingRunning = true
                sendSyncUpdate(UPDATE_STATUS or UPDATE_REBUILD)
                // wipe the local DB if this is a first background run. if this is a first foreground
                // run, InitActivity will have wiped for us
                if (!isAppForeground) {
                    dbHelper.dropAndRecreateTables()
                }
                // in case this is the first time we have run since moving the cache to the new location,
                // blow away the old version entirely. This line can be removed some time well after
                // v61+ is widely deployed
                FileCache.cleanUpOldCache1(this)
                FileCache.cleanUpOldCache2(this)
                val appVersion = PrefsUtils.getVersion(this)
                PrefsUtils.updateVersion(this, appVersion)
                // update user agent on api calls with latest app version
                val customUserAgent = NetworkUtils.getCustomUserAgent(appVersion)
                apiManager.updateCustomUserAgent(customUserAgent)
            }

            var autoVac = PrefsUtils.isTimeToVacuum(this)
            // this will lock up the DB for a few seconds, only do it if the UI is hidden
            if (isAppForeground) autoVac = false

            if (upgraded || autoVac) {
                isHousekeepingRunning = true
                sendSyncUpdate(UPDATE_STATUS)
                Log.i(this.javaClass.name, "rebuilding DB . . .")
                dbHelper.vacuum()
                Log.i(this.javaClass.name, ". . . . done rebuilding DB")
                PrefsUtils.updateLastVacuumTime(this)
            }
        } finally {
            if (isHousekeepingRunning) {
                isHousekeepingRunning = false
                sendSyncUpdate(UPDATE_METADATA)
            }
        }
    }

    /**
     * Perform any reading actions the user has done before we do anything else.
     */
    private suspend fun syncActions() {
        if (stopSync(this)) return
        if (backoffBackgroundCalls()) return

        var c: Cursor? = null
        try {
            c = dbHelper.getActions()
            lastActionCount = c.count
            if (lastActionCount < 1) return

            ActionsRunning = true

            val stateFilter = PrefsUtils.getStateFilter(this)

            actionsloop@ while (c.moveToNext()) {
                sendSyncUpdate(UPDATE_STATUS)
                val id = c.getString(c.getColumnIndexOrThrow(DatabaseConstants.ACTION_ID))
                var ra: ReadingAction
                try {
                    ra = fromCursor(c)
                } catch (e: IllegalArgumentException) {
                    Log.e(this.javaClass.name, "error unfreezing ReadingAction", e)
                    dbHelper.clearAction(id)
                    continue@actionsloop
                }

                // don't block story loading unless this is a brand new action
                if (ra.getTries() > 0 && PendingFeed != null) continue@actionsloop

                Log.d(this, "attempting action: " + ra.toContentValues().toString())
                val response = ra.doRemote(apiManager, dbHelper, stateFilter)

                if (response == null) {
                    Log.e(this.javaClass.name, "Discarding reading action with client-side error.")
                    dbHelper.clearAction(id)
                } else if (response.isProtocolError) {
                    // the network failed or we got a non-200, so be sure we retry
                    Log.i(this.javaClass.name, "Holding reading action with server-side or network error.")
                    dbHelper.incrementActionTried(id)
                    noteHardAPIFailure()
                    continue@actionsloop
                } else if (response.isError) {
                    // the API responds with a message either if the call was a client-side error or if it was handled in such a
                    // way that we should inform the user. in either case, it is considered complete.
                    Log.i(this.javaClass.name, "Discarding reading action with fatal message.")
                    dbHelper.clearAction(id)
                    val message = response.getErrorMessage(null)
                    if (message != null) sendToastError(message)
                } else {
                    // success!
                    dbHelper.clearAction(id)
                    FollowupActions.add(ra)
                    sendSyncUpdate(response.impactCode)
                }
                lastActionCount--
            }
        } finally {
            closeQuietly(c)
            ActionsRunning = false
            sendSyncUpdate(UPDATE_STATUS)
        }
    }

    /**
     * Some actions have a final, local step after being done remotely to ensure in-flight
     * API actions didn't race-overwrite them.  Do these, and then clean up the DB.
     */
    private suspend fun finishActions() {
        if (HaltNow) return
        if (FollowupActions.isEmpty()) return

        Log.d(this, "double-checking " + FollowupActions.size + " actions")
        var impactFlags = 0
        for (ra in FollowupActions) {
            val impact = ra.doLocal(this, dbHelper, true)
            impactFlags = impactFlags or impact
        }
        sendSyncUpdate(impactFlags)

        // if there is a feed fetch loop running, don't clear, there will likely be races for
        // stories that were just tapped as they were being re-fetched
        synchronized(PENDING_FEED_MUTEX) { if (PendingFeed != null) return }

        // if there is a what-is-unread sync in progress, hold off on confirming actions,
        // as this subservice can vend stale unread data
        if (UnreadsService.isDoMetadata) return

        FollowupActions.clear()
    }

    /**
     * The very first step of a sync - get the feed/folder list, unread counts, and
     * unread hashes. Doing this resets pagination on the server!
     */
    private suspend fun syncMetadata() {
        if (stopSync(this)) return
        if (backoffBackgroundCalls()) return
        val untriedActions = dbHelper.getUntriedActionCount()
        if (untriedActions > 0) {
            Log.i(this.javaClass.name, "$untriedActions outstanding actions, yielding metadata sync")
            return
        }

        if (DoFeedsFolders || PrefsUtils.isTimeToAutoSync(this)) {
            PrefsUtils.updateLastSyncTime(this)
            DoFeedsFolders = false
        } else {
            return
        }

        Log.i(this.javaClass.name, "ready to sync feed list")

        FFSyncRunning = true
        sendSyncUpdate(UPDATE_STATUS)

        // there is an issue with feeds that have no folder or folders that list feeds that do not exist.  capture them for workarounds.
        val debugFeedIdsFromFolders: MutableSet<String> = HashSet()
        val debugFeedIdsFromFeeds: MutableSet<String> = HashSet()
        orphanFeedIds.clear()
        disabledFeedIds.clear()

        try {
            val feedResponse = apiManager.getFolderFeedMapping(true)

            if (feedResponse == null) {
                noteHardAPIFailure()
                return
            }

            if (!feedResponse.isAuthenticated) {
                // we should not have got this far without being logged in, so the server either
                // expired or ignored out cookie. keep track of this.
                authFails += 1
                Log.w(this.javaClass.name, "Server ignored or rejected auth cookie.")
                if (authFails >= AppConstants.MAX_API_TRIES) {
                    Log.w(this.javaClass.name, "too many auth fails, resetting cookie")
                    PrefsUtils.logout(this, dbHelper)
                }
                DoFeedsFolders = true
                return
            } else {
                authFails = 0
            }

            if (HaltNow) return

            // a metadata sync invalidates pagination and feed status
            ExhaustedFeeds.clear()
            FeedPagesSeen.clear()
            FeedStoriesSeen.clear()
            UnreadsService.clear()
            RecountCandidates.clear()

            lastFFConnMillis = feedResponse.connTime
            lastFFReadMillis = feedResponse.readTime
            lastFFParseMillis = feedResponse.parseTime
            val startTime = System.currentTimeMillis()

            isPremium = feedResponse.isPremium
            isArchive = feedResponse.isArchive
            isStaff = feedResponse.isStaff

            PrefsUtils.setPremium(this, feedResponse.isPremium, feedResponse.premiumExpire)
            PrefsUtils.setArchive(this, feedResponse.isArchive, feedResponse.premiumExpire)
            PrefsUtils.setExtToken(this, feedResponse.shareExtToken)

            // note all feeds that belong to some folder so we can find orphans
            for (folder in feedResponse.folders) {
                debugFeedIdsFromFolders.addAll(folder.feedIds)
            }

            // data for the feeds table
            val feedValues: MutableList<ContentValues> = ArrayList()
            feedaddloop@ for (feed in feedResponse.feeds) {
                // note all feeds for which the API returned data
                debugFeedIdsFromFeeds.add(feed.feedId)
                // sanity-check that the returned feeds actually exist in a folder or at the root
                // if they do not, they should neither display nor count towards unread numbers
                if (!debugFeedIdsFromFolders.contains(feed.feedId)) {
                    Log.w(this.javaClass.name, "Found and ignoring orphan feed (in feeds but not folders): " + feed.feedId)
                    orphanFeedIds.add(feed.feedId)
                    continue@feedaddloop
                }
                if (!feed.active) {
                    // the feed is disabled/hidden, we don't want to fetch unreads
                    disabledFeedIds.add(feed.feedId)
                }
                feedValues.add(feed.values)
            }
            // also add the implied zero-id feed
            feedValues.add(Feed.getZeroFeed().values)

            // prune out missing feed IDs from folders
            for (id in debugFeedIdsFromFolders) {
                if (!debugFeedIdsFromFeeds.contains(id)) {
                    Log.w(this.javaClass.name, "Found and ignoring orphan feed (in folders but not feeds): $id")
                    orphanFeedIds.add(id)
                }
            }


            // data for the folder table
            val folderValues: MutableList<ContentValues> = ArrayList()
            val foldersSeen: MutableSet<String> = HashSet(feedResponse.folders.size)
            folderloop@ for (folder in feedResponse.folders) {
                // don't form graph loops in the folder tree
                if (foldersSeen.contains(folder.name)) continue@folderloop
                foldersSeen.add(folder.name)
                // prune out orphans before pushing to the DB
                folder.removeOrphanFeedIds(orphanFeedIds)
                folderValues.add(folder.values)
            }

            // data for the the social feeds table
            val socialFeedValues: MutableList<ContentValues> = ArrayList()
            for (feed in feedResponse.socialFeeds) {
                socialFeedValues.add(feed.values)
            }


            // populate the starred stories count table
            val starredCountValues: MutableList<ContentValues> = ArrayList()
            for (sc in feedResponse.starredCounts) {
                starredCountValues.add(sc.values)
            }

            // saved searches table
            val savedSearchesValues: MutableList<ContentValues> = ArrayList()
            for (savedSearch in feedResponse.savedSearches) {
                savedSearchesValues.add(savedSearch.getValues(dbHelper))
            }
            // the API vends the starred total as a different element, roll it into
            // the starred counts table using a special tag
            val totalStarred = StarredCount()
            totalStarred.count = feedResponse.starredCount
            totalStarred.tag = StarredCount.TOTAL_STARRED
            starredCountValues.add(totalStarred.values)

            dbHelper.setFeedsFolders(folderValues, feedValues, socialFeedValues, starredCountValues, savedSearchesValues)

            lastFFWriteMillis = System.currentTimeMillis() - startTime
            lastFeedCount = feedValues.size.toLong()

            Log.i(this.javaClass.name, "got feed list: $speedInfo")

            doMetadata()
            unreadsService?.start()
            cleanupService?.start()
            starredService?.start()
        } finally {
            FFSyncRunning = false
            sendSyncUpdate(UPDATE_METADATA or UPDATE_STATUS)
        }
    }

    /**
     * See if any feeds have been touched in a way that require us to double-check unread counts;
     */
    private suspend fun checkRecounts() {
        if (!FlushRecounts) return

        try {
            if (RecountCandidates.isEmpty()) return

            RecountsRunning = true
            sendSyncUpdate(UPDATE_STATUS)

            // of all candidate feeds that were touched, now check to see if any
            // actually need their counts fetched
            val dirtySets: MutableSet<FeedSet> = HashSet()
            for (fs in RecountCandidates) {
                // check for mismatched local and remote counts we need to reconcile
                if (dbHelper.getUnreadCount(fs, StateFilter.SOME) != dbHelper.getLocalUnreadCount(fs, StateFilter.SOME)) {
                    dirtySets.add(fs)
                }
                // check for feeds flagged for insta-fetch
                if (dbHelper.isFeedSetFetchPending(fs)) {
                    dirtySets.add(fs)
                }
            }
            if (dirtySets.size < 1) {
                RecountCandidates.clear()
                return
            }

            Log.i(this.javaClass.name, "recounting dirty feed sets: " + dirtySets.size)

            // if we are offline, the best we can do is perform a local unread recount and
            // save the true one for when we go back online.
            if (!NetworkUtils.isOnline(this)) {
                for (fs in RecountCandidates) {
                    dbHelper.updateLocalFeedCounts(fs)
                }
            } else {
                if (stopSync(this)) return
                // if any reading activities are pending, it makes no sense to recount yet
                if (dbHelper.getUntriedActionCount() > 0) return

                val apiIds: MutableSet<String> = HashSet()
                for (fs in RecountCandidates) {
                    apiIds.addAll(fs.flatFeedIds)
                }

                val apiResponse = apiManager.getFeedUnreadCounts(apiIds)
                if (apiResponse == null || apiResponse.isError) {
                    Log.w(this.javaClass.name, "Bad response to feed_unread_count")
                    return
                }
                if (apiResponse.feeds != null) {
                    for ((key, value) in apiResponse.feeds) {
                        dbHelper.updateFeedCounts(key, value.values)
                    }
                }
                if (apiResponse.socialFeeds != null) {
                    for ((key, value) in apiResponse.socialFeeds) {
                        val feedId = key.replace(APIConstants.VALUE_PREFIX_SOCIAL.toRegex(), "")
                        dbHelper.updateSocialFeedCounts(feedId, value.valuesSocial)
                    }
                }
                RecountCandidates.clear()

                // if there was a mismatch, some stories might have been missed at the head of the
                // pagination loop, so reset it
                for (fs in dirtySets) {
                    FeedPagesSeen[fs] = 0
                    FeedStoriesSeen[fs] = 0
                }
            }
        } finally {
            if (RecountsRunning) {
                RecountsRunning = false
                sendSyncUpdate(UPDATE_METADATA or UPDATE_STATUS)
            }
            FlushRecounts = false
        }
    }

    /**
     * Fetch stories needed because the user is actively viewing a feed or folder.
     */
    private suspend fun syncPendingFeedStories() {
        // track whether we actually tried to handle the feedset and found we had nothing
        // more to do, in which case we will clear it
        var finished = false

        val fs = PendingFeed

        try {
            // see if we need to quickly reset fetch state for a feed. we
            // do this before the loop to prevent-mid loop state corruption
            synchronized(MUTEX_ResetFeed) {
                if (ResetFeed != null) {
                    Log.i(this.javaClass.name, "Resetting state for feed set: $ResetFeed")
                    ExhaustedFeeds.remove(ResetFeed)
                    FeedStoriesSeen.remove(ResetFeed)
                    FeedPagesSeen.remove(ResetFeed)
                    ResetFeed = null
                }
            }
            // a reset should also reset the stories table, just in case an async page of stories came in between the
            // caller's (presumed) reset and our call ot prepareReadingSession(). unsetting the session feedset will
            // cause the later call to prepareReadingSession() to do another reset
            dbHelper.setSessionFeedSet(null)

            if (fs == null) {
                Log.d(this.javaClass.name, "No feed set to sync")
                return
            }

            prepareReadingSession(this, dbHelper, fs)

            LastFeedSet = fs

            if (ExhaustedFeeds.contains(fs)) {
                Log.i(this.javaClass.name, "No more stories for feed set: $fs")
                finished = true
                return
            }

            if (!FeedPagesSeen.containsKey(fs)) {
                FeedPagesSeen[fs] = 0
                FeedStoriesSeen[fs] = 0
                workaroundReadStoryTimestamp = Date().time
                workaroundGloblaSharedStoryTimestamp = Date().time
            }
            var pageNumber = FeedPagesSeen[fs] ?: return
            var totalStoriesSeen = FeedStoriesSeen[fs] ?: return

            val cursorFilters = CursorFilters(this, fs)

            StorySyncRunning = true
            sendSyncUpdate(UPDATE_STATUS)

            while (totalStoriesSeen < PendingFeedTarget) {
                if (stopSync(this)) return
                // this is a good heuristic for double-checking if we have left the story list
                if (FlushRecounts) return

                // bail if the active view has changed
                if (fs != PendingFeed) {
                    return
                }

                pageNumber++
                val apiResponse = apiManager.getStories(fs, pageNumber, cursorFilters.storyOrder, cursorFilters.readFilter)

                if (!isStoryResponseGood(apiResponse)) return

                if (fs != PendingFeed) {
                    return
                }

                insertStories(apiResponse, fs, cursorFilters.stateFilter)
                // re-do any very recent actions that were incorrectly overwritten by this page
                finishActions()
                sendSyncUpdate(UPDATE_STORY or UPDATE_STATUS)

                prefetchOriginalText(apiResponse)

                FeedPagesSeen[fs] = pageNumber
                totalStoriesSeen += apiResponse.stories.size
                FeedStoriesSeen[fs] = totalStoriesSeen
                if (apiResponse.stories.isEmpty()) {
                    ExhaustedFeeds.add(fs)
                    finished = true
                    return
                }

                // don't let the page loop block actions
                if (dbHelper.getUntriedActionCount() > 0) return
            }
            finished = true
        } finally {
            StorySyncRunning = false
            sendSyncUpdate(UPDATE_STATUS)
            synchronized(PENDING_FEED_MUTEX) {
                if (finished && fs == PendingFeed) PendingFeed = null
            }
        }
    }

    private fun isStoryResponseGood(response: StoriesResponse?): Boolean {
        if (response == null) {
            Log.e(this.javaClass.name, "Null response received while loading stories.")
            return false
        }
        if (response.stories == null) {
            Log.e(this.javaClass.name, "Null stories member received while loading stories.")
            return false
        }
        return true
    }

    private var workaroundReadStoryTimestamp: Long = 0
    private var workaroundGloblaSharedStoryTimestamp: Long = 0

    private suspend fun insertStories(apiResponse: StoriesResponse, fs: FeedSet, stateFilter: StateFilter) {
        if (fs.isAllRead) {
            // Ugly Hack Warning: the API doesn't vend the sortation key necessary to display
            // stories when in the "read stories" view. It does, however, return them in the
            // correct order, so we can fudge a fake last-read-stamp so they will show up.
            // Stories read locally with have the correct stamp and show up fine. When local
            // and remote stories are integrated, the remote hack will override the ordering
            // so they get put into the correct sequence recorded by the API (the authority).
            for (story in apiResponse.stories) {
                // this fake TS was set when we fetched the first page. have it decrease as
                // we page through, so they append to the list as if most-recent-first.
                workaroundReadStoryTimestamp--
                story.lastReadTimestamp = workaroundReadStoryTimestamp
            }
        }

        if (fs.isGlobalShared) {
            // Ugly Hack Warning: the API doesn't vend the sortation key necessary to display
            // stories when in the "global shared stories" view. It does, however, return them
            // in the expected order, so we can fudge a fake shared-timestamp so they can be
            // selected from the DB in the same order.
            for (story in apiResponse.stories) {
                // this fake TS was set when we fetched the first page. have it decrease as
                // we page through, so they append to the list as if most-recent-first.
                workaroundGloblaSharedStoryTimestamp--
                story.sharedTimestamp = workaroundGloblaSharedStoryTimestamp
            }
        }

        if (fs.isInfrequent) {
            // the API vends a river of stories from sites that publish infrequently, but the
            // list of which feeds qualify is not vended. as a workaround, stories received
            // from this API are specially tagged so they can be displayed
            for (story in apiResponse.stories) {
                story.infrequent = true
            }
        }

        if (fs.isAllSaved || fs.isAllRead) {
            // Note: for reasons relating to the impl. of the web UI, the API returns incorrect
            // intel values for stories from these two APIs.  Fix them so they don't show green
            // when they really aren't.
            for (story in apiResponse.stories) {
                story.intelligence.intelligenceFeed--
            }
        }

        if (fs.singleSavedTag != null) {
            // Workaround: the API doesn't vend an embedded 'feeds' block with metadata for feeds
            // to which the user is not subscribed but that contain saved stories. In order to
            // prevent these stories being invisible due to failed metadata joins, insert fake
            // feed data like with the zero-ID generic feed to match the web UI behaviour
            dbHelper.fixMissingStoryFeeds(apiResponse.stories)
        }

        if (fs.searchQuery != null) {
            // If this set of stories was found in response to the active search query, note
            // them as such in the DB so the UI can filter for them
            for (story in apiResponse.stories) {
                story.searchHit = fs.searchQuery
            }
        }

        Log.d(NBSyncService::class.java.name, "got stories from main fetch loop: " + apiResponse.stories.size)
        dbHelper.insertStories(apiResponse, stateFilter, true)
    }

    suspend fun insertStories(apiResponse: StoriesResponse, stateFilter: StateFilter) {
        Log.d(NBSyncService::class.java.name, "got stories from sub sync: " + apiResponse.stories.size)
        dbHelper.insertStories(apiResponse, stateFilter, false)
    }

    suspend fun prefetchOriginalText(apiResponse: StoriesResponse) {
        storyloop@ for (story in apiResponse.stories) {
            // only prefetch for unreads, so we don't grind to cache when the user scrolls
            // through old read stories
            if (story.read) continue@storyloop
            // if the feed is viewed in text mode by default, fetch that for offline reading
            val mode = PrefsUtils.getDefaultViewModeForFeed(this, story.feedId)
            if (mode == DefaultFeedView.TEXT) {
                if (dbHelper.getStoryText(story.storyHash) == null) {
                    addHash(story.storyHash)
                }
            }
        }
        originalTextService?.start()
    }

    fun prefetchImages(apiResponse: StoriesResponse) {
        storyloop@ for (story in apiResponse.stories) {
            // only prefetch for unreads, so we don't grind to cache when the user scrolls
            // through old read stories
            if (story.read) continue@storyloop
            // if the story provides known images we'll need for it, fetch those for offline reading
            if (story.imageUrls != null) {
                for (url in story.imageUrls) {
                    imagePrefetchService?.addUrl(url!!)
                }
            }
            if (story.thumbnailUrl != null) {
                imagePrefetchService?.addThumbnailUrl(story.thumbnailUrl)
            }
        }
        imagePrefetchService?.start()
    }

    suspend fun pushNotifications() {
        if (!PrefsUtils.isEnableNotifications(this)) return

        // don't notify stories until the queue is flushed so they don't churn
        if (storyHashQueue.isNotEmpty()) return
        // don't slow down active story loading
        if (PendingFeed != null) return

        val cFocus = dbHelper.getNotifyFocusStoriesCursor()
        val cUnread = dbHelper.getNotifyUnreadStoriesCursor()
        NotificationUtils.notifyStories(this, cFocus, cUnread, iconCache, dbHelper)
        closeQuietly(cFocus)
        closeQuietly(cUnread)
    }

    /**
     * Check to see if all async sync tasks have completed, indicating that sync can me marked as
     * complete.  Call this any time any individual sync task finishes.
     */
    fun checkCompletion() {
        //Log.d(this, "checking completion");
        if (mainSyncRunning) return
        if (cleanupService?.isRunning == true) return
        if (starredService?.isRunning == true) return
        if (originalTextService?.isRunning == true) return
        if (unreadsService?.isRunning == true) return
        if (imagePrefetchService?.isRunning == true) return
        Log.d(this, "confirmed completion")
        // iff all threads have finished, mark all received work as completed
        synchronized(COMPLETION_CALLBACKS_MUTEX) {
            for (params in outstandingStartParams) {
                jobFinished(params, false)
            }
            for (startId in outstandingStartIds) {
                stopSelf(startId)
            }
            outstandingStartIds.clear()
            outstandingStartParams.clear()
        }
    }

    private fun noteHardAPIFailure() {
        Log.w(this.javaClass.name, "hard API failure")
        lastAPIFailure = System.currentTimeMillis()
    }

    private fun backoffBackgroundCalls(): Boolean {
        if (isAppForeground) return false
        if (System.currentTimeMillis() > (lastAPIFailure + AppConstants.API_BACKGROUND_BACKOFF_MILLIS)) return false
        Log.i(this.javaClass.name, "abandoning background sync due to recent API failures.")
        return true
    }

    override fun onDestroy() {
        try {
            Log.d(this, "onDestroy")
            synchronized(COMPLETION_CALLBACKS_MUTEX) {
                if (outstandingStartIds.isNotEmpty() || outstandingStartParams.isNotEmpty()) {
                    Log.w(this, "Service scheduler destroyed before all jobs marked done?")
                }
            }
            cleanupService?.shutdown()
            unreadsService?.shutdown()
            starredService?.shutdown()
            originalTextService?.shutdown()
            imagePrefetchService?.shutdown()
            primaryExecutor?.let { executor ->
                executor.shutdown()
                try {
                    executor.awaitTermination(AppConstants.SHUTDOWN_SLACK_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
            Log.d(this, "onDestroy done")
        } catch (ex: Exception) {
            Log.e(this, "unclean shutdown", ex)
        }
        super.onDestroy()
    }

    fun sendSyncUpdate(update: Int) {
        submitUpdate(update)
    }

    private fun sendToastError(message: String) {
        submitError(message)
    }

    fun stopSync() = stopSync(this)

    companion object {
        private val COMPLETION_CALLBACKS_MUTEX = Any()
        private val PENDING_FEED_MUTEX = Mutex()

        @Volatile
        private var ActionsRunning = false

        @Volatile
        private var FFSyncRunning = false

        @Volatile
        private var StorySyncRunning = false

        @Volatile
        var isHousekeepingRunning: Boolean = false
            private set

        @Volatile
        private var RecountsRunning = false

        @Volatile
        private var DoFeedsFolders = false

        @Volatile
        private var HaltNow = false

        /** Informational flag only, as to whether we were offline last time we cycled.  */
        @Volatile
        var OfflineNow: Boolean = false

        @Volatile
        var authFails: Int = 0

        @JvmField
        @Volatile
        var isPremium: Boolean? = null

        @Volatile
        var isArchive: Boolean? = null

        @Volatile
        var isStaff: Boolean? = null

        private var lastFeedCount = 0L
        private var lastFFConnMillis = 0L
        private var lastFFReadMillis = 0L
        private var lastFFParseMillis = 0L
        private var lastFFWriteMillis = 0L

        /** Feed set that we need to sync immediately for the UI.  */
        private var PendingFeed: FeedSet? = null
        private var PendingFeedTarget = 0

        /** The last feed set that was actually fetched from the API.  */
        private var LastFeedSet: FeedSet? = null

        /** Feed sets that the API has said to have no more pages left.  */
        private val ExhaustedFeeds: MutableSet<FeedSet?> = HashSet()

        /** The number of pages we have collected for the given feed set.  */
        private val FeedPagesSeen: MutableMap<FeedSet?, Int> = HashMap()

        /** The number of stories we have collected for the given feed set.  */
        private val FeedStoriesSeen: MutableMap<FeedSet?, Int> = HashMap()

        /** Feed to reset to zero-state, so it is fetched fresh, presumably with new filters.  */
        private var ResetFeed: FeedSet? = null

        private val MUTEX_ResetFeed = Any()

        /** Actions that may need to be double-checked locally due to overlapping API calls.  */
        private val FollowupActions: MutableList<ReadingAction> = ArrayList()

        /** Feed IDs (API stype) that have been acted upon and need a double-check for counts.  */
        private val RecountCandidates: MutableSet<FeedSet> = HashSet()

        @Volatile
        private var FlushRecounts = false

        /** The time of the last hard API failure we encountered. Used to implement back-off so that the sync
         * service doesn't spin in the background chewing up battery when the API is unavailable.  */
        private var lastAPIFailure: Long = 0

        private var lastActionCount = 0

        fun stopSync(context: Context): Boolean {
            if (HaltNow) {
                Log.i(NBSyncService::class.java.name, "stopping sync, soft interrupt set.")
                return true
            }
            if (!NetworkUtils.isOnline(context)) {
                OfflineNow = true
                return true
            }
            return false
        }

        @JvmStatic
        val isFeedFolderSyncRunning: Boolean
            /**
             * Is the main feed/folder list sync running and blocking?
             */
            get() = isHousekeepingRunning || FFSyncRunning

        @JvmStatic
        val isFeedCountSyncRunning: Boolean
            get() = isHousekeepingRunning || RecountsRunning || FFSyncRunning

        /**
         * Is there a sync for a given FeedSet running?
         */
        @JvmStatic
        fun isFeedSetSyncing(fs: FeedSet?, context: Context): Boolean =
                fs == PendingFeed && !stopSync(context)

        @JvmStatic
        fun isFeedSetExhausted(fs: FeedSet?): Boolean = ExhaustedFeeds.contains(fs)

        @JvmStatic
        fun isFeedSetStoriesFresh(fs: FeedSet?): Boolean {
            val count = FeedStoriesSeen[fs] ?: return false
            return count >= 1
        }

        @JvmStatic
        fun getSyncStatusMessage(context: Context, brief: Boolean): String? {
            if (OfflineNow) return context.resources.getString(R.string.sync_status_offline)
            if (isHousekeepingRunning) return context.resources.getString(R.string.sync_status_housekeeping)
            if (FFSyncRunning) return context.resources.getString(R.string.sync_status_ffsync)
            if (CleanupService.activelyRunning) return context.resources.getString(R.string.sync_status_cleanup)
            if (StarredService.activelyRunning) return context.resources.getString(R.string.sync_status_starred)
            if (brief && !AppConstants.VERBOSE_LOG) return null
            if (ActionsRunning) return String.format(context.resources.getString(R.string.sync_status_actions), lastActionCount)
            if (RecountsRunning) return context.resources.getString(R.string.sync_status_recounts)
            if (StorySyncRunning) return context.resources.getString(R.string.sync_status_stories)
            if (UnreadsService.activelyRunning) return String.format(context.resources.getString(R.string.sync_status_unreads), UnreadsService.pendingCount)
            if (OriginalTextService.activelyRunning) return String.format(context.resources.getString(R.string.sync_status_text), OriginalTextService.pendingCount)
            if (ImagePrefetchService.activelyRunning) return String.format(context.resources.getString(R.string.sync_status_images), ImagePrefetchService.pendingCount)
            return null
        }

        /**
         * Force a refresh of feed/folder data on the next sync, even if enough time
         * hasn't passed for an autosync.
         */
        @JvmStatic
        fun forceFeedsFolders() {
            DoFeedsFolders = true
        }

        @JvmStatic
        fun flushRecounts() {
            FlushRecounts = true
        }

        /**
         * Requests that the service fetch additional stories for the specified feed/folder. Returns
         * true if more will be fetched as a result of this request.
         *
         * @param desiredStoryCount the minimum number of stories to fetch.
         * @param callerSeen the number of stories the caller thinks they have seen for the FeedSet
         * or a negative number if the caller trusts us to track for them, or null if the caller
         * has ambiguous or no state about the FeedSet and wants us to refresh for them.
         */
        @JvmStatic
        fun requestMoreForFeed(fs: FeedSet?, desiredStoryCount: Int, callerSeen: Int?): Boolean {
            synchronized(PENDING_FEED_MUTEX) {
                if (ExhaustedFeeds.contains(fs) && fs == LastFeedSet && callerSeen != null) {
                    android.util.Log.d(NBSyncService::class.java.name, "rejecting request for feedset that is exhaused")
                    return false
                }
                var alreadyPending = 0
                if (fs == PendingFeed) alreadyPending = PendingFeedTarget
                var alreadySeen = FeedStoriesSeen[fs]
                if (alreadySeen == null) alreadySeen = 0
                if ((callerSeen != null) && (callerSeen < alreadySeen)) {
                    // the caller is probably filtering and thinks they have fewer than we do, so
                    // update our count to agree with them, and force-allow another requet
                    alreadySeen = callerSeen
                    FeedStoriesSeen[fs] = callerSeen
                    alreadyPending = 0
                }

                PendingFeed = fs
                PendingFeedTarget = desiredStoryCount

                //Log.d(NBSyncService.class.getName(), "callerhas: " + callerSeen + "  have:" + alreadySeen + "  want:" + desiredStoryCount + "  pending:" + alreadyPending);
                if (fs != LastFeedSet) {
                    return true
                }
                if (desiredStoryCount <= alreadySeen) {
                    return false
                }
                if (desiredStoryCount <= alreadyPending) {
                    return false
                }
            }
            return true
        }

        /**
         * Prepare the reading session table to display the given feedset. This is done here
         * rather than in FeedUtils so we can track which FS is currently primed and not
         * constantly reset.  This is called not only when the UI wants to change out a
         * set but also when we sync a page of stories, since there are no guarantees which
         * will happen first.
         */
        suspend fun prepareReadingSession(
                context: Context,
                dbHelper: BlurDatabaseHelper,
                fs: FeedSet,
        ) = PENDING_FEED_MUTEX.withLock {
            val cursorFilters = CursorFilters(context, fs)
            if (fs != dbHelper.getSessionFeedSet()) {
                Log.d(NBSyncService::class.java.name, "preparing new reading session")
                // the next fetch will be the start of a new reading session; clear it so it
                // will be re-primed
                dbHelper.clearStorySession()
                // don't just rely on the auto-prepare code when fetching stories, it might be called
                // after we insert our first page and not trigger
                dbHelper.prepareReadingSession(fs, cursorFilters.stateFilter, cursorFilters.readFilter)
                // note which feedset we are loading so we can trigger another reset when it changes
                dbHelper.setSessionFeedSet(fs)
                submitUpdate(UPDATE_STORY or UPDATE_STATUS)
            }
        }

        /**
         * Gracefully stop the loading of the current FeedSet and unset the current story session
         * so it will get reset before any further stories are fetched.
         */
        @JvmStatic
        suspend fun resetReadingSession(dbHelper: BlurDatabaseHelper) {
            PENDING_FEED_MUTEX.withLock {
                Log.d(NBSyncService::class.java.name, "requesting reading session reset")
                PendingFeed = null
                dbHelper.setSessionFeedSet(null)
            }
        }

        /**
         * Reset the API pagniation state for the given feedset, presumably because the order or filter changed.
         */
        @JvmStatic
        fun resetFetchState(fs: FeedSet?) {
            synchronized(MUTEX_ResetFeed) {
                Log.d(NBSyncService::class.java.name, "requesting feed fetch state reset")
                ResetFeed = fs
            }
        }

        @JvmStatic
        fun addRecountCandidates(fs: FeedSet?) {
            if (fs == null) return
            // if this is a special feedset (read, saved, global shared, etc) that doesn't represent a
            // countable set of stories, don't bother recounting it
            if (fs.flatFeedIds.isEmpty()) return
            RecountCandidates.add(fs)
        }

        fun addRecountCandidates(sets: Set<FeedSet?>) {
            for (fs in sets) {
                addRecountCandidates(fs)
            }
        }

        @JvmStatic
        fun softInterrupt() {
            Log.i(NBSyncService::class.java.name, "soft stop")
            HaltNow = true
        }

        /**
         * Resets any internal temp vars or queues. Called when switching accounts.
         */
        @JvmStatic
        fun clearState() {
            PendingFeed = null
            ResetFeed = null
            FollowupActions.clear()
            RecountCandidates.clear()
            ExhaustedFeeds.clear()
            FeedPagesSeen.clear()
            FeedStoriesSeen.clear()
            OriginalTextService.clear()
            UnreadsService.clear()
            ImagePrefetchService.clear()
        }

        @JvmStatic
        val speedInfo: String
            get() {
                val s = StringBuilder()
                s.append(lastFeedCount).append(" feeds in ")
                s.append(" conn:").append(lastFFConnMillis)
                s.append(" read:").append(lastFFReadMillis)
                s.append(" parse:").append(lastFFParseMillis)
                s.append(" store:").append(lastFFWriteMillis)
                return s.toString()
            }

        @JvmStatic
        val pendingInfo: String
            get() {
                val s = StringBuilder()
                s.append(" pre:").append(lastActionCount)
                s.append(" post:").append(FollowupActions.size)
                return s.toString()
            }
    }
}
