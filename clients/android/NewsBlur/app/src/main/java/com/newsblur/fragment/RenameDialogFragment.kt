package com.newsblur.fragment

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.databinding.DialogRenameBinding
import com.newsblur.domain.Feed
import com.newsblur.util.AppConstants
import com.newsblur.util.FeedUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class RenameDialogFragment : DialogFragment() {

    @Inject
    lateinit var feedUtils: FeedUtils

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreate(savedInstanceState)

        val v = layoutInflater.inflate(R.layout.dialog_rename, null)
        val binding = DialogRenameBinding.bind(v)

        val builder = AlertDialog.Builder(requireContext())
        builder.setView(v)
        builder.setNegativeButton(R.string.alert_dialog_cancel) { dialogInterface, i -> this@RenameDialogFragment.dismiss() }
        if (arguments?.getString(RENAME_TYPE) == FEED) {
            val feed = arguments?.getSerializable(FEED) as Feed?
            builder.setTitle(String.format(resources.getString(R.string.title_rename_feed), feed?.title))
            binding.inputName.setText(feed?.title)
            builder.setPositiveButton(R.string.feed_name_save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    feedUtils.renameFeed(requireContext(), feed?.feedId, binding.inputName.text.toString())
                    withContext(Dispatchers.Main) {
                        this@RenameDialogFragment.dismiss()
                    }
                }
            }
        } else { // FOLDER
            val folderName = arguments?.getString(FOLDER_NAME)
            val folderParentName = arguments?.getString(FOLDER_PARENT)

            builder.setTitle(String.format(resources.getString(R.string.title_rename_folder), folderName))
            binding.inputName.setText(folderName)

            builder.setPositiveButton(R.string.folder_name_save, DialogInterface.OnClickListener { _, _ ->
                val newFolderName = binding.inputName.text.toString()
                if (TextUtils.isEmpty(newFolderName)) {
                    Toast.makeText(requireContext(), R.string.add_folder_name, Toast.LENGTH_SHORT).show()
                    return@OnClickListener
                }

                var inFolder: String? = ""
                if (!TextUtils.isEmpty(folderParentName) && folderParentName != AppConstants.ROOT_FOLDER) {
                    inFolder = folderParentName
                }
                feedUtils.renameFolder(folderName, newFolderName, inFolder, requireContext())
                this@RenameDialogFragment.dismiss()
            })
        }

        return builder.create()
    }

    companion object {
        private const val FEED = "feed"
        private const val FOLDER = "folder"
        private const val FOLDER_NAME = "folder_name"
        private const val FOLDER_PARENT = "folder_parent"
        private const val RENAME_TYPE = "rename_type"

        @JvmStatic
        fun newInstance(feed: Feed?): RenameDialogFragment {
            val fragment = RenameDialogFragment()
            val args = Bundle()
            args.putSerializable(FEED, feed)
            args.putString(RENAME_TYPE, FEED)
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        fun newInstance(folderName: String?, folderParent: String?): RenameDialogFragment {
            val fragment = RenameDialogFragment()
            val args = Bundle()
            args.putString(FOLDER_NAME, folderName)
            args.putString(FOLDER_PARENT, folderParent)
            args.putString(RENAME_TYPE, FOLDER)
            fragment.arguments = args
            return fragment
        }
    }
}
