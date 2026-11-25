package com.mirboard.domain.game.wordtemp.rules;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 낱말 정규화(`docs/rules-wordtemp.md` §5.1) — 힌트·추리 공통. NFC → 공백·문장부호 제거 → 완성형 한글 음절 1~10자.
 */
public final class WordNormalizer {

    public static final int MAX_LENGTH = 10;

    /** 지우는 것: 모든 공백·구분 문자, 문장부호(유니코드 P 전부 — {@code . , ! ? … · ' "} 등), 물결표. 이모지·기호는 지우지 않는다(→ 거절). */
    private static final Pattern STRIP = Pattern.compile("[\\s\\p{Z}\\p{P}~～]+");
    private static final Pattern HANGUL = Pattern.compile("[가-힣]{1," + MAX_LENGTH + "}");

    private WordNormalizer() {
    }

    /** 정규화한 낱말. 형식에 안 맞으면(빈 값·자모만·영문·숫자·이모지 섞임·11자 이상) 빈 값. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = strip(raw);
        return HANGUL.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }

    /** 공백·문장부호를 빼면 아무것도 안 남는가 — 추리의 "빈 답 = 패스"(§3.3) 판정. */
    public static boolean blank(String raw) {
        return raw == null || strip(raw).isEmpty();
    }

    private static String strip(String raw) {
        return STRIP.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("");
    }
}
