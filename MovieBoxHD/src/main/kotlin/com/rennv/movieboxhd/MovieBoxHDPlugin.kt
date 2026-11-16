package com.rennv.movieboxhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovieBoxHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieBoxHDProvider())
    }
}
