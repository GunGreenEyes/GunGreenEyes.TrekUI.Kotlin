package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.List;

public class LayoutsTest {
    private static Layouts.Options opts() {
        return new Layouts.Options();
    }

    @Test
    public void englishIsQwerty() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.EN, opts());
        assertThat(l.rows).hasSize(4);
        assertThat(l.rowString(0)).isEqualTo("qwertyuiop");
        assertThat(l.rowString(1)).isEqualTo("asdfghjkl");
        assertThat(l.rowString(2)).isEqualTo("#zxcvbnm#");
        assertThat(l.rows.get(2).get(0).kind).isEqualTo(Key.SHIFT);
        assertThat(l.rows.get(2).get(8).kind).isEqualTo(Key.DELETE);
    }

    @Test
    public void germanIsQwertzWithUmlauts() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.DE, opts());
        assertThat(l.rowString(0)).isEqualTo("qwertzuiopü");
        assertThat(l.rowString(1)).isEqualTo("asdfghjklöä");
        assertThat(l.rowString(2)).isEqualTo("#yxcvbnm#");
    }

    @Test
    public void frenchIsAzerty() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.FR, opts());
        assertThat(l.rowString(0)).isEqualTo("azertyuiop");
        assertThat(l.rowString(1)).isEqualTo("qsdfghjklm");
        assertThat(l.rowString(2)).isEqualTo("#wxcvbn'#");
    }

    @Test
    public void spanishHasEnye() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.ES, opts());
        assertThat(l.rowString(1)).isEqualTo("asdfghjklñ");
    }

    @Test
    public void everyLetterPageRowFitsTheWidestRow() {
        for (Language lang : Language.values()) {
            KeyLayout l = Layouts.build(Mode.LETTERS, lang, opts());
            float max = l.maxRowWeight();
            assertThat(max).isAtLeast(Layouts.MIN_ROW);
            // shift row and bottom row fill the full width
            assertThat(l.rowWeight(2)).isWithin(0.001f).of(max);
            assertThat(l.rowWeight(3)).isWithin(0.001f).of(max);
        }
    }

    @Test
    public void bottomRowHasSpaceEnterEmojiAndOptionalGlobe() {
        KeyLayout plain = Layouts.build(Mode.LETTERS, Language.EN, opts());
        assertThat(plain.find(Key.SPACE)).isNotNull();
        assertThat(plain.find(Key.ENTER)).isNotNull();
        assertThat(plain.find(Key.EMOJI)).isNotNull();
        assertThat(plain.find(Key.GLOBE)).isNull();
        Layouts.Options o = opts();
        o.globe = true;
        KeyLayout withGlobe = Layouts.build(Mode.LETTERS, Language.EN, o);
        assertThat(withGlobe.find(Key.GLOBE)).isNotNull();
        assertThat(withGlobe.rowWeight(3)).isWithin(0.001f).of(withGlobe.maxRowWeight());
    }

    @Test
    public void emailAndUrlFieldsSwapTheComma() {
        Layouts.Options o = opts();
        o.field = FieldRules.FieldKind.EMAIL;
        assertThat(Layouts.build(Mode.LETTERS, Language.EN, o).findChar("@")).isNotNull();
        o.field = FieldRules.FieldKind.URL;
        KeyLayout url = Layouts.build(Mode.LETTERS, Language.EN, o);
        assertThat(url.findChar("/")).isNotNull();
        assertThat(url.findChar(",")).isNull();
    }

    @Test
    public void topRowCarriesDigitHintsUnlessDigitRowIsOn() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.EN, opts());
        Key q = l.findChar("q");
        assertThat(q.hint).isEqualTo("1");
        assertThat(q.alternates.get(0)).isEqualTo("1");
        assertThat(l.findChar("p").hint).isEqualTo("0");
        assertThat(l.findChar("a").hint).isNull();

        Layouts.Options o = opts();
        o.digitRow = true;
        KeyLayout d = Layouts.build(Mode.LETTERS, Language.EN, o);
        assertThat(d.rows).hasSize(5);
        assertThat(d.rowString(0)).isEqualTo("1234567890");
        assertThat(d.findChar("q").hint).isNull();
    }

    @Test
    public void symbolPagesLinkToEachOtherAndBack() {
        KeyLayout s1 = Layouts.build(Mode.SYMBOLS, Language.EN, opts());
        assertThat(s1.rowString(0)).isEqualTo("1234567890");
        assertThat(s1.find(Key.TO_SYMBOLS_2)).isNotNull();
        assertThat(s1.find(Key.TO_LETTERS)).isNotNull();
        assertThat(s1.findChar("$")).isNotNull();
        KeyLayout s2 = Layouts.build(Mode.SYMBOLS_2, Language.EN, opts());
        assertThat(s2.find(Key.TO_SYMBOLS)).isNotNull();
        assertThat(s2.find(Key.TO_LETTERS)).isNotNull();
    }

    @Test
    public void euroLanguagesPutTheEuroFirst() {
        KeyLayout s1 = Layouts.build(Mode.SYMBOLS, Language.DE, opts());
        Key euro = s1.findChar("€");
        assertThat(euro).isNotNull();
        assertThat(euro.alternates).contains("$");
        assertThat(s1.findChar("$")).isNull();
    }

    @Test
    public void numberAndPhonePads() {
        KeyLayout n = Layouts.build(Mode.NUMERIC, Language.EN, opts());
        assertThat(n.rows).hasSize(4);
        assertThat(n.rowString(0)).isEqualTo("123-");
        assertThat(n.findChar("0")).isNotNull();
        assertThat(n.find(Key.ENTER)).isNotNull();
        KeyLayout p = Layouts.build(Mode.PHONE, Language.EN, opts());
        assertThat(p.rowString(3)).isEqualTo("*0##");
        assertThat(p.findChar("0").alternates).containsExactly("+");
        assertThat(p.find(Key.SHIFT)).isNull();
    }

    @Test
    public void emojiPageHasAWayBack() {
        KeyLayout e = Layouts.build(Mode.EMOJI, Language.EN, opts());
        assertThat(e.find(Key.TO_LETTERS)).isNotNull();
        assertThat(e.find(Key.DELETE)).isNotNull();
        List<Key> first = e.rows.get(0);
        assertThat(first).hasSize(Layouts.EMOJI_COLUMNS);
    }

    @Test
    public void lettersAreLetters() {
        KeyLayout l = Layouts.build(Mode.LETTERS, Language.FR, opts());
        assertThat(l.findChar("é")).isNull(); // accents only via long press
        assertThat(l.findChar("a").isLetter()).isTrue();
        assertThat(l.findChar("'").isLetter()).isFalse();
    }
}
