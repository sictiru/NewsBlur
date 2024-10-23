package com.newsblur.fragment

import android.database.Cursor
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.newsblur.R
import com.newsblur.activity.ItemsList
import com.newsblur.activity.NbActivity
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.database.StoryViewAdapter
import com.newsblur.database.StoryViewAdapter.OnStoryClickListener
import com.newsblur.databinding.FragmentItemgridBinding
import com.newsblur.databinding.RowFleuronBinding
import com.newsblur.di.IconLoader
import com.newsblur.di.ThumbnailLoader
import com.newsblur.service.NBSyncService.Companion.isFeedSetExhausted
import com.newsblur.service.NBSyncService.Companion.isFeedSetStoriesFresh
import com.newsblur.service.NBSyncService.Companion.isFeedSetSyncing
import com.newsblur.service.NBSyncService.Companion.requestMoreForFeed
import com.newsblur.util.CursorFilters
import com.newsblur.util.FeedSet
import com.newsblur.util.FeedUtils
import com.newsblur.util.ImageLoader
import com.newsblur.util.Log
import com.newsblur.util.PrefsUtils
import com.newsblur.util.ReadFilter
import com.newsblur.util.StoryListStyle
import com.newsblur.util.UIUtils
import com.newsblur.util.ViewUtils
import com.newsblur.viewModel.StoriesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
open class ItemSetFragment : NbFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    @Inject
    @IconLoader
    lateinit var iconLoader: ImageLoader

    @Inject
    @ThumbnailLoader
    lateinit var thumbnailLoader: ImageLoader

    private var cursorSeenYet = false // have we yet seen a valid cursor for our particular feedset?

    /**
     * Flag used to ensure that when the adapter resumes,
     * it omits any new stories that would disrupt the current order and cause the list to
     * unexpectedly jump, thereby preserving the scroll position. This flag specifically helps
     * manage the insertion of new stories that have been backfilled according to their timestamps.
     */
    private var skipBackFillingStories = false

    private var itemGridWidthPx = 0
    private var columnCount = 0

    private var gridSpacingPx = 0

    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: StoryViewAdapter

    // an optional pending scroll state to restore.
    private var gridState: Parcelable? = null

    // loading indicator for when stories are absent or stale (at top of list)
    // R.id.top_loading_throb
    // loading indicator for when stories are present and fresh (at bottom of list)
    private lateinit var bottomProgressView: LinearProgressIndicator

    // the fleuron has padding that can't be calculated until after layout, but only changes
    // rarely thereafter
    private var fleuronResized = false

    // de-dupe the massive stream of scrolling data to auto-mark read
    private var lastAutoMarkIndex = -1

    var indexOfLastUnread: Int = -1
    var fullFlingComplete: Boolean = false

    private lateinit var binding: FragmentItemgridBinding
    private lateinit var fleuronBinding: RowFleuronBinding
    private lateinit var storiesViewModel: StoriesViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storiesViewModel = ViewModelProvider(this).get(StoriesViewModel::class.java)
    }

    override fun onPause() {
        // a pause/resume cycle will depopulate and repopulate the list and trigger bad scroll
        // readings and cause zero-index refreshes, wasting massive cycles. hold the refresh logic
        // until the loaders reset
        cursorSeenYet = false
        skipBackFillingStories = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        fleuronResized = false
        updateLoadingIndicators()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState == null) return
        gridState = savedInstanceState.getParcelable(BUNDLE_GRIDSTATE)
        // dont actually re-use the state yet, the adapter probably doesn't have any data
        // and won't know how to scroll. swapCursor() will pass this to the adapter when
        // data are ready.
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_itemgrid, null)
        binding = FragmentItemgridBinding.bind(v)
        val fleuronView = inflater.inflate(R.layout.row_fleuron, null)
        fleuronBinding = RowFleuronBinding.bind(fleuronView)

        // disable the throbbers if animations are going to have a zero time scale
        val isDisableAnimations = ViewUtils.isPowerSaveMode(requireContext())

        binding.topLoadingIndicator.isEnabled = !isDisableAnimations

        val footerView = inflater.inflate(R.layout.row_loading_indicator, null)
        bottomProgressView = footerView.findViewById(R.id.itemlist_loading)
        bottomProgressView.setEnabled(!isDisableAnimations)

        fleuronBinding.root.visibility = View.INVISIBLE
        fleuronBinding.containerSubscribe.setOnClickListener { UIUtils.startSubscriptionActivity(requireContext()) }

        binding.itemgridfragmentGrid.viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                itemGridWidthPx = binding.itemgridfragmentGrid.measuredWidth
                binding.itemgridfragmentGrid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateListStyle()
            }
        })

        val listStyle = PrefsUtils.getStoryListStyle(requireContext(), feedSet)

        calcColumnCount(listStyle)
        layoutManager = GridLayoutManager(requireContext(), columnCount)
        binding.itemgridfragmentGrid.layoutManager = layoutManager
        setupAnimSpeeds()

        calcGridSpacing(listStyle)
        binding.itemgridfragmentGrid.addItemDecoration(object : ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect[gridSpacingPx, gridSpacingPx, gridSpacingPx] = gridSpacingPx
            }
        })

        adapter = StoryViewAdapter(requireActivity() as NbActivity, this, feedSet, listStyle, iconLoader, thumbnailLoader, feedUtils, onStoryClickListener)
        adapter.addFooterView(footerView)
        adapter.addFooterView(fleuronBinding.root)
        binding.itemgridfragmentGrid.adapter = adapter

        // the layout manager needs to know that the footer rows span all the way across
        layoutManager.spanSizeLookup = object : SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    StoryViewAdapter.VIEW_TYPE_FOOTER -> columnCount
                    else -> 1
                }
            }
        }

        binding.itemgridfragmentGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                this@ItemSetFragment.onScrolled(recyclerView, dx, dy)
            }
        })

        setupGestureDetector(binding.itemgridfragmentGrid)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    storiesViewModel.activeStoriesCursor.collectLatest { cursor: Cursor? ->
                        this@ItemSetFragment.setCursor(cursor)
                    }
                }
            }
        }

        val fs = feedSet
        if (fs == null) {
            Log.e(this.javaClass.name, "can't create fragment, no feedset ready")
            // this is probably happening in a finalisation cycle or during a crash, pop the activity stack
            try {
                requireActivity().finish()
            } catch (ignored: Exception) {
            }
        }
    }

    protected fun triggerRefresh(desiredStoryCount: Int, totalSeen: Int?) {
        // ask the sync service for as many stories as we want
        val gotSome = requestMoreForFeed(feedSet, desiredStoryCount, totalSeen)
        // if the service thinks it can get more, or if we haven't even seen a cursor yet, start the service
        if (gotSome || (totalSeen == null)) triggerSync()
    }

    /**
     * Indicate that the DB was cleared.
     */
    fun resetEmptyState() {
        updateAdapter(null)
        cursorSeenYet = false
    }

    /**
     * A calback for our adapter that async thaws the story list so the fragment can have
     * some info about the story list when it is ready.
     */
    fun storyThawCompleted(indexOfLastUnread: Int) {
        this.indexOfLastUnread = indexOfLastUnread
        this.fullFlingComplete = false
        // we don't actually calculate list speed until it has some stories
        setupAnimSpeeds()
    }

    fun scrollToTop() {
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    fun scrollToPosition(position: Int) {
        val layoutTotalPositions = layoutManager.itemCount - 1
        if (position in 1..layoutTotalPositions) {
            layoutManager.scrollToPosition(position)
        }
    }

    private val feedSet: FeedSet?
        get() = (requireActivity() as ItemsList).feedSet

    fun hasUpdated() {
        val fs = feedSet
        if (isAdded && fs != null) {
            storiesViewModel.getActiveStories(fs, CursorFilters(requireContext(), fs))
        }
    }

    protected fun updateAdapter(cursor: Cursor?) {
        adapter.swapCursor(cursor, binding.itemgridfragmentGrid, gridState, skipBackFillingStories)
        gridState = null
        adapter.updateFeedSet(feedSet)
        if (cursor != null && cursor.count > 0) {
            binding.emptyView.visibility = View.INVISIBLE
        } else {
            binding.emptyView.visibility = View.VISIBLE
        }

        // though we have stories, we might not yet have as many as we want
        ensureSufficientStories()
    }

    private suspend fun setCursor(cursor: Cursor?) {
        if (cursor != null) {
            if (!dbHelper.isFeedSetReady(feedSet)) {
                // the DB hasn't caught up yet from the last story list; don't display stale stories.
                Log.i(this.javaClass.name, "stale load")
                updateAdapter(null)
                triggerRefresh(1, null)
            } else {
                cursorSeenYet = true
                Log.d(this.javaClass.name, "loaded cursor with count: " + cursor.count)
                updateAdapter(cursor)
                if (cursor.count < 1) {
                    triggerRefresh(1, 0)
                }
            }
        }
        updateLoadingIndicators()
    }

    private fun updateLoadingIndicators() {
        calcFleuronPadding()

        if (cursorSeenYet && adapter.rawStoryCount > 0 && UIUtils.needsSubscriptionAccess(requireContext(), feedSet)) {
            fleuronBinding.root.visibility = View.VISIBLE
            fleuronBinding.containerSubscribe.visibility = View.VISIBLE
            binding.topLoadingIndicator.visibility = View.INVISIBLE
            bottomProgressView.visibility = View.INVISIBLE
            fleuronResized = false
            return
        }

        if ((!cursorSeenYet) || isFeedSetSyncing(feedSet, requireActivity())) {
            binding.emptyViewText.setText(R.string.empty_list_view_loading)
            binding.emptyViewText.setTypeface(binding.emptyViewText.typeface, Typeface.ITALIC)
            binding.emptyViewImage.visibility = View.INVISIBLE

            if (isFeedSetStoriesFresh(feedSet)) {
                binding.topLoadingIndicator.visibility = View.INVISIBLE
                bottomProgressView.visibility = View.VISIBLE
            } else {
                binding.topLoadingIndicator.visibility = View.VISIBLE
                bottomProgressView.visibility = View.GONE
            }
            fleuronBinding.root.visibility = View.INVISIBLE
        } else {
            val readFilter = PrefsUtils.getReadFilter(activity, feedSet)
            if (readFilter == ReadFilter.UNREAD) {
                binding.emptyViewText.setText(R.string.empty_list_view_no_stories_unread)
            } else {
                binding.emptyViewText.setText(R.string.empty_list_view_no_stories)
            }
            binding.emptyViewText.setTypeface(binding.emptyViewText.typeface, Typeface.NORMAL)
            binding.emptyViewImage.visibility = View.VISIBLE

            binding.topLoadingIndicator.visibility = View.INVISIBLE
            bottomProgressView.visibility = View.INVISIBLE
            if (cursorSeenYet && isFeedSetExhausted(feedSet) && (adapter.rawStoryCount > 0)) {
                fleuronBinding.containerSubscribe.visibility = View.GONE
                fleuronBinding.root.visibility = View.VISIBLE
            }
        }
    }

    fun notifyContentPrefsChanged() {
        adapter.notifyAllItemsChanged()
    }

    fun updateThumbnailStyle() {
        val thumbnailStyle = PrefsUtils.getThumbnailStyle(requireContext())
        adapter.setThumbnailStyle(thumbnailStyle)
        adapter.notifyAllItemsChanged()
    }

    fun updateListStyle() {
        val listStyle = PrefsUtils.getStoryListStyle(activity, feedSet)
        calcColumnCount(listStyle)
        calcGridSpacing(listStyle)
        layoutManager.spanCount = columnCount
        adapter.setStyle(listStyle)
        adapter.notifyAllItemsChanged()
    }

    fun updateSpacingStyle() {
        val spacingStyle = PrefsUtils.getSpacingStyle(requireContext())
        adapter.setSpacingStyle(spacingStyle)
        adapter.notifyAllItemsChanged()
    }

    fun updateTextSize() {
        val textSize = PrefsUtils.getListTextSize(requireContext())
        adapter.setTextSize(textSize)
        adapter.notifyAllItemsChanged()
    }

    private fun calcColumnCount(listStyle: StoryListStyle) {
        // sensible defaults
        var colsFine = 3
        var colsMed = 2
        var colsCoarse = 1

        // ensure we have measured
        if (itemGridWidthPx > 0) {
            val itemGridWidthDp = Math.round(UIUtils.px2dp(requireContext(), itemGridWidthPx))
            colsCoarse = itemGridWidthDp / 300
            colsMed = itemGridWidthDp / 200
            colsFine = itemGridWidthDp / 150
            // sanity check the counts are strictly increasing
            if (colsCoarse < 1) colsCoarse = 1
            if (colsMed <= colsCoarse) colsMed = colsCoarse + 1
            if (colsFine <= colsMed) colsFine = colsMed + 1
        }

        columnCount = if (listStyle == StoryListStyle.GRID_F) {
            colsFine
        } else if (listStyle == StoryListStyle.GRID_M) {
            colsMed
        } else if (listStyle == StoryListStyle.GRID_C) {
            colsCoarse
        } else {
            1
        }
    }

    private fun calcGridSpacing(listStyle: StoryListStyle) {
        gridSpacingPx = if (listStyle == StoryListStyle.LIST) {
            0
        } else {
            UIUtils.dp2px(requireContext(), GRID_SPACING_DP)
        }
    }

    private fun setupAnimSpeeds() {
        // to mitigate taps missed because of list pushdowns, RVs animate them. however, the speed
        // is device and settings dependent.  to keep the UI consistent across installs, take the
        // system default speed and tweak it towards a speed that looked and functioned well in
        // testing while still somewhat respecting the system's requested adjustments to speed.
        var targetAddDuration = 250L
        // moves are especially jarring, and very rare
        var targetMovDuration = 500L
        // if there are no stories in the list at all, let the first insert happen very quickly
        if (!::adapter.isInitialized || adapter.rawStoryCount < 1) {
            targetAddDuration = 0L
            targetMovDuration = 0L
        }

        val anim = binding.itemgridfragmentGrid.itemAnimator
        anim!!.addDuration = (anim.addDuration + targetAddDuration) / 2L
        anim.moveDuration = (anim.moveDuration + targetMovDuration) / 2L
    }

    private fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // the framework likes to trigger this on init before we even known counts, so disregard those
        if (!cursorSeenYet) return

        // don't bother checking on scroll up
        if (dy < 1) return

        // skip fetching more stories if premium access is required
        if (UIUtils.needsSubscriptionAccess(requireContext(), feedSet) && adapter.itemCount >= 3) return

        ensureSufficientStories()

        // the list can be scrolled past the last item thanks to the offset footer, but don't fling
        // past the last item, which can be confusing to users who don't know about or need the offset
        if (!fullFlingComplete &&
                layoutManager.findLastCompletelyVisibleItemPosition() >= adapter.storyCount) {
            binding.itemgridfragmentGrid.stopScroll()
            // but after halting at the end once, do allow scrolling past the bottom
            fullFlingComplete = true
        }

        // if flinging downwards, pause at the last unread as a convenience
        if (indexOfLastUnread >= 0 &&
                layoutManager.findLastCompletelyVisibleItemPosition() >= indexOfLastUnread) {
            // but don't interrupt if already past the last unread
            if (indexOfLastUnread >= layoutManager.findFirstCompletelyVisibleItemPosition()) {
                binding.itemgridfragmentGrid.stopScroll()
            }
            indexOfLastUnread = -1
        }

        if (PrefsUtils.isMarkReadOnFeedScroll(requireContext())) {
            // we want the top row of stories that is partially obscured. go back one from the first fully visible
            val markEnd = layoutManager.findFirstCompletelyVisibleItemPosition() - 1
            if (markEnd > lastAutoMarkIndex) {
                lastAutoMarkIndex = markEnd
                // iterate backwards through that row, marking read
                for (i in 0 until columnCount) {
                    val index = markEnd - i
                    val story = adapter.getStory(index)
                    if (story != null) {
                        feedUtils.markStoryAsRead(story, requireContext())
                    }
                }
            }
        }
    }

    private fun ensureSufficientStories() {
        // don't ask the list for how many rows it actually has - it may still be thawing from the cursor
        val totalCount = adapter.rawStoryCount
        val visibleCount = layoutManager.childCount
        val lastVisible = layoutManager.findLastVisibleItemPosition()


        // load an extra page worth of stories past the viewport plus at least two rows to prime the height calc
        val desiredStoryCount = lastVisible + (visibleCount * 2) + (columnCount * 2)
        triggerRefresh(desiredStoryCount, totalCount)
        //com.newsblur.util.Log.d(this, String.format(" total:%d  bound:%d  last%d  desire:%d", totalCount, visibleCount, lastVisible, desiredStoryCount));
    }

    private fun setupGestureDetector(v: RecyclerView) {
        val gestureDetector = GestureDetector(activity, SwipeBackGestureDetector())
        v.addOnItemTouchListener(object : SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }
        })
    }

    /**
     * A detector for the standard "swipe back out of activity" Android gesture.  Note that this does
     * not necessarily wait for an UP event, as RecyclerViews like to capture them.
     */
    internal inner class SwipeBackGestureDetector : SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null) return false
            if ((e1.x < 60f) &&  // the gesture should start from the left bezel and
                    ((e2.x - e1.x) > 90f) &&  // move horizontally to the right and
                    (abs((e1.y - e2.y).toDouble()) < 40f) // have minimal vertical travel, so we don't capture scrolling gestures
            ) {
                this@ItemSetFragment.requireActivity().finish()
                return true
            }
            return false
        }
    }

    /**
     * if the story list bottom has been reached, add an amount of padding to the footer so that it can still
     * be scrolled until the bottom most story reaches to top, for those who mark-by-scrolling.
     */
    private fun calcFleuronPadding() {
        // sanity check that we even have views yet
        if (fleuronResized || fleuronBinding.root.layoutParams == null) return
        val listHeight = binding.itemgridfragmentGrid.measuredHeight
        val oldLayout = fleuronBinding.root.layoutParams
        val newLayout = FrameLayout.LayoutParams(oldLayout)
        val marginPx_4dp = UIUtils.dp2px(requireContext(), 4)
        val fleuronFooterHeightPx = fleuronBinding.root.measuredHeight
        if (listHeight > 1) {
            newLayout.setMargins(0, marginPx_4dp, 0, listHeight - fleuronFooterHeightPx)
            fleuronResized = true
        } else {
            val defaultPx_100dp = UIUtils.dp2px(requireContext(), 100)
            newLayout.setMargins(0, marginPx_4dp, 0, defaultPx_100dp)
        }
        fleuronBinding.root.layoutParams = newLayout
    }

    private val onStoryClickListener: OnStoryClickListener
        get() = OnStoryClickListener { feedSet: FeedSet?, storyHash: String? ->
            val activity = (requireActivity() as ItemsList?)
            activity?.startReadingActivity(feedSet, storyHash)
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(BUNDLE_GRIDSTATE, binding.itemgridfragmentGrid.layoutManager?.onSaveInstanceState())
    }

    companion object {
        private const val BUNDLE_GRIDSTATE = "gridstate"

        private const val GRID_SPACING_DP = 5

        @JvmStatic
        fun newInstance(): ItemSetFragment {
            val fragment = ItemSetFragment()
            val arguments = Bundle()
            fragment.arguments = arguments
            return fragment
        }
    }
}
