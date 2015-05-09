package com.pr0gramm.app.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;

import com.google.common.base.Optional;
import com.pr0gramm.app.R;
import com.pr0gramm.app.Settings;
import com.pr0gramm.app.SyncBroadcastReceiver;
import com.pr0gramm.app.feed.FeedFilter;
import com.pr0gramm.app.feed.FeedProxy;
import com.pr0gramm.app.feed.FeedType;
import com.pr0gramm.app.services.BookmarkService;
import com.pr0gramm.app.services.SingleShotService;
import com.pr0gramm.app.services.UserService;
import com.pr0gramm.app.ui.dialogs.ErrorDialogFragment;
import com.pr0gramm.app.ui.dialogs.UpdateDialogFragment;
import com.pr0gramm.app.ui.fragments.DrawerFragment;
import com.pr0gramm.app.ui.fragments.FeedFragment;
import com.pr0gramm.app.ui.fragments.PostPagerFragment;

import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Minutes;

import javax.annotation.Nullable;
import javax.inject.Inject;

import roboguice.activity.RoboActionBarActivity;
import roboguice.inject.InjectView;
import rx.functions.Actions;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.pr0gramm.app.ui.dialogs.ErrorDialogFragment.defaultOnError;
import static com.pr0gramm.app.ui.fragments.BusyDialogFragment.busyDialog;
import static rx.android.observables.AndroidObservable.bindActivity;


/**
 * This is the main class of our pr0gramm app.
 */
public class MainActivity extends RoboActionBarActivity implements
        DrawerFragment.OnFeedFilterSelected,
        FragmentManager.OnBackStackChangedListener,
        ScrollHideToolbarListener.ToolbarActivity,
        MainActionHandler {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ErrorDialogFragment.OnErrorDialogHandler errorHandler = new ActivityErrorHandler(this);

    @InjectView(R.id.drawer_layout)
    private DrawerLayout drawerLayout;

    @InjectView(R.id.toolbar)
    private Toolbar toolbar;

    @Nullable
    @InjectView(R.id.toolbar_container)
    private View toolbarContainer;

    @Inject
    private UserService userService;

    @Inject
    private BookmarkService bookmarkService;

    @Inject
    private Settings settings;

    @Inject
    private SharedPreferences shared;

    @Inject
    private SingleShotService singleShotService;

    private ActionBarDrawerToggle drawerToggle;
    private ScrollHideToolbarListener scrollHideToolbarListener;
    private boolean startedWithIntent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // use toolbar as action bar
        setSupportActionBar(toolbar);

        // and hide it away on scrolling
        scrollHideToolbarListener = new ScrollHideToolbarListener(
                firstNonNull(toolbarContainer, toolbar));

        // prepare drawer layout
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.app_name, R.string.app_name);
        drawerLayout.setDrawerListener(drawerToggle);
        drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, Gravity.START);

        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        drawerToggle.syncState();

        // listen to fragment changes
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        if (savedInstanceState == null) {
            createDrawerFragment();

            Intent intent = getIntent();
            if (intent == null || Intent.ACTION_MAIN.equals(intent.getAction())) {
                // load feed-fragment into view
                gotoFeedFragment(new FeedFilter(), true);

            } else {
                startedWithIntent = true;
                onNewIntent(intent);
            }
        }

        if (singleShotService.isFirstTimeInVersion("changelog")) {
            ChangeLogDialog dialog = new ChangeLogDialog();
            dialog.show(getSupportFragmentManager(), null);

        } else {
            // start the update check.
            UpdateDialogFragment.checkForUpdates(this, false);
        }

        addOriginalContentBookmarkOnce();
    }

    /**
     * Adds a bookmark if there currently are no bookmarks.
     */
    private void addOriginalContentBookmarkOnce() {
        if (!singleShotService.isFirstTime("add_original_content_bookmarks"))
            return;

        bindActivity(this, bookmarkService.get().first()).subscribe(bookmarks -> {
            if (bookmarks.isEmpty()) {
                FeedFilter filter = new FeedFilter()
                        .withFeedType(FeedType.PROMOTED)
                        .withTags("original content");

                bookmarkService.create(filter);
            }
        }, Actions.empty());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (!Intent.ACTION_VIEW.equals(intent.getAction()))
            return;

        handleUri(intent.getData());
    }

    @Override
    protected void onStart() {
        super.onStart();

        // trigger updates while the activity is running
        sendSyncRequest.run();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(sendSyncRequest);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        getSupportFragmentManager().removeOnBackStackChangedListener(this);

        try {
            super.onDestroy();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onBackStackChanged() {
        updateToolbarBackButton();
        updateActionbarTitle();

        DrawerFragment drawer = getDrawerFragment();
        if (drawer != null) {
            FeedFilter currentFilter = getCurrentFeedFilter();

            // show the current item in the drawer
            drawer.updateCurrentFilters(currentFilter);
        }
    }

    private void updateActionbarTitle() {
        String title;
        FeedFilter filter = getCurrentFeedFilter();
        if (filter == null) {
            title = getString(R.string.pr0gramm);
        } else {
            title = FeedFilterFormatter.format(this, filter);
        }

        setTitle(title);
    }

    /**
     * Returns the current feed filter. Might be null, if no filter could be detected.
     */
    @Nullable
    private FeedFilter getCurrentFeedFilter() {
        // get the filter of the visible fragment.
        FeedFilter currentFilter = null;
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content);
        if (fragment != null) {
            if (fragment instanceof FeedFragment) {
                currentFilter = ((FeedFragment) fragment).getCurrentFilter();
            }

            if (fragment instanceof PostPagerFragment) {
                currentFilter = ((PostPagerFragment) fragment).getCurrentFilter();
            }
        }
        return currentFilter;
    }

    private void updateToolbarBackButton() {
        FragmentManager fm = getSupportFragmentManager();
        drawerToggle.setDrawerIndicatorEnabled(fm.getBackStackEntryCount() == 0);
        drawerToggle.syncState();
    }

    private void createDrawerFragment() {
        DrawerFragment fragment = new DrawerFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.left_drawer, fragment)
                .commit();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!drawerToggle.isDrawerIndicatorEnabled()) {
            if (item.getItemId() == android.R.id.home) {
                getSupportFragmentManager().popBackStack();
                return true;
            }
        }

        return drawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);

    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawers();
            return;
        }

        // at the end, go back to the "top" page before stopping everything.
        if (getSupportFragmentManager().getBackStackEntryCount() == 0 && !startedWithIntent) {
            FeedFilter filter = getCurrentFeedFilter();
            if (filter != null && !isTopFilter(filter)) {
                gotoFeedFragment(new FeedFilter(), true);
                return;
            }
        }

        super.onBackPressed();
    }

    private boolean isTopFilter(FeedFilter filter) {
        return filter.isBasic() && filter.getFeedType() == FeedType.PROMOTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ErrorDialogFragment.setGlobalErrorDialogHandler(errorHandler);
        onBackStackChanged();
    }

    @Override
    protected void onPause() {
        ErrorDialogFragment.unsetGlobalErrorDialogHandler(errorHandler);
        super.onPause();
    }

    @Override
    public void onPostClicked(FeedProxy feed, int idx) {
        if (idx < 0 || idx >= feed.getItemCount())
            return;

        Fragment fragment = PostPagerFragment.newInstance(feed, idx);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss();
    }

    @Override
    public void onLogoutClicked() {
        bindActivity(this, userService.logout())
                .lift(busyDialog(this))
                .subscribe(Actions.empty(), defaultOnError());
    }

    @Override
    public void onFeedFilterSelectedInNavigation(FeedFilter filter) {
        gotoFeedFragment(filter, true);
        drawerLayout.closeDrawers();
    }

    @Override
    public void onOtherNavigationItemClicked() {
        drawerLayout.closeDrawers();
    }

    @Override
    public void onFeedFilterSelected(FeedFilter filter) {
        gotoFeedFragment(filter, false);
    }

    @Override
    public void pinFeedFilter(FeedFilter filter, String title) {
        bookmarkService.create(filter, title).subscribe(Actions.empty(), defaultOnError());
        drawerLayout.openDrawer(Gravity.START);
    }

    private void gotoFeedFragment(FeedFilter newFilter, boolean clear) {
        gotoFeedFragment(newFilter, clear, Optional.<Long>absent());
    }

    private void gotoFeedFragment(FeedFilter newFilter, boolean clear, Optional<Long> start) {
        if (isFinishing())
            return;

        if (clear) {
            clearBackStack();
        }

        Fragment fragment = FeedFragment.newInstance(newFilter, start);

        // and show the fragment
        @SuppressLint("CommitTransaction")
        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content, fragment);

        if (!clear)
            transaction.addToBackStack(null);

        try {
            transaction.commit();
        } catch (IllegalStateException ignored) {
        }

        // trigger a back-stack changed after adding the fragment.
        new Handler().post(this::onBackStackChanged);
    }

    private DrawerFragment getDrawerFragment() {
        return (DrawerFragment) getSupportFragmentManager()
                .findFragmentById(R.id.left_drawer);
    }

    private void clearBackStack() {
        getSupportFragmentManager().popBackStackImmediate(
                null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    @Override
    public ScrollHideToolbarListener getScrollHideToolbarListener() {
        return scrollHideToolbarListener;
    }

    /**
     * Handles a uri to something on pr0gramm
     *
     * @param uri The uri to handle
     */
    private void handleUri(Uri uri) {
        Optional<FeedFilterWithStart> result = FeedFilterWithStart.fromUri(uri);
        if (result.isPresent()) {
            FeedFilter filter = result.get().getFilter();
            Optional<Long> start = result.get().getStart();

            boolean clear = getSupportFragmentManager().getBackStackEntryCount() == 0;
            gotoFeedFragment(filter, clear, start);

        } else {
            gotoFeedFragment(new FeedFilter(), true);
        }
    }

    @SuppressWarnings("Convert2Lambda")
    private final Runnable sendSyncRequest = new Runnable() {
        private Instant lastUpdate = new Instant(0);

        @Override
        public void run() {
            Instant now = Instant.now();
            if (Minutes.minutesBetween(lastUpdate, now).getMinutes() > 4) {
                Intent intent = new Intent(MainActivity.this, SyncBroadcastReceiver.class);
                MainActivity.this.sendBroadcast(intent);
            }

            // reschedule
            Duration delay = Minutes.minutes(5).toStandardDuration();
            handler.postDelayed(this, delay.getMillis());

            // and remember the last update time
            lastUpdate = now;
        }
    };
}
