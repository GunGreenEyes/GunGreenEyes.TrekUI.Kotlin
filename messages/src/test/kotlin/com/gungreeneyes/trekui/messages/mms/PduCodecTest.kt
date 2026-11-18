package com.gungreeneyes.trekui.messages.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PduCodecTest {
    private fun fakeJpeg(size: Int): ByteArray {
        val b = ByteArray(size) { (it * 31).toByte() }
        b[0] = 0xFF.toByte()
        b[1] = 0xD8.toByte()
        return b
    }

    @Test
    fun sendRequestRoundTrip() {
        val jpeg = fakeJpeg(5000)
        val req = MmsBuilder.sendRequest(
            listOf("+1 555 010 2345", "bob@example.com"), "Hello été 😀", listOf(jpeg), "T1234", 1_700_000_000L,
        )
        val back = PduCodec.decode(PduCodec.encode(req))
        assertThat(back).isNotNull()
        back!!
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_SEND_REQ)
        assertThat(back.transactionId).isEqualTo("T1234")
        assertThat(back.version).isEqualTo(0x12)
        assertThat(back.dateSeconds).isEqualTo(1_700_000_000L)
        assertThat(back.from).isNull()
        assertThat(back.to).containsExactly("+15550102345/TYPE=PLMN", "bob@example.com").inOrder()
        assertThat(back.contentType).isEqualTo(PduCodec.MULTIPART_RELATED)
        assertThat(back.start).isEqualTo("<smil>")
        assertThat(back.startType).isEqualTo(PduCodec.SMIL)
        assertThat(back.parts).hasSize(3)
        assertThat(back.parts[0].isSmil).isTrue()
        assertThat(back.parts[0].contentId).isEqualTo("smil")
        assertThat(back.images()).hasSize(1)
        assertThat(back.images()[0].data).isEqualTo(jpeg)
        assertThat(back.images()[0].location).isEqualTo("image0.jpg")
        assertThat(back.text()).isEqualTo("Hello été 😀")
    }

    @Test
    fun retrieveConfRoundTripWithNonAsciiSubjectAndSender() {
        val pdu = MmsPdu().apply {
            messageType = MmsPdu.TYPE_RETRIEVE_CONF
            transactionId = "abc"
            messageId = "msg-1"
            dateSeconds = 1234567
            from = "+15550102345/TYPE=PLMN"
            to.add("+15559990000/TYPE=PLMN")
            cc.add("+15551112222/TYPE=PLMN")
            subject = "Résumé"
            contentType = PduCodec.MULTIPART_MIXED
            parts.add(MmsPart.text("first", "a.txt"))
            parts.add(MmsPart.image("image/png", byteArrayOf(1, 2, 3), "p.png"))
        }
        val back = PduCodec.decode(PduCodec.encode(pdu))!!
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_RETRIEVE_CONF)
        assertThat(back.messageId).isEqualTo("msg-1")
        assertThat(back.from).isEqualTo("+15550102345/TYPE=PLMN")
        assertThat(back.to).containsExactly("+15559990000/TYPE=PLMN")
        assertThat(back.cc).containsExactly("+15551112222/TYPE=PLMN")
        assertThat(back.subject).isEqualTo("Résumé")
        assertThat(back.contentType).isEqualTo(PduCodec.MULTIPART_MIXED)
        assertThat(back.parts[0].charset).isEqualTo(PduCodec.CHARSET_UTF8)
        assertThat(back.parts[1].contentType).isEqualTo("image/png")
        assertThat(back.parts[1].data).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(back.text()).isEqualTo("first")
    }

    @Test
    fun notificationIndRoundTrip() {
        val n = MmsPdu().apply {
            messageType = MmsPdu.TYPE_NOTIFICATION_IND
            transactionId = "tx9"
            from = "+15550102345/TYPE=PLMN"
            messageSize = 123456
            contentLocation = "http://mmsc.example/abc"
        }
        val back = PduCodec.decode(PduCodec.encode(n))!!
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_NOTIFICATION_IND)
        assertThat(back.contentLocation).isEqualTo("http://mmsc.example/abc")
        assertThat(back.messageSize).isEqualTo(123456L)
        assertThat(back.from).isEqualTo("+15550102345/TYPE=PLMN")
    }

    @Test
    fun sendConfRoundTrip() {
        val c = MmsPdu().apply {
            messageType = MmsPdu.TYPE_SEND_CONF
            transactionId = "t"
            responseStatus = MmsPdu.RESPONSE_OK
            messageId = "carrier-id"
        }
        val back = PduCodec.decode(PduCodec.encode(c))!!
        assertThat(back.responseStatus).isEqualTo(MmsPdu.RESPONSE_OK)
        assertThat(back.messageId).isEqualTo("carrier-id")
    }

    @Test
    fun largePartsUseLongLengths() {
        val big = fakeJpeg(200_000)
        val req = MmsBuilder.sendRequest(listOf("5550102345"), null, listOf(big, fakeJpeg(40)), "T", 1L)
        val back = PduCodec.decode(PduCodec.encode(req))!!
        assertThat(back.images()).hasSize(2)
        assertThat(back.images()[0].data).isEqualTo(big)
        assertThat(back.text()).isEmpty()
    }

    @Test
    fun malformedInputDecodesToNull() {
        assertThat(PduCodec.decode(null)).isNull()
        assertThat(PduCodec.decode(byteArrayOf(0x8C.toByte()))).isNull()
        val truncated = PduCodec.encode(MmsBuilder.sendRequest(listOf("5550102345"), "hi", null, "T", 1L)).copyOf(20)
        assertThat(PduCodec.decode(truncated)).isNull()
    }

    @Test
    fun uintvarAndValueLengthEncoding() {
        val o = PduCodec.Out()
        o.uintvar(0x7F)
        o.uintvar(0x80)
        o.valueLength(30)
        o.valueLength(31)
        assertThat(o.bytes()).isEqualTo(byteArrayOf(0x7F, 0x81.toByte(), 0x00, 30, 31, 31))
        val input = PduCodec.In(o.bytes(), 0, o.size())
        assertThat(input.uintvar()).isEqualTo(0x7FL)
        assertThat(input.uintvar()).isEqualTo(0x80L)
    }

    @Test
    fun smilListsEverySlide() {
        val smil = MmsBuilder.smil(2, true)
        assertThat(smil).contains("image0.jpg")
        assertThat(smil).contains("image1.jpg")
        assertThat(smil).contains("text0.txt")
        assertThat(smil.toByteArray(Charsets.UTF_8).size).isGreaterThan(0)
    }
}
