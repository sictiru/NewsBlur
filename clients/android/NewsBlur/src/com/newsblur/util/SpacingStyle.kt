package com.newsblur.util

import android.content.Context

enum class SpacingStyle {
    COMFORTABLE, // default
    COMPACT,
    ;

    fun getGroupTitleVerticalPadding(context: Context): Int = when (this) {
        COMFORTABLE -> UIUtils.dp2px(context, 9)
        COMPACT -> UIUtils.dp2px(context, 4)
    }

    fun getChildTitleVerticalPadding(context: Context): Int = when (this) {
        COMFORTABLE -> UIUtils.dp2px(context, 7)
        COMPACT -> UIUtils.dp2px(context, 3)
    }

    fun getStoryTitleVerticalPadding(context: Context): Int = when (this) {
        COMFORTABLE -> UIUtils.dp2px(context, 11)
        COMPACT -> UIUtils.dp2px(context, 2)
    }

    fun getStoryContentVerticalPadding(context: Context): Int = when (this) {
        COMFORTABLE -> UIUtils.dp2px(context, 11)
        COMPACT -> UIUtils.dp2px(context, 2)
    }

    fun getStoryContainerMargin(context: Context): Int = when (this) {
        COMFORTABLE -> UIUtils.dp2px(context, 10)
        COMPACT -> UIUtils.dp2px(context, 2)
    }
}