package com.techmare.shuttercount

import android.content.Intent
import com.techmare.shuttercount.ui.Tab

/** Release builds: the screen check does nothing. */
object ScreenCheck {
    fun handle(intent: Intent, vm: MainViewModel): Tab? = null
}
