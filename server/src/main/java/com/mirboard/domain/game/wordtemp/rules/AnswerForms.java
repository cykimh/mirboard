package com.mirboard.domain.game.wordtemp.rules;

import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 정답 집합 A = {정답 낱말} ∪ 같은 말 목록, 전부 §5.1 정규화를 거친 값(`docs/rules-wordtemp.md` §7-1). 힌트 포함 검사와 정답 판정이 같이 쓴다.
 * 정규화에 실패하는 같은 말(은행 오타)은 조용히 빠진다 — 은행 검증은 데이터 쪽 테스트의 몫.
 */
public final class AnswerForms {

    private AnswerForms() {
    }

    public static Set<String> of(AnswerEntry answer) {
        Set<String> forms = new LinkedHashSet<>();
        WordNormalizer.normalize(answer.word()).ifPresent(forms::add);
        for (String alias : answer.aliases()) {
            WordNormalizer.normalize(alias).ifPresent(forms::add);
        }
        return forms;
    }
}
