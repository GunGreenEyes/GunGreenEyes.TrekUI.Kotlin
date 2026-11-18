package com.gungreeneyes.trekui.lockscreen;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory hand-over between {@link LockListener} (which builds the redacted notification list) and
 * {@link WakeActivity} (which draws it). Both run in the app's one process. Nothing is persisted: the
 * list exists only while the process lives and is rebuilt from the system on every listener connect.
 * Observers are called on the main thread.
 */
public final class LockHub {
    /** Notified when the list changed. */
    public interface Observer {
        void onItemsChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static volatile List<LockItem> items = Collections.emptyList();

    private LockHub() {}

    /** The current list, newest first (never null, unmodifiable). */
    public static List<LockItem> items() {
        return items;
    }

    /** Replaces the list and tells the observers. */
    static void publish(List<LockItem> next) {
        items = Collections.unmodifiableList(new ArrayList<>(next));
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) o.onItemsChanged();
        });
    }

    public static void observe(Observer o) {
        if (!OBSERVERS.contains(o)) OBSERVERS.add(o);
    }

    public static void unobserve(Observer o) {
        OBSERVERS.remove(o);
    }
}
