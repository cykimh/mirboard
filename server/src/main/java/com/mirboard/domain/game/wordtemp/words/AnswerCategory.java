package com.mirboard.domain.game.wordtemp.words;

import java.util.Map;

/**
 * 정답 은행의 범주 코드(`bank.tsv` 둘째 칸 — `animal`·`food` …) → 화면 이름(D-319). 후보를 본인에게 보여 줄 때만 쓴다 — 엔진의 "한 사람의
 * 후보는 서로 다른 범주" 판정은 코드로 한다. 은행에 새 범주가 생기면 여기에 이름을 더한다(`WordTempRealDataTest` 가 빠진 것을 잡는다).
 */
public final class AnswerCategory {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("animal", "동물"),
            Map.entry("appliance", "가전"),
            Map.entry("art", "예술"),
            Map.entry("body", "몸"),
            Map.entry("clothing", "옷"),
            Map.entry("food", "음식"),
            Map.entry("fruitveg", "과일·채소"),
            Map.entry("household", "생활용품"),
            Map.entry("job", "직업"),
            Map.entry("kitchen", "주방"),
            Map.entry("nature", "자연"),
            Map.entry("place", "장소"),
            Map.entry("school", "학교"),
            Map.entry("sports", "운동"),
            Map.entry("vehicle", "탈것"));

    private AnswerCategory() {
    }

    /** 범주의 화면 이름. 모르는 코드는 그대로 돌려준다(빈 값은 빈 문자열). */
    public static String label(String code) {
        return code == null ? "" : LABELS.getOrDefault(code, code);
    }

    public static boolean known(String code) {
        return LABELS.containsKey(code);
    }
}
