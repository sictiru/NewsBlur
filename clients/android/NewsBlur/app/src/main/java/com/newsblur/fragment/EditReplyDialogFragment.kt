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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class EditReplyDialogFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val story = requireArguments().getSerializable(STORY) as Story
        val commentUserId = requireArguments().getString(COMMENT_USER_ID)
        val replyId = requireArguments().getString(REPLY_ID)
        val replyText = requireArguments().getString(REPLY_TEXT)

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.edit_reply)

        val replyView = layoutInflater.inflate(R.layout.reply_dialog, null)
        builder.setView(replyView)
        val reply = replyView.findViewById<EditText>(R.id.reply_field)
        reply.setText(replyText)

        builder.setPositiveButton(R.string.edit_reply_update) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                feedUtils.updateReply(requireContext(), story, commentUserId, replyId, reply.text.toString())
                withContext(Dispatchers.Main) {
                    this@EditReplyDialogFragment.dismiss()
                }
            }
        }
        builder.setNegativeButton(R.string.edit_reply_delete) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                feedUtils.deleteReply(requireContext(), story, commentUserId, replyId)
                withContext(Dispatchers.Main) {
                    this@EditReplyDialogFragment.dismiss()
                }
            }
        }
        return builder.create()
    }

    companion object {
        private const val STORY = "story"
        private const val COMMENT_USER_ID = "comment_user_id"
        private const val REPLY_ID = "reply_id"
        private const val REPLY_TEXT = "reply_text"

        fun newInstance(story: Story, commentUserId: String?, replyId: String?, replyText: String?): EditReplyDialogFragment {
            val frag = EditReplyDialogFragment()
            val args = Bundle()
            args.putSerializable(STORY, story)
            args.putString(COMMENT_USER_ID, commentUserId)
            args.putString(REPLY_ID, replyId)
            args.putString(REPLY_TEXT, replyText)
            frag.arguments = args
            return frag
        }
    }
}
