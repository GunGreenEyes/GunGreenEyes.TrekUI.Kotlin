package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM tests for app-group persistence and list edits in [AppGroups]. */
class AppGroupsTest {

    private fun app(pkg: String) = AppGroups.Member("$pkg/.Main", -1)

    @Test
    fun encodeParse_roundTripKeepsIdsNamesOrderAndProfiles() {
        val groups = ArrayList<AppGroups.Group>()
        val work = AppGroups.create(groups, "work")!!
        AppGroups.addApp(work, app("com.mail"))
        AppGroups.addApp(work, AppGroups.Member("com.mail/.Main", 10))
        AppGroups.addApp(work, app("com.docs"))
        AppGroups.create(groups, "Games")

        val back = AppGroups.parse(AppGroups.encode(groups))

        assertThat(back).hasSize(2)
        assertThat(back[0].id).isEqualTo("g1")
        assertThat(back[0].name).isEqualTo("WORK")
        assertThat(back[0].apps).hasSize(3)
        assertThat(back[0].apps[1].user).isEqualTo(10L)
        assertThat(back[0].apps[2].comp).isEqualTo("com.docs/.Main")
        assertThat(back[1].id).isEqualTo("g2")
        assertThat(back[1].apps).isEmpty()
    }

    @Test
    fun keysUseTheFeaturePrefix() {
        assertThat(AppGroups.KEY).startsWith(AppGroups.KEY_PREFIX)
        assertThat(AppGroups.KEY_PREFIX).isEqualTo("group.")
    }

    @Test
    fun parse_corruptOrMissingGivesEmptyList() {
        assertThat(AppGroups.parse(null)).isEmpty()
        assertThat(AppGroups.parse("")).isEmpty()
        assertThat(AppGroups.parse("{not json")).isEmpty()
        assertThat(AppGroups.parse("{\"id\":\"g1\"}")).isEmpty()
    }

    @Test
    fun parse_dropsBadEntriesDuplicatesAndCapsMembers() {
        val apps = (0 until 20).joinToString(",") { "{\"comp\":\"p$it/.A\"}" }
        val raw = "[{\"name\":\"no id\"}," +
            "{\"id\":\"g1\",\"name\":\"\",\"apps\":[{\"comp\":\"\"},{\"comp\":\"a/.A\"},{\"comp\":\"a/.A\"},{}]}," +
            "{\"id\":\"g1\",\"name\":\"dup\"}," +
            "{\"id\":\"g2\",\"name\":\"many\",\"apps\":[" + apps + "]}," +
            "42]"
        val groups = AppGroups.parse(raw)
        assertThat(groups).hasSize(2)
        assertThat(groups[0].name).isEqualTo(AppGroups.DEFAULT_NAME)
        assertThat(groups[0].apps).hasSize(1)
        assertThat(groups[1].apps).hasSize(AppGroups.MAX_APPS)
    }

    @Test
    fun cleanName_trimsUppercasesAndCaps() {
        assertThat(AppGroups.cleanName("  social ")).isEqualTo("SOCIAL")
        assertThat(AppGroups.cleanName(null)).isEqualTo(AppGroups.DEFAULT_NAME)
        assertThat(AppGroups.cleanName("   ")).isEqualTo(AppGroups.DEFAULT_NAME)
        assertThat(AppGroups.cleanName("abcdefghijklmnopqrstuvwxyz")).hasLength(AppGroups.MAX_NAME)
    }

    @Test
    fun newId_fillsTheFirstGap() {
        val groups = arrayListOf(AppGroups.Group("g1", "A"), AppGroups.Group("g3", "C"))
        assertThat(AppGroups.newId(groups)).isEqualTo("g2")
        assertThat(AppGroups.newId(ArrayList())).isEqualTo("g1")
    }

    @Test
    fun create_stopsAtTheGroupLimit() {
        val groups = ArrayList<AppGroups.Group>()
        for (i in 0 until AppGroups.MAX_GROUPS) assertThat(AppGroups.create(groups, "G$i")).isNotNull()
        assertThat(AppGroups.create(groups, "one too many")).isNull()
        assertThat(groups).hasSize(AppGroups.MAX_GROUPS)
    }

    @Test
    fun addRemove_rejectDuplicatesAndFullGroups() {
        val g = AppGroups.Group("g1", "G")
        assertThat(AppGroups.addApp(g, app("a"))).isTrue()
        assertThat(AppGroups.addApp(g, app("a"))).isFalse()
        // same component in a work profile is a different member
        assertThat(AppGroups.addApp(g, AppGroups.Member("a/.Main", 11))).isTrue()
        for (i in 0 until 20) AppGroups.addApp(g, app("p$i"))
        assertThat(g.apps).hasSize(AppGroups.MAX_APPS)
        assertThat(AppGroups.removeApp(g, app("a"))).isTrue()
        assertThat(AppGroups.removeApp(g, app("a"))).isFalse()
        assertThat(AppGroups.indexOf(g, AppGroups.Member("a/.Main", 11))).isEqualTo(0)
    }

    @Test
    fun move_swapsNeighboursAndRefusesTheEnds() {
        val list = arrayListOf("a", "b", "c")
        assertThat(AppGroups.move(list, 0, -1)).isFalse()
        assertThat(AppGroups.move(list, 2, 1)).isFalse()
        assertThat(AppGroups.move(list, 5, -1)).isFalse()
        assertThat(AppGroups.move(list, 0, 1)).isTrue()
        assertThat(list).containsExactly("b", "a", "c").inOrder()
        assertThat(AppGroups.move(list, 2, -1)).isTrue()
        assertThat(list).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun delete_removesByIdOnly() {
        val groups = ArrayList<AppGroups.Group>()
        AppGroups.create(groups, "A")
        val b = AppGroups.create(groups, "B")
        assertThat(AppGroups.delete(groups, "missing")).isFalse()
        assertThat(AppGroups.delete(groups, "g1")).isTrue()
        assertThat(groups).containsExactly(b)
        assertThat(AppGroups.find(groups, null)).isNull()
    }
}
