package im.angry.openeuicc.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import im.angry.openeuicc.ui.minimal.ConfirmationCodeView
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog

class DownloadWizardConfirmationFragment : DownloadWizardActivity.DownloadWizardStepFragment() {
    private var confirmationDialog: ProfileEditorDialog? = null
    override val hasNext: Boolean get() = isAdded && !state.confirmationCode.isNullOrBlank()
    override val hasPrev = true
    override fun createNextFragment() = DownloadWizardProgressFragment()
    override fun createPrevFragment(): DownloadWizardActivity.DownloadWizardStepFragment? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideProgressBar()
    }

    override fun onStart() {
        super.onStart()
        if (confirmationDialog != null) return
        val activity = requireActivity() as DownloadWizardActivity
        val dialog = ProfileEditorDialog(requireContext())
        val form = ConfirmationCodeView(requireContext(), state.confirmationCode.orEmpty(),
            onCodeChanged = { state.confirmationCode = it.ifBlank { null } },
            onContinue = {
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                activity.continueConfirmedDownload()
            })
        dialog.setContentView(MinimalUi(requireContext()).scroll(form))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { activity.finish() }
        confirmationDialog = dialog
        dialog.show()
    }

    override fun onDestroyView() {
        confirmationDialog?.setOnCancelListener(null)
        confirmationDialog?.dismiss()
        confirmationDialog = null
        super.onDestroyView()
    }
}
