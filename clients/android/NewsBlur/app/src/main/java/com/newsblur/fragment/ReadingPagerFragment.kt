package com.newsblur.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.newsblur.R
import com.newsblur.activity.Reading
import com.newsblur.databinding.FragmentReadingpagerBinding

/*
 * A fragment to hold the story pager.  Eventually this fragment should hold much of the UI and logic
 * currently implemented in the Reading activity.  The crucial part, though, is that the pager exists
 * in a wrapper fragment and that the pager is passed a *child* FragmentManager of this fragment and
 * not just the standard support FM from the activity/context.  The pager platform code appears to
 * expect this design.
 */
class ReadingPagerFragment : NbFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_readingpager, container, false)
        val binding = FragmentReadingpagerBinding.bind(v)

        val activity = (activity as Reading?)

        (binding.readingPager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER

        activity?.offerPager(
                binding.readingPager,
                getChildFragmentManager(),
                lifecycle,
        )
        return v
    }

    companion object {
        fun newInstance(): ReadingPagerFragment {
            val fragment = ReadingPagerFragment()
            val arguments = Bundle()
            fragment.setArguments(arguments)
            return fragment
        }
    }
}
