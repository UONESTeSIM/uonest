package im.angry.openeuicc.di

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import im.angry.easyeuicc.R
import im.angry.openeuicc.core.EuiccChannel

class UnprivilegedCustomizableTextProvider(private val context: Context) : DefaultCustomizableTextProvider(context) {
    override val websiteUri: Uri
        get() = context.getString(R.string.pref_info_website_url).toUri()

    override fun formatNonUsbChannelName(logicalSlotId: Int): String =
        context.getString(R.string.channel_name_format_unpriv, logicalSlotId + 1)

    override fun formatNonUsbChannelNameWithSeId(
        logicalSlotId: Int,
        seId: EuiccChannel.SecureElementId
    ): String =
        context.getString(R.string.channel_name_format_unpriv_se, logicalSlotId + 1, seId.id)
}
