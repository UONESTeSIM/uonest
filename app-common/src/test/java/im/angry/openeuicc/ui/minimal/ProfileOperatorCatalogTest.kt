package im.angry.openeuicc.ui.minimal

import android.Manifest
import androidx.appcompat.view.ContextThemeWrapper
import im.angry.openeuicc.common.R
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileOperatorCatalogTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC)
    private fun profile(owner: String = "32F401", name: String = "giffgaff", provider: String = "giffgaff") =
        LocalProfileInfo("8944101234567890123", LocalProfileInfo.State.Disabled, name, "", provider,
            "", ProfileClass.Operational, ownerPlmn = owner)

    @Test fun `disabled giffgaff resolves to O2 and UK without phone permission or network history`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        context.getSharedPreferences("profile_network_logos", 0).edit()
            .putString("chip:8944101234567890123", "china_mobile").commit()
        val card = profile()
        val match = ProfileOperatorCatalog.match(context, card)
        assertEquals("234/o2.png", match.network?.icon)
        assertEquals("GB", match.country)
        val (logo, country) = ProfileOperatorCatalog.branding(context, card)
        assertNotNull(logo)
        assertTrue(maxOf(logo!!.width, logo.height) <= 128)
        assertEquals("GB", country)
        assertEquals(match, ProfileOperatorCatalog.match(context, card.copy(state = LocalProfileInfo.State.Enabled)))
    }

    @Test fun `PLMN decoding preserves two and three digit MNCs and rejects malformed BCD`() {
        assertEquals("234" to "10", ProfileOperatorCatalog.parsePlmn("32f401"))
        assertEquals("310" to "260", ProfileOperatorCatalog.parsePlmn("130062"))
        assertEquals("310" to "026", ProfileOperatorCatalog.parsePlmn("136020"))
        for (invalid in listOf("", "23410", "32F41A", "FFFFFF", "12345678"))
            assertNull(ProfileOperatorCatalog.parsePlmn(invalid))
    }

    @Test fun `owner wins over names and editable nicknames never identify a carrier`() {
        val namedGiffgaff = profile("64F010").copy(nickName = "O2")
        assertEquals("460/china-unicom.png", ProfileOperatorCatalog.match(context, namedGiffgaff).network?.icon)
        assertEquals("CN", ProfileOperatorCatalog.match(context, namedGiffgaff).country)
        val renamed = profile("", "unrecognized", "unrecognized").copy(nickName = "giffgaff")
        assertNull(ProfileOperatorCatalog.match(context, renamed).network)
        assertNull(ProfileOperatorCatalog.branding(context, renamed).first)
    }

    @Test fun `missing owner permits exact unambiguous names but not generic or conflicting ones`() {
        assertEquals("234/o2.png", ProfileOperatorCatalog.match(context, profile("")).network?.icon)
        for (name in listOf("eSIM", "SIM", "CMLINK", "O2", "my giffgaff card"))
            assertNull(ProfileOperatorCatalog.match(context, profile("", name, name)).network)
        assertNull(ProfileOperatorCatalog.match(context, profile("bad owner")).network)
    }

    @Test fun `unknown networks do not borrow logos while known MCC still supplies country`() {
        val unknown = profile("32F499")
        assertNull(ProfileOperatorCatalog.match(context, unknown).network)
        assertEquals("GB", ProfileOperatorCatalog.match(context, unknown).country)
        val sharedCountry = profile("63F210")
        assertNull(ProfileOperatorCatalog.match(context, sharedCountry).country)
    }

    @Test fun `specific CMI GDS profile resolves a provider shared by multiple countries`() {
        val card = profile("", "CMI_GDS", "CMLINK")
        val match = ProfileOperatorCatalog.match(context, card)
        assertEquals("454/cmcc-hk.png", match.network?.icon)
        assertEquals("HK", match.country)
        assertNotNull(ProfileOperatorCatalog.branding(context, card).first)
        assertNull(ProfileOperatorCatalog.match(context, card.copy(name = "unknown")).network)
    }

    @Test fun `all imported parent icons are packaged and decodable`() {
        val catalogs = org.json.JSONArray(context.assets.open("operator_catalog/catalog.json")
            .bufferedReader().use { it.readText() })
        val checked = mutableSetOf<String>()
        for (i in 0 until catalogs.length()) {
            val rows = catalogs.getJSONObject(i).getJSONArray("operators")
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                if (row.isNull("icon")) continue
                val path = row.getString("icon")
                if (checked.add(path)) context.assets.open("operator_catalog/icons/$path").use {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(it, null, options)
                    assertTrue(path, options.outWidth > 0 && options.outHeight > 0)
                }
            }
        }
        assertTrue(checked.size > 300)
    }
}
