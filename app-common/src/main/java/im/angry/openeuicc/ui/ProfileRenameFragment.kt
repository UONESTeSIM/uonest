package im.angry.openeuicc.ui

import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileEditorView
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.util.*
import kotlinx.coroutines.launch
import net.typeblog.lpac_jni.LocalProfileAssistant

class ProfileRenameFragment : androidx.appcompat.app.AppCompatDialogFragment(), EuiccChannelFragmentMarker {
    companion object {
        private const val FIELD_ICCID = "iccid"
        private const val FIELD_CURRENT_NAME = "currentName"

        const val TAG = "ProfileRenameFragment"

        fun newInstance(
            slotId: Int, portId: Int, seId: EuiccChannel.SecureElementId,
            iccid: String, currentName: String
        ) = newInstanceEuicc(ProfileRenameFragment::class.java, slotId, portId, seId) {
            putString(FIELD_ICCID, iccid)
            putString(FIELD_CURRENT_NAME, currentName)
        }
    }

    private lateinit var profileRenameNewName: android.widget.EditText
    private lateinit var progress: ProgressBar
    private lateinit var editor: ProfileEditorView

    private var renaming = false

    private val iccid: String by lazy {
        requireArguments().getString(FIELD_ICCID)!!
    }

    private val currentName: String by lazy {
        requireArguments().getString(FIELD_CURRENT_NAME)!!
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        editor = ProfileEditorView(requireContext(), savedInstanceState?.getString("draft") ?: currentName,
            onSave = { if (!renaming) rename() }, onCancel = { if (!renaming) dismiss() })
        profileRenameNewName = editor.nameEditor
        progress = editor.progress
        return MinimalUi(requireContext()).scroll(editor)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", profileRenameNewName.text.toString())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return ProfileEditorDialog(requireContext(), theme).also {
            it.setCanceledOnTouchOutside(false)
        }
    }

    private fun showErrorAndCancel(@StringRes resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()

        renaming = false
        isCancelable = true
        progress.visibility = View.GONE
    }

    private fun rename() {
        renaming = true
        isCancelable = false
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE

        val newName = profileRenameNewName.text.toString().trim()

        lifecycleScope.launch {
            ensureEuiccChannelManager()
            euiccChannelManagerService.waitForForegroundTask()
            val response = euiccChannelManagerService
                .launchProfileRenameTask(slotId, portId, seId, iccid, newName).stateFlow.waitDone()

            when (response) {
                is LocalProfileAssistant.ProfileNameTooLongException -> {
                    showErrorAndCancel(R.string.profile_rename_too_long)
                }

                is LocalProfileAssistant.ProfileNameIsInvalidUTF8Exception -> {
                    showErrorAndCancel(R.string.profile_rename_encoding_error)
                }

                is Throwable -> {
                    showErrorAndCancel(R.string.profile_rename_failure)
                }

                else -> {
                    parentFragment?.notifyEuiccProfilesChanged()

                    runCatching(::dismiss)
                }
            }
        }
    }
}
