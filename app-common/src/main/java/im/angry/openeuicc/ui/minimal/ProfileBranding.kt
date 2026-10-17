package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.util.Locale

object ProfileBranding {
    private var countryCodes: Map<String, String>? = null

    fun logo(context: Context, encoded: String, provider: String): Bitmap? {
        decodeLogo(encoded)?.let { return it }
        // Exact provider matching avoids assigning a brand from a user-edited nickname.
        if (!provider.trim().equals("CMLINK", ignoreCase = true)) return null
        return runCatching {
            context.assets.open("operator_logos/cmlink.png").use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    // ICCID's E.118 issuer country is not the subscription's roaming coverage.
    // Shared/geographically ambiguous and global calling codes deliberately have no default flag.
    @Synchronized fun country(context: Context, iccid: String): String? {
        val digits = iccid.trim().uppercase(Locale.ROOT).removeSuffix("F")
        if (digits.length !in 18..22 || !digits.startsWith("89") || digits.any { it !in '0'..'9' }) return null
        val codes = countryCodes ?: context.assets.open("iccid_country_codes.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate {
                val parts = it.split('=')
                parts[0] to parts[1]
            }
        }.also { countryCodes = it }
        val country = (1..3).firstNotNullOfOrNull { codes[digits.substring(2, 2 + it)] } ?: return null
        return country.takeIf { "${it.lowercase(Locale.ROOT)}.png" in context.assets.list("flags").orEmpty() }
    }

    fun decodeLogo(encoded: String): Bitmap? {
        if (encoded.isBlank() || encoded.length > 512 * 1024) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 128) {
                options.inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }
}
