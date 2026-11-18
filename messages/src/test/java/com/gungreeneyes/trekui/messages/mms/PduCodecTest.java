package com.gungreeneyes.trekui.messages.mms;

import static com.google.common.truth.Truth.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class PduCodecTest {
    private static byte[] fakeJpeg(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31);
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        return b;
    }

    @Test
    public void sendRequestRoundTrip() {
        byte[] jpeg = fakeJpeg(5000);
        MmsPdu req = MmsBuilder.sendRequest(Arrays.asList("+1 555 010 2345", "bob@example.com"),
                "Hello été 😀", Collections.singletonList(jpeg), "T1234", 1_700_000_000L);
        MmsPdu back = PduCodec.decode(PduCodec.encode(req));
        assertThat(back).isNotNull();
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_SEND_REQ);
        assertThat(back.transactionId).isEqualTo("T1234");
        assertThat(back.version).isEqualTo(0x12);
        assertThat(back.dateSeconds).isEqualTo(1_700_000_000L);
        assertThat(back.from).isNull();
        assertThat(back.to).containsExactly("+15550102345/TYPE=PLMN", "bob@example.com").inOrder();
        assertThat(back.contentType).isEqualTo(PduCodec.MULTIPART_RELATED);
        assertThat(back.start).isEqualTo("<smil>");
        assertThat(back.startType).isEqualTo(PduCodec.SMIL);
        assertThat(back.parts).hasSize(3);
        assertThat(back.parts.get(0).isSmil()).isTrue();
        assertThat(back.parts.get(0).contentId).isEqualTo("smil");
        assertThat(back.images()).hasSize(1);
        assertThat(back.images().get(0).data).isEqualTo(jpeg);
        assertThat(back.images().get(0).location).isEqualTo("image0.jpg");
        assertThat(back.text()).isEqualTo("Hello été 😀");
    }

    @Test
    public void retrieveConfRoundTripWithNonAsciiSubjectAndSender() {
        MmsPdu pdu = new MmsPdu();
        pdu.messageType = MmsPdu.TYPE_RETRIEVE_CONF;
        pdu.transactionId = "abc";
        pdu.messageId = "msg-1";
        pdu.dateSeconds = 1234567;
        pdu.from = "+15550102345/TYPE=PLMN";
        pdu.to.add("+15559990000/TYPE=PLMN");
        pdu.cc.add("+15551112222/TYPE=PLMN");
        pdu.subject = "Résumé";
        pdu.contentType = PduCodec.MULTIPART_MIXED;
        pdu.parts.add(MmsPart.text("first", "a.txt"));
        pdu.parts.add(MmsPart.image("image/png", new byte[]{1, 2, 3}, "p.png"));
        MmsPdu back = PduCodec.decode(PduCodec.encode(pdu));
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_RETRIEVE_CONF);
        assertThat(back.messageId).isEqualTo("msg-1");
        assertThat(back.from).isEqualTo("+15550102345/TYPE=PLMN");
        assertThat(back.to).containsExactly("+15559990000/TYPE=PLMN");
        assertThat(back.cc).containsExactly("+15551112222/TYPE=PLMN");
        assertThat(back.subject).isEqualTo("Résumé");
        assertThat(back.contentType).isEqualTo(PduCodec.MULTIPART_MIXED);
        assertThat(back.parts.get(0).charset).isEqualTo(PduCodec.CHARSET_UTF8);
        assertThat(back.parts.get(1).contentType).isEqualTo("image/png");
        assertThat(back.parts.get(1).data).isEqualTo(new byte[]{1, 2, 3});
        assertThat(back.text()).isEqualTo("first");
    }

    @Test
    public void notificationIndRoundTrip() {
        MmsPdu n = new MmsPdu();
        n.messageType = MmsPdu.TYPE_NOTIFICATION_IND;
        n.transactionId = "tx9";
        n.from = "+15550102345/TYPE=PLMN";
        n.messageSize = 123456;
        n.contentLocation = "http://mmsc.example/abc";
        MmsPdu back = PduCodec.decode(PduCodec.encode(n));
        assertThat(back.messageType).isEqualTo(MmsPdu.TYPE_NOTIFICATION_IND);
        assertThat(back.contentLocation).isEqualTo("http://mmsc.example/abc");
        assertThat(back.messageSize).isEqualTo(123456);
        assertThat(back.from).isEqualTo("+15550102345/TYPE=PLMN");
    }

    @Test
    public void sendConfRoundTrip() {
        MmsPdu c = new MmsPdu();
        c.messageType = MmsPdu.TYPE_SEND_CONF;
        c.transactionId = "t";
        c.responseStatus = MmsPdu.RESPONSE_OK;
        c.messageId = "carrier-id";
        MmsPdu back = PduCodec.decode(PduCodec.encode(c));
        assertThat(back.responseStatus).isEqualTo(MmsPdu.RESPONSE_OK);
        assertThat(back.messageId).isEqualTo("carrier-id");
    }

    @Test
    public void largePartsUseLongLengths() {
        byte[] big = fakeJpeg(200_000);
        MmsPdu req = MmsBuilder.sendRequest(Collections.singletonList("5550102345"), null,
                Arrays.asList(big, fakeJpeg(40)), "T", 1L);
        MmsPdu back = PduCodec.decode(PduCodec.encode(req));
        assertThat(back.images()).hasSize(2);
        assertThat(back.images().get(0).data).isEqualTo(big);
        assertThat(back.text()).isEmpty();
    }

    @Test
    public void malformedInputDecodesToNull() {
        assertThat(PduCodec.decode(null)).isNull();
        assertThat(PduCodec.decode(new byte[]{(byte) 0x8C})).isNull();
        byte[] truncated = Arrays.copyOf(PduCodec.encode(MmsBuilder.sendRequest(
                Collections.singletonList("5550102345"), "hi", null, "T", 1L)), 20);
        assertThat(PduCodec.decode(truncated)).isNull();
    }

    @Test
    public void uintvarAndValueLengthEncoding() {
        PduCodec.Out o = new PduCodec.Out();
        o.uintvar(0x7F);
        o.uintvar(0x80);
        o.valueLength(30);
        o.valueLength(31);
        assertThat(o.bytes()).isEqualTo(new byte[]{0x7F, (byte) 0x81, 0x00, 30, 31, 31});
        PduCodec.In in = new PduCodec.In(o.bytes(), 0, o.size());
        assertThat(in.uintvar()).isEqualTo(0x7F);
        assertThat(in.uintvar()).isEqualTo(0x80);
    }

    @Test
    public void smilListsEverySlide() {
        String smil = MmsBuilder.smil(2, true);
        assertThat(smil).contains("image0.jpg");
        assertThat(smil).contains("image1.jpg");
        assertThat(smil).contains("text0.txt");
        assertThat(smil.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(0);
    }
}
