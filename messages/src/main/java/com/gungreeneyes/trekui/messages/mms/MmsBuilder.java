package com.gungreeneyes.trekui.messages.mms;

import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds an outgoing m-send-req (pure, unit-tested): images and text in a multipart/related body whose
 * first part is a generated SMIL layout (image above text, one slide per image), which is what carriers
 * and receiving phones expect. Recipients are written as {@code <number>/TYPE=PLMN} (e-mail addresses
 * as they are).
 */
public final class MmsBuilder {
    /** Content-ID of the SMIL part; the multipart Start parameter points at it. */
    public static final String SMIL_ID = "smil";

    private MmsBuilder() {}

    /**
     * An m-send-req to {@code recipients} with optional {@code text} and {@code images} (JPEG bytes).
     *
     * @param transactionId unique per message (the carrier echoes it in m-send-conf)
     * @param dateSeconds   send time, seconds since the epoch
     */
    public static MmsPdu sendRequest(List<String> recipients, String text, List<byte[]> images,
                                     String transactionId, long dateSeconds) {
        MmsPdu pdu = new MmsPdu();
        pdu.messageType = MmsPdu.TYPE_SEND_REQ;
        pdu.transactionId = transactionId;
        pdu.dateSeconds = dateSeconds;
        for (String r : recipients) pdu.to.add(mmsAddress(r));
        pdu.contentType = PduCodec.MULTIPART_RELATED;
        pdu.start = "<" + SMIL_ID + ">";
        pdu.startType = PduCodec.SMIL;

        int imageCount = images == null ? 0 : images.size();
        boolean hasText = text != null && !text.trim().isEmpty();
        pdu.parts.add(new MmsPart(PduCodec.SMIL, "smil.xml", SMIL_ID, PduCodec.CHARSET_UTF8,
                smil(imageCount, hasText).getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < imageCount; i++) {
            pdu.parts.add(MmsPart.image(PduCodec.IMAGE_JPEG, images.get(i), "image" + i + ".jpg"));
        }
        if (hasText) pdu.parts.add(MmsPart.text(text, "text0.txt"));
        return pdu;
    }

    /** A phone number as an MMS address ({@code +15550102345/TYPE=PLMN}); e-mail unchanged. */
    public static String mmsAddress(String address) {
        String n = PhoneNumbers.normalize(address);
        if (n.indexOf('@') >= 0 || !PhoneNumbers.isPhoneLike(n)) return n;
        return n + "/TYPE=PLMN";
    }

    /**
     * SMIL layout: an Image region above a Text region; one {@code <par>} slide per image, the text on
     * the first slide (or a text-only slide when there are no images).
     */
    static String smil(int imageCount, boolean hasText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<smil><head><layout><root-layout/>");
        if (imageCount > 0) sb.append("<region id=\"Image\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\" fit=\"meet\"/>");
        if (hasText) sb.append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>");
        sb.append("</layout></head><body>");
        int slides = Math.max(1, imageCount);
        for (int i = 0; i < slides; i++) {
            sb.append("<par dur=\"5000ms\">");
            if (i < imageCount) sb.append("<img src=\"image").append(i).append(".jpg\" region=\"Image\"/>");
            if (hasText && i == 0) sb.append("<text src=\"text0.txt\" region=\"Text\"/>");
            sb.append("</par>");
        }
        sb.append("</body></smil>");
        return sb.toString();
    }
}
