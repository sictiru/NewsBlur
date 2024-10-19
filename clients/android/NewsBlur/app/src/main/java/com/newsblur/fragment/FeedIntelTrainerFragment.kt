package com.newsblur.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.databinding.DialogTrainfeedBinding
import com.newsblur.domain.Classifier
import com.newsblur.domain.Feed
import com.newsblur.util.FeedSet
import com.newsblur.util.FeedUtils
import com.newsblur.util.UIUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class FeedIntelTrainerFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    private var feed: Feed? = null
    private var fs: FeedSet? = null
    private var classifier: Classifier? = null

    private lateinit var binding: DialogTrainfeedBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreate(savedInstanceState)
        feed = arguments?.getSerializable("feed") as Feed?
        fs = arguments?.getSerializable("feedset") as FeedSet?

        val v = layoutInflater.inflate(R.layout.dialog_trainfeed, null)
        binding = DialogTrainfeedBinding.bind(v)

        viewLifecycleOwner.lifecycleScope.launch {
            classifier = dbHelper.getClassifierForFeed(feed?.feedId)
            // get the list of suggested tags
            val allTags = dbHelper.getTagsForFeed(feed?.feedId).toMutableList()
            // get the list of suggested authors
            val allAuthors = dbHelper.getAuthorsForFeed(feed?.feedId).toMutableList()

            withContext(Dispatchers.Main) {
                // display known title classifiers
                for ((key) in classifier!!.title) {
                    val row = layoutInflater.inflate(R.layout.include_intel_row, null)
                    val label = row.findViewById<TextView>(R.id.intel_row_label)
                    label.text = key
                    UIUtils.setupIntelDialogRow(row, classifier!!.title, key)
                    binding.existingTitleIntelContainer.addView(row)
                }
                if (classifier?.title?.isEmpty() == true) binding.intelTitleHeader.visibility = View.GONE

                // augment that list with known trained tags
                classifier?.tags?.forEach {
                    if (!allTags.contains(it.key)) {
                        allTags.add(it.key)
                    }
                }
            }

            for (tag in allTags) {
                val row = layoutInflater.inflate(R.layout.include_intel_row, null)
                val label = row.findViewById<TextView>(R.id.intel_row_label)
                label.text = tag
                UIUtils.setupIntelDialogRow(row, classifier!!.tags, tag)
                binding.existingTagIntelContainer.addView(row)
            }
            if (allTags.isEmpty()) binding.intelTagHeader.visibility = View.GONE

            // augment that list with known trained authors
            classifier?.authors?.forEach {
                if (!allAuthors.contains(it.key)) {
                    allAuthors.add(it.key)
                }
            }

            for (author in allAuthors) {
                val rowAuthor = layoutInflater.inflate(R.layout.include_intel_row, null)
                val labelAuthor = rowAuthor.findViewById<TextView>(R.id.intel_row_label)
                labelAuthor.text = author
                UIUtils.setupIntelDialogRow(rowAuthor, classifier!!.authors, author)
                binding.existingAuthorIntelContainer.addView(rowAuthor)
            }
            if (allAuthors.size < 1) binding.intelAuthorHeader.visibility = View.GONE

            // for feel-level intel, the label is the title and the intel identifier is the feed ID
            val rowFeed = layoutInflater.inflate(R.layout.include_intel_row, null)
            val labelFeed = rowFeed.findViewById<TextView>(R.id.intel_row_label)
            labelFeed.text = feed!!.title
            classifier?.let {
                UIUtils.setupIntelDialogRow(rowFeed, it.feeds, feed?.feedId)
            }
            binding.existingFeedIntelContainer.addView(rowFeed)
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.feed_intel_dialog_title)
        builder.setView(v)

        builder.setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> this@FeedIntelTrainerFragment.dismiss() }
        builder.setPositiveButton(R.string.dialog_story_intel_save) { _, _ ->
            feedUtils.updateClassifier(feed?.feedId, classifier, fs, requireContext())
            this@FeedIntelTrainerFragment.dismiss()
        }

        val dialog: Dialog = builder.create()
        dialog.window?.attributes?.gravity = Gravity.BOTTOM
        return dialog
    }

    companion object {

        @JvmStatic
        fun newInstance(feed: Feed?, fs: FeedSet?): FeedIntelTrainerFragment {
            val fragment = FeedIntelTrainerFragment()
            val args = Bundle()
            args.putSerializable("feed", feed)
            args.putSerializable("feedset", fs)
            fragment.arguments = args
            return fragment
        }
    }
}

