package com.yizhaoqi.smartpai.utils;

import java.nio.charset.StandardCharsets;

public final class TextEncodingRepairUtil {

    private TextEncodingRepairUtil() {
    }

    public static String repairMojibake(String value) {
        if (value == null || value.isBlank() || !looksLikeUtf8Mojibake(value)) {
            return value;
        }
        try {
            return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    public static boolean looksLikeUtf8Mojibake(String value) {
        return value != null && (value.indexOf('å') >= 0
                || value.indexOf('ç') >= 0
                || value.indexOf('è') >= 0
                || value.indexOf('é') >= 0
                || value.indexOf('ä') >= 0
                || value.indexOf('æ') >= 0
                || value.indexOf('ã') >= 0);
    }
}
