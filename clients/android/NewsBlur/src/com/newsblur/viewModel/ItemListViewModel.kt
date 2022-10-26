package com.newsblur.viewModel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.newsblur.util.Session

class ItemListViewModel : ViewModel() {

    val nextSession = MutableLiveData<Session?>()

    fun updateSession(session: Session) {
        nextSession.value = session
    }
}