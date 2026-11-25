package com.mirboard.domain.game.wordtemp.rules;

import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.Optional;
import java.util.Set;

/**
 * 힌트와 정답의 포함 규칙(`docs/rules-wordtemp.md` §5.2, §15-2·3).
 *
 * <ul>
 *   <li>힌트가 정답(또는 같은 말)과 같거나 그것을 <b>포함</b> → {@code HINT_IS_ANSWER}(정답 수영 → 수영장 ✗)</li>
 *   <li><b>2자 이상</b>인 힌트가 정답(또는 같은 말) 안에 들어 있음 → {@code HINT_INSIDE_ANSWER}(정답 눈사람 → 사람 ✗, 눈 ○)</li>
 * </ul>
 */
public final class HintRules {

    private HintRules() {
    }

    /** 정규화된 힌트가 어기는 포함 규칙. 없으면 빈 값. */
    public static Optional<RejectionReason> containment(AnswerEntry answer, String hint) {
        Set<String> forms = AnswerForms.of(answer);
        for (String form : forms) {
            if (hint.contains(form)) {
                return Optional.of(RejectionReason.HINT_IS_ANSWER);
            }
        }
        if (hint.length() >= 2) {
            for (String form : forms) {
                if (form.contains(hint)) {
                    return Optional.of(RejectionReason.HINT_INSIDE_ANSWER);
                }
            }
        }
        return Optional.empty();
    }
}
