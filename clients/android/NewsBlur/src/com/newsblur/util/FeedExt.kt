package com.newsblur.util

import com.newsblur.domain.Feed

object FeedExt {

    fun Feed.isNotifyEmail(): Boolean = isNotify(NOTIFY_EMAIL)

    fun Feed.isNotifyWeb(): Boolean = isNotify(NOTIFY_WEB)

    fun Feed.isNotifyIOS(): Boolean = isNotify(NOTIFY_IOS)

    fun Feed.isNotifyAndroid(): Boolean = isNotify(NOTIFY_ANDROID)

    fun Feed.enableNotification(type: String) {
        if (notificationTypes == null) notificationTypes = mutableListOf()
        if (!notificationTypes.contains(type)) notificationTypes.add(type)
    }

    fun Feed.disableNotification(type: String) {
        notificationTypes?.remove(type)
    }

    @JvmStatic
    fun Feed.isAndroidNotifyUnread(): Boolean = isNotifyUnread() && isNotifyAndroid()

    @JvmStatic
    fun Feed.isNotifyUnread(): Boolean = notificationFilter == NOTIFY_FILTER_UNREAD

    @JvmStatic
    fun Feed.isNotifyFocus(): Boolean = notificationFilter == NOTIFY_FILTER_FOCUS

    fun Feed.setNotifyFocus() {
        notificationFilter = NOTIFY_FILTER_UNREAD
    }

    fun Feed.setNotifyUnread() {
        notificationFilter = NOTIFY_FILTER_UNREAD
    }

    private fun Feed.isNotify(type: String): Boolean = notificationTypes?.contains(type) ?: false

    const val NOTIFY_EMAIL = "email"
    const val NOTIFY_WEB = "web"
    const val NOTIFY_IOS = "ios"
    const val NOTIFY_ANDROID = "android"

    const val NOTIFY_FILTER_UNREAD = "unread"
    const val NOTIFY_FILTER_FOCUS = "focus"
}