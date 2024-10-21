package com.newsblur.fragment

import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.domain.Comment
import com.newsblur.domain.Story
import com.newsblur.domain.UserDetails
import com.newsblur.util.FeedUtils
import com.newsblur.util.PrefsUtils
import com.newsblur.util.UIUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ShareDialogFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    private lateinit var story: Story
    private lateinit var commentEditText: EditText
    private lateinit var user: UserDetails

    private var previousComment: Comment? = null
    private var sourceUserId: String? = null

    private var hasBeenShared = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        story = arguments?.getSerializable(STORY) as Story
        user = PrefsUtils.getUserDetails(requireContext())
        sourceUserId = arguments?.getString(SOURCE_USER_ID)

        for (sharedUserId in story.sharedUserIds) {
            if (TextUtils.equals(user.id, sharedUserId)) {
                hasBeenShared = true
                break
            }
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(String.format(resources.getString(R.string.share_save_newsblur), UIUtils.fromHtml(story.title)))

        val replyView = layoutInflater.inflate(R.layout.share_dialog, null)
        builder.setView(replyView)
        commentEditText = replyView.findViewById(R.id.comment_field)

        viewLifecycleOwner.lifecycleScope.launch {
            if (hasBeenShared) {
                previousComment = dbHelper.getComment(story.id, user.id)
            }
            withContext(Dispatchers.IO) {
                previousComment?.let {
                    commentEditText.setText(it.commentText)
                }
            }
        }

        var positiveButtonText = R.string.share_this_story
        var negativeButtonText = R.string.alert_dialog_cancel
        if (hasBeenShared) {
            positiveButtonText = R.string.update_shared
            negativeButtonText = R.string.unshare
        }

        builder.setPositiveButton(positiveButtonText) { _, _ ->
            val shareComment = commentEditText.getText().toString()
            viewLifecycleOwner.lifecycleScope.launch {
                feedUtils.shareStory(story, shareComment, sourceUserId, requireContext())
                withContext(Dispatchers.Main) {
                    this@ShareDialogFragment.dismiss()
                }
            }
        }
        if (hasBeenShared) {
            // unshare
            builder.setNegativeButton(negativeButtonText) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    feedUtils.unshareStory(story, requireContext())
                    withContext(Dispatchers.Main) {
                        this@ShareDialogFragment.dismiss()
                    }
                }
            }
        } else {
            // cancel
            builder.setNegativeButton(negativeButtonText) { _, _ ->
                this@ShareDialogFragment.dismiss()
            }
        }
        return builder.create()
    }

    companion object {

        private const val STORY = "story"
        private const val SOURCE_USER_ID = "sourceUserId"

        fun newInstance(story: Story?, sourceUserId: String?): ShareDialogFragment {
            val frag = ShareDialogFragment()
            val args = Bundle()
            args.putSerializable(STORY, story)
            args.putString(SOURCE_USER_ID, sourceUserId)
            frag.arguments = args
            return frag
        }
    }
}
