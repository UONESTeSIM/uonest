package im.angry.openeuicc.ui

import im.angry.openeuicc.ui.minimal.ProfileCardView
import im.angry.openeuicc.ui.minimal.ProfileSwitchDialog
import im.angry.openeuicc.ui.minimal.ProfileOperatorCatalog
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.ui.wizard.AddEsimActivity
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass

open class EuiccManagementFragment : Fragment(), EuiccProfilesChangedListener,
    EuiccChannelFragmentMarker {
    companion object {
        const val TAG = "EuiccManagementFragment"

        fun newInstance(
            slotId: Int,
            portId: Int,
            seId: EuiccChannel.SecureElementId
        ): EuiccManagementFragment =
            newInstanceEuicc(EuiccManagementFragment::class.java, slotId, portId, seId)
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var profileList: RecyclerView
    private var logicalSlotId: Int = -1
    private lateinit var eid: String
    private var enabledProfile: LocalProfileInfo? = null

    private val adapter = EuiccProfileAdapter()
    // Marker for when this fragment might enter an invalid state
    // e.g. after a failed enable / disable operation
    private var switchingProfile = false
    private var deletingProfile = false
    internal val isSwitchingProfile: Boolean get() = switchingProfile || deletingProfile
    private var switchDialog: ProfileSwitchDialog? = null
    private var invalid = false
    private var hasReadProfiles = false
    internal val canAddProfile: Boolean get() = view != null && hasReadProfiles && !invalid && logicalSlotId != -1
    internal val canRetainOnSlotRefresh: Boolean get() = !invalid

    // Subscribe to settings we care about outside of coroutine contexts while initializing
    // This gives us access to the "latest" state without having to launch coroutines
    private lateinit var disableSafeguardFlow: StateFlow<Boolean>

    private lateinit var unfilteredProfileListFlow: StateFlow<Boolean>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_euicc, container, false)

        swipeRefresh = view.requireViewById(R.id.swipe_refresh)
        im.angry.openeuicc.ui.minimal.MinimalUi(requireContext()).styleHomeRefresh(swipeRefresh)
        fab = view.requireViewById(R.id.fab)
        profileList = view.requireViewById(R.id.profile_list)
        val origFabMarginRight = (fab.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        val origFabMarginBottom = (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        setupRootViewSystemBarInsets(
            view, arrayOf(
                mainViewPaddingInsetHandler(profileList),
                { insets ->
                    fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        rightMargin = origFabMarginRight + insets.right
                        bottomMargin = origFabMarginBottom + insets.bottom
                    }
                }
            ))


        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh.setOnRefreshListener { refresh() }
        profileList.adapter = adapter
        profileList.layoutManager =
            LinearLayoutManager(view.context, LinearLayoutManager.VERTICAL, false)

        fab.visibility = View.GONE
    }

    fun downloadProfile() {
        if (!canAddProfile || isSwitchingProfile || swipeRefresh.isRefreshing) return
        startActivity(AddEsimActivity.newIntent(requireContext(), logicalSlotId, seId))
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onEuiccProfilesChanged() {
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_euicc, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.show_notifications).isVisible =
            logicalSlotId != -1
        menu.findItem(R.id.euicc_info).isVisible =
            logicalSlotId != -1
        menu.findItem(R.id.euicc_memory_reset).isVisible =
            enabledProfile == null
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.show_notifications -> {
            Intent(requireContext(), NotificationsActivity::class.java).apply {
                putExtra("logicalSlotId", logicalSlotId)
                putExtra("seId", seId)
                startActivity(this)
            }
            true
        }

        R.id.euicc_info -> {
            Intent(requireContext(), EuiccInfoActivity::class.java).apply {
                putExtra("logicalSlotId", logicalSlotId)
                putExtra("seId", seId)
                startActivity(this)
            }
            true
        }

        R.id.euicc_memory_reset -> {
            EuiccMemoryResetFragment.newInstance(slotId, portId, seId, eid)
                .show(childFragmentManager, EuiccMemoryResetFragment.TAG)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    protected open suspend fun onCreateFooterViews(
        parent: ViewGroup,
        profiles: List<LocalProfileInfo>
    ): List<View> =
        if (profiles.isEmpty()) {
            val view = layoutInflater.inflate(R.layout.footer_no_profile, parent, false)
            listOf(view)
        } else {
            listOf()
        }

    internal fun refreshProfiles(showIndicator: Boolean) = refresh(showIndicator)

    private fun refresh(showIndicator: Boolean = true) {
        if (invalid || isSwitchingProfile) return
        if (showIndicator) swipeRefresh.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                doRefresh()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                hasReadProfiles = false
                android.util.Log.w(TAG, "Could not refresh profiles", e)
                context?.let { Toast.makeText(it, R.string.profile_read_temporarily_failed, Toast.LENGTH_LONG).show() }
            } finally {
                swipeRefresh.isRefreshing = false
                activity?.invalidateOptionsMenu()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    protected open suspend fun doRefresh() {
        ensureEuiccChannelManager()
        euiccChannelManagerService.waitForForegroundTask()

        if (!::disableSafeguardFlow.isInitialized) {
            disableSafeguardFlow =
                preferenceRepository.disableSafeguardFlow.stateIn(lifecycleScope)
        }
        if (!::unfilteredProfileListFlow.isInitialized) {
            unfilteredProfileListFlow =
                preferenceRepository.unfilteredProfileListFlow.stateIn(lifecycleScope)
        }

        val profiles = withEuiccChannel { channel ->
            logicalSlotId = channel.logicalSlotId
            eid = channel.lpa.eID
            enabledProfile = channel.lpa.profiles.enabled
            euiccChannelManager.notifyEuiccProfilesChanged(channel.logicalSlotId)
            if (unfilteredProfileListFlow.value)
                channel.lpa.profiles
            else
                channel.lpa.profiles.operational
        }

        withContext(Dispatchers.Main) {
            profileList.setPadding(profileList.paddingLeft,
                if (profiles.isEmpty()) 0 else (6 * resources.displayMetrics.density).toInt(),
                profileList.paddingRight, profileList.paddingBottom)
            adapter.profiles = profiles
            adapter.footerViews = onCreateFooterViews(profileList, profiles)
            adapter.notifyDataSetChanged()
            swipeRefresh.isRefreshing = false
            hasReadProfiles = true
            activity?.invalidateOptionsMenu()
        }
    }

    private suspend fun showSwitchFailureText() = withContext(Dispatchers.Main) {
        val resId = R.string.toast_profile_enable_failed
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        hasReadProfiles = false
        activity?.invalidateOptionsMenu()
        switchDialog?.dismiss()
        switchDialog = null
        super.onDestroyView()
    }

    private fun enableOrDisableProfile(iccid: String, enable: Boolean) {
        if (isSwitchingProfile) return
        switchingProfile = true
        fab.isEnabled = false
        val dialog = ProfileSwitchDialog(requireContext())
        switchDialog = dialog
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ensureEuiccChannelManager()
                euiccChannelManagerService.waitForForegroundTask()

                val err = euiccChannelManagerService
                    .launchProfileSwitchTask(
                        slotId, portId, seId, iccid, enable,
                        reconnectTimeoutMillis = 30 * 1000
                    )
                    .stateFlow.waitDone()

                if (err != null) dialog.dismiss()
                when (err) {
                    null -> {}
                    is EuiccChannelManagerService.SwitchingProfilesRefreshException -> {
                        // This is only really fatal for internal eSIMs
                        if (!isUsb) {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                                    .setMessage(R.string.profile_switch_did_not_refresh)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
                                    .setOnDismissListener { _ -> requireActivity().finish() }
                                    .show()
                            }
                        }
                    }

                    is TimeoutCancellationException -> {
                        withContext(Dispatchers.Main) {
                            // Prevent this Fragment from being used again
                            invalid = true
                            activity?.invalidateOptionsMenu()
                            // Timed out waiting for SIM to come back online, we can no longer assume that the LPA is still valid
                            AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                                .setMessage(appContainer.customizableTextProvider.profileSwitchingTimeoutMessage)
                                .setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
                                .setOnDismissListener { _ -> requireActivity().finish() }
                                .show()
                        }
                    }

                    else -> showSwitchFailureText()
                }

                if (!invalid) {
                    try {
                        doRefresh()
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Could not read profiles after switching", e)
                        Toast.makeText(requireContext(), R.string.profile_read_temporarily_failed, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not switch profile", e)
                showSwitchFailureText()
            } finally {
                dialog.dismiss()
                if (switchDialog === dialog) switchDialog = null
                switchingProfile = false
                fab.isEnabled = true
            }
        }
    }

    internal fun deleteProfile(iccid: String) {
        if (invalid || isSwitchingProfile) return
        deletingProfile = true
        fab.isEnabled = false
        val dialog = ProfileSwitchDialog(requireContext())
        switchDialog = dialog
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ensureEuiccChannelManager()
                euiccChannelManagerService.waitForForegroundTask()
                val error = euiccChannelManagerService
                    .launchProfileDeleteTask(slotId, portId, seId, iccid)
                    .stateFlow.waitDone()
                if (error != null) {
                    Toast.makeText(requireContext(), R.string.task_profile_delete_failure, Toast.LENGTH_LONG).show()
                }
                try {
                    doRefresh()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Could not read profiles after deletion", e)
                    Toast.makeText(requireContext(), R.string.profile_read_temporarily_failed, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not delete profile", e)
                Toast.makeText(requireContext(), R.string.task_profile_delete_failure, Toast.LENGTH_LONG).show()
            } finally {
                dialog.dismiss()
                if (switchDialog === dialog) switchDialog = null
                deletingProfile = false
                fab.isEnabled = true
            }
        }
    }

    protected open fun populatePopupWithProfileActions(
        popup: PopupMenu,
        profile: LocalProfileInfo
    ) {
        popup.inflate(R.menu.profile_options)
        popup.menu.findItem(R.id.rename).setTitle(R.string.minimal_edit)
        if (!profile.isEnabled) return
        popup.menu.findItem(R.id.enable).isVisible = false
        popup.menu.findItem(R.id.delete).isVisible = false

        // We hide the disable option by default to avoid "bricking" some cards that won't get
        // recognized again by the phone's modem. However, we don't have that worry if we are
        // accessing it through a USB card reader, or when the user explicitly opted in
        if (!isUsb && !disableSafeguardFlow.value) return
        popup.menu.findItem(R.id.disable).isVisible = true
    }

    sealed class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        enum class Type(val value: Int) {
            PROFILE(0),
            FOOTER(1);

            companion object {
                fun fromInt(value: Int) =
                    entries.first { it.value == value }
            }
        }
    }

    inner class FooterViewHolder : ViewHolder(FrameLayout(requireContext())) {
        init {
            itemView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun attach(view: View) {
            view.parent?.let { (it as ViewGroup).removeView(view) }
            itemView.updateLayoutParams {
                height = if (view.id == R.id.no_profile_container)
                    ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            (itemView as FrameLayout).apply {
                removeAllViews()
                addView(view)
            }
        }

        fun detach() {
            (itemView as FrameLayout).removeAllViews()
        }
    }

    inner class ProfileViewHolder(private val card: ProfileCardView) : ViewHolder(card) {
        private lateinit var profile: LocalProfileInfo
        private var canEnable = false

        init {
            card.onEdit = { performAction(R.id.rename) }
            card.onDelete = { performAction(R.id.delete) }
        }

        fun setProfile(profile: LocalProfileInfo) {
            this.profile = profile
            card.close()
            card.name.text = profile.displayName
            card.provider.text = profile.providerName
            card.provider.isVisible = card.provider.text.isNotBlank()
            card.setIccid(profile.iccid)
            card.profileClass.isVisible = unfilteredProfileListFlow.value
            card.profileClass.setText(when (profile.profileClass) {
                ProfileClass.Testing -> R.string.profile_class_testing
                ProfileClass.Provisioning -> R.string.profile_class_provisioning
                ProfileClass.Operational -> R.string.profile_class_operational
            })
            val (logo, country) = ProfileOperatorCatalog.branding(requireContext(), profile)
            card.setBranding(logo, country)
            card.toggle.setOnCheckedChangeListener(null)
            card.toggle.isChecked = profile.isEnabled
            card.toggle.contentDescription = getString(R.string.minimal_profile_toggle, profile.displayName)
            card.canDelete = !profile.isEnabled
            var updating = false
            card.toggle.setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    updating = true
                    card.toggle.isChecked = profile.isEnabled
                    updating = false
                    performAction(if (checked) R.id.enable else R.id.disable)
                }
            }
        }
        fun setEnabledProfile(enabledProfile: LocalProfileInfo?) {
            canEnable = enabledProfile == null || enabledProfile.profileClass == profile.profileClass
        }
        private fun actions() = PopupMenu(requireContext(), card).apply {
            populatePopupWithProfileActions(this, profile)
        }
        private fun performAction(id: Int) {
            if (invalid || isSwitchingProfile || swipeRefresh.isRefreshing) return
            val item = actions().menu.findItem(id)
            if (item != null && item.isVisible && item.isEnabled) onMenuItemClicked(item)
            else Toast.makeText(requireContext(), R.string.minimal_protected, Toast.LENGTH_LONG).show()
        }

        private fun onMenuItemClicked(item: MenuItem): Boolean =
            when (item.itemId) {
                R.id.enable -> {
                    if (canEnable) {
                        enableOrDisableProfile(profile.iccid, true)
                    } else {
                        val resId = R.string.toast_profile_enable_cross_class
                        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG)
                            .show()
                    }
                    true
                }

                R.id.disable -> {
                    enableOrDisableProfile(profile.iccid, false)
                    true
                }

                R.id.rename -> {
                    ProfileRenameFragment.newInstance(
                        slotId,
                        portId,
                        seId,
                        profile.iccid,
                        profile.displayName
                    )
                        .show(childFragmentManager, ProfileRenameFragment.TAG)
                    true
                }

                R.id.delete -> {
                    ProfileDeleteFragment.newInstance(
                        slotId,
                        portId,
                        seId,
                        profile.iccid,
                        profile.displayName
                    )
                        .show(childFragmentManager, ProfileDeleteFragment.TAG)
                    true
                }

                else -> false
            }
    }

    inner class EuiccProfileAdapter : RecyclerView.Adapter<ViewHolder>() {
        var profiles: List<LocalProfileInfo> = listOf()
        var footerViews: List<View> = listOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            when (ViewHolder.Type.fromInt(viewType)) {
                ViewHolder.Type.PROFILE -> {
                    ProfileViewHolder(ProfileCardView(parent.context))
                }

                ViewHolder.Type.FOOTER -> {
                    FooterViewHolder()
                }
            }

        override fun getItemViewType(position: Int): Int =
            when {
                position < profiles.size -> {
                    ViewHolder.Type.PROFILE.value
                }

                position >= profiles.size && position < profiles.size + footerViews.size -> {
                    ViewHolder.Type.FOOTER.value
                }

                else -> -1
            }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (holder) {
                is ProfileViewHolder -> {
                    holder.setProfile(profiles[position])
                    holder.setEnabledProfile(profiles.enabled)
                }

                is FooterViewHolder -> {
                    holder.attach(footerViews[position - profiles.size])
                }
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            if (holder is FooterViewHolder) {
                holder.detach()
            }
        }

        override fun getItemCount(): Int = profiles.size + footerViews.size
    }
}
