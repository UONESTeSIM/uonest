package im.angry.openeuicc.core.usb

enum class UsbCardFailure { NO_CARD, CARD_UNRESPONSIVE, UNSUPPORTED_CARD, READ_FAILED }

/** Retains evidence across AID attempts, including exceptions cleared by the JNI bridge. */
class UsbCardProbe {
    companion object {
        fun isCardAbsent(response: UsbCcidTransceiver.CcidDataBlock): Boolean =
            response.iccStatus == 2.toByte() &&
                (response.commandStatus == 0.toByte() ||
                    (response.commandStatus == 1.toByte() && response.bError == 0xFE.toByte()))
    }
    private var failure: UsbCardFailure? = null
    private var unsupportedApplication = false
    private var selectedApplication = false

    val result: UsbCardFailure
        get() = failure ?: if (unsupportedApplication && !selectedApplication) {
            UsbCardFailure.UNSUPPORTED_CARD
        } else {
            UsbCardFailure.READ_FAILED
        }

    fun recordActivationFailure(error: Exception) {
        // ICC_MUTE during activation cannot distinguish an empty reader that reports
        // presence incorrectly from an inserted card with bad contacts. Do not call it NO_CARD.
        if (failure == null && error is UsbCcidTransceiver.UsbCcidErrorException &&
            error.errorResponse.iccStatus == 1.toByte() &&
            error.errorResponse.commandStatus == 1.toByte() &&
            error.errorResponse.bError == 0xFE.toByte()) {
            failure = UsbCardFailure.CARD_UNRESPONSIVE
        }
    }

    fun <T> track(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (failure == null) {
            failure = if (e is UsbCcidTransceiver.UsbCcidErrorException &&
                isCardAbsent(e.errorResponse)) {
                UsbCardFailure.NO_CARD
            } else {
                UsbCardFailure.READ_FAILED
            }
        }
        throw e
    }

    fun recordOpenChannelResponse(response: ByteArray) {
        when (statusWord(response)) {
            0x9000 -> Unit
            // Logical channels / function not supported by this card.
            0x6881, 0x6A81 -> unsupportedApplication = true
            else -> failure = failure ?: UsbCardFailure.READ_FAILED
        }
    }

    fun recordSelectResponse(response: ByteArray) {
        when (statusWord(response)) {
            0x9000 -> selectedApplication = true
            0x6A82 -> unsupportedApplication = true // ISD-R application not found.
            else -> failure = failure ?: UsbCardFailure.READ_FAILED
        }
    }

    private fun statusWord(response: ByteArray): Int? =
        if (response.size < 2) null else
            ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                (response.last().toInt() and 0xFF)
}
