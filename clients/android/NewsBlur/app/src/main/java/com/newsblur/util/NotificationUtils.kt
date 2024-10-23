package com.newsblur.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import com.newsblur.R
import com.newsblur.activity.FeedReading
import com.newsblur.activity.Reading
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.DatabaseConstants
import com.newsblur.domain.Story
import com.newsblur.util.PendingIntentUtils.getImmutableActivity
import com.newsblur.util.PendingIntentUtils.getImmutableBroadcast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NotificationUtils {

    private const val NOTIFY_COLOUR = -0x2575cb
    private const val MAX_CONCUR_NOTIFY = 5
    private val mutex = Mutex()

    /**
     * @param storiesFocus  a cursor of unread, focus stories to notify, ordered newest to oldest
     * @param storiesUnread a cursor of unread, neutral stories to notify, ordered newest to oldest
     */
    suspend fun notifyStories(context: Context, storiesFocus: Cursor?, storiesUnread: Cursor?, iconCache: FileCache, dbHelper: BlurDatabaseHelper) = mutex.withLock {
        val nm = NotificationManagerCompat.from(context)

        var count = 0
        while (storiesFocus?.moveToNext() == true) {
            val story = Story.fromCursor(storiesFocus)
            if (story.read) {
                nm.cancel(story.hashCode())
                continue
            }
            if (dbHelper.isStoryDismissed(story.storyHash)) {
                nm.cancel(story.hashCode())
                continue
            }
            if (StoryUtils.hasOldTimestamp(story.timestamp)) {
                dbHelper.putStoryDismissed(story.storyHash)
                nm.cancel(story.hashCode())
                continue
            }
            if (count < MAX_CONCUR_NOTIFY) {
                val n = buildStoryNotification(story, storiesFocus, context, iconCache)
                nm.notify(story.hashCode(), n)
            } else {
                nm.cancel(story.hashCode())
                dbHelper.putStoryDismissed(story.storyHash)
            }
            count++
        }
        while (storiesUnread?.moveToNext() == true) {
            val story = Story.fromCursor(storiesUnread)
            if (story.read) {
                nm.cancel(story.hashCode())
                continue
            }
            if (dbHelper.isStoryDismissed(story.storyHash)) {
                nm.cancel(story.hashCode())
                continue
            }
            if (StoryUtils.hasOldTimestamp(story.timestamp)) {
                dbHelper.putStoryDismissed(story.storyHash)
                nm.cancel(story.hashCode())
                continue
            }
            if (count < MAX_CONCUR_NOTIFY) {
                val n = buildStoryNotification(story, storiesUnread, context, iconCache)
                nm.notify(story.hashCode(), n)
            } else {
                nm.cancel(story.hashCode())
                dbHelper.putStoryDismissed(story.storyHash)
            }
            count++
        }
    }

    /**
     * creates notification channels necessary for 26+, if applicable
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = context.getString(R.string.story_notification_channel_name)
            val id = context.getString(R.string.story_notification_channel_id)
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildStoryNotification(story: Story, cursor: Cursor, context: Context, iconCache: FileCache): Notification {
        Log.d(NotificationUtils::class.java.name, "Building notification")
        val i = Intent(context, FeedReading::class.java)
        // the action is unused, but bugs in some platform versions ignore extras if it is unset
        i.setAction(story.storyHash)
        // these extras actually dictate activity behaviour
        i.putExtra(Reading.EXTRA_FEEDSET, FeedSet.singleFeed(story.feedId))
        i.putExtra(Reading.EXTRA_STORY_HASH, story.storyHash)
        // force a new Reading activity, since if multiple notifications are tapped, any re-use or
        // stacking of the activity would almost certainly out-race the sync loop and cause stale
        // UI on some devices.
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // set the requestCode to the story hashcode to prevent the PI re-using the wrong Intent
        val pendingIntent = getImmutableActivity(context, story.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT)

        val dismissIntent = Intent(context, NotifyDismissReceiver::class.java)
        dismissIntent.putExtra(Reading.EXTRA_STORY_HASH, story.storyHash)
        val dismissPendingIntent = getImmutableBroadcast(context.applicationContext, story.hashCode(), dismissIntent, 0)

        val saveIntent = Intent(context, NotifySaveReceiver::class.java)
        saveIntent.putExtra(Reading.EXTRA_STORY_HASH, story.storyHash)
        val savePendingIntent = getImmutableBroadcast(context.applicationContext, story.hashCode(), saveIntent, 0)

        val markreadIntent = Intent(context, NotifyMarkreadReceiver::class.java)
        markreadIntent.putExtra(Reading.EXTRA_STORY_HASH, story.storyHash)
        val markreadPendingIntent = getImmutableBroadcast(context.applicationContext, story.hashCode(), markreadIntent, 0)

        val shareIntent = Intent(context, NotifyShareReceiver::class.java)
        shareIntent.putExtra(Reading.EXTRA_STORY, story)
        val sharePendingIntent = getImmutableBroadcast(context.applicationContext, story.hashCode(), shareIntent, 0)

        val feedTitle = cursor.getString(cursor.getColumnIndex(DatabaseConstants.FEED_TITLE))
        val title = StringBuilder()
        title.append(feedTitle).append(": ").append(story.title)

        val faviconUrl = cursor.getString(cursor.getColumnIndex(DatabaseConstants.FEED_FAVICON_URL))
        val feedIcon = ImageLoader.getCachedImageSynchro(iconCache, faviconUrl)

        val nb = NotificationCompat.Builder(context, context.getString(R.string.story_notification_channel_id))
                .setContentTitle(title.toString())
                .setContentText(story.shortContent)
                .setSmallIcon(R.drawable.logo_monochrome)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(dismissPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setWhen(story.timestamp)
                .addAction(0, "Mark Read", markreadPendingIntent)
                .addAction(0, "Save", savePendingIntent)
                .addAction(0, "Share", sharePendingIntent)
                .setColor(NOTIFY_COLOUR)
        if (feedIcon != null) {
            nb.setLargeIcon(feedIcon)
        }

        return nb.build()
    }

    @JvmStatic
    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    fun cancel(context: Context, nid: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(nid)
    }

    fun hasPermissions(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    fun shouldShowRationale(fragment: Fragment): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        fragment.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    } else false
}
