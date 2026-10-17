package im.angry.openeuicc.ui.wizard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import im.angry.openeuicc.util.LPAString
import im.angry.openeuicc.util.decodeQrFromBitmap
import im.angry.openeuicc.util.use

internal fun parseActivationInput(input: String): LPAString {
    val trimmed = input.trim()
    val code = if (trimmed.startsWith("LPA:", true) || trimmed.startsWith("1$")) trimmed else {
        val uri = Uri.parse(trimmed)
        require(uri.isHierarchical) { "Invalid activation link" }
        uri.queryParameterNames.asSequence().flatMap { uri.getQueryParameters(it).asSequence() }
            .firstOrNull { it.startsWith("LPA:1$", true) }
            ?: throw IllegalArgumentException("No activation code")
    }
    return LPAString.parse(code).also { require(isValidAddress(it.address)) { "Invalid server" } }
}

/** Bound bitmap allocation before decoding photos from the system picker. */
internal fun readActivationQr(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
        options.inSampleSize *= 2
    }
    return resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.use(::decodeQrFromBitmap)
    }
}

internal fun isValidAddress(input: CharSequence): Boolean {
    if (!input.contains('.')) return false
    var fqdn = input
    var port = 443
    if (input.contains(':')) {
        val portIndex = input.lastIndexOf(':')
        fqdn = input.take(portIndex)
        port = input.substring(portIndex + 1, input.length).toIntOrNull(10) ?: 0
    }
    // see https://en.wikipedia.org/wiki/Port_(computer_networking)
    if (port !in 1..0xffff) return false
    // see https://en.wikipedia.org/wiki/Fully_qualified_domain_name
    if (fqdn.isEmpty() || fqdn.length > 255) return false
    for (part in fqdn.split('.')) {
        if (part.isEmpty() || part.length > 64) return false
        if (part.first() == '-' || part.last() == '-') return false
        for (c in part) {
            if (c.isLetterOrDigit() || c == '-') continue
            return false
        }
    }
    return true
}
