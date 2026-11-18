package com.gungreeneyes.trekui

import android.app.Activity
import android.content.Context
import android.view.View
import java.lang.reflect.InvocationTargetException

/**
 * Reflection bridge to the launcher's package-private members.
 *
 * The test APK is loaded by a different class loader than the app, so ART refuses direct access to
 * package-private fields (`IllegalAccessError`) even though the tests sit in the same Java
 * package. Everything the scenarios need from inside the app goes through here, with
 * `setAccessible(true)`; a rename in the app shows up as one clear failure naming the member.
 * Call these on the main thread.
 */
internal object AppHooks {

    /** `MainActivity.openDrawer(null, false)`: opens the app drawer without the search keyboard. */
    fun openDrawer(home: Activity) {
        call(home, "openDrawer", null, false)
    }

    /** `MainActivity.drawer`: the app drawer view. */
    fun drawer(home: Activity): View = field(home, "drawer") as View

    fun drawerOpen(home: Activity): Boolean = call(drawer(home), "isOpen") as Boolean

    fun closeDrawer(home: Activity) {
        call(drawer(home), "close", false)
    }

    /** `MainActivity.openConfig()`: starts the launcher settings. */
    fun openSettings(home: Activity) {
        call(home, "openConfig")
    }

    /** `MainActivity.requestDefaultHome(activity)`: the in-app "make me your home" request. */
    fun requestDefaultHome(home: Activity) {
        callStatic(home.javaClass, "requestDefaultHome", home)
    }

    /** Number of launchable apps the launcher's `AppRepo` has loaded. */
    fun appCount(context: Context): Int {
        try {
            val repo = Class.forName("com.gungreeneyes.trekui.AppRepo")
            val r = callStatic(repo, "get", context)!!
            return (call(r, "apps") as List<*>).size
        } catch (e: ClassNotFoundException) {
            throw AssertionError("AppRepo missing", e)
        }
    }

    /** `Skin.faction`: the interface the launcher last applied. */
    fun skinFaction(): Int {
        try {
            val f = Class.forName("com.gungreeneyes.trekui.Skin").getDeclaredField("faction")
            f.isAccessible = true
            return f.getInt(null)
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("Skin.faction not accessible", e)
        }
    }

    // ---- reflection ------------------------------------------------------------------------------

    fun field(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (ignored: NoSuchFieldException) {
                // try the superclass
            } catch (e: IllegalAccessException) {
                throw AssertionError(e)
            }
            c = c.superclass
        }
        throw AssertionError("no field '" + name + "' in " + target.javaClass.name)
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? = invoke(target.javaClass, target, name, args)

    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? = invoke(type, null, name, args)

    private fun invoke(type: Class<*>, target: Any?, name: String, args: Array<out Any?>): Any? {
        var c: Class<*>? = type
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != args.size) continue
                m.isAccessible = true
                try {
                    return m.invoke(target, *args)
                } catch (wrongOverload: IllegalArgumentException) {
                    // another overload with the same arity: keep looking
                } catch (e: InvocationTargetException) {
                    val cause = e.cause
                    if (cause is RuntimeException) throw cause
                    if (cause is Error) throw cause
                    throw RuntimeException(cause)
                } catch (e: IllegalAccessException) {
                    throw AssertionError(e)
                }
            }
            c = c.superclass
        }
        throw AssertionError("no method " + name + "/" + args.size + " in " + type.name)
    }
}
