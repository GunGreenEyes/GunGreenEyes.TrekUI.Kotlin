package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.Calendar;
import java.util.GregorianCalendar;

import org.junit.After;
import org.junit.Test;

/** JVM tests for PipePages' pure helpers: titles, labels, ritual mask, layout padding, file names. */
public class PipePagesTest {
    private final int savedFaction = Skin.faction;

    @After
    public void restoreFaction() {
        Skin.faction = savedFaction;
    }

    @Test
    public void title_perPageAndPerScene() {
        assertThat(PipePages.title("pipes")).isEqualTo("PIPE REGISTER");
        assertThat(PipePages.title("blends")).isEqualTo("TOBACCO & BLENDS");
        assertThat(PipePages.title("journal")).isEqualTo("PRIVATE JOURNAL");
        assertThat(PipePages.title("backup")).isEqualTo("FILING & RECORDS");
        assertThat(PipePages.title("unknown")).isEqualTo("THE STUDY");
        Skin.faction = Skin.BRIAR;
        assertThat(PipePages.title("ritual")).isEqualTo("THE QUIET HOUR");
        Skin.faction = Skin.LEDGER;
        assertThat(PipePages.title("ritual")).isEqualTo("TODAY'S RITUAL");
        Skin.faction = Skin.MIDNIGHT;
        assertThat(PipePages.title("ritual")).isEqualTo("CEREMONY & SESSION");
    }

    @Test
    public void noun_perCollection() {
        assertThat(PipePages.noun("pipes")).isEqualTo("PIPE");
        assertThat(PipePages.noun("blends")).isEqualTo("BLEND");
        assertThat(PipePages.noun("journal")).isEqualTo("JOURNAL ENTRY");
    }

    @Test
    public void withLine_joinsWhateverExists() {
        assertThat(PipePages.withLine("Peterson 317", "London Mixture")).isEqualTo("Peterson 317 with London Mixture");
        assertThat(PipePages.withLine("Peterson 317", null)).isEqualTo("Peterson 317");
        assertThat(PipePages.withLine(null, "London Mixture")).isEqualTo("London Mixture");
        assertThat(PipePages.withLine("", "London Mixture")).isEqualTo("London Mixture");
        assertThat(PipePages.withLine(null, null)).isEmpty();
    }

    @Test
    public void journalDetail_formatsTheHeaderLine() {
        assertThat(PipePages.journalDetail("Mon 1 Jan", "Pipe with Blend", "Notes")).isEqualTo("Mon 1 Jan  •  Pipe with Blend\nNotes");
        assertThat(PipePages.journalDetail("Mon 1 Jan", "", "Notes")).isEqualTo("Mon 1 Jan\nNotes");
        assertThat(PipePages.journalDetail("", "Pipe with Blend", "Notes")).isEqualTo("Notes");
    }

    @Test
    public void ritualMask_toggleAndQuery() {
        int mask = 0;
        for (int bit = 0; bit < PipePages.RITUAL.length; bit++) {
            assertThat(PipePages.stepDone(mask, bit)).isFalse();
            mask = PipePages.toggleStep(mask, bit);
            assertThat(PipePages.stepDone(mask, bit)).isTrue();
        }
        assertThat(mask).isEqualTo(0b11111);
        mask = PipePages.toggleStep(mask, 2);
        assertThat(mask).isEqualTo(0b11011);
        assertThat(PipePages.stepDone(mask, 2)).isFalse();
    }

    @Test
    public void sidePadding_centresACappedColumn() {
        // phone: narrower than the cap, minimum padding
        assertThat(PipePages.sidePadding(1080, 36, 2160)).isEqualTo(36);
        // tablet landscape: centred
        assertThat(PipePages.sidePadding(2560, 48, 1440)).isEqualTo(560);
        // just over the cap: never below the minimum
        assertThat(PipePages.sidePadding(1460, 48, 1440)).isEqualTo(48);
    }

    @Test
    public void exportFileName_isDated() {
        Calendar c = new GregorianCalendar(2026, Calendar.NOVEMBER, 7);
        assertThat(PipePages.exportFileName(c.getTime())).isEqualTo("pipe_collection_2026-11-07.json");
    }
}
