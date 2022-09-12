package com.newsblur.activity

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.newsblur.R
import com.newsblur.databinding.ViewNotificationsItemBinding
import com.newsblur.domain.Feed
import com.newsblur.util.ImageLoader

class NotificationsAdapter(
        private val imageLoader: ImageLoader,
) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    private val feeds: MutableList<Feed> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ViewNotificationsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, imageLoader)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(feeds[position])

    override fun getItemCount(): Int = feeds.size

    fun refreshFeeds(feeds: List<Feed>) {
        this.feeds.clear()
        this.feeds.addAll(feeds)
        this.notifyItemRangeInserted(0, feeds.size)
    }

    class ViewHolder(val binding: ViewNotificationsItemBinding, val imageLoader: ImageLoader) : RecyclerView.ViewHolder(binding.root) {

        fun bind(feed: Feed) {
            binding.toggleUnread.isEnabled = false
            binding.textTitle.text = feed.title
            imageLoader.displayImage(feed.faviconUrl, binding.imgIcon, binding.imgIcon.height, true)
            binding.toggleUnread.setOnClickListener {
                binding.toggleUnread.isEnabled = !binding.toggleUnread.isEnabled
            }
            binding.textEmail.addOnCheckedChangeListener { button, isChecked ->
                Log.d("sictiru", "is checked $isChecked")
            }
        }
    }

    interface Listener {
        fun onNotificationTypeClick(feed: Feed, type: NotificationType)

        fun onNotificationSettingClick(feed: Feed, setting: NotificationSetting)
    }

    sealed class NotificationSetting {
        abstract val isEnabled: Boolean

        class Android(override val isEnabled: Boolean) : NotificationSetting()

        class IOS(override val isEnabled: Boolean) : NotificationSetting()

        class Email(override val isEnabled: Boolean) : NotificationSetting()

        class Web(override val isEnabled: Boolean) : NotificationSetting()
    }

    sealed class NotificationType {
        object Unread : NotificationType()

        object Focus : NotificationType()
    }
}