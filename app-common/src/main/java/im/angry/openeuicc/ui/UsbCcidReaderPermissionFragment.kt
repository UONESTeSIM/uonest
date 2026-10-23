package im.angry.openeuicc.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.usb.UsbCardFailure
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.ui.minimal.UsbReaderStatusView
import im.angry.openeuicc.ui.minimal.UsbReaderStatusView.State
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A fragment to handle USB reader-specific permission flow. If/after
 * permission is granted, this fragment simply calls back to MainActivity
 * to instantiate the corresponding EuiccManagementFragment(s) for the USB
 * reader.
 *
 * Having this fragment allows MainActivity to be (mostly) agnostic
 * of the underlying implementation of different types of channels.
 * When permission is granted, this fragment will simply load
 * EuiccManagementFragment using its own childFragmentManager.
 *
 * Note that for now we assume there will only be one USB card reader
 * device. This is also an implicit assumption in EuiccChannelManager.
 */
class UsbCcidReaderPermissionFragment : Fragment(), OpenEuiccContextMarker {
    companion object {
        const val ACTION_USB_PERMISSION = "im.angry.openeuicc.USB_PERMISSION"
    }

    private val euiccChannelManager: EuiccChannelManager by lazy {
        (requireActivity() as MainActivity).euiccChannelManager
    }

    private val usbManager: UsbManager by lazy {
        requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                if (usbDevice != null && usbManager.hasPermission(usbDevice)) {
                    if (isResumed) loadUsbChannel()
                }
            }
        }
    }

    private lateinit var usbPendingIntent: PendingIntent

    private lateinit var statusView: UsbReaderStatusView

    private var usbDevice: UsbDevice? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    private fun loadUsbChannel() {
        if (loadJob?.isActive == true) return
        loadJob = viewLifecycleOwner.lifecycleScope.launch { tryLoadUsbChannel() }
    }

    override fun onResume() {
        super.onResume()
        loadUsbChannel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_usb_ccid_reader, container, false)

        statusView = view as UsbReaderStatusView
        statusView.onRetry = { loadUsbChannel() }
        statusView.onGrantPermission = {
            usbDevice?.let { usbManager.requestPermission(it, usbPendingIntent) }
        }

        return view
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usbPendingIntent = PendingIntent.getBroadcast(
            requireContext(), 0,
            Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                usbPermissionReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            requireContext().registerReceiver(usbPermissionReceiver, filter)
        }

    }

    override fun onDestroyView() {
        requireContext().unregisterReceiver(usbPermissionReceiver)
        super.onDestroyView()
    }

    private suspend fun tryLoadUsbChannel() {
        statusView.showState(State.LOADING)

        try {
            val activity = requireActivity() as MainActivity
            var result = activity.euiccChannelManagerService.pollUsbCard()
            while (result == null) {
                activity.euiccChannelManagerService.waitForForegroundTask()
                result = activity.euiccChannelManagerService.pollUsbCard()
            }
            val (device, canOpen, failure) = result
            activity.onUsbReaderResult(result)

            usbDevice = device

            if (device != null && !canOpen && !usbManager.hasPermission(device)) {
                statusView.showState(State.PERMISSION)
            } else if (device != null && canOpen) {
                val seIds = withContext(Dispatchers.IO) {
                    euiccChannelManager.flowEuiccSecureElements(EuiccChannelManager.USB_CHANNEL_ID, 0).toList()
                }
                if (seIds.isEmpty()) statusView.showState(State.FAILURE)
                else (requireActivity() as MainActivity).instantiateUsbTabs(seIds)
            } else {
                statusView.showState(when (failure) {
                    UsbCardFailure.NO_CARD -> State.NO_CARD
                    UsbCardFailure.CARD_UNRESPONSIVE -> State.CARD_UNRESPONSIVE
                    UsbCardFailure.UNSUPPORTED_CARD -> State.UNSUPPORTED_CARD
                    else -> State.FAILURE
                })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            statusView.showState(State.FAILURE)
        }
    }
}
