package com.pr0gramm.app.ui.fragments.feed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.whenResumed
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.pr0gramm.app.*
import com.pr0gramm.app.api.pr0gramm.MessageConverter
import com.pr0gramm.app.feed.*
import com.pr0gramm.app.feed.ContentType.SFW
import com.pr0gramm.app.parcel.getFreezable
import com.pr0gramm.app.parcel.putFreezable
import com.pr0gramm.app.services.*
import com.pr0gramm.app.services.preloading.PreloadService
import com.pr0gramm.app.ui.*
import com.pr0gramm.app.ui.ScrollHideToolbarListener.ToolbarActivity
import com.pr0gramm.app.ui.back.BackAwareFragment
import com.pr0gramm.app.ui.base.*
import com.pr0gramm.app.ui.dialogs.PopupPlayer
import com.pr0gramm.app.ui.fragments.CommentRef
import com.pr0gramm.app.ui.fragments.ItemUserAdminDialog
import com.pr0gramm.app.ui.fragments.OverscrollLinearSmoothScroller
import com.pr0gramm.app.ui.fragments.PostPagerFragment
import com.pr0gramm.app.ui.views.CustomSwipeRefreshLayout
import com.pr0gramm.app.ui.views.SearchOptionsView
import com.pr0gramm.app.ui.views.UserInfoView
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.di.instance
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.min


/**
 */
class FeedFragment : BaseFragment("FeedFragment"), FilterFragment, TitleFragment, BackAwareFragment {
    private val settings = Settings.get()

    private val feedStateModel by viewModels {
        val start = arguments?.getParcelable<CommentRef?>(ARG_FEED_START)
        if (start != null) {
            logger.debug { "Requested to open item $start on load" }
            autoScrollRef = ScrollRef(start, autoOpen = true)
        }

        FeedViewModel(
                filter = requireArguments().getFreezable(ARG_FEED_FILTER, FeedFilter),
                loadAroundItemId = autoScrollRef?.ref?.itemId,

                feedService = instance(),
                userService = instance(),
                seenService = instance(),
                inMemoryCacheService = instance(),
                preloadManager = instance(),
                adService = instance()
        )
    }

    private val userStateModel by viewModels {
        UserStateModel(
                filter = requireArguments().getFreezable(ARG_FEED_FILTER, FeedFilter),
                queryForUserInfo = isNormalMode,
                userService = instance(),
                inboxService = instance()
        )
    }

    private val feedService: FeedService by instance()
    private val bookmarkService: BookmarkService by instance()
    private val userService: UserService by instance()
    private val singleShotService: SingleShotService by instance()
    private val inMemoryCacheService: InMemoryCacheService by instance()
    private val recentSearchesServices: RecentSearchesServices by instance()
    private val followService: FollowService by instance()
    private val shareService: ShareService by instance()

    private val recyclerView: RecyclerView by bindView(R.id.list)
    private val swipeRefreshLayout: CustomSwipeRefreshLayout by bindView(R.id.refresh)
    private val searchContainer: ScrollView by bindView(R.id.search_container)
    private val searchView: SearchOptionsView by bindView(R.id.search_options)

    private val isNormalMode: Boolean by fragmentArgumentWithDefault(true, ARG_NORMAL_MODE)

    private val doIfAuthorizedHelper = LoginActivity.helper(this)

    private var bookmarkable: Boolean = false
    private var autoScrollRef: ScrollRef? = null

    private var lastCheckForNewItemsTime = Instant(0)

    private lateinit var interstitialAdler: InterstitialAdler

    private lateinit var feedAdapter: FeedAdapter

    private val scrollToolbar: Boolean
        get() = isNormalMode

    private val feed: Feed get() = feedStateModel.feedState.value.feed

    /**
     * Initialize a new feed fragment.
     */
    init {
        setHasOptionsMenu(true)

        debugOnly {
            MainScope.launch {
                lifecycle.asEventFlow().collect { event ->
                    this@FeedFragment.trace { "$event" }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        interstitialAdler = InterstitialAdler(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity()

        val abHeight = AndroidUtility.getActionBarContentOffset(activity)

        feedAdapter = FeedAdapter((activity as MainActivity).adViewAdapter)

        launchInViewScope {
            feedAdapter.state.collect {
                this@FeedFragment.autoScrollRef?.let { autoScrollRef ->
                    if (autoScrollRef.autoOpen) {
                        performAutoOpen(autoScrollRef.ref)
                    }
                }
            }
        }

        // prepare the list of items
        val spanCount = thumbnailColumCount
        recyclerView.itemAnimator = null
        recyclerView.adapter = feedAdapter
        recyclerView.layoutManager = InternalGridLayoutManager(activity, spanCount).apply {
            spanSizeLookup = feedAdapter.SpanSizeLookup(spanCount)
        }

        activity.configureRecyclerView("Feed", recyclerView)

        recyclerView.addOnScrollListener(onScrollListener)

        // we can still swipe up if we are not at the start of the feed.
        swipeRefreshLayout.setCanChildScrollUpTest {
            val state = feedStateModel.feedState.value
            trace { "empty=${state.empty}, atStart=${feed.isAtStart}" }
            !state.empty && !feed.isAtStart && feed.size > 0
        }

        swipeRefreshLayout.setColorSchemeResources(ThemeHelper.accentColor)
        swipeRefreshLayout.setProgressViewOffset(false, 0, (1.5 * abHeight).toInt())

        swipeRefreshLayout.setOnRefreshListener {
            logger.debug { "onRefresh called for swipe view." }
            swipeRefreshLayout.isRefreshing = false
            refreshContent()
        }

        resetToolbar()

        createRecyclerViewClickListener()

        // execute a search when we get a search term
        searchView.searchQuery = { this.performSearch(it) }
        searchView.searchCanceled = { hideSearchContainer() }

        // restore open search
        if (savedInstanceState != null && savedInstanceState.getBoolean("searchContainerVisible")) {
            showSearchContainer(false)
        }

        // close search on click into the darkened area.
        searchContainer.setOnTouchListener(DetectTapTouchListener { hideSearchContainer() })


        launchInViewScope {
            data class Update(
                    val feedState: FeedViewModel.FeedState,
                    val userState: UserStateModel.UserState
            )

            combine(feedStateModel.feedState, userStateModel.userState) { feedState, userState -> Update(feedState, userState) }.collect { update ->
                logger.debug { "Apply update: $update" }

                update.feedState.errorConsumable?.consume { error ->
                    displayFeedError(error)
                }

                update.feedState.autoScrollRef?.consume { ref ->
                    autoScrollRef = ref
                }

                updateAdapterState(update.feedState, update.userState)
            }
        }

        launchInViewScope {
            whenResumed {
                userStateModel.userState.collectLatest { userState ->
                    requireActivity().invalidateOptionsMenu()

                    val userId = userState.userInfo?.info?.user?.id
                    if (userId != null) {
                        followService.getState(userId.toLong()).collect {
                            requireActivity().invalidateOptionsMenu()
                        }
                    }
                }
            }
        }

        launchInViewScope {
            userService.selectedContentTypes.collect { contentTypes ->
                if (feed.contentType != contentTypes) {
                    replaceFeedFilter()
                }

                activity.invalidateOptionsMenu()
            }
        }
    }

    private fun updateAdapterState(feedState: FeedViewModel.FeedState, userState: UserStateModel.UserState) {
        trace { "updateAdapterState()" }

        val context = context
        if (this.activity == null || context == null) {
            logger.warn { "updateAdapterState called with activity alredy null." }
            return
        }

        val filter = feedState.feed.filter

        val entries = mutableListOf<FeedAdapter.Entry>()

        logger.time("Update adapter") {
            // add a little spacer to the top to account for the action bar
            if (useToolbarTopMargin()) {
                val offset = AndroidUtility.getActionBarContentOffset(context)
                if (offset > 0) {
                    entries += FeedAdapter.Entry.Spacer(1, height = offset)
                }
            }

            if (feedState.loading == FeedManager.LoadingSpace.PREV) {
                entries += FeedAdapter.Entry.LoadingHint
            }

            if (userState.userInfo != null) {
                val userInfo = userState.userInfo
                val isSelfInfo = userInfo.info.user.name.equals(userState.ownUsername, ignoreCase = true)

                // if we found this user using a normal 'search', we will show a hint
                // that the user exists
                if (filter.tags != null) {
                    if (!isSelfInfo) {
                        val userAndMark = userInfo.info.user.run { UserAndMark(name, mark) }
                        entries += FeedAdapter.Entry.UserHint(userAndMark, this::openUserUploads)
                    }

                } else {
                    entries += FeedAdapter.Entry.User(userState.userInfo, isSelfInfo, userActionListener)

                    if (userState.userInfoCommentsOpen) {
                        val user = userService.name
                        userInfo.comments.mapTo(entries) { comment ->
                            val msg = MessageConverter.of(userState.userInfo.info.user, comment)
                            FeedAdapter.Entry.Comment(msg, user)
                        }
                    }

                    entries += FeedAdapter.Entry.Spacer(2, layout = R.layout.user_info_footer)
                }

            } else if (filter.username != null) {
                val item = feedState.feed.firstOrNull { it.user.equals(filter.username, ignoreCase = true) }
                if (item != null) {
                    val user = UserAndMark(item.user, item.mark)
                    entries += FeedAdapter.Entry.UserLoading(user)
                    entries += FeedAdapter.Entry.Spacer(2, layout = R.layout.user_info_footer)
                }
            }

            if (feedState.missingContentType != null) {
                if (userService.isAuthorized) {
                    entries += FeedAdapter.Entry.MissingContentType(feedState.missingContentType)
                } else {
                    val msg = buildString {
                        append(getString(R.string.could_not_load_feed_content_type, feedState.missingContentType.name))
                        append(" ")
                        append(getString(R.string.could_not_load_feed_content_type__signin, feedState.missingContentType.name))
                    }

                    entries += FeedAdapter.Entry.Error(msg)
                }

            } else if (!userState.userInfoCommentsOpen) {
                // check if we need to check if the posts are 'seen'
                val markAsSeen = feedState.markItemsAsSeen && !run {
                    userState.ownUsername != null && userState.ownUsername.equalsIgnoreCase(filter.username)
                }

                // always show at least one ad banner - e.g. during load
                if (feedState.adsVisible && feedState.feed.isEmpty()) {
                    entries += FeedAdapter.Entry.Ad(0)
                }

                var itemColumnIndex = 0

                for (item in feedState.feed) {
                    val id = item.id
                    val seen = markAsSeen && id in feedState.seen
                    val repost = inMemoryCacheService.isRepost(id)
                    val preloaded = id in feedState.preloadedItemIds

                    // show an ad banner every ~50 lines
                    if (feedState.adsVisible && (itemColumnIndex % (50 * thumbnailColumCount)) == 0) {
                        entries += FeedAdapter.Entry.Ad(itemColumnIndex.toLong())
                    }

                    val highlight = thumbnailColumCount <= 3
                            && settings.highlightItemsInFeed
                            && item.id in feedState.highlightedItemIds

                    var indexToInsert = entries.size

                    if (highlight) {
                        indexToInsert -= itemColumnIndex % thumbnailColumCount
                    } else {
                        itemColumnIndex++
                    }

                    entries.add(indexToInsert, FeedAdapter.Entry.Item(item, repost, preloaded, seen, highlight))
                }

                when {
                    feedState.loading == FeedManager.LoadingSpace.NEXT ->
                        entries += FeedAdapter.Entry.LoadingHint

                    feedState.error != null -> {
                        val errorStr = ErrorFormatting.format(requireContext(), feedState.error)
                        entries += FeedAdapter.Entry.Error(errorStr)
                    }

                    feedState.empty ->
                        entries += FeedAdapter.Entry.EmptyHint
                }
            }

            autoScrollRef?.let { ref ->
                logger.debug { "autoScrollRef before setting new items: $autoScrollRef" }
                if (ref.keepScroll) {
                    val lm = recyclerView.layoutManager as GridLayoutManager

                    val pos = lm.findLastVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION) {
                        val vh = recyclerView.findViewHolderForLayoutPosition(pos)
                        vh?.itemView?.requestFocus()
                    }

                    autoScrollRef = null
                }
            }

            feedAdapter.submitList(entries)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (view != null) {
            outState.putBoolean("searchContainerVisible", searchContainerIsVisible())
        }
    }

    private fun initialSearchViewState(): Bundle? {
        return arguments?.getBundle(ARG_SEARCH_QUERY_STATE) ?: run {
            currentFilter.tags?.let { tags ->
                SearchOptionsView.ofQueryTerm(tags)
            }
        }
    }

    private fun useToolbarTopMargin(): Boolean {
        return isNormalMode
    }

    private val userActionListener = object : UserInfoView.UserActionListener {
        override fun onWriteMessageClicked(name: String) {
            doIfAuthorizedHelper.run {
                ConversationActivity.start(requireContext(), name)
            }
        }

        override fun onUserViewCollectionsClicked(name: String, targetCollection: PostCollection?) {
            val filter = currentFilter.basic().withCollection(name,
                    targetCollection?.key ?: "**ANY", targetCollection?.title ?: "**ANY")

            if (filter != currentFilter) {
                (activity as MainActionHandler).onFeedFilterSelected(filter)
            }

            userStateModel.closeUserComments()
        }

        override fun onShowUploadsClicked(name: String) {
            val filter = currentFilter.basic().withFeedType(FeedType.NEW).withUser(name)
            if (filter != currentFilter) {
                (activity as MainActionHandler).onFeedFilterSelected(filter)
            }

            userStateModel.closeUserComments()
        }

        override fun onShowCommentsClicked() {
            userStateModel.openUserComments()
        }

        override fun shareUserProfile(name: String) {
            shareService.shareUserProfile(requireActivity(), name)
        }
    }

    private fun openUserUploads(name: String) {
        val handler = requireActivity() as MainActionHandler
        handler.onFeedFilterSelected(currentFilter.basic()
                .withFeedType(FeedType.NEW)
                .withUser(name))
    }

    override fun onDestroyView() {
        recyclerView.removeOnScrollListener(onScrollListener)

        // destroy any ad views that might still exist
        // feedAdapter.destroyAdView()

        super.onDestroyView()
    }

    private fun resetToolbar() {
        val activity = activity
        if (activity is ToolbarActivity) {
            activity.scrollHideToolbarListener.reset()
        }
    }

    private fun hideToolbar() {
        if (isNormalMode) {
            val activity = activity
            if (activity is ToolbarActivity) {
                activity.scrollHideToolbarListener.hide()
            }
        }
    }

    private fun onBookmarkableStateChanged(bookmarkable: Boolean) {
        if (this.bookmarkable != bookmarkable) {
            this.bookmarkable = bookmarkable
            activity?.invalidateOptionsMenu()
        }
    }

    private val selectedContentType: EnumSet<ContentType>
        get() {
            if (!userService.isAuthorized)
                return EnumSet.of(SFW)

            return settings.contentType
        }

    override fun onResume() {
        super.onResume()

        Track.openFeed(currentFilter)

        // check if we should show the pin button or not.
        if (settings.showPinButton) {
            val bookmarkable = bookmarkService.isBookmarkable(currentFilter)
            onBookmarkableStateChanged(bookmarkable)
        }

        // we might want to check for new items on resume, but only once every two minutes.
        val checkForNewItemInterval = Duration.seconds(if (BuildConfig.DEBUG) 5 else 60)
        val threshold = Instant.now().minus(checkForNewItemInterval)
        if (feed.created.isBefore(threshold) && lastCheckForNewItemsTime.isBefore(threshold)) {
            lastCheckForNewItemsTime = Instant.now()
            checkForNewItems()
        }
    }

    private fun performAutoScroll() {
        val ref = autoScrollRef ?: return

        if (ref.autoOpen) {
            return
        }

        // if we currently scroll the view, lets just do this later.
        if (recyclerView.isComputingLayout) {
            launchInViewScope {
                awaitFrame()
                performAutoScroll()
            }

            return
        }

        val containsRef = feedAdapter.items.any { entry ->
            entry is FeedAdapter.Entry.Item && entry.item.id == ref.itemId
        }

        if (containsRef) {
            autoScrollRef = null
            scrollToItem(ref.itemId, ref.smoothScroll)

        } else if (ref.feed != null) {
            // mark the feed as applied
            autoScrollRef = ref.copy(feed = null)

            // apply the updated feed reference
            feedStateModel.replaceCurrentFeed(feed.mergeIfPossible(ref.feed) ?: ref.feed)
        }
    }

    fun updateFeedItemTarget(feed: Feed, item: FeedItem) {
        logger.info { "Want to resume from $item" }
        autoScrollRef = ScrollRef(CommentRef(item), feed, smoothScroll = true)
    }

    private fun checkForNewItems() {
        if (!feed.isAtStart || feed.filter.feedType == FeedType.RANDOM || feed.isEmpty()) {
            logger.debug { "Not checking for new items as we are not at the beginning of the feed" }
            return
        }

        launchUntilPause {
            logger.info { "Checking for new items in current feed" }

            val query = FeedService.FeedQuery(feed.filter, feed.contentType)
            catchAll {
                val response = feedService.load(query)

                val previousIds = feed.mapTo(mutableSetOf()) { it.id }
                val itemCount = response.items.count { it.id !in previousIds }
                if (itemCount > 0 && feed.isNotEmpty() && feed.filter == query.filter) {
                    newItemsSnackbar(itemCount)
                }
            }
        }
    }

    private fun newItemsSnackbar(itemCount: Int) {
        val text = when {
            itemCount == 1 -> getString(R.string.hint_new_items_one)
            itemCount <= 16 -> getString(R.string.hint_new_items_some, itemCount)
            else -> getString(R.string.hint_new_items_many)
        }

        val view = view ?: return

        val snackbar = Snackbar.make(view, text, Snackbar.LENGTH_LONG).apply {
            configureNewStyle()
            setAction(R.string.hint_refresh_load) { refreshContent() }
            show()
        }

        // dismiss once the fragment stops.
        launchUntilViewDestroy {
            // wait for the stop event
            lifecycle.asEventFlow().firstOrNull { it == Lifecycle.Event.ON_STOP }
            snackbar.dismiss()
        }
    }

    private fun replaceFeedFilter(feedFilter: FeedFilter? = null, item: Long? = null) {
        val startAtItemId = item
                ?: autoScrollRef?.ref?.itemId
                ?: findLastVisibleFeedItem(userService.selectedContentType)?.id

        if (autoScrollRef == null) {
            autoScrollRef = startAtItemId?.let { id -> ScrollRef(CommentRef(id)) }
        }

        // this clears the current feed immediately
        val filter = feedFilter ?: feed.filter
        feedStateModel.restart(
                feed = Feed(filter, userService.selectedContentType),
                aroundItemId = startAtItemId
        )

        activity?.invalidateOptionsMenu()
    }

    /**
     * Finds the last item in the feed that is visible and of one of the given content types

     * @param contentType The target-content type.
     */
    private fun findLastVisibleFeedItem(
            contentType: Set<ContentType> = ContentType.AllSet): FeedItem? {

        // if we don't have a view, there wont be a visible item either.
        if (view == null) {
            return null
        }

        val items = feedAdapter.items.takeUnless { it.isEmpty() } ?: return null

        val layoutManager = recyclerView.layoutManager as? GridLayoutManager
        return layoutManager?.let { _ ->
            // if the first row is visible, skip this stuff.
            val firstCompletelyVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
            if (firstCompletelyVisible == 0 || firstCompletelyVisible == RecyclerView.NO_POSITION)
                return null

            val lastCompletelyVisible = layoutManager.findLastCompletelyVisibleItemPosition()
            if (lastCompletelyVisible == RecyclerView.NO_POSITION)
                return null

            val idx = (lastCompletelyVisible).coerceIn(items.indices)
            items.take(idx)
                    .mapNotNull { item -> (item as? FeedAdapter.Entry.Item)?.item }
                    .lastOrNull { contentType.contains(it.contentType) }
        }
    }

    /**
     * Depending on whether the screen is landscape or portrait, and how large
     * the screen is, we show a different number of items per row.
     */
    private val thumbnailColumCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        val config = resources.configuration
        val portrait = config.screenWidthDp < config.screenHeightDp

        val screenWidth = config.screenWidthDp
        min((screenWidth / 120.0 + 0.5).toInt(), if (portrait) 5 else 7)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_feed, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val activity = activity ?: return

        val filter = currentFilter
        val feedType = filter.feedType

        menu.findItem(R.id.action_refresh)?.isVisible = settings.showRefreshButton
        menu.findItem(R.id.action_bookmark)?.isVisible = bookmarkable
        menu.findItem(R.id.action_preload)?.isVisible = feedType.preloadable

        // hide search item, if we are not searchable
        val searchable = currentFilter.feedType.searchable
        menu.findItem(R.id.action_search)?.isVisible = searchable

        // switching to normal mode leaves the special favorites fragment.
        menu.findItem(R.id.action_feedtype)?.isVisible = isNormalMode

        val adminOnUserProfile = userService.userIsAdmin && userStateModel.userInfo?.info?.user?.name != null
        menu.findItem(R.id.action_block_user)?.isVisible = adminOnUserProfile
        menu.findItem(R.id.action_open_in_admin)?.isVisible = adminOnUserProfile

        menu.findItem(R.id.action_feedtype)?.let { item ->
            item.isVisible = !filter.isBasic && isNormalMode

            item.setTitle(if (switchFeedTypeTarget(filter) === FeedType.PROMOTED)
                R.string.action_switch_to_top else R.string.action_switch_to_new)
        }

        menu.findItem(R.id.action_change_content_type)?.let { item ->
            if (userService.isAuthorized) {
                val icon = ContentTypeDrawable(activity, selectedContentType)
                icon.textSize = resources.getDimensionPixelSize(
                        R.dimen.feed_content_type_action_icon_text_size).toFloat()

                item.icon = icon
                item.isVisible = true

                updateContentTypeItems(menu)

            } else {
                item.isVisible = false
            }
        }

        val bookmark = menu.findItem(R.id.action_bookmark)
        if (bookmark != null && filter.username != null) {
            // never bookmark a user
            bookmark.isVisible = false
        }
    }

    private fun switchFeedTypeTarget(filter: FeedFilter): FeedType {
        return if (filter.feedType !== FeedType.PROMOTED) FeedType.PROMOTED else FeedType.NEW
    }

    private fun updateContentTypeItems(menu: Menu) {
        // only one content type selected?
        val withoutImplicits = settings.contentType.withoutImplicit()
        val single = withoutImplicits.size == 1

        val types = mapOf(
                R.id.action_content_type_sfw to settings.contentTypeSfw,
                R.id.action_content_type_nsfw to settings.contentTypeNsfw,
                R.id.action_content_type_nsfl to settings.contentTypeNsfl)

        for ((key, value) in types) {
            menu.findItem(key)?.let { item ->
                item.isChecked = value
                item.isEnabled = !single || !value
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val contentTypes = mapOf(
                R.id.action_content_type_sfw to "pref_feed_type_sfw",
                R.id.action_content_type_nsfw to "pref_feed_type_nsfw",
                R.id.action_content_type_nsfl to "pref_feed_type_nsfl")

        if (contentTypes.containsKey(item.itemId)) {
            val newState = !item.isChecked
            settings.edit {
                putBoolean(contentTypes[item.itemId], newState)
            }

            // this applies the new content types and refreshes the menu.
            return true
        }

        return true == when (item.itemId) {
            R.id.action_feedtype -> switchFeedType()
            R.id.action_refresh -> refreshFeedWithIndicator()
            R.id.action_bookmark -> pinCurrentFeedFilter()
            R.id.action_preload -> preloadCurrentFeed()
            R.id.action_block_user -> onBlockUserClicked()
            R.id.action_search -> resetAndShowSearchContainer()
            R.id.action_open_in_admin -> openUserInAdmin()

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openUserInAdmin() {
        val uri = "https://pr0gramm.com/backend/admin/?view=users&action=show&id=${userStateModel.userInfo?.info?.user?.id}"
        BrowserHelper.openCustomTab(requireContext(), Uri.parse(uri), handover = true)
    }

    private fun switchFeedType() {
        var filter = currentFilter
        filter = filter.withFeedType(switchFeedTypeTarget(filter))
        (activity as MainActionHandler).onFeedFilterSelected(filter, initialSearchViewState())
    }

    private fun refreshFeedWithIndicator() {
        refreshContent()
    }

    private fun refreshContent() {
        resetToolbar()
        feedStateModel.refresh()
    }

    private fun pinCurrentFeedFilter() {
        // not bookmarkable anymore.
        onBookmarkableStateChanged(false)

        val filter = currentFilter
        val title = FeedFilterFormatter.format(context, filter).singleline
        (activity as MainActionHandler).bookmarkFilter(filter, title)
    }

    private fun preloadCurrentFeed() {
        if (AndroidUtility.isOnMobile(activity)) {
            showDialog(this) {
                content(R.string.preload_not_on_mobile)
                negative()
                positive {
                    doPreloadCurrentFeed(allowOnMobile = true)
                }
            }

            return
        } else {
            doPreloadCurrentFeed(allowOnMobile = false)
        }
    }

    private fun doPreloadCurrentFeed(allowOnMobile: Boolean) {
        val activity = activity ?: return

        // start preloading now
        PreloadService.preload(activity, feed, allowOnMobile)

        Track.preloadCurrentFeed(feed.size)

        singleShotService.doOnce("preload_info_hint") {
            showDialog(this) {
                content(R.string.preload_info_hint)
                positive()
            }
        }
    }

    private fun onBlockUserClicked() {
        this.userStateModel.userInfo?.info?.user?.name?.let { name ->
            val dialog = ItemUserAdminDialog.forUser(name)
            dialog.maybeShow(parentFragmentManager, "BlockUserDialog")
        }
    }

    private fun performSearch(query: SearchOptionsView.SearchQuery) {
        hideSearchContainer()

        val current = currentFilter
        var filter = current.withTagsNoReset(query.combined)

        // do nothing, if the filter did not change
        if (current == filter)
            return

        var startAt: CommentRef? = null
        if (query.combined.trim().matches("[1-9][0-9]{5,}|id:[0-9]+".toRegex())) {
            filter = filter.withTags("")
            startAt = CommentRef(query.combined.filter { it in '0'..'9' }.toLong())
        }

        val searchQueryState = searchView.currentState()
        (activity as MainActionHandler).onFeedFilterSelected(filter, searchQueryState, startAt)

        // store the term for later
        if (query.queryTerm.isNotBlank()) {
            recentSearchesServices.storeTerm(query.queryTerm)
        }
    }

    private fun onItemClicked(item: FeedItem, commentRef: CommentRef? = null, preview: ImageView? = null) {
        val activity = activity ?: return

        // reset auto open.
        autoScrollRef = null

        val idx = feed.indexById(item.id) ?: return
        trace { "onItemClicked(feedIndex=$idx, id=${item.id})" }

        try {
            val generator: FancyExifThumbnailGenerator by instance()

            val fragment = PostPagerFragment.newInstance(feed, idx, commentRef, title?.title)
            if (preview != null) {
                // pass pixels info to target fragment.
                val image = preview.drawable

                val info = PreviewInfo.of(requireContext(), item, image)
                info.preloadFancyPreviewImage(generator)
                fragment.setPreviewInfo(info)
            }

            // Only set the target fragment if we are using the same fragment manager
            // to replace the current fragment. This is not the case, if we were started
            // from the Favorites page.
            if (parentFragmentManager === activity.supportFragmentManager) {
                fragment.setTargetFragment(this, 0)
            }

            activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.content, fragment)
                    .addToBackStack(null)
                    .commit()

        } catch (error: Exception) {
            logger.warn("Error while showing post", error)
        }
    }

    override val currentFilter: FeedFilter
        get() = feed.filter

    override val title: TitleFragment.Title?
        get() {
            val context = context ?: return null
            return FeedFilterFormatter.toTitle(context, feed.filter,
                    titleOverride = arguments?.getString(ARG_TITLE))
        }

    private fun createRecyclerViewClickListener() {
        val listener = RecyclerItemClickListener(recyclerView)

        listener.itemClicked = { view ->
            extractFeedItemHolder(view)?.let { holder ->
                interstitialAdler.runWithAd {
                    onItemClicked(holder.item, preview = holder.imageView)
                }
            }
        }

        listener.itemLongClicked = itemLongClicked@{ view ->
            val holder = extractFeedItemHolder(view) ?: return@itemLongClicked
            val activity = this.activity ?: return@itemLongClicked

            if (!this@FeedFragment.isStateSaved) {
                PopupPlayer.open(activity, holder.item)
                swipeRefreshLayout.isEnabled = false
            }
        }

        listener.itemLongClickEnded = itemLongClickEnded@{
            if (!this@FeedFragment.isStateSaved) {
                PopupPlayer.close(activity ?: return@itemLongClickEnded)
                swipeRefreshLayout.isEnabled = true
            }
        }

        launchUntilDestroy(ignoreErrors = true) {
            settings.changes().onStart { emit("") }.collect {
                listener.enableLongClick(settings.enableQuickPeek)
            }
        }
    }

    private fun displayFeedError(error: Throwable) {
        logger.error("Error loading the feed", error)

        when (error) {
            is FeedException.NotPublicException -> showFeedNotPublicError()
            is FeedException.NotFoundException -> showFeedNotFoundError()
        }
    }

    private fun showFeedNotFoundError() {
        showDialog(context ?: return) {
            content(R.string.error_feed_not_found)
            positive {
                // open top instead
                autoScrollRef = null
                replaceFeedFilter(FeedFilter())
            }
        }
    }

    private fun showFeedNotPublicError() {
        // TODO do we keep this?
        val username = currentFilter.username ?: return

        val targetItem = autoScrollRef

        if (targetItem != null) {
            showDialog(this) {
                content(R.string.error_feed_not_public__item, username)

                negative()

                positive {
                    val filter = currentFilter.basic()
                    replaceFeedFilter(filter, targetItem.itemId)
                }
            }
        } else {
            showDialog(this) {
                content(R.string.error_feed_not_public__general, username)
                positive()
            }
        }
    }

    private fun resetAndShowSearchContainer() {
        searchView.applyState(initialSearchViewState())
        showSearchContainer(true)
    }

    private fun showSearchContainer(animated: Boolean) {
        val context = context ?: return

        if (searchContainerIsVisible())
            return

        val view = view ?: return

        view.post { this.hideToolbar() }

        // ensure that the search view is initialized
        searchView.initView()

        // prepare search view
        val typeName = FeedFilterFormatter.feedTypeToString(context, currentFilter.withTagsNoReset("dummy"))
        searchView.setQueryHint(getString(R.string.action_search, typeName))

        if (isNormalMode) {
            val paddingTop = AndroidUtility.getStatusBarHeight(context)
            searchView.setPadding(0, paddingTop, 0, 0)
        } else {
            searchView.enableSimpleSearch()
        }

        searchContainer.isVisible = true

        if (animated) {
            searchContainer.alpha = 0f

            val searchView = searchView
            searchContainer.animate()
                    .withEndAction { searchView.requestSearchFocus() }
                    .alpha(1f)

            searchView.translationY = (-(0.1 * view.height).toInt()).toFloat()

            searchView.animate()
                    .setInterpolator(DecelerateInterpolator())
                    .translationY(0f)
        } else {
            searchContainer.animate().cancel()
            searchContainer.alpha = 1f

            searchView.animate().cancel()
            searchView.translationY = 0f
        }
    }

    override fun onBackButton(): Boolean {
        if (searchContainerIsVisible()) {
            hideSearchContainer()
            return true
        }

        return false
    }

    private fun searchContainerIsVisible(): Boolean {
        return view != null && searchContainer.isVisible
    }

    private fun hideSearchContainer() {
        if (!searchContainerIsVisible())
            return

        val containerView = this.searchContainer
        containerView.animate()
                .withEndAction { containerView.isVisible = false }
                .alpha(0f)

        val height = view?.height ?: 0
        searchView.animate().translationY((-(0.1 * height).toInt()).toFloat())

        resetToolbar()

        AndroidUtility.hideSoftKeyboard(searchView)
    }

    private fun performAutoOpen(ref: CommentRef) {
        if (isStateSaved || view == null)
            return

        logger.info { "Trying to do auto load of $ref" }
        val idx = feed.indexById(ref.itemId) ?: return

        logger.debug { "Found item at idx=$idx" }

        // scroll to item now and click
        scrollToItem(ref.itemId)

        launchInViewScope {
            awaitFrame()
            onItemClicked(feed[idx], ref)
        }

        // prevent flickering of items before executing the child
        // fragment transaction.
        recyclerView.visibility = View.INVISIBLE
    }

    private fun scrollToItem(itemId: Long, smoothScroll: Boolean = false) {
        trace { "scrollToItem($itemId, smooth=$smoothScroll)" }

        logger.debug { "Checking if we can scroll to item $itemId" }
        val idx = feedAdapter.items
                .indexOfFirst { it is FeedAdapter.Entry.Item && it.item.id == itemId }
                .takeIf { it >= 0 } ?: return

        logger.debug { "Found item at idx=$idx, will scroll now (smooth=$smoothScroll)" }

        val recyclerView = recyclerView
        if (smoothScroll) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

            // smooth scroll to the target position
            val context = recyclerView.context
            layoutManager.startSmoothScroll(OverscrollLinearSmoothScroller(context, idx,
                    dontScrollIfVisible = true,
                    offsetTop = AndroidUtility.getActionBarContentOffset(context) + context.dp(32),
                    offsetBottom = context.dp(32)))

        } else {
            // over scroll a bit
            recyclerView.scrollToPosition(idx + thumbnailColumCount)
        }
    }

    private fun extractFeedItemHolder(view: View): FeedItemViewHolder? {
        return view.tag as? FeedItemViewHolder
    }

    private inner class InternalGridLayoutManager(context: Context, spanCount: Int) : GridLayoutManager(context, spanCount) {
        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            performAutoScroll()
        }
    }

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {

            val activity = activity as? ToolbarActivity
            if (scrollToolbar && activity != null) {
                activity.scrollHideToolbarListener.onScrolled(dy)
            }

            if (feedStateModel.feedState.value.isLoading || feedAdapter.updating) {
                return
            }

            val layoutManager = recyclerView.gridLayoutManager
            val totalItemCount = layoutManager.itemCount

            // start loading the next page pretty early.
            val maxEdgeDistance = 48

            if (dy > 0 && !feed.isAtEnd) {
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (lastVisibleItem >= 0 && totalItemCount > maxEdgeDistance && lastVisibleItem >= totalItemCount - maxEdgeDistance) {
                    logger.info { "Request next page now (last visible is $lastVisibleItem of $totalItemCount. Last feed item is ${feed.oldestItem}" }
                    feedStateModel.loaderNext()
                }
            }

            if (dy < 0 && !feed.isAtStart) {
                val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()
                if (firstVisibleItem >= 0 && totalItemCount > maxEdgeDistance && firstVisibleItem < maxEdgeDistance) {
                    logger.info { "Request previous page now (first visible is $firstVisibleItem of $totalItemCount. Most recent feed item is ${feed.newestItem}" }
                    feedStateModel.loaderPrevious()
                }
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                val activity = activity as? ToolbarActivity
                if (activity != null) {
                    val y = ScrollHideToolbarListener.estimateRecyclerViewScrollY(recyclerView)
                            ?: Integer.MAX_VALUE

                    activity.scrollHideToolbarListener.onScrollFinished(y)
                }
            }
        }
    }

    companion object {
        private const val ARG_FEED_FILTER = "FeedFragment.filter"
        private const val ARG_FEED_START = "FeedFragment.start"
        private const val ARG_NORMAL_MODE = "FeedFragment.simpleMode"
        private const val ARG_TITLE = "FeedFragment.title"
        private const val ARG_SEARCH_QUERY_STATE = "FeedFragment.searchQueryState"

        fun newInstance(feedFilter: FeedFilter,
                        start: CommentRef?,
                        searchQueryState: Bundle?,
                        title: String? = null): FeedFragment {

            return FeedFragment().apply {
                arguments = bundle {
                    putFreezable(ARG_FEED_FILTER, feedFilter)
                    putParcelable(ARG_FEED_START, start)
                    putBoolean(ARG_NORMAL_MODE, true)
                    putBundle(ARG_SEARCH_QUERY_STATE, searchQueryState)

                    title?.let { putString(ARG_TITLE, title) }
                }
            }
        }

        fun newEmbedArguments(filter: FeedFilter) = bundle {
            putFreezable(ARG_FEED_FILTER, filter)
            putBoolean(ARG_NORMAL_MODE, false)
        }
    }
}

private val RecyclerView.gridLayoutManager: GridLayoutManager
    get() = layoutManager as GridLayoutManager
