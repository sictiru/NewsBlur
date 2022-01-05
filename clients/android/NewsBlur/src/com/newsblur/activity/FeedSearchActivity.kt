package com.newsblur.activity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.newsblur.activity.FeedSearchAdapter.OnFeedSearchResultClickListener
import com.newsblur.databinding.ActivityFeedSearchBinding
import com.newsblur.domain.FeedResult
import com.newsblur.fragment.AddFeedFragment
import com.newsblur.fragment.AddFeedFragment.AddFeedProgressListener
import com.newsblur.network.APIManager
import com.newsblur.util.executeAsyncTask
import dagger.hilt.android.AndroidEntryPoint
import java.net.MalformedURLException
import java.net.URL
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class FeedSearchActivity : NbActivity(), OnFeedSearchResultClickListener, AddFeedProgressListener {

    @Inject
    lateinit var apiManager: APIManager

    private val supportedUrlProtocols: MutableSet<String> = HashSet(2)

    init {
        supportedUrlProtocols.add("http")
        supportedUrlProtocols.add("https")
    }

    private lateinit var adapter: FeedSearchAdapter
    private lateinit var binding: ActivityFeedSearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupViews()
        setupListeners()
        binding.inputSearchQuery.requestFocus()
    }

    override fun onFeedSearchResultClickListener(result: FeedResult) {
        showAddFeedDialog(result.url, result.label)
    }

    override fun addFeedStarted() {
        // loading views handled by AddFeedFragment
    }

    private fun setupViews() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        adapter = FeedSearchAdapter(this)
        binding.feedResultList.adapter = adapter
    }

    private fun setupListeners() {
        binding.toolbarArrow.setOnClickListener { finish() }
        binding.toolbarIcon.setOnClickListener { finish() }
        binding.clearText.setOnClickListener { binding.inputSearchQuery.setText("") }

        binding.inputSearchQuery.addTextChangedListener(object : TextWatcher {

            private val handler = Handler(Looper.getMainLooper())
            private var searchQueryRunnable: Runnable? = null

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                searchQueryRunnable?.let { handler.removeCallbacks(it) }
                searchQueryRunnable = Runnable {
                    if (tryAddByURL(s.toString())) {
                        return@Runnable
                    }

                    syncClearIconVisibility(s)
                    if (s.isNotEmpty()) searchQuery(s)
                    else syncSearchResults(arrayOf())
                }
                handler.postDelayed(searchQueryRunnable!!, 350)
            }
        })
    }

    private fun searchQuery(query: Editable) {
        lifecycleScope.executeAsyncTask(
                onPreExecute = {
                    binding.loadingCircle.visibility = View.VISIBLE
                    binding.clearText.visibility = View.GONE
                },
                doInBackground = {
                    apiManager.searchForFeed(query.toString())
                },
                onPostExecute = {
                    binding.loadingCircle.visibility = View.GONE
                    binding.clearText.visibility = View.VISIBLE
                    syncSearchResults(it ?: arrayOf())
                }
        )
    }

    private fun syncClearIconVisibility(query: Editable) {
        binding.clearText.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun syncSearchResults(results: Array<FeedResult>) {
        adapter.replaceAll(results)
    }

    /**
     * See if the text entered in the query field was actually a URL so we can skip the
     * search step and just let users who know feed URLs directly subscribe.
     */
    private fun tryAddByURL(s: String): Boolean {
        var u: URL? = null
        try {
            u = URL(s)
        } catch (mue: MalformedURLException) {
            // this just signals that the string wasn't a URL, we will return
        }
        if (u == null) {
            return false
        }
        if (!supportedUrlProtocols.contains(u.protocol)) {
            return false
        }
        if (u.host == null || u.host.trim().isEmpty()) {
            return false
        }
        showAddFeedDialog(s, s)
        return true
    }

    private fun showAddFeedDialog(feedUrl: String, feedLabel: String) {
        val addFeedFragment: DialogFragment = AddFeedFragment.newInstance(feedUrl, feedLabel)
        addFeedFragment.show(supportFragmentManager, "dialog")
    }
}