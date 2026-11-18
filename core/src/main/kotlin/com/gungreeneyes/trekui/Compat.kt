package com.gungreeneyes.trekui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings

/**
 * Platform calls newer than minSdk 23 that the shared code needs, each with its Android 6.0-7.1 fallback
 * (TUK-T14; the Java edition's `Compat`, LCA-T65). The pure parts are unit-tested in `CompatTest`.
 */
object Compat {

    /**
     * A variable font from the assets at [weight] (`wght` axis). Font variations are API 26; on 23-25 it is
     * the font's default instance, made bold for [heavy]. Falls back to condensed sans-serif when unreadable.
     */
    @JvmStatic
    fun variableFont(assets: AssetManager, path: String, weight: Int, heavy: Boolean): Typeface {
        val t: Typeface? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Typeface.Builder(assets, path).setFontVariationSettings("'wght' $weight").build()
            } else {
                val base = Typeface.createFromAsset(assets, path)
                if (heavy) Typeface.create(base, Typeface.BOLD) else base
            }
        } catch (ignored: RuntimeException) {
            null // fall through to the system face
        }
        return t ?: Typeface.create("sans-serif-condensed", if (heavy) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * True when the system animator scale is not zero: [ValueAnimator.areAnimatorsEnabled] on API 26+, the
     * "Animator duration scale" setting itself on 23-25.
     */
    @JvmStatic
    fun animatorsEnabled(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled()
        val scale = try {
            Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (ignored: RuntimeException) {
            1f // unreadable: animations on, the platform default
        }
        return animatorsEnabled(scale)
    }

    /** Animations run unless the duration scale is zero (developer options "Animation off"). */
    @JvmStatic
    fun animatorsEnabled(durationScale: Float): Boolean = durationScale > 0f

    /**
     * A `Content-Length` header as a number, or -1 when missing or not a non-negative number. Replaces
     * `URLConnection.getContentLengthLong` (API 24) and also reads lengths over 2 GB on 23.
     */
    @JvmStatic
    fun contentLength(header: String?): Long {
        val n = header?.trim()?.toLongOrNull() ?: return -1
        return if (n >= 0) n else -1
    }
}
