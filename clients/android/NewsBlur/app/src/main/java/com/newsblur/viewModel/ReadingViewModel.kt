package com.newsblur.viewModel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReadingViewModel
@Inject constructor() : ViewModel() {

    private val maxEntries = 250

    private val scrollByHash = object : LinkedHashMap<String, Float>(
            maxEntries + 1,
            0.75f,
            true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>): Boolean = size > maxEntries
    }

    fun getScroll(hash: String): Float = synchronized(scrollByHash) {
        scrollByHash[hash] ?: 0f
    }

    fun setScroll(hash: String, rel: Float) = synchronized(scrollByHash) {
        scrollByHash[hash] = rel.coerceIn(0f, 1f)
    }
}