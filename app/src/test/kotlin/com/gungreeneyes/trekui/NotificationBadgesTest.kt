package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [NotificationBadges]: unread counting rules, labels and tile keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationBadgesTest {

    @Test
    fun countsEachClearableNotificationPerApp() {
        val m = NotificationBadges.count(listOf(note("a"), note("a"), note("b")), OWN)
        assertThat(m["a"]).isEqualTo(2)
        assertThat(m["b"]).isEqualTo(1)
        assertThat(m[NotificationBadges.ALL]).isEqualTo(3)
    }

    @Test
    fun usesTheAppsNumberWhenSet() {
        val m = NotificationBadges.count(listOf(NotificationBadges.Entry("mail", null, false, false, true, 7)), OWN)
        assertThat(m["mail"]).isEqualTo(7)
    }

    @Test
    fun skipsOngoingOwnAndDotlessNotifications() {
        val m = NotificationBadges.count(
            listOf(
                NotificationBadges.Entry("music", null, false, true, true, 0), // ongoing (media, foreground service)
                NotificationBadges.Entry(OWN, null, false, false, true, 0), // our own
                NotificationBadges.Entry("quiet", null, false, false, false, 0), // dots off for this app / channel
                NotificationBadges.Entry(null, null, false, false, true, 0),
            ),
            OWN,
        )
        assertThat(m).isEmpty()
    }

    @Test
    fun groupSummaryCountsOnlyWithoutChildren() {
        val withChildren = NotificationBadges.count(
            listOf(
                NotificationBadges.Entry("chat", "g", true, false, true, 0),
                NotificationBadges.Entry("chat", "g", false, false, true, 0),
                NotificationBadges.Entry("chat", "g", false, false, true, 0),
            ),
            OWN,
        )
        assertThat(withChildren["chat"]).isEqualTo(2)
        val alone = NotificationBadges.count(listOf(NotificationBadges.Entry("chat", "g", true, false, true, 0)), OWN)
        assertThat(alone["chat"]).isEqualTo(1)
        // another app's summary for a group of the same name is unaffected
        val other = NotificationBadges.count(
            listOf(
                NotificationBadges.Entry("chat", "g", false, false, true, 0),
                NotificationBadges.Entry("mail", "g", true, false, true, 0),
            ),
            OWN,
        )
        assertThat(other["mail"]).isEqualTo(1)
    }

    @Test
    fun labels() {
        assertThat(NotificationBadges.label(0)).isEmpty()
        assertThat(NotificationBadges.label(-3)).isEmpty()
        assertThat(NotificationBadges.label(5)).isEqualTo("5")
        assertThat(NotificationBadges.label(99)).isEqualTo("99")
        assertThat(NotificationBadges.label(100)).isEqualTo("99+")
    }

    @Test
    fun tileKeys() {
        val app = Tile()
        app.comp = "org.thoughtcrime.securesms/.RoutingActivity"
        assertThat(NotificationBadges.keyFor(app)).isEqualTo("org.thoughtcrime.securesms")
        val shade = Tile()
        shade.action = Tile.A_SHADE
        assertThat(NotificationBadges.keyFor(shade)).isEqualTo(NotificationBadges.ALL)
        val config = Tile()
        config.action = Tile.A_CONFIG
        assertThat(NotificationBadges.keyFor(config)).isNull()
        assertThat(NotificationBadges.keyFor(Tile())).isNull()
        assertThat(NotificationBadges.keyFor(null)).isNull()
        assertThat(NotificationBadges.packageOf("noslash")).isNull()
    }

    @Test
    fun liveCountsStartEmptyAndClear() {
        NotificationBadges.clear()
        assertThat(NotificationBadges.countFor("a")).isEqualTo(0)
        assertThat(NotificationBadges.countFor(null)).isEqualTo(0)
    }

    private companion object {
        const val OWN = "com.gungreeneyes.trekui"

        fun note(pkg: String): NotificationBadges.Entry = NotificationBadges.Entry(pkg, null, false, false, true, 0)
    }
}
