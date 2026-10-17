package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import net.typeblog.lpac_jni.LocalProfileInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Offline profile-owner matching, independent of enabled state and the roaming network. */
object ProfileOperatorCatalog {
    internal data class Network(val mcc: String, val mnc: String, val country: String?,
        val name: String, val brand: String, val icon: String?, val gids: List<JSONObject>)
    internal data class Match(val network: Network?, val country: String?)
    private var networks: List<Network>? = null
    private var countries: Map<String, String?> = emptyMap()
    private val bitmaps = LruCache<String, Bitmap>(24)

    @Synchronized private fun load(context: Context): List<Network> {
        networks?.let { return it }
        val catalogs = context.assets.open("operator_catalog/catalog.json").bufferedReader()
            .use { JSONArray(it.readText()) }
        val result = mutableListOf<Network>()
        val countryMap = mutableMapOf<String, String?>()
        for (i in 0 until catalogs.length()) {
            val catalog = catalogs.getJSONObject(i)
            val mcc = catalog.getString("mcc")
            // Shared MCC territories are not assigned a guessed flag.
            val country = catalog.getString("iso").takeIf { it.matches(Regex("[A-Z]{2}")) }
            countryMap[mcc] = country
            val rows = catalog.getJSONArray("operators")
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val gids = row.getJSONArray("gids")
                result.add(Network(mcc, row.getString("mnc"), country,
                    row.getString("operator"), row.getString("brand"),
                    if (row.isNull("icon")) null else row.getString("icon"),
                    List(gids.length()) { gids.getJSONObject(it) }))
            }
        }
        countries = countryMap
        networks = result
        return result
    }

    internal fun parsePlmn(hex: String): Pair<String, String>? {
        val value = hex.trim().uppercase(Locale.ROOT)
        if (!value.matches(Regex("[0-9A-F]{6}"))) return null
        val mcc = "${value[1]}${value[0]}${value[3]}"
        val mnc = "${value[5]}${value[4]}" + if (value[2] == 'F') "" else value[2]
        if ((mcc + mnc).any { it !in '0'..'9' }) return null
        return mcc to mnc
    }

    internal fun match(context: Context, profile: LocalProfileInfo): Match {
        val rows = load(context)
        val plmn = parsePlmn(profile.ownerPlmn)
        if (plmn != null) {
            val candidates = rows.filter { it.mcc == plmn.first && it.mnc == plmn.second }
            val ranked = candidates.map { row -> row to
                (nameScore(row, profile) +
                    (if (row.gids.any { matchesGid(it, profile) }) 100 else 0)) }
            val best = ranked.maxOfOrNull { it.second }
            val network = uniqueNetwork(ranked.filter { it.second == best }.map { it.first })
            val country = if (countries.containsKey(plmn.first)) countries[plmn.first]
                else ProfileBranding.country(context, profile.iccid)
            return Match(network, country)
        }
        // Only exact, unambiguous original metadata names can fill in a missing owner.
        // Never match the editable nickname or a previous profile's network cache.
        val ranked = if (profile.ownerPlmn.isBlank()) rows.map { it to nameScore(it, profile) }
            .filter { it.second > 0 } else emptyList()
        val best = ranked.maxOfOrNull { it.second }
        val network = uniqueNetwork(ranked.filter { it.second == best }.map { it.first })
        return Match(network, network?.country ?: ProfileBranding.country(context, profile.iccid))
    }

    private fun uniqueNetwork(rows: List<Network>): Network? {
        if (rows.isEmpty()) return null
        return rows.first().takeIf { rows.map { Triple(it.mcc, it.mnc, it.icon) }.distinct().size == 1 }
    }

    private fun normalizedName(value: String) = value.trim().lowercase(Locale.ROOT)
    private fun nameMatches(value: String, expected: String): Boolean {
        val name = normalizedName(value)
        return name.length >= 3 && name !in setOf("esim", "sim", "unknown", "default") &&
            name.any { it.isLetter() } && name == normalizedName(expected)
    }

    private fun nameScore(row: Network, profile: LocalProfileInfo): Int {
        val names = mutableListOf(row.name, row.brand)
        row.gids.forEach { gid ->
            for (key in listOf("profile_names", "profile_provider_names")) {
                val values = gid.optJSONArray(key) ?: continue
                for (i in 0 until values.length()) names.add(values.getString(i))
            }
        }
        return (if (names.any { nameMatches(profile.name, it) }) 40 else 0) +
            (if (names.any { nameMatches(profile.providerName, it) }) 20 else 0)
    }

    private fun matchesGid(gid: JSONObject, profile: LocalProfileInfo): Boolean {
        var compared = false
        for ((key, actual) in listOf("gid1" to profile.ownerGid1, "gid2" to profile.ownerGid2)) {
            val expected = gid.optString(key).trim()
            if (expected.isEmpty() || expected.equals("none", true)) continue
            compared = true
            if (!expected.equals(actual.trim(), true)) return false
        }
        return compared
    }

    fun branding(context: Context, profile: LocalProfileInfo): Pair<Bitmap?, String?> {
        val match = match(context, profile)
        val logo = match.network?.icon?.let { icon ->
            bitmaps.get(icon) ?: runCatching {
                val path = "operator_catalog/icons/$icon"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 128) inSampleSize *= 2
                }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                    ?.also { bitmaps.put(icon, it) }
            }.getOrNull()
        } ?: ProfileBranding.decodeLogo(profile.iconBase64)
        return logo to match.country
    }
}
