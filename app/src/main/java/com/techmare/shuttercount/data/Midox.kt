package com.techmare.shuttercount.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * The shop's Midox app: "Add to Midox" opens its Stock › Serial numbers with this camera filled in
 * (name, serial and today's shutter count in the note). Midox matches the name to a used camera in
 * its Prices; if the serial is already there, the new count goes into that one's note.
 */
object Midox {
    private const val PACKAGE = "ma.midoxshop.prices"
    private const val ACTION = "ma.midoxshop.prices.ADD_SERIAL"

    fun installed(context: Context): Boolean =
        Intent(ACTION).setPackage(PACKAGE).resolveActivity(context.packageManager) != null

    fun addSerial(context: Context, camera: String, serial: String?, count: Long) {
        val i = Intent(ACTION).setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("camera", camera)
            .putExtra("serial", serial.orEmpty())
            .putExtra("shutter", count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        try {
            context.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Update Midox to add serial numbers from here", Toast.LENGTH_LONG).show()
        }
    }
}
