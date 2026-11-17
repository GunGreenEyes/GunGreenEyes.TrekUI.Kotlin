package com.gungreeneyes.trekui;

import android.view.View;

/**
 * A deploying home layout: the Collective {@link CollectiveHub}, the Command {@link CommandCascade} and the
 * Imperial {@link ImperialDeploy}. MainActivity builds the home tiles through this interface without
 * knowing which interface is active; the layout decides geometry and animation.
 */
interface RevealHost {
    /** Adds a tile; content is its icon (or a "+" for an empty tile). Returns the touch target. */
    View addNode(View content, String label, int color, boolean empty);

    /** Dim the layout while an info panel is open over it (1 = normal). */
    void setDim(float v);

    /** Fold or deploy; onEnd (may be null) runs when the motion finishes. */
    void setOpen(boolean open, boolean animated, Runnable onEnd);

    /** Keep the master control at its deployed position (used when switching between pages). */
    void setPinned(boolean pinned);

    /** What tapping the master control does; null = fold/deploy (the default). */
    void setMasterAction(Runnable action);

    /** Text on the master control (null = the layout's default). */
    void setMasterTitle(String title);

    /** Number shown with the master control (-1 = the node count). */
    void setCount(int n);

    /** The info-panel row under the layout ({@link RevealInfo}, {@link CollectiveInfo}). */
    interface InfoBar {
        /** Lights the key of the open panel; -1 when none is open. */
        void setActive(int index);
    }
}
