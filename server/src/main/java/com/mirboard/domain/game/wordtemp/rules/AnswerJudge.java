package com.mirboard.domain.game.wordtemp.rules;

import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.List;
import java.util.Set;

/**
 * 정답 판정(`docs/rules-wordtemp.md` §7). 정규화 → 정답 집합과 같음 → 아니면 끝말 <b>하나</b>를 뗀 값이 정답 집합에 있음. 오타는 인정하지 않는다.
 */
public final class AnswerJudge {

    /** 끝말 목록 [결정] — 뗀 뒤 1자 이상 남을 때만, 두 번 떼지 않는다. 정답 쪽은 떼지 않는다. */
    public static final List<String> ENDINGS = List.of("요", "다", "이다", "임", "입니다", "이요", "예요", "이에요");

    private AnswerJudge() {
    }

    /** @param guess §5.1 로 정규화된 추리 답(패스는 부르지 않는다) */
    public static boolean correct(AnswerEntry answer, String guess) {
        if (guess == null || guess.isEmpty()) {
            return false;
        }
        Set<String> forms = AnswerForms.of(answer);
        if (forms.contains(guess)) {
            return true;
        }
        for (String ending : ENDINGS) {
            if (guess.length() > ending.length() && guess.endsWith(ending)
                    && forms.contains(guess.substring(0, guess.length() - ending.length()))) {
                return true;
            }
        }
        return false;
    }
}
