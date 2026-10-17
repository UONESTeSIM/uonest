package im.angry.openeuicc.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileDeleteView
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.util.*

class ProfileDeleteFragment : DialogFragment(), EuiccChannelFragmentMarker {
    companion object {
        const val TAG = "ProfileDeleteFragment"
        private const val FIELD_ICCID = "iccid"
        private const val FIELD_NAME = "name"

        fun newInstance(slotId: Int, portId: Int, seId: EuiccChannel.SecureElementId, iccid: String, name: String) =
            newInstanceEuicc(ProfileDeleteFragment::class.java, slotId, portId, seId) {
                putString(FIELD_ICCID, iccid)
                putString(FIELD_NAME, name)
            }
    }

    private val iccid by lazy {
        requireArguments().getString(FIELD_ICCID)!!
    }

    private val name by lazy {
        requireArguments().getString(FIELD_NAME)!!
    }

    private lateinit var content: ProfileDeleteView
    private var deleting = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        content = ProfileDeleteView(requireContext(), getString(R.string.profile_delete_confirm, name),
            title = getString(R.string.profile_delete_title),
            onDelete = { if (!deleting) delete() }, onCancel = { if (!deleting) dismiss() })
        content.deleteButton.setText(R.string.profile_delete_action)
        content.titleView.typeface = android.graphics.Typeface.create(
            "sans-serif", android.graphics.Typeface.BOLD)
        content.messageView.apply {
            gravity = android.view.Gravity.CENTER
            textSize = 14f
        }
        return ProfileEditorDialog(requireContext()).apply {
            setContentView(MinimalUi(requireContext()).scroll(content))
        }
    }

    private fun delete() {
        deleting = true
        requireDialog().setCanceledOnTouchOutside(false)
        isCancelable = false
        content.deleteButton.isEnabled = false
        content.cancelButton.isEnabled = false

        val parent = requireParentFragment() as EuiccManagementFragment
        dismiss()
        parent.deleteProfile(iccid)
    }
}
