package com.rennv.nontonwok

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NontonWokPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NontonWokProvider())
    }
}
