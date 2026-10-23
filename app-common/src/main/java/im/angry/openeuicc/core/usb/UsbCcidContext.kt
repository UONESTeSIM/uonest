package im.angry.openeuicc.core.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import im.angry.openeuicc.util.preferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * A wrapper over an usb device + interface, manages the lifecycle independent
 * of the APDU interface exposed to lpac-jni.
 *
 * This allows us to try multiple AIDs on each interface without opening / closing
 * the USB connection numerous times.
 */
class UsbCcidContext private constructor(
    private val conn: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    val verboseLoggingFlow: Flow<Boolean>,
    val useTpdu: Boolean
) {
    companion object {
        fun createFromUsbDevice(
            context: Context,
            usbDevice: UsbDevice,
            usbInterface: UsbInterface
        ): UsbCcidContext? = runCatching {
            val (bulkIn, bulkOut) = usbInterface.endpoints.bulkPair
            if (bulkIn == null || bulkOut == null) return@runCatching null
            val conn = context.getSystemService(UsbManager::class.java).openDevice(usbDevice)
                ?: return@runCatching null
            if (!conn.claimInterface(usbInterface, true)) {
                conn.close()
                return@runCatching null
            }

            val forceTpduMode = runBlocking { context.preferenceRepository.forceTpduModeFlow.first() }
            val useTpdu = forceTpduMode || isKnownTpduReader(usbDevice.vendorId, usbDevice.productId)

            UsbCcidContext(
                conn,
                bulkIn,
                bulkOut,
                context.preferenceRepository.verboseLoggingFlow,
                useTpdu
            )
        }.getOrNull()
    }

    /**
     * When set to false (the default), the disconnect() method does nothing.
     * This allows the separation of device disconnection from lpac-jni's APDU interface.
     */
    var allowDisconnect = false
    val cardProbe = UsbCardProbe()
    private var initialized = false
    lateinit var transceiver: UsbCcidTransceiver
    var atr: ByteArray? = null

    fun connect() = cardProbe.track {
        if (initialized) {
            return@track
        }

        val ccidDescription = UsbCcidDescription.fromRawDescriptors(conn.rawDescriptors)!!

        if (!ccidDescription.hasT0Protocol) {
            throw IllegalArgumentException("Unsupported card reader; T=0 support is required")
        }

        transceiver = UsbCcidTransceiver(conn, bulkIn, bulkOut, ccidDescription, verboseLoggingFlow)

        // Some readers report inactive + ICC_MUTE on empty-slot power-on. Ask for
        // physical presence separately; a failed/unsupported query must not imply no card.
        val slotStatus = try {
            transceiver.getSlotStatus()
        } catch (e: Exception) {
            Log.w("UsbCcidContext", "Could not query slot status; trying card activation", e)
            null
        }
        if (slotStatus != null && UsbCardProbe.isCardAbsent(slotStatus)) {
            throw UsbCcidTransceiver.UsbCcidErrorException("No card present", slotStatus)
        }

        try {
            // 6.1.1.1 PC_to_RDR_IccPowerOn (Page 20 of 40)
            // https://www.usb.org/sites/default/files/DWG_Smart-Card_USB-ICC_ICCD_rev10.pdf
            atr = transceiver.iccPowerOn().data
        } catch (e: Exception) {
            cardProbe.recordActivationFailure(e)
            Log.d("UsbCcidContext", "Card activation failed: $e")
            throw e
        }

        initialized = true
    }

    fun disconnect() {
        if (allowDisconnect) {
            conn.close()
            atr = null
            initialized = false
        }
    }
}
