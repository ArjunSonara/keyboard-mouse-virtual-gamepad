package com.virtualpad.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * High-End Tactile Haptic Engine for VirtualPad.
 * Uses both ViewRootImpl hardware haptic constants (prebaked vendor drivers)
 * and direct Vibrator with modern VibrationAttributes (USAGE_TOUCH / USAGE_GAME).
 */
class HapticHelper(private val context: Context, var targetView: View? = null) {

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Standard button click: crisp, tactile mechanical switch feel.
     */
    fun click() {
        if (!HudConfig.isHapticEnabled(context)) return
        val intensity = HudConfig.getHapticIntensity(context)

        // 1. Native View-level hardware tap (bypasses system mute via flags)
        try {
            targetView?.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        // 2. Direct Vibrator pulse with USAGE_TOUCH
        vibrateDirect(
            durationMs = 38L,
            amplitudePct = intensity * 0.85f,
            predefined = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_CLICK else -1
        )
    }

    /**
     * Heavy trigger pull (LT / RT / Mouse Clicks): punchy, deep recoil impulse.
     */
    fun heavyClick() {
        if (!HudConfig.isHapticEnabled(context)) return
        val intensity = HudConfig.getHapticIntensity(context)

        // 1. Native View-level heavy feedback
        try {
            targetView?.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        // 2. Direct Vibrator pulse with high amplitude
        vibrateDirect(
            durationMs = 65L,
            amplitudePct = intensity * 1.0f,
            predefined = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_HEAVY_CLICK else -1
        )
    }

    /**
     * D-Pad directional navigation: light, snappy micro-tick.
     */
    fun tick() {
        if (!HudConfig.isHapticEnabled(context)) return
        val intensity = HudConfig.getHapticIntensity(context)

        try {
            targetView?.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        vibrateDirect(
            durationMs = 24L,
            amplitudePct = intensity * 0.60f,
            predefined = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_TICK else -1
        )
    }

    /**
     * Analog Stick outer deadzone/edge bump: tactile boundary feel.
     */
    fun edgeBump() {
        if (!HudConfig.isHapticEnabled(context)) return
        val intensity = HudConfig.getHapticIntensity(context)

        try {
            targetView?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        vibrateDirect(
            durationMs = 45L,
            amplitudePct = intensity * 0.75f,
            predefined = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_CLICK else -1
        )
    }

    private fun vibrateDirect(durationMs: Long, amplitudePct: Float, predefined: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            val amp = (amplitudePct.coerceIn(0.15f, 1.0f) * 255).toInt().coerceIn(30, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, amp)
                } else if (predefined >= 0) {
                    VibrationEffect.createPredefined(predefined)
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build()
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, amp)
                } else if (predefined >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(predefined)
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect, audioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
