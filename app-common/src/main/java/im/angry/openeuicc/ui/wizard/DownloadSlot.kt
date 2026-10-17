package im.angry.openeuicc.ui.wizard

import im.angry.openeuicc.core.EuiccChannel

/** Stable slot/secure-element encoding shared by add and download screens. */
internal fun encodeSyntheticSlotId(logicalSlotId: Int, seId: EuiccChannel.SecureElementId): Int =
    (logicalSlotId shl 16) + seId.id

internal fun decodeSyntheticSlotId(id: Int): Pair<Int, EuiccChannel.SecureElementId> =
    Pair(id shr 16, EuiccChannel.SecureElementId.createFromInt(id and 0xFF))
