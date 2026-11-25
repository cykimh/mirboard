package com.mirboard.domain.game.wordtemp.words;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 정답 은행의 낱말 하나(`docs/rules-wordtemp.md` §1·§7). 후보로 나눠 줄 때 이 값이 그대로 매치 상태에 들어간다 — 표·은행 버전이 판 도중
 * 바뀌어도 상태의 낱말·같은 말 목록으로 판정이 이어진다(§6.3).
 *
 * @param word     정답 낱말(완성형 한글)
 * @param category 범주 — 한 사람의 후보는 서로 다른 범주(§3.1)
 * @param gloss    뜻풀이 한 줄 — 실시간 채점 입력(S2). 엔진은 읽지 않는다
 * @param aliases  같은 말·표기 변형(휴대폰 ↔ 핸드폰, 케이크 ↔ 케익) — 은행에 적힌 것만 인정한다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnswerEntry(String word, String category, String gloss, List<String> aliases) {

    public AnswerEntry {
        if (word == null || word.isBlank()) {
            throw new IllegalArgumentException("answer word is blank");
        }
        category = category == null ? "" : category;
        gloss = gloss == null ? "" : gloss;
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static AnswerEntry of(String word, String category, String... aliases) {
        return new AnswerEntry(word, category, "", List.of(aliases));
    }
}
