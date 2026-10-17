package net.typeblog.lpac_jni

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real APDU -> C parser -> JNI path using synthetic responses only. */
@RunWith(AndroidJUnit4::class)
class ProfileOwnerParsingTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        require(value.size < 128)
        val header = if (tag > 255) bytes(tag shr 8, tag and 255) else bytes(tag)
        return header + bytes(value.size) + value
    }

    private fun readProfiles(profiles: ByteArray, check: (Long) -> Unit) {
        val response = tlv(0xBF2D, tlv(0xA0, profiles)) + bytes(0x90, 0)
        val apdu = object : ApduInterface {
            override val valid = true
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun logicalChannelOpen(aid: ByteArray) = 1
            override fun logicalChannelClose(handle: Int) = Unit
            override fun transmit(handle: Int, tx: ByteArray): ByteArray {
                // Fail if anything except a read-only ProfileInfoListRequest is attempted.
                assertArrayEquals(bytes(0xBF, 0x2D, 0), tx.copyOfRange(5, tx.size))
                return response
            }
        }
        val http = object : HttpInterface {
            override fun usePublicKeyIds(pkids: Array<String>) = Unit
            override fun transmit(url: String, tx: ByteArray, headers: Array<String>): HttpInterface.HttpResponse =
                error("Profile metadata must not require a network request")
        }
        val context = LpacJni.createContext(bytes(0xA0), apdu, http)
        try {
            LpacJni.euiccSetMss(context, 0x7F)
            val head = LpacJni.es10cGetProfilesInfo(context)
            assertNotEquals(0L, head)
            try { check(head) } finally { LpacJni.profilesFree(head) }
        } finally { LpacJni.destroyContext(context) }
    }

    @Test fun ownerAndGidsReachKotlinWithoutLeakingIntoTheNextProfile() {
        // SGP.22 OperatorId uses AUTOMATIC TAGS, not universal OCTET STRING tags.
        val owner = tlv(0x80, bytes(0x32, 0xF4, 0x01)) + tlv(0x81, bytes(0x50, 0x8F)) + tlv(0x82, bytes(0xFF, 0xFF))
        val first = tlv(0xE3, tlv(0x92, "giffgaff".toByteArray()) + tlv(0xB7, owner))
        val second = tlv(0xE3, tlv(0x92, "legacy".toByteArray()))
        readProfiles(first + second) { head ->
            assertEquals("32f401", LpacJni.profileGetOwnerPlmn(head).lowercase())
            assertEquals("508f", LpacJni.profileGetOwnerGid1(head).lowercase())
            assertEquals("ffff", LpacJni.profileGetOwnerGid2(head).lowercase())
            val next = LpacJni.profilesNext(head)
            assertEquals("legacy", LpacJni.profileGetName(next))
            assertEquals("", LpacJni.profileGetOwnerPlmn(next))
            assertEquals("", LpacJni.profileGetOwnerGid1(next))
            assertEquals(0L, LpacJni.profilesNext(next))
        }
    }

    @Test fun optionalGidsAndMalformedOwnerDoNotBreakProfileReading() {
        for (owner in listOf(tlv(0x80, bytes(0x32, 0xF4, 0x01)), tlv(0x80, bytes(0x32, 0xF4)), bytes(0x80, 3, 0x32))) {
            readProfiles(tlv(0xE3, tlv(0x92, "card".toByteArray()) + tlv(0xB7, owner))) { head ->
                assertEquals("card", LpacJni.profileGetName(head))
                assertEquals(if (owner.size == 5) "32f401" else "", LpacJni.profileGetOwnerPlmn(head).lowercase())
                assertEquals("", LpacJni.profileGetOwnerGid1(head))
                assertEquals("", LpacJni.profileGetOwnerGid2(head))
            }
        }
    }

    @Test fun gid2DoesNotBecomeGid1WhenGid1IsAbsent() {
        val owner = tlv(0x80, bytes(0x32, 0xF4, 0x01)) + tlv(0x82, bytes(0xFF))
        readProfiles(tlv(0xE3, tlv(0xB7, owner))) { head ->
            assertEquals("32f401", LpacJni.profileGetOwnerPlmn(head).lowercase())
            assertEquals("", LpacJni.profileGetOwnerGid1(head))
            assertEquals("ff", LpacJni.profileGetOwnerGid2(head).lowercase())
        }
    }
}
