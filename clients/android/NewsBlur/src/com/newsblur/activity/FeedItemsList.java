package com.newsblur.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import android.view.Menu;
import android.view.MenuItem;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.android.play.core.tasks.Task;
import com.newsblur.R;
import com.newsblur.domain.Feed;
import com.newsblur.fragment.DeleteFeedFragment;
import com.newsblur.fragment.FeedIntelTrainerFragment;
import com.newsblur.fragment.RenameDialogFragment;
import com.newsblur.util.FeedSet;
import com.newsblur.util.FeedUtils;
import com.newsblur.util.PrefsUtils;
import com.newsblur.util.UIUtils;

public class FeedItemsList extends ItemsList {

    public static final String EXTRA_FEED = "feed";
    public static final String EXTRA_FOLDER_NAME = "folderName";
	private Feed feed;
	private String folderName;
	private ReviewManager reviewManager;
	private ReviewInfo reviewInfo;

    public static void startActivity(Context context, FeedSet feedSet,
                                     Feed feed, String folderName) {
        Intent intent = new Intent(context, FeedItemsList.class);
        intent.putExtra(ItemsList.EXTRA_FEED_SET, feedSet);
        intent.putExtra(FeedItemsList.EXTRA_FEED, feed);
        intent.putExtra(FeedItemsList.EXTRA_FOLDER_NAME, folderName);
        context.startActivity(intent);
    }

	@Override
	protected void onCreate(Bundle bundle) {
		feed = (Feed) getIntent().getSerializableExtra(EXTRA_FEED);
        folderName = getIntent().getStringExtra(EXTRA_FOLDER_NAME);

		super.onCreate(bundle);

        UIUtils.setupToolbar(this, feed.faviconUrl, feed.title, iconLoader, false);
        checkInAppReview();
    }

    @Override
    public void onBackPressed() {
        // see checkInAppReview()
        if (reviewInfo != null) {
            Task<Void> flow = reviewManager.launchReviewFlow(this, reviewInfo);
            flow.addOnCompleteListener(task -> {
                PrefsUtils.setInAppReviewed(this);
                super.onBackPressed();
            });
        } else {
            super.onBackPressed();
        }
    }

    public void deleteFeed() {
		DialogFragment deleteFeedFragment = DeleteFeedFragment.newInstance(feed, folderName);
		deleteFeedFragment.show(getSupportFragmentManager(), "dialog");
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (super.onOptionsItemSelected(item)) {
            return true;
        }
        if (item.getItemId() == R.id.menu_delete_feed) {
            deleteFeed();
            return true;
        }
        if (item.getItemId() == R.id.menu_notifications_disable) {
            feedUtils.disableNotifications(this, feed);
            return true;
        }
        if (item.getItemId() == R.id.menu_notifications_focus) {
            feedUtils.enableFocusNotifications(this, feed);
            return true;
        }
        if (item.getItemId() == R.id.menu_notifications_unread) {
            feedUtils.enableUnreadNotifications(this, feed);
            return true;
        }
        if (item.getItemId() == R.id.menu_instafetch_feed) {
            feedUtils.instaFetchFeed(this, feed.feedId);
            this.finish();
            return true;
        }
        if (item.getItemId() == R.id.menu_intel) {
            FeedIntelTrainerFragment intelFrag = FeedIntelTrainerFragment.newInstance(feed, fs);
            intelFrag.show(getSupportFragmentManager(), FeedIntelTrainerFragment.class.getName());
            return true;
        }
        if (item.getItemId() == R.id.menu_rename_feed) {
            RenameDialogFragment frag = RenameDialogFragment.newInstance(feed);
            frag.show(getSupportFragmentManager(), RenameDialogFragment.class.getName());
            return true;
            // TODO: since this activity uses a feed object passed as an extra and doesn't query the DB,
            // the name change won't be reflected until the activity finishes.
        }
        if (item.getItemId() == R.id.menu_statistics) {
            feedUtils.openStatistics(this, feed.feedId);
            return true;
        }
        return false;
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
        if (!feed.active) {
            // there is currently no way for a feed to be un-muted while in this activity, so
            // don't bother creating the menu, which contains no valid options for a muted feed
            return false;
        }
		super.onCreateOptionsMenu(menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
        if (feed.isNotifyUnread()) {
            menu.findItem(R.id.menu_notifications_disable).setChecked(false);
            menu.findItem(R.id.menu_notifications_unread).setChecked(true);
            menu.findItem(R.id.menu_notifications_focus).setChecked(false);
        } else if (feed.isNotifyFocus()) {
            menu.findItem(R.id.menu_notifications_disable).setChecked(false);
            menu.findItem(R.id.menu_notifications_unread).setChecked(false);
            menu.findItem(R.id.menu_notifications_focus).setChecked(true);
        } else {
            menu.findItem(R.id.menu_notifications_disable).setChecked(true);
            menu.findItem(R.id.menu_notifications_unread).setChecked(false);
            menu.findItem(R.id.menu_notifications_focus).setChecked(false);
        }
		return true;
	}

    @Override
    String getSaveSearchFeedId() {
        return "feed:" + feed.feedId;
    }

    private void checkInAppReview() {
        if (!PrefsUtils.hasInAppReviewed(this)) {
            reviewManager = ReviewManagerFactory.create(this);
            Task<ReviewInfo> request = reviewManager.requestReviewFlow();
            request.addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    reviewInfo = task.getResult();
                }
            });
        }
    }
}
