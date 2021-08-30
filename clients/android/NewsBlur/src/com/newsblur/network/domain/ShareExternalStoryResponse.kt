package com.newsblur.network.domain

class ShareExternalStoryResponse : NewsBlurResponse() {

    override fun isError() = code != 0
}