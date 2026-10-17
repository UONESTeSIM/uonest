package im.angry.openeuicc.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey

internal object UnprivilegedPreferenceKeys {
    // ---- Miscellaneous ----
    // Keep results for the original device-compatibility criteria separate from earlier probes.
    val QUICK_COMPATIBILITY_PASSED = booleanPreferencesKey("quick_compatibility_original_passed")
}

class UnprivilegedPreferenceRepository(context: Context) : PreferenceRepository(context) {
    // ---- Miscellaneous ----
    val quickCompatibilityPassedFlow = bindFlow(UnprivilegedPreferenceKeys.QUICK_COMPATIBILITY_PASSED, false)
}
