package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.Locale

/**
 * Fast emulator-free screenshots: renders the home screen of every interface (8 variants, incl. CLASSIC/NEXT CONSOLE) at a table of screen
 * sizes, aspect ratios, orientations and densities with Robolectric native graphics, and writes PNGs
 * to `assets/robolectric/<interface>/<config>.png` plus `assets/robolectric/manifest.json`.
 *
 * Opt-in, because it writes ~90 images: run with the environment variable
 * `TREKUI_SCREENSHOTS=1`, e.g.
 * `TREKUI_SCREENSHOTS=1 ./gradlew :app:testDevelopDebugUnitTest --tests '*RobolectricScreenshots*'`.
 * Without it every case is skipped. `TREKUI_ASSETS_DIR` overrides the output root.
 *
 * Each case also asserts that the activity reaches RESUMED and that the render is not blank,
 * which catches interface/size combinations that crash during layout.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RobolectricScreenshots(
    private val iface: String,
    private val spec: Array<Any?>,
    private val screen: String,
    private val qualifiers: String,
) {

    @Test
    fun rendersHome() {
        assumeTrue("set TREKUI_SCREENSHOTS=1 to render screenshots", enabled())
        RuntimeEnvironment.setQualifiers(qualifiers)
        // Robolectric gives every test a fresh Application but keeps app statics: drop the cached
        // Prefs singleton so it reads this test's SharedPreferences.
        resetStatic("com.gungreeneyes.trekui.Prefs", "instance")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("trekui", Context.MODE_PRIVATE)
        val faction = spec[1] as Int
        val e = sp.edit()
            .putBoolean("welcomed", true)
            .putBoolean("bootSeq", false)
            .putBoolean("anim", false)
            .putInt("faction", faction)
            .putInt(Prefs.layoutKeyFor(Skin.COMMAND), 0).putInt(Prefs.layoutKeyFor(Skin.IMPERIAL), 0)
            .putInt(Prefs.layoutKeyFor(Skin.COLLECTIVE), 0)
        // spec[2] != null marks the alternative layout; the pref key comes from the app itself.
        if (spec[2] != null) e.putInt(Prefs.layoutKeyFor(faction), spec[3] as Int)
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertEquals("$iface: interface not applied", faction.toLong(), Skin.faction.toLong())
            val decor = controller.get().window.decorView
            val w = decor.width
            val h = decor.height
            assertTrue("$iface@$screen: window not laid out (${w}x$h)", w > 0 && h > 0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            assertTrue("$iface@$screen: render is blank", !isUniform(bmp))

            val dir = File(outputRoot(), iface)
            assertTrue("cannot create $dir", dir.isDirectory || dir.mkdirs())
            val png = File(dir, "$screen.png")
            FileOutputStream(png).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            synchronized(MANIFEST) {
                MANIFEST.add(
                    String.format(
                        Locale.US,
                        "{\"interface\":\"%s\",\"config\":\"%s\",\"qualifiers\":\"%s\",\"width\":%d,\"height\":%d,\"path\":\"robolectric/%s/%s.png\"}",
                        iface, screen, qualifiers, w, h, iface, screen,
                    ),
                )
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    companion object {
        /** name, Robolectric qualifiers (screen size in dp, orientation, density bucket). */
        @JvmField
        val SCREENS: Array<Array<String>> = arrayOf(
            arrayOf("phone-small-16x9-xhdpi", "w360dp-h640dp-port-xhdpi"),
            arrayOf("phone-5x3-mdpi", "w480dp-h800dp-port-mdpi"),
            arrayOf("phone-19.5x9-xxhdpi", "w411dp-h891dp-port-xxhdpi"),
            arrayOf("phone-20x9-xxhdpi", "w412dp-h915dp-port-xxhdpi"),
            arrayOf("phone-21x9-xxhdpi", "w360dp-h840dp-port-xxhdpi"),
            arrayOf("phone-19.5x9-xxxhdpi", "w411dp-h891dp-port-xxxhdpi"),
            arrayOf("phone-20x9-landscape", "w915dp-h412dp-land-xxhdpi"),
            arrayOf("fold-inner-6x5", "w673dp-h841dp-port-xhdpi"),
            arrayOf("tablet-7in-16x10", "w600dp-h960dp-port-xhdpi"),
            arrayOf("tablet-4x3", "w768dp-h1024dp-port-xhdpi"),
            arrayOf("tablet-16x10-landscape", "w1280dp-h800dp-land-xhdpi"),
        )

        /** name, faction, layout key (or null), layout value - mirrors androidTest's Interface enum. */
        @JvmField
        val INTERFACES: Array<Array<Any?>> = arrayOf(
            arrayOf("command", 0, null, 0), arrayOf("command_reveal", 0, "layout", 1),
            arrayOf("imperial", 1, null, 0), arrayOf("imperial_reveal", 1, "layout", 1),
            arrayOf("collective", 2, null, 0), arrayOf("collective_hub", 2, "layout", 1),
            arrayOf("classic_console", 9, null, 0), arrayOf("next_console", 10, null, 0),
        )

        private val MANIFEST = ArrayList<String>()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}@{2}")
        fun cases(): Collection<Array<Any?>> {
            val out = ArrayList<Array<Any?>>()
            for (i in INTERFACES) {
                for (s in SCREENS) out.add(arrayOf(i[0], i, s[0], s[1]))
            }
            return out
        }

        @JvmStatic
        fun enabled(): Boolean {
            val v = System.getenv("TREKUI_SCREENSHOTS")
            return !v.isNullOrEmpty() && "0" != v && !"false".equals(v, ignoreCase = true)
        }

        /** assets/robolectric under the repository root (the test JVM runs in app/). */
        @JvmStatic
        fun outputRoot(): File {
            val override = System.getenv("TREKUI_ASSETS_DIR")
            val root = if (!override.isNullOrEmpty()) {
                File(override)
            } else {
                val dir = File(System.getProperty("user.dir")!!).absoluteFile
                File(if (dir.name == "app") dir.parentFile else dir, "assets")
            }
            return File(root, "robolectric")
        }

        @JvmStatic
        fun resetStatic(cls: String, field: String) {
            try {
                val f = Class.forName(cls).getDeclaredField(field)
                f.isAccessible = true
                f.set(null, null)
            } catch (ignored: ReflectiveOperationException) {
                // field renamed/removed: nothing cached to reset
            }
        }

        /** True if every sampled pixel has the same colour (nothing was drawn). */
        @JvmStatic
        fun isUniform(b: Bitmap): Boolean {
            val first = b.getPixel(0, 0)
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    if (b.getPixel(x, y) != first) return false
                    x += maxOf(1, b.width / 40)
                }
                y += maxOf(1, b.height / 40)
            }
            return true
        }

        @JvmStatic
        @AfterClass
        fun writeManifest() {
            if (!enabled() || MANIFEST.isEmpty()) return
            val root = outputRoot()
            if (!root.isDirectory && !root.mkdirs()) return
            FileOutputStream(File(root, "manifest.json")).use { out ->
                out.write(("[\n  " + MANIFEST.joinToString(",\n  ") + "\n]\n").toByteArray(Charsets.UTF_8))
            }
        }
    }
}
