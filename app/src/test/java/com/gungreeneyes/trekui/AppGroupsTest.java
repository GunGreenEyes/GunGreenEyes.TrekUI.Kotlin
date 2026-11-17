package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** JVM tests for app-group persistence and list edits in {@link AppGroups}. */
public class AppGroupsTest {

    private static AppGroups.Member app(String pkg) {
        return new AppGroups.Member(pkg + "/.Main", -1);
    }

    @Test
    public void encodeParse_roundTripKeepsIdsNamesOrderAndProfiles() {
        List<AppGroups.Group> groups = new ArrayList<>();
        AppGroups.Group work = AppGroups.create(groups, "work");
        AppGroups.addApp(work, app("com.mail"));
        AppGroups.addApp(work, new AppGroups.Member("com.mail/.Main", 10));
        AppGroups.addApp(work, app("com.docs"));
        AppGroups.create(groups, "Games");

        List<AppGroups.Group> back = AppGroups.parse(AppGroups.encode(groups));

        assertThat(back).hasSize(2);
        assertThat(back.get(0).id).isEqualTo("g1");
        assertThat(back.get(0).name).isEqualTo("WORK");
        assertThat(back.get(0).apps).hasSize(3);
        assertThat(back.get(0).apps.get(1).user).isEqualTo(10L);
        assertThat(back.get(0).apps.get(2).comp).isEqualTo("com.docs/.Main");
        assertThat(back.get(1).id).isEqualTo("g2");
        assertThat(back.get(1).apps).isEmpty();
    }

    @Test
    public void keysUseTheFeaturePrefix() {
        assertThat(AppGroups.KEY).startsWith(AppGroups.KEY_PREFIX);
        assertThat(AppGroups.KEY_PREFIX).isEqualTo("group.");
    }

    @Test
    public void parse_corruptOrMissingGivesEmptyList() {
        assertThat(AppGroups.parse(null)).isEmpty();
        assertThat(AppGroups.parse("")).isEmpty();
        assertThat(AppGroups.parse("{not json")).isEmpty();
        assertThat(AppGroups.parse("{\"id\":\"g1\"}")).isEmpty();
    }

    @Test
    public void parse_dropsBadEntriesDuplicatesAndCapsMembers() {
        StringBuilder apps = new StringBuilder();
        for (int i = 0; i < 20; i++) apps.append(i > 0 ? "," : "").append("{\"comp\":\"p").append(i).append("/.A\"}");
        String raw = "[{\"name\":\"no id\"},"
                + "{\"id\":\"g1\",\"name\":\"\",\"apps\":[{\"comp\":\"\"},{\"comp\":\"a/.A\"},{\"comp\":\"a/.A\"},{}]},"
                + "{\"id\":\"g1\",\"name\":\"dup\"},"
                + "{\"id\":\"g2\",\"name\":\"many\",\"apps\":[" + apps + "]},"
                + "42]";
        List<AppGroups.Group> groups = AppGroups.parse(raw);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).name).isEqualTo(AppGroups.DEFAULT_NAME);
        assertThat(groups.get(0).apps).hasSize(1);
        assertThat(groups.get(1).apps).hasSize(AppGroups.MAX_APPS);
    }

    @Test
    public void cleanName_trimsUppercasesAndCaps() {
        assertThat(AppGroups.cleanName("  social ")).isEqualTo("SOCIAL");
        assertThat(AppGroups.cleanName(null)).isEqualTo(AppGroups.DEFAULT_NAME);
        assertThat(AppGroups.cleanName("   ")).isEqualTo(AppGroups.DEFAULT_NAME);
        assertThat(AppGroups.cleanName("abcdefghijklmnopqrstuvwxyz")).hasLength(AppGroups.MAX_NAME);
    }

    @Test
    public void newId_fillsTheFirstGap() {
        List<AppGroups.Group> groups = new ArrayList<>(Arrays.asList(new AppGroups.Group("g1", "A"), new AppGroups.Group("g3", "C")));
        assertThat(AppGroups.newId(groups)).isEqualTo("g2");
        assertThat(AppGroups.newId(new ArrayList<>())).isEqualTo("g1");
    }

    @Test
    public void create_stopsAtTheGroupLimit() {
        List<AppGroups.Group> groups = new ArrayList<>();
        for (int i = 0; i < AppGroups.MAX_GROUPS; i++) assertThat(AppGroups.create(groups, "G" + i)).isNotNull();
        assertThat(AppGroups.create(groups, "one too many")).isNull();
        assertThat(groups).hasSize(AppGroups.MAX_GROUPS);
    }

    @Test
    public void addRemove_rejectDuplicatesAndFullGroups() {
        AppGroups.Group g = new AppGroups.Group("g1", "G");
        assertThat(AppGroups.addApp(g, app("a"))).isTrue();
        assertThat(AppGroups.addApp(g, app("a"))).isFalse();
        // same component in a work profile is a different member
        assertThat(AppGroups.addApp(g, new AppGroups.Member("a/.Main", 11))).isTrue();
        for (int i = 0; i < 20; i++) AppGroups.addApp(g, app("p" + i));
        assertThat(g.apps).hasSize(AppGroups.MAX_APPS);
        assertThat(AppGroups.removeApp(g, app("a"))).isTrue();
        assertThat(AppGroups.removeApp(g, app("a"))).isFalse();
        assertThat(AppGroups.indexOf(g, new AppGroups.Member("a/.Main", 11))).isEqualTo(0);
    }

    @Test
    public void move_swapsNeighboursAndRefusesTheEnds() {
        List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));
        assertThat(AppGroups.move(list, 0, -1)).isFalse();
        assertThat(AppGroups.move(list, 2, 1)).isFalse();
        assertThat(AppGroups.move(list, 5, -1)).isFalse();
        assertThat(AppGroups.move(list, 0, 1)).isTrue();
        assertThat(list).containsExactly("b", "a", "c").inOrder();
        assertThat(AppGroups.move(list, 2, -1)).isTrue();
        assertThat(list).containsExactly("b", "c", "a").inOrder();
    }

    @Test
    public void delete_removesByIdOnly() {
        List<AppGroups.Group> groups = new ArrayList<>();
        AppGroups.create(groups, "A");
        AppGroups.Group b = AppGroups.create(groups, "B");
        assertThat(AppGroups.delete(groups, "missing")).isFalse();
        assertThat(AppGroups.delete(groups, "g1")).isTrue();
        assertThat(groups).containsExactly(b);
        assertThat(AppGroups.find(groups, null)).isNull();
    }
}
