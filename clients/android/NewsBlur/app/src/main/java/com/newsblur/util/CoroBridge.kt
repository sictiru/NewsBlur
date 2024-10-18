package com.newsblur.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CoroBridge {

    @JvmStatic
    fun <T> launch(
            background: () -> T,
    ) = NBScope.launch(Dispatchers.IO) {
        background()
    }

    @JvmStatic
    fun <T> launchAndPostExecute(
            background: () -> T,
            postExecute: (T) -> Unit,
    ) = NBScope.future(Dispatchers.IO) {
        val result = background()
        withContext(Dispatchers.Main) {
            postExecute(result)
        }
    }
}