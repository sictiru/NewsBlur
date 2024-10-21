package com.newsblur.fragment

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.domain.Story
import com.newsblur.util.FeedUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReplyDialogFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    private var commentUserId: String? = null
    private lateinit var story: Story

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        story = arguments?.getSerializable(STORY) as Story
        commentUserId = arguments?.getString(COMMENT_USER_ID)

        val builder = AlertDialog.Builder(requireContext())
        val shareString = resources.getString(R.string.reply_to)
        builder.setTitle(String.format(shareString, arguments?.getString(COMMENT_USERNAME)))

        val replyView = layoutInflater.inflate(R.layout.reply_dialog, null)
        builder.setView(replyView)
        val reply = replyView.findViewById<EditText>(R.id.reply_field)

        builder.setPositiveButton(R.string.alert_dialog_ok) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                feedUtils.replyToComment(story.id, story.feedId, commentUserId, reply.text.toString(), requireContext())
            }
        }
        builder.setNegativeButton(R.string.alert_dialog_cancel) { _, _ ->
            this@ReplyDialogFragment.dismiss()
        }
        return builder.create()
    }

    companion object {
        private const val STORY = "story"
        private const val COMMENT_USER_ID = "comment_user_id"
        private const val COMMENT_USERNAME = "comment_username"

        fun newInstance(story: Story, commentUserId: String?, commentUsername: String?): ReplyDialogFragment {
            val frag = ReplyDialogFragment()
            val args = Bundle()
            args.putSerializable(STORY, story)
            args.putString(COMMENT_USER_ID, commentUserId)
            args.putString(COMMENT_USERNAME, commentUsername)
            frag.arguments = args
            return frag
        }
    }
}
