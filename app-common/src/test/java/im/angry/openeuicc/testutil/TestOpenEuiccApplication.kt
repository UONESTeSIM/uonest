package im.angry.openeuicc.testutil

import android.app.Service
import android.content.Context
import im.angry.openeuicc.OpenEuiccApplication
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.core.EuiccChannelManagerFactory
import im.angry.openeuicc.di.AppContainer
import im.angry.openeuicc.di.DefaultAppContainer
import im.angry.openeuicc.util.PreferenceRepository

/**
 * Application used by Robolectric tests: swaps the app container's
 * EuiccChannelManagerFactory so that the *real* EuiccChannelManagerService
 * receives a mock EuiccChannelManager (and LPA) when it asks for one.
 *
 * The mock must be installed before the service is built:
 * `TestOpenEuiccApplication.mockEuiccChannelManager = ...`
 */
class TestOpenEuiccApplication : OpenEuiccApplication() {
    companion object {
        lateinit var mockEuiccChannelManager: EuiccChannelManager
    }

    override val appContainer: AppContainer by lazy {
        TestAppContainer(this, mockEuiccChannelManager)
    }
}

class TestAppContainer(
    context: Context,
    private val mockEuiccChannelManager: EuiccChannelManager
) : DefaultAppContainer(context) {
    override val euiccChannelManagerFactory: EuiccChannelManagerFactory =
        object : EuiccChannelManagerFactory {
            override fun createEuiccChannelManager(serviceContext: Service): EuiccChannelManager =
                mockEuiccChannelManager
        }

    // Real PreferenceRepository (DataStore-backed) is fine: it returns defaults.
    override val preferenceRepository: PreferenceRepository =
        PreferenceRepository(context)
}
