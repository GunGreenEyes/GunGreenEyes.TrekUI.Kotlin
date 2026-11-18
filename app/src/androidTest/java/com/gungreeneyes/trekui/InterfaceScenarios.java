package com.gungreeneyes.trekui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Feature: every interface renders and fits the screen ({@code features/interfaces.feature}).
 *
 * <p>Runs once per {@link Interface}: the three console factions, their alternative layouts and the
 * CLASSIC CONSOLE and NEXT CONSOLE eras.</p>
 */
@RunWith(Parameterized.class)
public class InterfaceScenarios extends AppScenario {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> interfaces() {
        List<Object[]> out = new ArrayList<>();
        for (Interface i : Interface.values()) out.add(new Object[]{i});
        return out;
    }

    private final Interface iface;

    public InterfaceScenarios(Interface iface) {
        this.iface = iface;
    }

    /** Scenario: the interface renders its home screen without crashing. */
    @Test
    public void interfaceRenders() {
        scenario.given("the launcher is installed")
                .when("I switch to the " + iface + " interface and open home");
        MainActivity home = launchHome(iface);
        scenario.then("the home screen is shown in that interface");
        assertEquals(scenario.lastStep() + ": faction", iface.faction, (int) onMain(AppHooks::skinFaction));
        assertShowsContent(home);
        media.screenshot(iface.slug() + "-home");
        scenario.and("the activity is still running after a few seconds");
        android.os.SystemClock.sleep(2000);
        assertFalse(scenario.lastStep() + ": activity finished/crashed", home.isFinishing() || home.isDestroyed());
    }

    /** Scenario: nothing on the interface's home screen is clipped or under a system bar. */
    @Test
    public void interfaceFitsScreen() {
        scenario.given("the " + iface + " interface is showing");
        MainActivity home = launchHome(iface);
        scenario.then("no labelled or clickable view is clipped off-screen or under a system bar");
        assertFitsScreen(home, iface.slug() + " home");
    }

    /** Scenario: the app drawer opens over the interface and lists the installed apps. */
    @Test
    public void drawerOpensAndListsApps() {
        scenario.given("the " + iface + " interface is showing");
        MainActivity home = launchHome(iface);
        scenario.when("I open the app drawer");
        instrumentation.runOnMainSync(() -> AppHooks.openDrawer(home));
        settle();
        scenario.then("the drawer is open and lists apps");
        assertTrue(scenario.lastStep() + ": drawer open", onMain(() -> AppHooks.drawerOpen(home)));
        int apps = onMain(() -> AppHooks.appCount(home));
        assertTrue(scenario.lastStep() + ": app repo empty", apps > 0);
        int labelled = onMain(() -> countDescribed(AppHooks.drawer(home)));
        assertTrue(scenario.lastStep() + ": no app entries visible in the drawer", labelled > 0);
        media.screenshot(iface.slug() + "-drawer");
        scenario.and("nothing in the drawer is clipped");
        assertFitsScreen(home, iface.slug() + " drawer");
        instrumentation.runOnMainSync(() -> AppHooks.closeDrawer(home));
    }

    /** Views below {@code v} that carry a content description (drawer entries are labelled). */
    static int countDescribed(View v) {
        if (v.getVisibility() != View.VISIBLE) return 0;
        int n = v.getContentDescription() != null && v.getContentDescription().length() > 0 ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += countDescribed(g.getChildAt(i));
        }
        return n;
    }
}
