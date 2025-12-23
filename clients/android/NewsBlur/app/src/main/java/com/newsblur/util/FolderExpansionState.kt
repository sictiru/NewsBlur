package com.newsblur.util

import com.newsblur.domain.Folder
import com.newsblur.preference.PrefsRepo

interface FolderExpansionState {
    fun isExpanded(flatFolderName: String): Boolean

    fun setExpanded(
        flatFolderName: String,
        expanded: Boolean,
    )
}

class PrefsFolderExpansionState(
    private val prefsRepo: PrefsRepo,
) : FolderExpansionState {
    override fun isExpanded(flatFolderName: String): Boolean = prefsRepo.getBoolean("${AppConstants.FOLDER_PRE}_$flatFolderName", true)

    override fun setExpanded(
        flatFolderName: String,
        expanded: Boolean,
    ) {
        prefsRepo.putBoolean("${AppConstants.FOLDER_PRE}_$flatFolderName", expanded)
    }
}

fun closedCanonicalFolders(
    flatFolders: Map<String, Folder>,
    folderExpansionState: FolderExpansionState?,
): Set<String> {
    if (folderExpansionState == null) return emptySet()

    val closed = HashSet<String>()
    for ((flatName, folder) in flatFolders) {
        if (folder.name == AppConstants.ROOT_FOLDER) continue

        if (!folderExpansionState.isExpanded(flatName)) {
            closed.add(folder.name) // canonical
        }
    }
    return closed
}
