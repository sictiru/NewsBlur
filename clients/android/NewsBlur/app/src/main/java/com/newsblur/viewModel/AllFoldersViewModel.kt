package com.newsblur.viewModel

import android.os.CancellationSignal
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.newsblur.domain.FeedQueryResult
import com.newsblur.domain.FolderQueryResult
import com.newsblur.domain.SavedSearch
import com.newsblur.domain.SavedStoryCountsQueryResult
import com.newsblur.domain.SocialFeed
import com.newsblur.repository.FolderListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AllFoldersViewModel
    @Inject
    constructor(
        private val repo: FolderListRepository,
    ) : ViewModel() {
        private val cancellationSignal = CancellationSignal()

        private val _uiState = MutableStateFlow(FolderListUiState())
        val uiState: LiveData<FolderListUiState> = _uiState.asLiveData()

        fun getData() {
            _uiState.update { it.copy(isLoading = true, error = null) }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val socialDeferred = async { repo.loadSocialFeeds(cancellationSignal) }
                    val savedCountsDeferred = async { repo.loadSavedStoryCounts(cancellationSignal) }
                    val savedSearchDeferred = async { repo.loadSavedSearches(cancellationSignal) }

                    val folders = repo.loadFolders(cancellationSignal)
                    val feeds = repo.loadFeeds(cancellationSignal)

                    _uiState.update { state ->
                        state.copy(
                            folders = folders,
                            feeds = feeds,
                        )
                    }
                    val social = socialDeferred.await()
                    val savedCounts = savedCountsDeferred.await()
                    val savedSearches = savedSearchDeferred.await()

                    _uiState.update { state ->
                        state.copy(
                            socialFeeds = social,
                            savedStoryCounts = savedCounts,
                            savedSearches = savedSearches,
                            isLoading = false,
                            error = null,
                        )
                    }
                } catch (t: Throwable) {
                    _uiState.update { it.copy(isLoading = false, error = t) }
                }
            }
        }

        override fun onCleared() {
            cancellationSignal.cancel()
            super.onCleared()
        }
    }

data class FolderListUiState(
    val socialFeeds: List<SocialFeed>? = null,
    val folders: FolderQueryResult? = null,
    val feeds: FeedQueryResult? = null,
    val savedStoryCounts: SavedStoryCountsQueryResult? = null,
    val savedSearches: List<SavedSearch>? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)
