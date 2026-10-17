package im.angry.openeuicc.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState

class DownloadWizardProgressFragment : DownloadWizardActivity.DownloadWizardStepFragment() {
    private lateinit var animation: DownloadAnimationView
    private var isDone = false
    private var returnHomeJob: Job? = null

    override val keepScreenOn = true
    override val hasNext: Boolean get() = isDone && state.downloadError == null
    override val hasPrev: Boolean get() = false
    override val nextButtonTextRes: Int
        get() = R.string.download_animation_done

    override fun createNextFragment(): DownloadWizardActivity.DownloadWizardStepFragment? =
        null

    override fun createPrevFragment(): DownloadWizardActivity.DownloadWizardStepFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_progress, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        animation = view.requireViewById(R.id.download_animation)
        val progress = view as DownloadProgressView
        progress.returnHome.setOnClickListener { (requireActivity() as DownloadWizardActivity).returnHome() }
        hideProgressBar()
        // One subscription per view, including while paused. Recreating the view
        // recovers the existing task instead of starting another download.
        viewLifecycleOwner.lifecycleScope.launch {
            ensureEuiccChannelManager()
            val handle = startDownloadOrSubscribe()
            if (handle == null) {
                requireActivity().finish()
                return@launch
            }
            handle.stateFlow.collect { task ->
                when (task) {
                    is EuiccChannelManagerService.ForegroundTaskState.Done -> {
                        state.downloadError = task.error?.let { error ->
                            error as? LocalProfileAssistant.ProfileDownloadException
                                ?: LocalProfileAssistant.ProfileDownloadException(
                                    error.toString(), null, null, null, null
                                )
                        }
                        progress.showResult(if (task.error == null)
                            DownloadAnimationView.Result.Success else DownloadAnimationView.Result.Failure, state.downloadError)
                        isDone = true
                        refreshButtons()
                        if (task.error == null && returnHomeJob == null) {
                            returnHomeJob = viewLifecycleOwner.lifecycleScope.launch {
                                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                    delay(2000)
                                    requireActivity().finish()
                                }
                            }
                        }
                    }
                    is EuiccChannelManagerService.ForegroundTaskState.InProgress -> {
                        progress.showResult(DownloadAnimationView.Result.Downloading)
                        (task.context as? ProfileDownloadState)?.let { updateProgress(it) }
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onDestroyView() {
        returnHomeJob?.cancel()
        returnHomeJob = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        animation.setActive(true)
    }

    override fun onPause() {
        animation.setActive(false)
        super.onPause()
    }

    private fun updateProgress(progress: ProfileDownloadState) {
        (requireView() as DownloadProgressView).showStage(getString(when (progress) {
            is ProfileDownloadState.Preparing -> R.string.download_wizard_progress_step_preparing
            is ProfileDownloadState.Connecting -> R.string.download_wizard_progress_step_connecting
            is ProfileDownloadState.Authenticating -> R.string.download_wizard_progress_step_authenticating
            is ProfileDownloadState.ConfirmingDownload -> R.string.download_progress_confirming
            is ProfileDownloadState.Downloading -> R.string.download_wizard_progress_step_downloading
            is ProfileDownloadState.Finalizing -> R.string.download_wizard_progress_step_finalizing
        }))
    }
    private suspend fun startDownloadOrSubscribe(): EuiccChannelManagerService.ForegroundTaskHandle? =
        if (state.downloadStarted) {
            // This will also return null if task ID is -1 (uninitialized), too
            euiccChannelManagerService.recoverForegroundTaskSubscriber(state.downloadTaskID)
        } else {
            val (logicalSlotId, seId) = decodeSyntheticSlotId(state.selectedSyntheticSlotId)

            // Set started to true even before we start -- in case we get killed in the middle
            state.downloadStarted = true

            // NOTE: Keep using launchProfileDownload() here; the UI ↔ service
            // contract it implements (launch + auto-confirm metadata via the back
            // channel) is covered by DownloadTaskLauncherTest.
            val ret = launchProfileDownload(
                euiccChannelManagerService,
                euiccChannelManager,
                logicalSlotId,
                seId,
                ProfileDownloadInput(state.smdp, state.matchingId, state.imei, state.confirmationCode)
            )

            state.downloadTaskID = ret.taskId

            ret
        }

}
