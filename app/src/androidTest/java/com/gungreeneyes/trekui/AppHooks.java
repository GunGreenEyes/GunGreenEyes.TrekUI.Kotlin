package com.gungreeneyes.trekui;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection bridge to the launcher's package-private members.
 *
 * <p>The test APK is loaded by a different class loader than the app, so ART refuses direct access to
 * package-private fields ({@code IllegalAccessError}) even though the tests sit in the same Java
 * package. Everything the scenarios need from inside the app goes through here, with
 * {@code setAccessible(true)}; a rename in the app shows up as one clear failure naming the member.
 * Call these on the main thread.</p>
 */
final class AppHooks {
    private AppHooks() {
    }

    /** {@code MainActivity.openDrawer(null, false)}: opens the app drawer without the search keyboard. */
    static void openDrawer(Activity home) {
        call(home, "openDrawer", null, false);
    }

    /** {@code MainActivity.drawer}: the app drawer view. */
    static View drawer(Activity home) {
        return (View) field(home, "drawer");
    }

    static boolean drawerOpen(Activity home) {
        return (Boolean) call(drawer(home), "isOpen");
    }

    static void closeDrawer(Activity home) {
        call(drawer(home), "close", false);
    }

    /** {@code MainActivity.openConfig()}: starts the launcher settings. */
    static void openSettings(Activity home) {
        call(home, "openConfig");
    }

    /** {@code MainActivity.requestDefaultHome(activity)}: the in-app "make me your home" request. */
    static void requestDefaultHome(Activity home) {
        callStatic(home.getClass(), "requestDefaultHome", home);
    }

    /** Number of launchable apps the launcher's {@code AppRepo} has loaded. */
    static int appCount(Context context) {
        try {
            Class<?> repo = Class.forName("com.gungreeneyes.trekui.AppRepo");
            Object r = callStatic(repo, "get", context);
            return ((List<?>) call(r, "apps")).size();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("AppRepo missing", e);
        }
    }

    /** {@code Skin.faction}: the interface the launcher last applied. */
    static int skinFaction() {
        try {
            Field f = Class.forName("com.gungreeneyes.trekui.Skin").getDeclaredField("faction");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Skin.faction not accessible", e);
        }
    }

    // ---- reflection ------------------------------------------------------------------------------

    static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no field '" + name + "' in " + target.getClass().getName());
    }

    static Object call(Object target, String name, Object... args) {
        return invoke(target.getClass(), target, name, args);
    }

    static Object callStatic(Class<?> type, String name, Object... args) {
        return invoke(type, null, name, args);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object[] args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
                m.setAccessible(true);
                try {
                    return m.invoke(target, args);
                } catch (IllegalArgumentException wrongOverload) {
                    // another overload with the same arity: keep looking
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new RuntimeException(cause);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("no method " + name + "/" + args.length + " in " + type.getName());
    }
}
