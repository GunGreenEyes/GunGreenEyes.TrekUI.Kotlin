package com.gungreeneyes.trekui

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Feature: every interface renders and fits the screen (`features/interfaces.feature`).
 *
 * Runs once per [Interface]: the three console factions, their alternative layouts and the
 * CLASSIC CONSOLE and NEXT CONSOLE eras.
 */
@RunWith(Parameterized::class)
class InterfaceScenarios(private val iface: Interface) : AppScenario() {

    /** Scenario: the interface renders its home screen without crashing. */
    @Test
    fun interfaceRenders() {
        scenario.given("the launcher is installed")
            .`when`("I switch to the $iface interface and open home")
        val home = launchHome(iface)
        scenario.then("the home screen is shown in that interface")
        assertEquals(scenario.lastStep + ": faction", iface.faction.toLong(), onMain { AppHooks.skinFaction() }.toLong())
        assertShowsContent(home)
        media.screenshot(iface.slug() + "-home")
        scenario.and("the activity is still running after a few seconds")
        SystemClock.sleep(2000)
        assertFalse(scenario.lastStep + ": activity finished/crashed", home.isFinishing || home.isDestroyed)
    }

    /** Scenario: nothing on the interface's home screen is clipped or under a system bar. */
    @Test
    fun interfaceFitsScreen() {
        scenario.given("the $iface interface is showing")
        val home = launchHome(iface)
        scenario.then("no labelled or clickable view is clipped off-screen or under a system bar")
        assertFitsScreen(home, iface.slug() + " home")
    }

    /** Scenario: the app drawer opens over the interface and lists the installed apps. */
    @Test
    fun drawerOpensAndListsApps() {
        scenario.given("the $iface interface is showing")
        val home = launchHome(iface)
        scenario.`when`("I open the app drawer")
        instrumentation.runOnMainSync { AppHooks.openDrawer(home) }
        settle()
        scenario.then("the drawer is open and lists apps")
        assertTrue(scenario.lastStep + ": drawer open", onMain { AppHooks.drawerOpen(home) })
        val apps = onMain { AppHooks.appCount(home) }
        assertTrue(scenario.lastStep + ": app repo empty", apps > 0)
        val labelled = onMain { countDescribed(AppHooks.drawer(home)) }
        assertTrue(scenario.lastStep + ": no app entries visible in the drawer", labelled > 0)
        media.screenshot(iface.slug() + "-drawer")
        scenario.and("nothing in the drawer is clipped")
        assertFitsScreen(home, iface.slug() + " drawer")
        instrumentation.runOnMainSync { AppHooks.closeDrawer(home) }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun interfaces(): Collection<Array<Any>> = Interface.entries.map { arrayOf<Any>(it) }

        /** Views below `v` that carry a content description (drawer entries are labelled). */
        @JvmStatic
        fun countDescribed(v: View): Int {
            if (v.visibility != View.VISIBLE) return 0
            var n = if (!v.contentDescription.isNullOrEmpty()) 1 else 0
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) n += countDescribed(v.getChildAt(i))
            }
            return n
        }
    }
}
