package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** {@link NotificationBadges}: unread counting rules, labels and tile keys. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationBadgesTest {

    private static final String OWN = "com.gungreeneyes.trekui";

    private static NotificationBadges.Entry note(String pkg) {
        return new NotificationBadges.Entry(pkg, null, false, false, true, 0);
    }

    @Test
    public void countsEachClearableNotificationPerApp() {
        Map<String, Integer> m = NotificationBadges.count(Arrays.asList(note("a"), note("a"), note("b")), OWN);
        assertThat(m.get("a")).isEqualTo(2);
        assertThat(m.get("b")).isEqualTo(1);
        assertThat(m.get(NotificationBadges.ALL)).isEqualTo(3);
    }

    @Test
    public void usesTheAppsNumberWhenSet() {
        Map<String, Integer> m = NotificationBadges.count(Collections.singletonList(
                new NotificationBadges.Entry("mail", null, false, false, true, 7)), OWN);
        assertThat(m.get("mail")).isEqualTo(7);
    }

    @Test
    public void skipsOngoingOwnAndDotlessNotifications() {
        Map<String, Integer> m = NotificationBadges.count(Arrays.asList(
                new NotificationBadges.Entry("music", null, false, true, true, 0),   // ongoing (media, foreground service)
                new NotificationBadges.Entry(OWN, null, false, false, true, 0),      // our own
                new NotificationBadges.Entry("quiet", null, false, false, false, 0), // dots off for this app / channel
                new NotificationBadges.Entry(null, null, false, false, true, 0)), OWN);
        assertThat(m).isEmpty();
    }

    @Test
    public void groupSummaryCountsOnlyWithoutChildren() {
        Map<String, Integer> withChildren = NotificationBadges.count(Arrays.asList(
                new NotificationBadges.Entry("chat", "g", true, false, true, 0),
                new NotificationBadges.Entry("chat", "g", false, false, true, 0),
                new NotificationBadges.Entry("chat", "g", false, false, true, 0)), OWN);
        assertThat(withChildren.get("chat")).isEqualTo(2);
        Map<String, Integer> alone = NotificationBadges.count(Collections.singletonList(
                new NotificationBadges.Entry("chat", "g", true, false, true, 0)), OWN);
        assertThat(alone.get("chat")).isEqualTo(1);
        // another app's summary for a group of the same name is unaffected
        Map<String, Integer> other = NotificationBadges.count(Arrays.asList(
                new NotificationBadges.Entry("chat", "g", false, false, true, 0),
                new NotificationBadges.Entry("mail", "g", true, false, true, 0)), OWN);
        assertThat(other.get("mail")).isEqualTo(1);
    }

    @Test
    public void labels() {
        assertThat(NotificationBadges.label(0)).isEmpty();
        assertThat(NotificationBadges.label(-3)).isEmpty();
        assertThat(NotificationBadges.label(5)).isEqualTo("5");
        assertThat(NotificationBadges.label(99)).isEqualTo("99");
        assertThat(NotificationBadges.label(100)).isEqualTo("99+");
    }

    @Test
    public void tileKeys() {
        Tile app = new Tile();
        app.comp = "org.thoughtcrime.securesms/.RoutingActivity";
        assertThat(NotificationBadges.keyFor(app)).isEqualTo("org.thoughtcrime.securesms");
        Tile shade = new Tile();
        shade.action = Tile.A_SHADE;
        assertThat(NotificationBadges.keyFor(shade)).isEqualTo(NotificationBadges.ALL);
        Tile config = new Tile();
        config.action = Tile.A_CONFIG;
        assertThat(NotificationBadges.keyFor(config)).isNull();
        assertThat(NotificationBadges.keyFor(new Tile())).isNull();
        assertThat(NotificationBadges.keyFor(null)).isNull();
        assertThat(NotificationBadges.packageOf("noslash")).isNull();
    }

    @Test
    public void liveCountsStartEmptyAndClear() {
        NotificationBadges.clear();
        assertThat(NotificationBadges.countFor("a")).isEqualTo(0);
        assertThat(NotificationBadges.countFor(null)).isEqualTo(0);
    }
}
