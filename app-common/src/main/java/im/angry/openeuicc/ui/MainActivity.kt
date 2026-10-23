package im.angry.openeuicc.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.usb.interfaces
import im.angry.openeuicc.core.usb.smartCard
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.ui.wizard.DownloadTargetActivity
import im.angry.openeuicc.util.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@SuppressLint("NotifyDataSetChanged")
open class MainActivity : BaseEuiccAccessActivity(), OpenEuiccContextMarker {
    companion object {
        const val TAG = "MainActivity"

        const val PERMISSION_REQUEST_CODE = 1000

        internal fun slotAfterRefresh(slots: List<Int>, selected: Int?, lastPhone: Int, readerAdded: Boolean = false): Int {
            if (readerAdded && EuiccChannelManager.USB_CHANNEL_ID in slots)
                return slots.indexOf(EuiccChannelManager.USB_CHANNEL_ID)
            val target = if (selected == EuiccChannelManager.USB_CHANNEL_ID && selected !in slots)
                lastPhone else selected
            return slots.indexOf(target).coerceAtLeast(0)
        }
    }

    private lateinit var loadingProgress: ProgressBar
    private lateinit var tabs: TabLayout
    private lateinit var tabsContainer: View
    private lateinit var viewPager: ViewPager2

    private var refreshing = false
    private var initialized = false
    private var pendingUsbRefresh = false
    private var updatingPages = false
    private var lastPhoneSlot = 0
    private var slotUpdate: kotlinx.coroutines.Job? = null
    private var lastUsbResult: EuiccChannelManager.UsbChannelOpenResult? = null

    private data class Page(
        val id: Long,
        val logicalSlotId: Int,
        val title: String,
        val isPlaceholder: Boolean,
        val channelKey: String?,
        val createFragment: () -> Fragment
    )

    private val pages: MutableList<Page> = mutableListOf()
    private var nextPageId = 0L

    private fun newPage(
        logicalSlotId: Int,
        title: String,
        isPlaceholder: Boolean = false,
        channelKey: String? = null,
        createFragment: () -> Fragment
    ): Page = Page(nextPageId++, logicalSlotId, title, isPlaceholder, channelKey, createFragment)

    private val pagerAdapter by lazy {
        object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size

            override fun createFragment(position: Int): Fragment = pages[position].createFragment()

            override fun getItemId(position: Int): Long = pages[position].id

            override fun containsItem(itemId: Long): Boolean = pages.any { it.id == itemId }
        }
    }

    protected lateinit var tm: TelephonyManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (initialized) refresh(true, showIndicator = false)
            } else if (initialized) {
                slotUpdate?.cancel()
                // Preserve the choice across the debounce, even if switching finishes meanwhile.
                val showIndicator = !isProfileSwitchInProgress
                slotUpdate = lifecycleScope.launch {
                    kotlinx.coroutines.delay(700)
                    refresh(showIndicator = showIndicator)
                }
            }
        }
    }

    @SuppressLint("WrongConstant", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lastPhoneSlot = savedInstanceState?.getInt("lastPhoneSlot") ?: 0
        setContentView(R.layout.activity_main)
        im.angry.openeuicc.ui.minimal.HomeGlass.install(this)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar?.title = applicationInfo.loadLabel(packageManager)
        requireViewById<im.angry.openeuicc.ui.minimal.MinimalToolbar>(R.id.toolbar).apply {
            setTitleTextAppearance(this@MainActivity, R.style.MinimalBrandText)
            setTitleTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.minimal_secondary))
        }
        loadingProgress = requireViewById(R.id.loading)
        tabs = requireViewById<TabLayout>(R.id.main_tabs).apply {
            // Let the selected segment's shadow extend into the surrounding 3dp inset.
            clipChildren = false
            clipToPadding = false
            (getChildAt(0) as? android.view.ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
        }
        tabsContainer = requireViewById(R.id.main_tabs_container)
        viewPager = requireViewById(R.id.view_pager)

        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false // Horizontal gestures belong to profile actions.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { invalidateOptionsMenu() }
        })
        TabLayoutMediator(tabs, viewPager, true, false) { tab, pos ->
            val ui = im.angry.openeuicc.ui.minimal.MinimalUi(this)
            val contentColors = androidx.appcompat.content.res.AppCompatResources.getColorStateList(this, R.color.minimal_tab_content)
            tab.text = pages[pos].title
            tab.customView = android.widget.LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.minimal_tab)
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.minimal_tab_elevation)
                outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
                outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(if (pages[pos].logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID)
                        R.drawable.ic_minimal_usb_reader else R.drawable.ic_minimal_sim)
                    isDuplicateParentStateEnabled = true
                    imageTintList = contentColors
                }, android.widget.LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).apply { marginEnd = ui.dp(7) })
                addView(ui.text(pages[pos].title, 14f).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    isDuplicateParentStateEnabled = true
                    setTextColor(contentColors)
                })
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, ui.dp(44))
            }
            (tab.customView?.parent as? android.view.ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
        }.attach()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.isSelected = true
                if (!updatingPages) pages.getOrNull(tab.position)?.logicalSlotId?.let {
                    if (it >= 0 && it != EuiccChannelManager.USB_CHANNEL_ID) lastPhoneSlot = it
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) { tab.customView?.isSelected = false }
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        tm = telephonyManager

        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("android.intent.action.SIM_STATE_CHANGED")
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                euiccChannelManagerLoaded.await()
                while (isActive) {
                    delay(1500)
                    if (!initialized || refreshing) continue
                    try {
                        val result = euiccChannelManagerService.pollUsbCard() ?: continue
                        val previous = lastUsbResult
                        lastUsbResult = result
                        if (previous != null && result != previous) {
                            Log.d(TAG, "USB card changed; refreshing slots")
                            refresh(fromUsbEvent = true, showIndicator = false)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not check USB card; will retry", e)
                    }
                }
            }
        }

        setupRootViewSystemBarInsets(
            window.decorView.rootView, arrayOf(
                { insets ->
                    activityToolbarInsetHandler(insets)
                    val toolbar = requireViewById<View>(R.id.toolbar)
                    val startInset = if (toolbar.layoutDirection == View.LAYOUT_DIRECTION_RTL) insets.right else insets.left
                    val endInset = if (toolbar.layoutDirection == View.LAYOUT_DIRECTION_RTL) insets.left else insets.right
                    // Align the glass circle's outer edge with the cards below.
                    toolbar.setPaddingRelative((18 * resources.displayMetrics.density).toInt() + startInset, toolbar.paddingTop,
                        (18 * resources.displayMetrics.density).toInt() + endInset, toolbar.paddingBottom)
                }
            ), consume = false
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_main, menu)
        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(this)
        listOf(R.id.minimal_add_action to R.drawable.ic_minimal_plus,
            R.id.minimal_more_action to R.drawable.ic_minimal_more).forEach { (id, icon) ->
            val item = menu.findItem(id)
            val button = android.widget.ImageButton(this).apply {
                contentDescription = item.title
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_text))
                background = im.angry.openeuicc.ui.minimal.HomeGlass.surface(this,
                    whiteAlpha = 170, cornerRadiusDp = 20f)
                elevation = ui.dp(2).toFloat()
                outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
                outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
                if (id == R.id.minimal_more_action) {
                    setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                } else {
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                }
                setOnClickListener { onOptionsItemSelected(item) }
            }
            item.actionView = android.widget.FrameLayout(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    ui.dp(if (id == R.id.minimal_more_action) 56 else 48), ui.dp(40))
                clipChildren = false
                clipToPadding = false
                addView(button, android.widget.FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), android.view.Gravity.END))
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.minimal_add_action)?.isVisible = currentProfilePage()?.canAddProfile == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.minimal_add_action -> {
                currentProfilePage()?.takeIf { it.canAddProfile }?.downloadProfile()
                true
            }
            R.id.minimal_more_action -> { showMore(); true }

            else -> super.onOptionsItemSelected(item)
        }

    private fun currentProfilePage(): EuiccManagementFragment? = pages.getOrNull(viewPager.currentItem)?.let {
        supportFragmentManager.findFragmentByTag("f${it.id}") as? EuiccManagementFragment
    }

    private fun showMore() {
        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(this, rowTitleSize = 16f, rowDetailSize = 13f, glassCards = true)
        val dialog = android.app.Dialog(this, R.style.Theme_OpenEUICC)
        val page = ui.page()
        val header = android.widget.LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, ui.dp(60)).apply {
                marginStart = -ui.dp(16)
            }
            setPaddingRelative(0, ui.dp(8), ui.dp(4), ui.dp(8))
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_minimal_back)
            imageTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_text))
            contentDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = null
            setOnClickListener { dialog.dismiss() }
        }, android.widget.LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        header.addView(ui.text(getString(R.string.minimal_more), 22f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        page.addView(header)
        val current = currentProfilePage()
        if (current != null) {
            val menu = android.widget.PopupMenu(this, requireViewById(R.id.toolbar)).menu
            current.onCreateOptionsMenu(menu, menuInflater)
            current.onPrepareOptionsMenu(menu)
            val items = (0 until menu.size()).map(menu::getItem)
                .filter { it.isVisible && it.itemId != R.id.euicc_memory_reset }
                .sortedBy { if (it.itemId == R.id.euicc_info) 0 else if (it.itemId == R.id.show_notifications) 1 else 2 }
            if (items.isNotEmpty()) {
                items.forEach { item ->
                    val card = ui.card(page, small = true)
                    val (icon, description) = when (item.itemId) {
                        R.id.euicc_info -> R.drawable.ic_minimal_sim to R.string.minimal_info_detail
                        R.id.show_notifications -> R.drawable.ic_minimal_bell to R.string.minimal_notifications_detail
                        else -> R.drawable.ic_minimal_toolkit to R.string.minimal_toolkit_detail
                    }
                    ui.row(card, item.title ?: "", getString(description), icon) {
                        if (item.itemId != R.id.euicc_info && item.itemId != R.id.show_notifications) {
                            dialog.dismiss()
                        }
                        if (!current.onOptionsItemSelected(item)) item.intent?.let { intent ->
                            runCatching { startActivity(intent) }.onFailure {
                                android.widget.Toast.makeText(this, R.string.minimal_error, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        addMoreActions(ui, page) { dialog.dismiss() }
        addMoreSettingsActions(ui, page)
        dialog.setContentView(ui.scroll(page))
        dialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            setupRootViewSystemBarInsets(window.decorView, arrayOf(ui.pageInsets(page, true)))
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }

    protected open fun addMoreActions(
        ui: im.angry.openeuicc.ui.minimal.MinimalUi,
        page: android.widget.LinearLayout,
        dismiss: () -> Unit
    ) {}

    protected open fun addMoreSettingsActions(
        ui: im.angry.openeuicc.ui.minimal.MinimalUi,
        page: android.widget.LinearLayout
    ) {
        ui.row(ui.card(page, small = true), getString(R.string.pref_settings), getString(R.string.minimal_settings_detail), R.drawable.ic_minimal_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (initialized) refresh()
    }

    override fun onInit() {
        refresh()
    }

    private fun ensureNotificationPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permissions = arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return
        requestPermissions(permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lastPhoneSlot", lastPhoneSlot)
        super.onSaveInstanceState(outState)
    }

    private suspend fun init() {
        if (!initialized) {
            loadingProgress.visibility = View.VISIBLE
            viewPager.visibility = View.GONE
            tabsContainer.visibility = View.GONE
        }
        // Prevent concurrent access with any running foreground task
        euiccChannelManagerService.waitForForegroundTask()

        // Discovery must not require permission or a successful card connection.
        val usbDevice = getSystemService(UsbManager::class.java).deviceList.values
            .firstOrNull { it.interfaces.smartCard != null }
        val hadUsb = pages.any { it.logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID }

        val newPages: MutableList<Page> = mutableListOf()

        euiccChannelManager.flowInternalEuiccPorts().onEach { (slotId, portId) ->
            Log.d(TAG, "slot $slotId port $portId")

            euiccChannelManager.flowEuiccSecureElements(slotId, portId).onEach { seId ->
                euiccChannelManager.withEuiccChannel(slotId, portId, seId) { channel ->
                    if (preferenceRepository.verboseLoggingFlow.first()) {
                        Log.d(TAG, channel.lpa.eID)
                    }
                    // Request the system to refresh the list of profiles every time we start
                    // Note that this is currently supposed to be no-op when unprivileged,
                    // but it could change in the future
                    euiccChannelManager.notifyEuiccProfilesChanged(channel.logicalSlotId)

                    val channelName = if (channel.hasMultipleSE) {
                        appContainer.customizableTextProvider.formatNonUsbChannelNameWithSeId(
                            channel.logicalSlotId,
                            channel.seId
                        )
                    } else {
                        appContainer.customizableTextProvider.formatNonUsbChannelName(channel.logicalSlotId)
                    }
                    newPages.add(newPage(channel.logicalSlotId, channelName,
                        channelKey = "$slotId:$portId:${seId.id}:${channel.lpa.eID}") {
                        appContainer.uiComponentFactory.createEuiccManagementFragment(
                            slotId,
                            portId,
                            seId
                        )
                    })
                }
            }.collect()
        }.collect()

        // If USB readers exist, add them at the very last
        // We use a wrapper fragment to handle logic specific to USB readers
        usbDevice?.let {
            newPages.add(newPage(EuiccChannelManager.USB_CHANNEL_ID, getString(R.string.usb_tab_title)) {
                UsbCcidReaderPermissionFragment()
            })
        }
        viewPager.visibility = View.VISIBLE

        val slotCount = tm.activeModemCountCompat
        repeat(slotCount) { slotId ->
            if (newPages.none { it.logicalSlotId == slotId }) {
                newPages.add(newPage(slotId,
                    appContainer.customizableTextProvider.formatNonUsbChannelName(slotId),
                    isPlaceholder = true) {
                    appContainer.uiComponentFactory.createNoEuiccPlaceholderFragment()
                })
            }
        }
        if (newPages.isEmpty()) {
            newPages.add(newPage(-1, "", isPlaceholder = true) {
                appContainer.uiComponentFactory.createNoEuiccPlaceholderFragment()
            })
        }
        tabsContainer.visibility = if (newPages.any { it.logicalSlotId != -1 }) View.VISIBLE else View.GONE

        newPages.sortBy { it.logicalSlotId }

        // Keep existing content and scroll position when the same chip is still present.
        for (index in newPages.indices) {
            val candidate = newPages[index]
            if (candidate.logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID) continue
            pages.firstOrNull {
                it.logicalSlotId == candidate.logicalSlotId && it.title == candidate.title &&
                    it.isPlaceholder == candidate.isPlaceholder && it.channelKey == candidate.channelKey &&
                    (supportFragmentManager.findFragmentByTag("f${it.id}") as? EuiccManagementFragment)
                        ?.canRetainOnSlotRefresh != false
            }?.let { newPages[index] = it }
        }
        val selectedSlot = pages.getOrNull(viewPager.currentItem)?.logicalSlotId
        val readerAdded = !hadUsb && usbDevice != null
        if (readerAdded && selectedSlot != null && selectedSlot >= 0 &&
            selectedSlot != EuiccChannelManager.USB_CHANNEL_ID) lastPhoneSlot = selectedSlot
        updatingPages = true
        val pagesChanged = pages.map { it.id } != newPages.map { it.id }
        pages.clear()
        pages.addAll(newPages)
        invalidateOptionsMenu()

        loadingProgress.visibility = View.GONE
        if (pagesChanged) pagerAdapter.notifyDataSetChanged()

        viewPager.setCurrentItem(slotAfterRefresh(pages.map { it.logicalSlotId }, selectedSlot, lastPhoneSlot, readerAdded), false)
        updatingPages = false
        if (readerAdded) {
            tabs.getTabAt(pages.indexOfFirst { it.logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID })
                ?.customView?.apply {
                    alpha = 0f
                    animate().alpha(1f).setDuration(180).start()
                }
        }

        if (pages.isNotEmpty()) {
            ensureNotificationPermissions()
        }

        ShortcutManagerCompat.setDynamicShortcuts(this, buildShortcuts().take(4))

        initialized = true
    }

    fun refreshSlots() { if (initialized) refresh() }

    internal fun onUsbReaderResult(result: EuiccChannelManager.UsbChannelOpenResult) {
        lastUsbResult = result
    }

    private val isProfileSwitchInProgress: Boolean
        get() = supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>()
            .any { it.isSwitchingProfile }

    private fun refresh(fromUsbEvent: Boolean = false, showIndicator: Boolean = true) {
        if (refreshing) {
            if (fromUsbEvent) pendingUsbRefresh = true
            return
        }
        refreshing = true
        val showRefreshIndicator = showIndicator && !isProfileSwitchInProgress
        lifecycleScope.launch {
            val indicator = pages.getOrNull(viewPager.currentItem)?.let {
                supportFragmentManager.findFragmentByTag("f${it.id}")?.view
                    ?.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_refresh)
            }
            val existingPageIds = pages.map { it.id }.toSet()
            var succeeded = false
            if (showRefreshIndicator) indicator?.isRefreshing = true
            try {
                init()
                succeeded = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh slots", e)
                android.widget.Toast.makeText(this@MainActivity, R.string.minimal_error,
                    android.widget.Toast.LENGTH_LONG).show()
            } finally {
                indicator?.isRefreshing = false
                loadingProgress.visibility = View.GONE
                refreshing = false
                if (pendingUsbRefresh) {
                    pendingUsbRefresh = false
                    refresh(true, showIndicator = false)
                }
            }
            if (succeeded) {
                pages.filter { it.id in existingPageIds }.forEach {
                    (supportFragmentManager.findFragmentByTag("f${it.id}") as? EuiccManagementFragment)
                        ?.refreshProfiles(showRefreshIndicator)
                }
            }
        }
    }

    protected open fun buildShortcuts(): List<ShortcutInfoCompat> {
        val downloadShortcut = ShortcutInfoCompat.Builder(this, "download")
            .setShortLabel(getString(R.string.profile_download))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_task_sim_card_download))
            .setIntent(Intent(this, DownloadTargetActivity::class.java).apply { action = Intent.ACTION_VIEW })
            .build()
        return listOf(downloadShortcut)
    }

    fun instantiateUsbTabs(seIds: List<EuiccChannel.SecureElementId>) {
        val existingUsbPageIndex = pages.indexOfFirst { it.logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID }
        if (existingUsbPageIndex == -1) return

        val usbPages =
            seIds.map { seId ->
                val name = if (seIds.size == 1) {
                    getString(R.string.usb_tab_title)
                } else {
                    getString(R.string.usb_tab_title_se, seId.id)
                }
                newPage(EuiccChannelManager.USB_CHANNEL_ID, name) {
                    appContainer.uiComponentFactory.createEuiccManagementFragment(
                        EuiccChannelManager.USB_CHANNEL_ID,
                        0,
                        seId
                    )
                }
            }

        if (usbPages.isEmpty()) return
        val selectedIndex = viewPager.currentItem
        updatingPages = true
        // Add before removing to avoid out-of-bounds problems
        pages.addAll(existingUsbPageIndex, usbPages)
        // Remove the old USB reader page
        pages.removeAt(existingUsbPageIndex + usbPages.size)

        if (pages.size > 1) {
            tabsContainer.visibility = View.VISIBLE
        }
        pagerAdapter.notifyDataSetChanged()
        viewPager.setCurrentItem(selectedIndex.coerceAtMost(pages.lastIndex), false)
        updatingPages = false
        invalidateOptionsMenu()
    }
}
