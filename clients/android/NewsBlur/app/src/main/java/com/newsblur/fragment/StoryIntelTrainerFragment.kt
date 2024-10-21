package com.newsblur.fragment

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.databinding.DialogTrainstoryBinding
import com.newsblur.domain.Classifier
import com.newsblur.domain.Story
import com.newsblur.util.FeedSet
import com.newsblur.util.FeedUtils
import com.newsblur.util.UIUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class StoryIntelTrainerFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    private lateinit var story: Story
    private lateinit var classifier: Classifier

    private var fs: FeedSet? = null
    private var newTitleTraining: Int? = null

    private lateinit var binding: DialogTrainstoryBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreate(savedInstanceState)
        story = requireArguments().getSerializable("story") as Story
        fs = requireArguments().getSerializable("feedset") as FeedSet?

        val v = layoutInflater.inflate(R.layout.dialog_trainstory, null)
        binding = DialogTrainstoryBinding.bind(v)

        // set up the special title training box for the title from this story and the associated buttons
        binding.intelTitleSelection.setText(story.title)
        // the layout sets inputType="none" on this EditText, but a widespread platform bug requires us
        // to also set this programmatically to make the field read-only for selection.
        binding.intelTitleSelection.inputType = InputType.TYPE_NULL
        // the user is selecting for our custom widget, not to copy/paste
        binding.intelTitleSelection.disableActionMenu()
        // pre-select the whole title to make it easier for the user to manipulate the selection handles
        binding.intelTitleSelection.selectAll()
        // do this after init and selection to prevent toast spam
        binding.intelTitleSelection.setForceSelection(true)
        // the disposition buttons for a new title training don't immediately impact the classifier object,
        // lest the user want to change selection substring after choosing the disposition.  so just store
        // the training factor in a variable that can be pulled on completion
        binding.intelTitleLike.setOnClickListener {
            newTitleTraining = Classifier.LIKE
            binding.intelTitleLike.setBackgroundResource(R.drawable.ic_thumb_up_green)
            binding.intelTitleDislike.setBackgroundResource(R.drawable.ic_thumb_down_yellow)
        }
        binding.intelTitleDislike.setOnClickListener {
            newTitleTraining = Classifier.DISLIKE
            binding.intelTitleLike.setBackgroundResource(R.drawable.ic_thumb_up_yellow)
            binding.intelTitleDislike.setBackgroundResource(R.drawable.ic_thumb_down_red)
        }
        binding.intelTitleClear.setOnClickListener {
            newTitleTraining = null
            binding.intelTitleLike.setBackgroundResource(R.drawable.ic_thumb_up_yellow)
            binding.intelTitleDislike.setBackgroundResource(R.drawable.ic_thumb_down_yellow)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            classifier = dbHelper.getClassifierForFeed(story.feedId)
            withContext(Dispatchers.Main) {
                // scan trained title fragments for this feed and see if any apply to this story
                for ((key) in classifier.title) {
                    if (story.title.indexOf(key!!) >= 0) {
                        val row = layoutInflater.inflate(R.layout.include_intel_row, null)
                        val label = row.findViewById<TextView>(R.id.intel_row_label)
                        label.text = key
                        UIUtils.setupIntelDialogRow(row, classifier.title, key)
                        binding.existingTitleIntelContainer.addView(row)
                    }
                }

                // list all tags for this story, trained or not
                for (tag in story.tags) {
                    val row = layoutInflater.inflate(R.layout.include_intel_row, null)
                    val label = row.findViewById<TextView>(R.id.intel_row_label)
                    label.text = tag
                    UIUtils.setupIntelDialogRow(row, classifier.tags, tag)
                    binding.existingTagIntelContainer.addView(row)
                }
                if (story.tags.isEmpty()) binding.intelTagHeader.visibility = View.GONE

                // there is a single author per story
                if (!TextUtils.isEmpty(story.authors)) {
                    val rowAuthor = layoutInflater.inflate(R.layout.include_intel_row, null)
                    val labelAuthor = rowAuthor.findViewById<TextView>(R.id.intel_row_label)
                    labelAuthor.text = story.authors
                    UIUtils.setupIntelDialogRow(rowAuthor, classifier.authors, story.authors)
                    binding.existingAuthorIntelContainer.addView(rowAuthor)
                } else {
                    binding.intelAuthorHeader.visibility = View.GONE
                }
            }

            val feedTitle = feedUtils.getFeedTitle(story.feedId)
            withContext(Dispatchers.Main) {
                // there is a single feed to be trained, but it is a bit odd in that the label is the title and
                // the intel identifier is the feed ID
                val rowFeed = layoutInflater.inflate(R.layout.include_intel_row, null)
                val labelFeed = rowFeed.findViewById<TextView>(R.id.intel_row_label)
                labelFeed.text = feedTitle
                UIUtils.setupIntelDialogRow(rowFeed, classifier.feeds, story.feedId)
                binding.existingFeedIntelContainer.addView(rowFeed)
            }
        }

        val builder = AlertDialog.Builder(requireActivity())
        builder.setTitle(R.string.story_intel_dialog_title)
        builder.setView(v)

        builder.setNegativeButton(R.string.alert_dialog_cancel) { _, _ ->
            this@StoryIntelTrainerFragment.dismiss()
        }
        builder.setPositiveButton(R.string.dialog_story_intel_save) { _, _ ->
            if (::classifier.isInitialized) {
                if (newTitleTraining != null && !TextUtils.isEmpty(binding.intelTitleSelection.selection)) {
                    classifier.title[binding.intelTitleSelection.selection] = newTitleTraining
                }
                feedUtils.updateClassifier(story.feedId, classifier, fs, requireActivity())
            }
            this@StoryIntelTrainerFragment.dismiss()
        }

        val dialog: Dialog = builder.create()
        dialog.window?.attributes?.gravity = Gravity.BOTTOM
        return dialog
    }

    companion object {
        @JvmStatic
        fun newInstance(story: Story, fs: FeedSet?): StoryIntelTrainerFragment {
            require(story.feedId != "0") { "cannot intel train stories with a null/zero feed" }
            val fragment = StoryIntelTrainerFragment()
            val args = Bundle()
            args.putSerializable("story", story)
            args.putSerializable("feedset", fs)
            fragment.arguments = args
            return fragment
        }
    }
}

