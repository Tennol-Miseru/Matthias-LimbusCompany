package com.laevatein.combat;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure matching rules shared by the server and the dependency-free regression test. */
final class RevengeCalls {
    static final int FULL = 3;
    private static final String[][] WORDS = {
        {"马蒂亚斯", "马提亚斯", "馬蒂亞斯", "馬提亞斯"},
        {"中指"},
        {"蜘蛛巢"},
        {"毅力哇啦", "毅力瓦拉", "毅力哇拉", "毅力瓦啦"},
        {"伊利哇啦", "伊利瓦拉", "伊利哇拉", "伊利瓦啦"},
        {"良秀"}
    };
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{Z}\\s]+");
    private static final Pattern ENGLISH_NAME = Pattern.compile("(?<![a-z0-9])matthias(?![a-z0-9])");

    private RevengeCalls() {}

    static int matchCount(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String compact = SEPARATORS.matcher(text).replaceAll("");
        int count = 0;
        boolean middle = false;
        for (int i = 0; i < WORDS.length; i++) {
            for (String word : WORDS[i]) {
                if (!compact.contains(word)) continue;
                if (i == 1) middle = true;
                else count++;
                break;
            }
            if (i == 0 && ENGLISH_NAME.matcher(text).find()
                && java.util.Arrays.stream(WORDS[0]).noneMatch(compact::contains)) count++;
        }
        // The common word is a call by itself, or when paired with a clear reference.
        return count + (middle && (count > 0 || compact.equals("中指")) ? 1 : 0);
    }

    static int angerAfter(int anger, int distinctCalls) {
        if (distinctCalls <= 0) return anger;
        return distinctCalls >= 2 ? FULL : Math.min(FULL, anger + 1);
    }
}
