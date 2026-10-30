package io.embodit.xrayhand.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.xrayhand.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
