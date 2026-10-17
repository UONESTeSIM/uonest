package im.angry.openeuicc.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import im.angry.openeuicc.OpenEuiccApplication
import im.angry.openeuicc.common.R
import im.angry.openeuicc.util.*

class SettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SCREEN = "settings_screen"
    }

    private val appContainer
        get() = (application as OpenEuiccApplication).appContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        setupRootViewSystemBarInsets(
            window.decorView.rootView, arrayOf(
                this::activityToolbarInsetHandler
            ), consume = false
        )

        if (savedInstanceState != null) return
        val settingsFragment = appContainer.uiComponentFactory.createSettingsFragment()
        settingsFragment.arguments = Bundle().apply {
            putString(EXTRA_SCREEN, intent.getStringExtra(EXTRA_SCREEN))
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, settingsFragment)
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
}
