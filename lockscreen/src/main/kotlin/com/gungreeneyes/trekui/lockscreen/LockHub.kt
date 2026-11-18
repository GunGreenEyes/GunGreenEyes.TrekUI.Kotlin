package com.gungreeneyes.trekui.lockscreen

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory hand-over between [LockListener] (which builds the redacted notification list) and
 * [WakeActivity] (which draws it). Both run in the app's one process. Nothing is persisted: the list exists
 * only while the process lives and is rebuilt from the system on every listener connect. Observers are
 * called on the main thread.
 */
object LockHub {
    /** Notified when the list changed. */
    fun interface Observer {
        fun onItemsChanged()
    }

    private val MAIN = Handler(Looper.getMainLooper())
    private val OBSERVERS = CopyOnWriteArrayList<Observer>()

    /** The current list, newest first (never null, unmodifiable). */
    @Volatile
    var items: List<LockItem> = emptyList()
        private set

    /** Replaces the list and tells the observers. */
    internal fun publish(next: List<LockItem>) {
        items = Collections.unmodifiableList(ArrayList(next))
        MAIN.post { OBSERVERS.forEach { it.onItemsChanged() } }
    }

    fun observe(o: Observer) {
        OBSERVERS.addIfAbsent(o)
    }

    fun unobserve(o: Observer) {
        OBSERVERS.remove(o)
    }
}
