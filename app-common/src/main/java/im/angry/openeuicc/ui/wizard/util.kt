package im.angry.openeuicc.ui.wizard

import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService
import net.typeblog.lpac_jni.ProfileDownloadInput

/**
 * Launch a profile download through EuiccChannelManagerService on behalf of a UI
 * component, and auto-confirm the metadata-confirmation step (for now).
 *
 * This is a helper function in order to run tests against.
 *
 * TODO: Remove this when we move towards more testable UI components (like Jetpack Compose).
 *       Also probably won't need this when we implement real confirmation in UI.
 */
suspend fun launchProfileDownload(
    service: EuiccChannelManagerService,
    manager: EuiccChannelManager,
    logicalSlotId: Int,
    seId: EuiccChannel.SecureElementId,
    input: ProfileDownloadInput,
    autoConfirm: Boolean = true
): EuiccChannelManagerService.ForegroundTaskHandle {
    service.waitForForegroundTask()

    val (slotId, portId) = manager.withEuiccChannel(logicalSlotId, seId) { channel ->
        Pair(channel.slotId, channel.portId)
    }

    val handle = service.launchProfileDownloadTask(slotId, portId, seId, input)

    if (autoConfirm) {
        // TODO: Ask user to confirm metadata for real if we need it
        handle.backChannel.send(true)
    }

    return handle
}
