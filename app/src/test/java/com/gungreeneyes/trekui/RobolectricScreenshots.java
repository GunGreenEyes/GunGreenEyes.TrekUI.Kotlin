package com.gungreeneyes.trekui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Fast emulator-free screenshots: renders the home screen of every interface (8 variants, incl. CLASSIC/NEXT CONSOLE) at a table of screen
 * sizes, aspect ratios, orientations and densities with Robolectric native graphics, and writes PNGs
 * to {@code assets/robolectric/<interface>/<config>.png} plus {@code assets/robolectric/manifest.json}.
 *
 * <p>Opt-in, because it writes ~90 images: run with the environment variable
 * {@code TREKUI_SCREENSHOTS=1}, e.g.
 * {@code TREKUI_SCREENSHOTS=1 ./gradlew :app:testDevelopDebugUnitTest --tests '*RobolectricScreenshots*'}.
 * Without it every case is skipped. {@code TREKUI_ASSETS_DIR} overrides the output root.</p>
 *
 * <p>Each case also asserts that the activity reaches RESUMED and that the render is not blank,
 * which catches interface/size combinations that crash during layout.</p>
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class RobolectricScreenshots {

    /** name, Robolectric qualifiers (screen size in dp, orientation, density bucket). */
    static final String[][] SCREENS = {
            {"phone-small-16x9-xhdpi", "w360dp-h640dp-port-xhdpi"},
            {"phone-5x3-mdpi", "w480dp-h800dp-port-mdpi"},
            {"phone-19.5x9-xxhdpi", "w411dp-h891dp-port-xxhdpi"},
            {"phone-20x9-xxhdpi", "w412dp-h915dp-port-xxhdpi"},
            {"phone-21x9-xxhdpi", "w360dp-h840dp-port-xxhdpi"},
            {"phone-19.5x9-xxxhdpi", "w411dp-h891dp-port-xxxhdpi"},
            {"phone-20x9-landscape", "w915dp-h412dp-land-xxhdpi"},
            {"fold-inner-6x5", "w673dp-h841dp-port-xhdpi"},
            {"tablet-7in-16x10", "w600dp-h960dp-port-xhdpi"},
            {"tablet-4x3", "w768dp-h1024dp-port-xhdpi"},
            {"tablet-16x10-landscape", "w1280dp-h800dp-land-xhdpi"},
    };

    /** name, faction, layout key (or null), layout value - mirrors androidTest's Interface enum. */
    static final Object[][] INTERFACES = {
            {"command", 0, null, 0}, {"command_reveal", 0, "layout", 1},
            {"imperial", 1, null, 0}, {"imperial_reveal", 1, "layout", 1},
            {"collective", 2, null, 0}, {"collective_hub", 2, "layout", 1},
            {"classic_console", 9, null, 0}, {"next_console", 10, null, 0},
    };

    private static final List<String> MANIFEST = new ArrayList<>();

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}@{2}")
    public static Collection<Object[]> cases() {
        List<Object[]> out = new ArrayList<>();
        for (Object[] i : INTERFACES) {
            for (String[] s : SCREENS) out.add(new Object[]{i[0], i, s[0], s[1]});
        }
        return out;
    }

    private final String iface;
    private final Object[] spec;
    private final String screen;
    private final String qualifiers;

    public RobolectricScreenshots(String iface, Object[] spec, String screen, String qualifiers) {
        this.iface = iface;
        this.spec = spec;
        this.screen = screen;
        this.qualifiers = qualifiers;
    }

    static boolean enabled() {
        String v = System.getenv("TREKUI_SCREENSHOTS");
        return v != null && !v.isEmpty() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    /** assets/robolectric under the repository root (the test JVM runs in app/). */
    static File outputRoot() {
        String override = System.getenv("TREKUI_ASSETS_DIR");
        File root;
        if (override != null && !override.isEmpty()) {
            root = new File(override);
        } else {
            File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
            root = new File(dir.getName().equals("app") ? dir.getParentFile() : dir, "assets");
        }
        return new File(root, "robolectric");
    }

    @Test
    public void rendersHome() throws IOException {
        assumeTrue("set TREKUI_SCREENSHOTS=1 to render screenshots", enabled());
        RuntimeEnvironment.setQualifiers(qualifiers);
        // Robolectric gives every test a fresh Application but keeps app statics: drop the cached
        // Prefs singleton so it reads this test's SharedPreferences.
        resetStatic("com.gungreeneyes.trekui.Prefs", "instance");
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("trekui", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit()
                .putBoolean("welcomed", true)
                .putBoolean("bootSeq", false)
                .putBoolean("anim", false)
                .putInt("faction", (Integer) spec[1])
                .putInt(Prefs.layoutKeyFor(Skin.COMMAND), 0).putInt(Prefs.layoutKeyFor(Skin.IMPERIAL), 0)
                .putInt(Prefs.layoutKeyFor(Skin.COLLECTIVE), 0);
        // spec[2] != null marks the alternative layout; the pref key comes from the app itself.
        if (spec[2] != null) e.putInt(Prefs.layoutKeyFor((Integer) spec[1]), (Integer) spec[3]);
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit();

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
            org.junit.Assert.assertEquals(iface + ": interface not applied", ((Integer) spec[1]).intValue(), Skin.faction);
            View decor = controller.get().getWindow().getDecorView();
            int w = decor.getWidth(), h = decor.getHeight();
            assertTrue(iface + "@" + screen + ": window not laid out (" + w + "x" + h + ")", w > 0 && h > 0);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bmp));
            assertTrue(iface + "@" + screen + ": render is blank", !isUniform(bmp));

            File dir = new File(outputRoot(), iface);
            assertTrue("cannot create " + dir, dir.isDirectory() || dir.mkdirs());
            File png = new File(dir, screen + ".png");
            try (FileOutputStream out = new FileOutputStream(png)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            synchronized (MANIFEST) {
                MANIFEST.add(String.format(Locale.US,
                        "{\"interface\":\"%s\",\"config\":\"%s\",\"qualifiers\":\"%s\",\"width\":%d,\"height\":%d,\"path\":\"robolectric/%s/%s.png\"}",
                        iface, screen, qualifiers, w, h, iface, screen));
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    static void resetStatic(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, null);
        } catch (ReflectiveOperationException ignored) {
            // field renamed/removed: nothing cached to reset
        }
    }

    /** True if every sampled pixel has the same colour (nothing was drawn). */
    static boolean isUniform(Bitmap b) {
        int first = b.getPixel(0, 0);
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 40)) {
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 40)) {
                if (b.getPixel(x, y) != first) return false;
            }
        }
        return true;
    }

    @AfterClass
    public static void writeManifest() throws IOException {
        if (!enabled() || MANIFEST.isEmpty()) return;
        File root = outputRoot();
        if (!root.isDirectory() && !root.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(new File(root, "manifest.json"))) {
            out.write(("[\n  " + String.join(",\n  ", MANIFEST) + "\n]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
