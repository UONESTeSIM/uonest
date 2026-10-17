package im.angry.openeuicc.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import im.angry.easyeuicc.R
import im.angry.openeuicc.di.UnprivilegedUiComponentFactory
import im.angry.openeuicc.util.*

class QuickCompatibilityActivity : AppCompatActivity(), OpenEuiccContextMarker {
    companion object {
        const val EXTRA_MANUAL_CHECK = "manual_check"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quick_compatibility)

        if (savedInstanceState != null) return

        val quickCompatibilityFragment =
            (appContainer.uiComponentFactory as UnprivilegedUiComponentFactory)
                .createQuickCompatibilityFragment()

        supportFragmentManager.beginTransaction()
            .replace(R.id.quick_compatibility_container, quickCompatibilityFragment)
            .commit()
    }
}
