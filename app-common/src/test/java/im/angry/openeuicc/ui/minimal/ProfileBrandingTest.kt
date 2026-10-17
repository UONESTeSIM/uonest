package im.angry.openeuicc.ui.minimal

import android.graphics.Bitmap
import android.util.Base64
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import im.angry.openeuicc.common.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileBrandingTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC)

    @Test fun `issuer country is automatic but shared and global codes stay unknown`() {
        assertEquals("HK", ProfileBranding.country(context, "89852342022498371227"))
        assertEquals("CN", ProfileBranding.country(context, "8986001234567890123"))
        assertEquals("JP", ProfileBranding.country(context, "8981101234567890123F"))
        assertNull(ProfileBranding.country(context, "8914801234567890123"))
        assertNull(ProfileBranding.country(context, "8988301234567890123"))
        assertNull(ProfileBranding.country(context, "not-an-iccid"))
    }

    @Test fun `legacy manual flags and hidden flags cannot override issuer country`() {
        val iccid = "89852342022498371227"
        val prefs = context.getSharedPreferences("profile_icons", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("chip:$iccid", "JP").commit()
        assertEquals("HK", ProfileBranding.country(context, iccid))
        prefs.edit().putString("chip:$iccid", "").commit()
        assertEquals("HK", ProfileBranding.country(context, iccid))
        prefs.edit().putString("chip:8988301234567890123", "JP").commit()
        assertNull(ProfileBranding.country(context, "8988301234567890123"))
        prefs.edit().clear().commit()
    }

    @Test fun `valid operator image is scaled and malformed images fall back safely`() {
        val original = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        original.compress(Bitmap.CompressFormat.PNG, 100, output)
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val decoded = ProfileBranding.decodeLogo(encoded)!!
        assertEquals(128, decoded.width)
        assertEquals(64, decoded.height)
        assertEquals(128, ProfileBranding.logo(context, encoded, "CMLINK")!!.width)
        assertNotNull(ProfileBranding.logo(context, "", "cmlink"))
        assertNull(ProfileBranding.logo(context, "", "unknown provider"))
        assertNull(ProfileBranding.decodeLogo("not base64!"))
        assertNull(ProfileBranding.decodeLogo("A".repeat(512 * 1024 + 1)))
        val card = ProfileCardView(context)
        card.setBranding(encoded, "HK")
        val badge = (card.name.parent as android.view.ViewGroup).getChildAt(0)
        assertEquals(View.VISIBLE, badge.visibility)
        // RecyclerView reuse must clear a previous profile's logo and country.
        card.setBranding("", null)
        assertEquals(View.GONE, badge.visibility)
        assertEquals(1, card.icon.childCount)
    }
}
