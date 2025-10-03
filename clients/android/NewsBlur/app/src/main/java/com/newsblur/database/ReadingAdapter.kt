package com.newsblur.database

import android.database.Cursor
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.newsblur.activity.Reading
import com.newsblur.domain.Classifier
import com.newsblur.domain.Story
import com.newsblur.fragment.LoadingFragment
import com.newsblur.fragment.ReadingItemFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingAdapter(
        private val hostFragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val sourceUserId: String?,
        private val showFeedMetadata: Boolean,
        private val activity: Reading,
        private val dbHelper: BlurDatabaseHelper,
) : FragmentStateAdapter(hostFragmentManager, lifecycle) {

    companion object {
        private const val LOADING_ID = Long.MIN_VALUE
    }

    private val stories = mutableListOf<Story>()
    private val classifiers = mutableMapOf<String, Classifier>()
    private var showLoading = true

    private var mostRecentCursor: Cursor? = null
    private var lastRawStoryCount: Int = 0

    override fun getItemCount(): Int = if (showLoading) 1 else stories.size

    override fun createFragment(position: Int): Fragment {
        return if (showLoading) LoadingFragment() else {
            val s = stories[position]
            ReadingItemFragment.newInstance(
                    s,
                    s.extern_feedTitle,
                    s.extern_feedColor,
                    s.extern_feedFade,
                    s.extern_faviconBorderColor,
                    s.extern_faviconTextColor,
                    s.extern_faviconUrl,
                    classifiers[s.feedId],
                    showFeedMetadata,
                    sourceUserId
            )
        }
    }

    override fun getItemId(position: Int): Long =
            if (showLoading) LOADING_ID else stories[position].storyHash.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean =
            if (showLoading) itemId == LOADING_ID
            else stories.any { it.storyHash.hashCode().toLong() == itemId }

    fun getStory(position: Int): Story? = stories.getOrNull(position)
    val count: Int get() = stories.size

    val rawStoryCount: Int get() = lastRawStoryCount

    fun getPosition(target: Story): Int =
            stories.indexOfFirst { it.storyHash == target.storyHash }

    fun findFirstUnread(): Int =
            stories.indexOfFirst { !it.read }.let { if (it >= 0) it else -1 }

    fun findHash(hash: String): Int =
            stories.indexOfFirst { it.storyHash == hash }.let { if (it >= 0) it else -1 }

    fun findFragmentForPosition(position: Int): ReadingItemFragment? {
        val id = getItemId(position)
        return hostFragmentManager.findFragmentByTag("f$id") as? ReadingItemFragment
    }

    fun swapCursor(lifecycleScope: LifecycleCoroutineScope, cursor: Cursor) {
        mostRecentCursor = cursor
        lastRawStoryCount = if (!cursor.isClosed) cursor.count else 0

        lifecycleScope.launch(Dispatchers.IO) {
            thawAndApply(cursor)
        }
    }

    private suspend fun thawAndApply(cursor: Cursor) {
        if (cursor !== mostRecentCursor) return

        val newStories: MutableList<Story> = ArrayList()
        val seenFeedIds = HashSet<String>()

        try {
            if (cursor.isClosed) return
            cursor.moveToPosition(-1)
            while (cursor.moveToNext()) {
                if (cursor.isClosed || cursor !== mostRecentCursor) return
                val s = Story.fromCursor(cursor)
                s.bindExternValues(cursor)
                newStories.add(s)
                seenFeedIds.add(s.feedId)
            }
        } catch (_: Throwable) {
            return
        }

        val clsMap = HashMap<String, Classifier>(seenFeedIds.size)
        for (feedId in seenFeedIds) {
            clsMap[feedId] = dbHelper.getClassifierForFeed(feedId)
        }

        withContext(Dispatchers.Main) {
            if (cursor !== mostRecentCursor) return@withContext
            stories.clear()
            stories.addAll(newStories)
            classifiers.clear()
            classifiers.putAll(clsMap)

            if (showLoading) {
                showLoading = false
                notifyDataSetChanged()
            } else {
                notifyDataSetChanged()
            }
            dispatchUpdatesToFragments()

            activity.pagerUpdated()
        }
    }

    private fun dispatchUpdatesToFragments() {
        if (showLoading) return
        for (position in 0 until itemCount) {
            val tag = "f${getItemId(position)}"
            (hostFragmentManager.findFragmentByTag(tag) as? ReadingItemFragment)
                    ?.offerStoryUpdate(stories[position])
        }
    }
}