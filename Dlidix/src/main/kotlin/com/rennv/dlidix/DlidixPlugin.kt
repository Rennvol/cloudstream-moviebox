package com.rennv.dlidix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DlidixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DlidixProvider())
    }
}
