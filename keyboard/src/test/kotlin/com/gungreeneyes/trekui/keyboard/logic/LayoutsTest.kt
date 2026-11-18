package com.gungreeneyes.trekui.keyboard.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LayoutsTest {
    private fun opts() = Layouts.Options()

    @Test
    fun englishIsQwerty() {
        val l = Layouts.build(Mode.LETTERS, Language.EN, opts())
        assertThat(l.rows).hasSize(4)
        assertThat(l.rowString(0)).isEqualTo("qwertyuiop")
        assertThat(l.rowString(1)).isEqualTo("asdfghjkl")
        assertThat(l.rowString(2)).isEqualTo("#zxcvbnm#")
        assertThat(l.rows[2][0].kind).isEqualTo(Key.SHIFT)
        assertThat(l.rows[2][8].kind).isEqualTo(Key.DELETE)
    }

    @Test
    fun germanIsQwertzWithUmlauts() {
        val l = Layouts.build(Mode.LETTERS, Language.DE, opts())
        assertThat(l.rowString(0)).isEqualTo("qwertzuiopü")
        assertThat(l.rowString(1)).isEqualTo("asdfghjklöä")
        assertThat(l.rowString(2)).isEqualTo("#yxcvbnm#")
    }

    @Test
    fun frenchIsAzerty() {
        val l = Layouts.build(Mode.LETTERS, Language.FR, opts())
        assertThat(l.rowString(0)).isEqualTo("azertyuiop")
        assertThat(l.rowString(1)).isEqualTo("qsdfghjklm")
        assertThat(l.rowString(2)).isEqualTo("#wxcvbn'#")
    }

    @Test
    fun spanishHasEnye() {
        val l = Layouts.build(Mode.LETTERS, Language.ES, opts())
        assertThat(l.rowString(1)).isEqualTo("asdfghjklñ")
    }

    @Test
    fun everyLetterPageRowFitsTheWidestRow() {
        for (lang in Language.entries) {
            val l = Layouts.build(Mode.LETTERS, lang, opts())
            val max = l.maxRowWeight()
            assertThat(max).isAtLeast(Layouts.MIN_ROW)
            // shift row and bottom row fill the full width
            assertThat(l.rowWeight(2)).isWithin(0.001f).of(max)
            assertThat(l.rowWeight(3)).isWithin(0.001f).of(max)
        }
    }

    @Test
    fun bottomRowHasSpaceEnterEmojiAndOptionalGlobe() {
        val plain = Layouts.build(Mode.LETTERS, Language.EN, opts())
        assertThat(plain.find(Key.SPACE)).isNotNull()
        assertThat(plain.find(Key.ENTER)).isNotNull()
        assertThat(plain.find(Key.EMOJI)).isNotNull()
        assertThat(plain.find(Key.GLOBE)).isNull()
        val o = opts()
        o.globe = true
        val withGlobe = Layouts.build(Mode.LETTERS, Language.EN, o)
        assertThat(withGlobe.find(Key.GLOBE)).isNotNull()
        assertThat(withGlobe.rowWeight(3)).isWithin(0.001f).of(withGlobe.maxRowWeight())
    }

    @Test
    fun emailAndUrlFieldsSwapTheComma() {
        val o = opts()
        o.field = FieldRules.FieldKind.EMAIL
        assertThat(Layouts.build(Mode.LETTERS, Language.EN, o).findChar("@")).isNotNull()
        o.field = FieldRules.FieldKind.URL
        val url = Layouts.build(Mode.LETTERS, Language.EN, o)
        assertThat(url.findChar("/")).isNotNull()
        assertThat(url.findChar(",")).isNull()
    }

    @Test
    fun topRowCarriesDigitHintsUnlessDigitRowIsOn() {
        val l = Layouts.build(Mode.LETTERS, Language.EN, opts())
        val q = l.findChar("q")!!
        assertThat(q.hint).isEqualTo("1")
        assertThat(q.alternates[0]).isEqualTo("1")
        assertThat(l.findChar("p")!!.hint).isEqualTo("0")
        assertThat(l.findChar("a")!!.hint).isNull()

        val o = opts()
        o.digitRow = true
        val d = Layouts.build(Mode.LETTERS, Language.EN, o)
        assertThat(d.rows).hasSize(5)
        assertThat(d.rowString(0)).isEqualTo("1234567890")
        assertThat(d.findChar("q")!!.hint).isNull()
    }

    @Test
    fun symbolPagesLinkToEachOtherAndBack() {
        val s1 = Layouts.build(Mode.SYMBOLS, Language.EN, opts())
        assertThat(s1.rowString(0)).isEqualTo("1234567890")
        assertThat(s1.find(Key.TO_SYMBOLS_2)).isNotNull()
        assertThat(s1.find(Key.TO_LETTERS)).isNotNull()
        assertThat(s1.findChar("$")).isNotNull()
        val s2 = Layouts.build(Mode.SYMBOLS_2, Language.EN, opts())
        assertThat(s2.find(Key.TO_SYMBOLS)).isNotNull()
        assertThat(s2.find(Key.TO_LETTERS)).isNotNull()
    }

    @Test
    fun euroLanguagesPutTheEuroFirst() {
        val s1 = Layouts.build(Mode.SYMBOLS, Language.DE, opts())
        val euro = s1.findChar("€")
        assertThat(euro).isNotNull()
        assertThat(euro!!.alternates).contains("$")
        assertThat(s1.findChar("$")).isNull()
    }

    @Test
    fun numberAndPhonePads() {
        val n = Layouts.build(Mode.NUMERIC, Language.EN, opts())
        assertThat(n.rows).hasSize(4)
        assertThat(n.rowString(0)).isEqualTo("123-")
        assertThat(n.findChar("0")).isNotNull()
        assertThat(n.find(Key.ENTER)).isNotNull()
        val p = Layouts.build(Mode.PHONE, Language.EN, opts())
        assertThat(p.rowString(3)).isEqualTo("*0##")
        assertThat(p.findChar("0")!!.alternates).containsExactly("+")
        assertThat(p.find(Key.SHIFT)).isNull()
    }

    @Test
    fun emojiPageHasAWayBack() {
        val e = Layouts.build(Mode.EMOJI, Language.EN, opts())
        assertThat(e.find(Key.TO_LETTERS)).isNotNull()
        assertThat(e.find(Key.DELETE)).isNotNull()
        val first = e.rows[0]
        assertThat(first).hasSize(Layouts.EMOJI_COLUMNS)
    }

    @Test
    fun lettersAreLetters() {
        val l = Layouts.build(Mode.LETTERS, Language.FR, opts())
        assertThat(l.findChar("é")).isNull() // accents only via long press
        assertThat(l.findChar("a")!!.isLetter).isTrue()
        assertThat(l.findChar("'")!!.isLetter).isFalse()
    }
}
