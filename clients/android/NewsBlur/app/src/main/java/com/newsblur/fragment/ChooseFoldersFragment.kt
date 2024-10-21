package com.newsblur.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ListAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.databinding.DialogChoosefoldersBinding
import com.newsblur.domain.Feed
import com.newsblur.domain.Folder
import com.newsblur.util.FeedUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

@AndroidEntryPoint
class ChooseFoldersFragment : DialogFragment() {

    @Inject
    lateinit var dbHelper: BlurDatabaseHelper

    @Inject
    lateinit var feedUtils: FeedUtils

    private lateinit var feed: Feed
    private lateinit var binding: DialogChoosefoldersBinding

    private val newFolders: MutableSet<String?> = HashSet()
    private val oldFolders: MutableSet<String?> = HashSet()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreate(savedInstanceState)
        feed = requireArguments().getSerializable("feed") as Feed

        val v = layoutInflater.inflate(R.layout.dialog_choosefolders, null)
        binding = DialogChoosefoldersBinding.bind(v)

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(String.format(resources.getString(R.string.title_choose_folders), feed.title))
        builder.setView(v)

        builder.setNegativeButton(R.string.alert_dialog_cancel) { _, _ ->
            this@ChooseFoldersFragment.dismiss()
        }
        builder.setPositiveButton(R.string.dialog_folders_save) { _, _ ->
            feedUtils.moveFeedToFolders(requireContext(), feed.feedId, newFolders, oldFolders)
            this@ChooseFoldersFragment.dismiss()
        }

        val dialog: Dialog = builder.create()
        dialog.window?.attributes?.gravity = Gravity.BOTTOM
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = dbHelper.getFolders()
            Collections.sort(folders, Folder.FolderComparator)

            for (folder in folders) {
                if (folder.feedIds.contains(feed.feedId)) {
                    newFolders.add(folder.name)
                    oldFolders.add(folder.name)
                }
            }

            val adapter: ListAdapter = object : ArrayAdapter<Folder?>(requireContext(), R.layout.row_choosefolders, R.id.choosefolders_foldername, folders) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val row = v.findViewById<CheckBox>(R.id.choosefolders_foldername)
                    if (position == 0) {
                        row.setText(R.string.top_level)
                    }
                    row.isChecked = folders[position].feedIds.contains(feed.feedId)
                    row.setOnClickListener { v ->
                        val rowChecBbox = v as CheckBox
                        if (rowChecBbox.isChecked) {
                            folders[position].feedIds.add(feed.feedId)
                            newFolders.add(folders[position].name)
                        } else {
                            folders[position].feedIds.remove(feed.feedId)
                            newFolders.remove(folders[position].name)
                        }
                    }
                    return v
                }
            }
            binding.chooseFoldersList.adapter = adapter
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(feed: Feed): ChooseFoldersFragment {
            val fragment = ChooseFoldersFragment()
            val args = Bundle()
            args.putSerializable("feed", feed)
            fragment.arguments = args
            return fragment
        }
    }
}

