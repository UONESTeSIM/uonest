package im.angry.openeuicc

import android.app.Application
import im.angry.openeuicc.di.AppContainer
import im.angry.openeuicc.di.DefaultAppContainer

open class OpenEuiccApplication : Application() {
    open val appContainer: AppContainer by lazy {
        DefaultAppContainer(this)
    }

}
