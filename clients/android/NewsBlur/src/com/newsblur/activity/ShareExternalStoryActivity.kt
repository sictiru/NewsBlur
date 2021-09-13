package com.newsblur.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.newsblur.R
import com.newsblur.databinding.ActivityShareExternalStoryBinding
import com.newsblur.network.APIManager
import com.newsblur.util.*

class ShareExternalStoryActivity : AppCompatActivity() {

    private val apiManager: APIManager by lazy { APIManager(this) }
    private val storyTitle: String? by lazy { intent.getStringExtra(Intent.EXTRA_SUBJECT) }
    private val storyUrl: String? by lazy { intent.getStringExtra(Intent.EXTRA_TEXT) }

    private lateinit var binding: ActivityShareExternalStoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        PrefsUtils.applyTranslucentThemePreference(this)
        super.onCreate(savedInstanceState)
        binding = ActivityShareExternalStoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FeedUtils.offerInitContext(this)

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            handleIntent()
        } else {
            finishWithToast("NewsBlur share action unrecognized")
        }
    }

    private fun handleIntent() {
        if (!storyTitle.isNullOrEmpty() && !storyUrl.isNullOrEmpty()) {
            binding.textTitle.text = getString(R.string.share_newsblur, storyTitle)

            binding.textCancel.setOnClickListener { finish() }
            binding.textShare.setOnClickListener { shareStory(binding.inputComment.text.toString()) }
        } else {
            finishWithToast("NewsBlur share metadata unrecognized")
        }
    }

    private fun shareStory(comment: String) {
        lifecycleScope.executeAsyncTask(
                onPreExecute = {
                    binding.progressIndicator.setViewVisible()
                    binding.containerButtons.setViewGone()
                },
                doInBackground = {
                    apiManager.shareExternalStory(storyTitle!!, storyUrl!!, comment)
                },
                onPostExecute = { response ->
                    if (!response.isError) {
                        finishWithToast("NewsBlur shared $storyTitle successfully!")
                    } else {
                        val errorMessage = response.getErrorMessage("Sharing story was unsuccessful")
                        finishWithToast(errorMessage)
                    }
                }
        )
    }

    private fun finishWithToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}