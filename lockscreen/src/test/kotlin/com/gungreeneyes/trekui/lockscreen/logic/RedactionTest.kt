package com.gungreeneyes.trekui.lockscreen.logic

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import com.google.common.truth.Truth.assertThat
import com.gungreeneyes.trekui.lockscreen.logic.Redaction.Show
import org.junit.Test

/** Lock-screen privacy rules for the wake screen's notification list. */
class RedactionTest {
    @Test
    fun constantsMatchThePlatform() {
        assertThat(PUB).isEqualTo(Notification.VISIBILITY_PUBLIC)
        assertThat(PRIV).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(SECRET).isEqualTo(Notification.VISIBILITY_SECRET)
        assertThat(NONE).isEqualTo(NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE)
        assertThat(Redaction.IMPORTANCE_LOW).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(Redaction.IMPORTANCE_UNSPECIFIED).isEqualTo(NotificationManager.IMPORTANCE_UNSPECIFIED)
    }

    @Test
    fun privateIsRedactedByDefaultOnASecureLock() {
        assertThat(Redaction.decide(PRIV, NONE, true, true, null, false)).isEqualTo(Show.REDACTED)
        assertThat(Redaction.decide(PRIV, NONE, true, true, true, false)).isEqualTo(Show.REDACTED)
    }

    @Test
    fun privateNeedsBothTheUserAndTheSystemToShowInFull() {
        assertThat(Redaction.decide(PRIV, NONE, true, true, true, true)).isEqualTo(Show.FULL)
        assertThat(Redaction.decide(PRIV, NONE, true, true, null, true)).isEqualTo(Show.FULL)
        assertThat(Redaction.decide(PRIV, NONE, true, true, false, true)).isEqualTo(Show.REDACTED)
    }

    @Test
    fun secretIsNeverListed() {
        assertThat(Redaction.decide(SECRET, NONE, true, true, true, true)).isEqualTo(Show.HIDDEN)
        assertThat(Redaction.decide(SECRET, NONE, false, true, true, true)).isEqualTo(Show.HIDDEN)
    }

    @Test
    fun publicIsShownInFull() {
        assertThat(Redaction.decide(PUB, NONE, true, true, false, false)).isEqualTo(Show.FULL)
    }

    @Test
    fun channelSettingCanOnlyMakeItMorePrivate() {
        assertThat(Redaction.effectiveVisibility(PUB, PRIV)).isEqualTo(PRIV)
        assertThat(Redaction.effectiveVisibility(PUB, SECRET)).isEqualTo(SECRET)
        assertThat(Redaction.effectiveVisibility(PRIV, PUB)).isEqualTo(PRIV)
        assertThat(Redaction.effectiveVisibility(SECRET, PUB)).isEqualTo(SECRET)
        assertThat(Redaction.effectiveVisibility(PUB, NONE)).isEqualTo(PUB)
        assertThat(Redaction.decide(PUB, PRIV, true, true, false, false)).isEqualTo(Show.REDACTED)
        assertThat(Redaction.decide(PUB, SECRET, true, true, true, true)).isEqualTo(Show.HIDDEN)
    }

    @Test
    fun unknownVisibilityCountsAsPrivate() {
        assertThat(Redaction.effectiveVisibility(42, NONE)).isEqualTo(PRIV)
        assertThat(Redaction.decide(42, NONE, true, true, null, false)).isEqualTo(Show.REDACTED)
    }

    @Test
    fun insecureKeyguardShowsPrivateInFull() {
        assertThat(Redaction.decide(PRIV, NONE, false, true, false, false)).isEqualTo(Show.FULL)
    }

    @Test
    fun systemSwitchOffHidesEverything() {
        assertThat(Redaction.decide(PUB, NONE, true, false, true, true)).isEqualTo(Show.HIDDEN)
        assertThat(Redaction.decide(PRIV, NONE, false, false, true, true)).isEqualTo(Show.HIDDEN)
    }

    @Test
    fun listingSkipsOwnSummariesMediaMinimisedAndSuspended() {
        assertThat(Redaction.listable(false, false, false, 3, false)).isTrue()
        assertThat(Redaction.listable(false, false, false, Redaction.IMPORTANCE_UNSPECIFIED, false)).isTrue()
        assertThat(Redaction.listable(true, false, false, 3, false)).isFalse()
        assertThat(Redaction.listable(false, true, false, 3, false)).isFalse()
        assertThat(Redaction.listable(false, false, true, 3, false)).isFalse()
        assertThat(Redaction.listable(false, false, false, 1, false)).isFalse()
        assertThat(Redaction.listable(false, false, false, 3, true)).isFalse()
    }

    private companion object {
        const val PUB = Redaction.PUBLIC
        const val PRIV = Redaction.PRIVATE
        const val SECRET = Redaction.SECRET
        const val NONE = Redaction.NO_OVERRIDE
    }
}
