package com.newsblur.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.newsblur.databinding.ActivityShareExternalBinding
import com.newsblur.util.FeedUtils

class ShareExternal : AppCompatActivity() {

    private val tag = "ShareExternal"
    private lateinit var binding: ActivityShareExternalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareExternalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FeedUtils.offerInitContext(this)

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            handleIntent(intent)
        } else {
            Toast.makeText(this, "NewsBlur share action unrecognized", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleIntent(intent: Intent) {
        val storyTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let {
            binding.textTitle.text = it
            Log.d(tag, "Extra subject $it")
        }
        val storyUrl = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            Log.d(tag, "Extra text $it")
            binding.textUrl.text = it
        }
    }
}