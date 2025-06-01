package com.minosyx

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EkinoPlugin : Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // All providers should be added in this manner
        registerMainAPI(EkinoProvider())

        openSettings = {
            val frag = BlankFragment(this)
            activity?.let { frag.show(it.supportFragmentManager, "Frag") }
        }
    }
}
