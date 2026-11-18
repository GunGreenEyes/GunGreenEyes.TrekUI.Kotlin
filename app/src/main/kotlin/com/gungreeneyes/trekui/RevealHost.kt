package com.gungreeneyes.trekui

import android.view.View

/**
 * A deploying home layout: the Collective [CollectiveHub], the Command [CommandCascade] and the
 * Imperial [ImperialDeploy]. MainActivity builds the home tiles through this interface without
 * knowing which interface is active; the layout decides geometry and animation.
 */
interface RevealHost {
    /** Adds a tile; content is its icon (or a "+" for an empty tile). Returns the touch target. */
    fun addNode(content: View, label: String, color: Int, empty: Boolean): View

    /** Dim the layout while an info panel is open over it (1 = normal). */
    fun setDim(v: Float)

    /** Fold or deploy; onEnd (may be null) runs when the motion finishes. */
    fun setOpen(open: Boolean, animated: Boolean, onEnd: Runnable?)

    /** Keep the master control at its deployed position (used when switching between pages). */
    fun setPinned(pinned: Boolean)

    /** What tapping the master control does; null = fold/deploy (the default). */
    fun setMasterAction(action: Runnable?)

    /** Text on the master control (null = the layout's default). */
    fun setMasterTitle(title: String?)

    /** Number shown with the master control (-1 = the node count). */
    fun setCount(n: Int)

    /** The info-panel row under the layout ([RevealInfo], [CollectiveInfo]). */
    interface InfoBar {
        /** Lights the key of the open panel; -1 when none is open. */
        fun setActive(index: Int)
    }
}
