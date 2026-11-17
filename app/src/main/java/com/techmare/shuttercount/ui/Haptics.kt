package com.techmare.shuttercount.ui

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/** The app's haptic vocabulary. Each cue maps to the closest system effect the phone offers. */
enum class Buzz {
    /** A light, repeatable tick: a counter wheel turning. */
    TICK,
    /** Moving between sections: a tab. */
    SEGMENT,
    /** A deliberate press: deleting a check, a camera being detected. */
    CLICK,
    /** Something worked: a count was read, a note was saved. */
    CONFIRM,
    /** Something did not work: no count found, the camera did not answer. */
    REJECT,
    SWITCH_ON,
    SWITCH_OFF,
}

/** Plays [kind] through the system's touch feedback, so it follows the phone's own haptics setting. */
fun View.buzz(kind: Buzz) {
    val sdk = Build.VERSION.SDK_INT
    performHapticFeedback(
        when (kind) {
            Buzz.TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Buzz.SEGMENT -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Buzz.CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
            Buzz.CONFIRM -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            Buzz.REJECT -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Buzz.SWITCH_ON -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            Buzz.SWITCH_OFF -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        },
    )
}

/** Opening animation, first beat: a soft, low tick as the aperture pulls in. */
fun View.buzzPullIn() {
    if (!primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f)) buzz(Buzz.TICK)
}

/** Opening animation, second beat: a short rising push as the aperture opens toward the viewer. */
fun View.buzzOpen() {
    if (!primitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f)) buzz(Buzz.CLICK)
}

/**
 * Plays one of the richer haptic primitives that phones with a good vibration motor offer (a
 * Pixel does). Sent as touch feedback, so the phone's haptics setting still applies. Returns
 * false when the phone has no such primitive, and the caller falls back to a plain cue.
 */
private fun View.primitive(id: Int, scale: Float): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return runCatching {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
        if (!vibrator.areAllPrimitivesSupported(id)) return false
        vibrator.vibrate(
            VibrationEffect.startComposition().addPrimitive(id, scale).compose(),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
        )
        true
    }.getOrDefault(false)
}
