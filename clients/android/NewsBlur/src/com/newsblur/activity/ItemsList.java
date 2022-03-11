package com.newsblur.activity;

import static com.newsblur.service.NBSyncReceiver.UPDATE_REBUILD;
import static com.newsblur.service.NBSyncReceiver.UPDATE_STATUS;
import static com.newsblur.service.NBSyncReceiver.UPDATE_STORY;

import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import com.newsblur.R;
import com.newsblur.database.BlurDatabaseHelper;
import com.newsblur.databinding.ActivityItemslistBinding;
import com.newsblur.di.IconLoader;
import com.newsblur.fragment.ItemSetFragment;
import com.newsblur.fragment.ReadFilterDialogFragment;
import com.newsblur.fragment.SaveSearchFragment;
import com.newsblur.fragment.StoryOrderDialogFragment;
import com.newsblur.fragment.TextSizeDialogFragment;
import com.newsblur.service.NBSyncService;
import com.newsblur.util.AppConstants;
import com.newsblur.util.FeedSet;
import com.newsblur.util.FeedUtils;
import com.newsblur.util.ImageLoader;
import com.newsblur.util.PrefConstants.ThemeValue;
import com.newsblur.util.PrefsUtils;
import com.newsblur.util.ReadFilter;
import com.newsblur.util.ReadFilterChangedListener;
import com.newsblur.util.StateFilter;
import com.newsblur.util.StoryContentPreviewStyle;
import com.newsblur.util.StoryListStyle;
import com.newsblur.util.StoryOrder;
import com.newsblur.util.StoryOrderChangedListener;
import com.newsblur.util.ThumbnailStyle;
import com.newsblur.util.UIUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public abstract class ItemsList extends NbActivity implements StoryOrderChangedListener, ReadFilterChangedListener, OnSeekBarChangeListener {

    @Inject
    BlurDatabaseHelper dbHelper;

    @Inject
    FeedUtils feedUtils;

    @Inject
    @IconLoader
    ImageLoader iconLoader;

    public static final String EXTRA_FEED_SET = "feed_set";
    public static final String EXTRA_STORY_HASH = "story_hash";
    public static final String EXTRA_WIDGET_STORY = "widget_story";
	private static final String STORY_ORDER = "storyOrder";
	private static final String READ_FILTER = "readFilter";
    private static final String DEFAULT_FEED_VIEW = "defaultFeedView";
    private static final String BUNDLE_ACTIVE_SEARCH_QUERY = "activeSearchQuery";
    private ActivityItemslistBinding binding;

	protected ItemSetFragment itemSetFragment;
	protected StateFilter intelState;

    protected FeedSet fs;
	
	@Override
    protected void onCreate(Bundle bundle) {
		super.onCreate(bundle);

        overridePendingTransition(R.anim.slide_in_from_right, R.anim.slide_out_to_left);

		fs = (FeedSet) getIntent().getSerializableExtra(EXTRA_FEED_SET);
		intelState = PrefsUtils.getStateFilter(this);

        // this is not strictly necessary, since our first refresh with the fs will swap in
        // the correct session, but that can be delayed by sync backup, so we try here to
        // reduce UI lag, or in case somehow we got redisplayed in a zero-story state
        feedUtils.prepareReadingSession(fs, false);

        if (getIntent().getBooleanExtra(EXTRA_WIDGET_STORY, false)) {
            String hash = (String) getIntent().getSerializableExtra(EXTRA_STORY_HASH);
            UIUtils.startReadingActivity(fs, hash, this);
        } else if (PrefsUtils.isAutoOpenFirstUnread(this)) {
            if (dbHelper.getUnreadCount(fs, intelState) > 0) {
                UIUtils.startReadingActivity(fs, Reading.FIND_FIRST_UNREAD, this);
            }
        }

        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        binding = ActivityItemslistBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		FragmentManager fragmentManager = getSupportFragmentManager();
		itemSetFragment = (ItemSetFragment) fragmentManager.findFragmentByTag(ItemSetFragment.class.getName());
		if (itemSetFragment == null) {
            itemSetFragment = ItemSetFragment.newInstance();
			itemSetFragment.setRetainInstance(true);
			FragmentTransaction transaction = fragmentManager.beginTransaction();
			transaction.add(R.id.activity_itemlist_container, itemSetFragment, ItemSetFragment.class.getName());
			transaction.commit();
		}

        String activeSearchQuery;
        if (bundle != null) {
            activeSearchQuery = bundle.getString(BUNDLE_ACTIVE_SEARCH_QUERY);
        } else {
            activeSearchQuery = fs.getSearchQuery();
        }
        if (activeSearchQuery != null) {
            binding.itemlistSearchQuery.setText(activeSearchQuery);
            binding.itemlistSearchQuery.setVisibility(View.VISIBLE);
            checkSearchQuery();
        }

        binding.itemlistSearchQuery.setOnKeyListener(new OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((keyCode == KeyEvent.KEYCODE_BACK) && (event.getAction() == KeyEvent.ACTION_DOWN)) {
                    binding.itemlistSearchQuery.setVisibility(View.GONE);
                    binding.itemlistSearchQuery.setText("");
                    checkSearchQuery();
                    return true;
                }
                if ((keyCode == KeyEvent.KEYCODE_ENTER) && (event.getAction() == KeyEvent.ACTION_DOWN)) {
                    checkSearchQuery();
                    return true;
                }   
                return false;
            }
        });
	}

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding.itemlistSearchQuery != null) {
            String q = binding.itemlistSearchQuery.getText().toString().trim();
            if (q.length() > 0) {
                outState.putString(BUNDLE_ACTIVE_SEARCH_QUERY, q);
            }
        }
    }

    public FeedSet getFeedSet() {
        return this.fs;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (NBSyncService.isHousekeepingRunning()) finish();
        updateStatusIndicators();
        // Reading activities almost certainly changed the read/unread state of some stories. Ensure
        // we reflect those changes promptly.
        itemSetFragment.hasUpdated();
    }

    @Override
    protected void onPause() {
        super.onPause();
        NBSyncService.addRecountCandidates(fs);
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.itemslist, menu);

        if (fs.isGlobalShared() || 
            fs.isAllSocial() ||
            fs.isFilterSaved() ||
            fs.isAllSaved() ||
            fs.isSingleSavedTag() ||
            fs.isInfrequent() ||
            fs.isAllRead() ) {
            menu.findItem(R.id.menu_mark_all_as_read).setVisible(false);
        }

        if (fs.isGlobalShared() ||
            fs.isAllSocial() ||
            fs.isAllRead() ) {
            menu.findItem(R.id.menu_story_order).setVisible(false);
        }

        if (fs.isGlobalShared() ||
            fs.isFilterSaved() ||
            fs.isAllSaved() ||
            fs.isSingleSavedTag() ||
            fs.isInfrequent() || 
            fs.isAllRead() ) {
            menu.findItem(R.id.menu_read_filter).setVisible(false);
            menu.findItem(R.id.menu_mark_read_on_scroll).setVisible(false);
            menu.findItem(R.id.menu_story_content_preview_style).setVisible(false);
            menu.findItem(R.id.menu_story_thumbnail_style).setVisible(false);
        }

        if (fs.isGlobalShared() ||
            fs.isAllSocial() ||
            fs.isInfrequent() ||
            fs.isAllRead() ) {
            menu.findItem(R.id.menu_search_stories).setVisible(false);
        }

        if ((!fs.isSingleNormal()) || fs.isFilterSaved()) {
            menu.findItem(R.id.menu_notifications).setVisible(false);
            menu.findItem(R.id.menu_delete_feed).setVisible(false);
            menu.findItem(R.id.menu_instafetch_feed).setVisible(false);
            menu.findItem(R.id.menu_intel).setVisible(false);
            menu.findItem(R.id.menu_rename_feed).setVisible(false);
            menu.findItem(R.id.menu_statistics).setVisible(false);
        }

        if (!fs.isInfrequent()) {
            menu.findItem(R.id.menu_infrequent_cutoff).setVisible(false);
        }

		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);

        StoryListStyle listStyle = PrefsUtils.getStoryListStyle(this, fs);
        if (listStyle == StoryListStyle.GRID_F) {
             menu.findItem(R.id.menu_list_style_grid_f).setChecked(true);
        } else if (listStyle == StoryListStyle.GRID_M) {
             menu.findItem(R.id.menu_list_style_grid_m).setChecked(true);
        } else if (listStyle == StoryListStyle.GRID_C) {
             menu.findItem(R.id.menu_list_style_grid_c).setChecked(true);
        } else {
            menu.findItem(R.id.menu_list_style_list).setChecked(true);
        }

        ThemeValue themeValue = PrefsUtils.getSelectedTheme(this);
        if (themeValue == ThemeValue.LIGHT) {
            menu.findItem(R.id.menu_theme_light).setChecked(true);
        } else if (themeValue == ThemeValue.DARK) {
            menu.findItem(R.id.menu_theme_dark).setChecked(true);
        } else if (themeValue == ThemeValue.BLACK) {
            menu.findItem(R.id.menu_theme_black).setChecked(true);
        } else if (themeValue == ThemeValue.AUTO) {
            menu.findItem(R.id.menu_theme_auto).setChecked(true);
        }

        if (!TextUtils.isEmpty(binding.itemlistSearchQuery.getText())) {
            menu.findItem(R.id.menu_save_search).setVisible(true);
        } else {
            menu.findItem(R.id.menu_save_search).setVisible(false);
        }

        StoryContentPreviewStyle previewStyle = PrefsUtils.getStoryContentPreviewStyle(this);
        if (previewStyle == StoryContentPreviewStyle.NONE) {
            menu.findItem(R.id.menu_story_content_preview_none).setChecked(true);
        } else if (previewStyle == StoryContentPreviewStyle.SMALL) {
            menu.findItem(R.id.menu_story_content_preview_small).setChecked(true);
        } else if (previewStyle == StoryContentPreviewStyle.MEDIUM) {
            menu.findItem(R.id.menu_story_content_preview_medium).setChecked(true);
        } else if (previewStyle == StoryContentPreviewStyle.LARGE) {
            menu.findItem(R.id.menu_story_content_preview_large).setChecked(true);
        }

        ThumbnailStyle thumbnailStyle = PrefsUtils.getThumbnailStyle(this);
        if (thumbnailStyle == ThumbnailStyle.LEFT_SMALL) {
            menu.findItem(R.id.menu_story_thumbnail_left_small).setChecked(true);
        } else if (thumbnailStyle == ThumbnailStyle.LEFT_LARGE) {
            menu.findItem(R.id.menu_story_thumbnail_left_large).setChecked(true);
        } else if (thumbnailStyle == ThumbnailStyle.RIGHT_SMALL) {
            menu.findItem(R.id.menu_story_thumbnail_right_small).setChecked(true);
        } else if (thumbnailStyle == ThumbnailStyle.RIGHT_LARGE) {
            menu.findItem(R.id.menu_story_thumbnail_right_large).setChecked(true);
        } else if (thumbnailStyle == ThumbnailStyle.OFF) {
            menu.findItem(R.id.menu_story_thumbnail_no_preview).setChecked(true);
        }

        boolean isMarkReadOnScroll = PrefsUtils.isMarkReadOnFeedScroll(this);
        if (isMarkReadOnScroll) {
            menu.findItem(R.id.menu_mark_read_on_scroll_enabled).setChecked(true);
        } else {
            menu.findItem(R.id.menu_mark_read_on_scroll_disabled).setChecked(true);
        }

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		} else if (item.getItemId() == R.id.menu_mark_all_as_read) {
            feedUtils.markRead(this, fs, null, null, R.array.mark_all_read_options, true);
			return true;
		} else if (item.getItemId() == R.id.menu_story_order) {
            StoryOrder currentValue = getStoryOrder();
            StoryOrderDialogFragment storyOrder = StoryOrderDialogFragment.newInstance(currentValue);
            storyOrder.show(getSupportFragmentManager(), STORY_ORDER);
            return true;
        } else if (item.getItemId() == R.id.menu_read_filter) {
            ReadFilter currentValue = getReadFilter();
            ReadFilterDialogFragment readFilter = ReadFilterDialogFragment.newInstance(currentValue);
            readFilter.show(getSupportFragmentManager(), READ_FILTER);
            return true;
		} else if (item.getItemId() == R.id.menu_textsize) {
			TextSizeDialogFragment textSize = TextSizeDialogFragment.newInstance(PrefsUtils.getListTextSize(this), TextSizeDialogFragment.TextSizeType.ListText);
			textSize.show(getSupportFragmentManager(), TextSizeDialogFragment.class.getName());
			return true;
        } else if (item.getItemId() == R.id.menu_search_stories) {
            if (binding.itemlistSearchQuery.getVisibility() != View.VISIBLE) {
                binding.itemlistSearchQuery.setVisibility(View.VISIBLE);
                binding.itemlistSearchQuery.requestFocus();
            } else {
                binding.itemlistSearchQuery.setVisibility(View.GONE);
                checkSearchQuery();
            }
        } else if(item.getItemId() == R.id.menu_theme_auto) {
		    PrefsUtils.setSelectedTheme(this, ThemeValue.AUTO);
		    UIUtils.restartActivity(this);
        } else if (item.getItemId() == R.id.menu_theme_light) {
            PrefsUtils.setSelectedTheme(this, ThemeValue.LIGHT);
            UIUtils.restartActivity(this);
        } else if (item.getItemId() == R.id.menu_theme_dark) {
            PrefsUtils.setSelectedTheme(this, ThemeValue.DARK);
            UIUtils.restartActivity(this);
        } else if (item.getItemId() == R.id.menu_theme_black) {
            PrefsUtils.setSelectedTheme(this, ThemeValue.BLACK);
            UIUtils.restartActivity(this);
        } else if (item.getItemId() == R.id.menu_list_style_list) {
            PrefsUtils.updateStoryListStyle(this, fs, StoryListStyle.LIST);
            itemSetFragment.updateStyle();
        } else if (item.getItemId() == R.id.menu_list_style_grid_f) {
            PrefsUtils.updateStoryListStyle(this, fs, StoryListStyle.GRID_F);
            itemSetFragment.updateStyle();
        } else if (item.getItemId() == R.id.menu_list_style_grid_m) {
            PrefsUtils.updateStoryListStyle(this, fs, StoryListStyle.GRID_M);
            itemSetFragment.updateStyle();
        } else if (item.getItemId() == R.id.menu_list_style_grid_c) {
            PrefsUtils.updateStoryListStyle(this, fs, StoryListStyle.GRID_C);
            itemSetFragment.updateStyle();
        } else if (item.getItemId() == R.id.menu_save_search) {
            String feedId = getSaveSearchFeedId();
            if (feedId != null) {
                String query = binding.itemlistSearchQuery.getText().toString();
                SaveSearchFragment frag = SaveSearchFragment.newInstance(feedId, query);
                frag.show(getSupportFragmentManager(), SaveSearchFragment.class.getName());
            }
        } else if (item.getItemId() == R.id.menu_story_content_preview_none) {
		    PrefsUtils.setStoryContentPreviewStyle(this, StoryContentPreviewStyle.NONE);
		    itemSetFragment.notifyContentPrefsChanged();
        } else if (item.getItemId() == R.id.menu_story_content_preview_small) {
            PrefsUtils.setStoryContentPreviewStyle(this, StoryContentPreviewStyle.SMALL);
            itemSetFragment.notifyContentPrefsChanged();
        } else if (item.getItemId() == R.id.menu_story_content_preview_medium) {
            PrefsUtils.setStoryContentPreviewStyle(this, StoryContentPreviewStyle.MEDIUM);
            itemSetFragment.notifyContentPrefsChanged();
        } else if (item.getItemId() == R.id.menu_story_content_preview_large) {
            PrefsUtils.setStoryContentPreviewStyle(this, StoryContentPreviewStyle.LARGE);
            itemSetFragment.notifyContentPrefsChanged();
        } else if (item.getItemId() == R.id.menu_mark_read_on_scroll_disabled) {
		    PrefsUtils.setMarkReadOnScroll(this, false);
        } else if (item.getItemId() == R.id.menu_mark_read_on_scroll_enabled) {
		    PrefsUtils.setMarkReadOnScroll(this, true);
        } else if (item.getItemId() == R.id.menu_story_thumbnail_left_small) {
		    PrefsUtils.setThumbnailStyle(this, ThumbnailStyle.LEFT_SMALL);
            itemSetFragment.updateThumbnailStyle();
        } else if (item.getItemId() == R.id.menu_story_thumbnail_left_large) {
            PrefsUtils.setThumbnailStyle(this, ThumbnailStyle.LEFT_LARGE);
            itemSetFragment.updateThumbnailStyle();
        } else if (item.getItemId() == R.id.menu_story_thumbnail_right_small) {
            PrefsUtils.setThumbnailStyle(this, ThumbnailStyle.RIGHT_SMALL);
            itemSetFragment.updateThumbnailStyle();
        } else if (item.getItemId() == R.id.menu_story_thumbnail_right_large) {
            PrefsUtils.setThumbnailStyle(this, ThumbnailStyle.RIGHT_LARGE);
            itemSetFragment.updateThumbnailStyle();
        } else if (item.getItemId() == R.id.menu_story_thumbnail_no_preview) {
            PrefsUtils.setThumbnailStyle(this, ThumbnailStyle.OFF);
            itemSetFragment.updateThumbnailStyle();
        }
	
		return false;
	}
	
	public StoryOrder getStoryOrder() {
        return PrefsUtils.getStoryOrder(this, fs);
    }
    
	private void updateStoryOrderPreference(StoryOrder newOrder) {
        PrefsUtils.updateStoryOrder(this, fs, newOrder);
    }
	
	private ReadFilter getReadFilter() {
        return PrefsUtils.getReadFilter(this, fs);
    }

    private void updateReadFilterPreference(ReadFilter newValue) {
        PrefsUtils.updateReadFilter(this, fs, newValue);
    }

    @Override
	public void handleUpdate(int updateType) {
        if ((updateType & UPDATE_REBUILD) != 0) {
            finish();
        }
        if ((updateType & UPDATE_STATUS) != 0) {
            updateStatusIndicators();
        }
		if ((updateType & UPDATE_STORY) != 0) {
            if (itemSetFragment != null) {
			    itemSetFragment.hasUpdated();
            }
        }
    }

    private void updateStatusIndicators() {
        if (binding.itemlistSyncStatus != null) {
            String syncStatus = NBSyncService.getSyncStatusMessage(this, true);
            if (syncStatus != null)  {
                if (AppConstants.VERBOSE_LOG) {
                    syncStatus = syncStatus + UIUtils.getMemoryUsageDebug(this);
                }
                binding.itemlistSyncStatus.setText(syncStatus);
                binding.itemlistSyncStatus.setVisibility(View.VISIBLE);
            } else {
                binding.itemlistSyncStatus.setVisibility(View.GONE);
            }
        }
    }

    private void checkSearchQuery() {
        String q = binding.itemlistSearchQuery.getText().toString().trim();
        if (q.length() < 1) {
            updateFleuron(false);
            q = null;
        } else if (!PrefsUtils.getIsPremium(this)) {
            updateFleuron(true);
            return;
        }

        String oldQuery = fs.getSearchQuery();
        fs.setSearchQuery(q);
        if (!TextUtils.equals(q, oldQuery)) {
            feedUtils.prepareReadingSession(fs, true);
            triggerSync();
            itemSetFragment.resetEmptyState();
            itemSetFragment.hasUpdated();
            itemSetFragment.scrollToTop();
        }
    }

    private void updateFleuron(boolean requiresPremium) {
	    FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out);

	    if (requiresPremium) {
	        transaction.hide(itemSetFragment);
            binding.footerFleuron.textSubscription.setText(R.string.premium_subscribers_search);
            binding.footerFleuron.containerSubscribe.setVisibility(View.VISIBLE);
            binding.footerFleuron.getRoot().setVisibility(View.VISIBLE);
            binding.footerFleuron.containerSubscribe.setOnClickListener(view -> UIUtils.startPremiumActivity(this));
        } else {
	        transaction.show(itemSetFragment);
            binding.footerFleuron.containerSubscribe.setVisibility(View.GONE);
            binding.footerFleuron.getRoot().setVisibility(View.GONE);
            binding.footerFleuron.containerSubscribe.setOnClickListener(null);
        }
	    transaction.commit();
    }

	@Override
    public void storyOrderChanged(StoryOrder newValue) {
        updateStoryOrderPreference(newValue);
        restartReadingSession();
    }

    @Override
    public void readFilterChanged(ReadFilter newValue) {
        updateReadFilterPreference(newValue);
        restartReadingSession();
    }

    protected void restartReadingSession() {
        NBSyncService.resetFetchState(fs);
        feedUtils.prepareReadingSession(fs, true);
        triggerSync();
        itemSetFragment.resetEmptyState();
        itemSetFragment.hasUpdated();
        itemSetFragment.scrollToTop();
    }

    // NB: this callback is for the text size slider
	@Override
	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        float size = AppConstants.LIST_FONT_SIZE[progress];
	    PrefsUtils.setListTextSize(this, size);
        if (itemSetFragment != null) itemSetFragment.setTextSize(size);
	}

    // unused OnSeekBarChangeListener method
	@Override
	public void onStartTrackingTouch(SeekBar seekBar) {
	}

    // unused OnSeekBarChangeListener method
	@Override
	public void onStopTrackingTouch(SeekBar seekBar) {
	}

    @Override
    public void finish() {
        super.finish();
        /*
         * Animate out the list by sliding it to the right and the Main activity in from
         * the left.  Do this when going back to Main as a subtle hint to the swipe gesture,
         * to make the gesture feel more natural, and to override the really ugly transition
         * used in some of the newer platforms.
         */
        overridePendingTransition(R.anim.slide_in_from_left, R.anim.slide_out_to_right);
    }

    abstract String getSaveSearchFeedId();
}
