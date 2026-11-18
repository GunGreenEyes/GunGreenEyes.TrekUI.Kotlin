package com.gungreeneyes.trekui.messages.mms;

import java.nio.charset.StandardCharsets;

/** One body part of an MMS (text, image or SMIL layout). Plain value object. */
public final class MmsPart {
    /** MIME type, e.g. {@code text/plain}, {@code image/jpeg}, {@code application/smil}. */
    public final String contentType;
    /** Content-Location / file name, e.g. {@code image0.jpg}; may be null. */
    public final String location;
    /** Content-ID without angle brackets, e.g. {@code image0}; may be null. */
    public final String contentId;
    /** IANA MIBenum charset of a text part (106 = UTF-8), or 0 when not given. */
    public final int charset;
    public final byte[] data;

    public MmsPart(String contentType, String location, String contentId, int charset, byte[] data) {
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
        this.location = location;
        this.contentId = contentId;
        this.charset = charset;
        this.data = data == null ? new byte[0] : data;
    }

    /** A UTF-8 text/plain part. */
    public static MmsPart text(String text, String name) {
        return new MmsPart(PduCodec.TEXT_PLAIN, name, stripExtension(name), PduCodec.CHARSET_UTF8,
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** An image part with its bytes. */
    public static MmsPart image(String contentType, byte[] data, String name) {
        return new MmsPart(contentType, name, stripExtension(name), 0, data);
    }

    public boolean isText() {
        return contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain");
    }

    public boolean isImage() {
        return contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }

    public boolean isSmil() {
        return contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/smil");
    }

    /** The text of a text part, decoded with its charset (UTF-8 when unknown). */
    public String text() {
        return PduCodec.decodeText(data, charset);
    }

    private static String stripExtension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
