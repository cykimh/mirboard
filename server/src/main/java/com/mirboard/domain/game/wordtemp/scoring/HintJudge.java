package com.mirboard.domain.game.wordtemp.scoring;

import com.mirboard.domain.game.wordtemp.words.AnswerEntry;

/**
 * (정답, 힌트) 한 쌍을 모델에 묻는 한 번의 호출(D-317). 운영 구현은 {@link ClaudeHintJudge}, 테스트는 가짜를 쓴다 — 네트워크 호출 0.
 * 상한·캐시·동시 실행 제한은 {@link AsyncLiveHintScorer} 가 맡고 여기는 묻고 답을 읽기만 한다.
 */
public interface HintJudge {

    /**
     * @throws JudgeException 실패·시간 초과·거절·형식 밖 응답. {@link JudgeException#fatal()} 이면 다시 불러도 안 되는 실패(키·요청 꼴)다
     */
    Verdict judge(AnswerEntry answer, String hint);

    /** @param reason 모델의 한 줄 까닭 — 로그·검수용이고 화면에 쓰지 않는다 */
    record Verdict(boolean validNoun, int score, String reason) {
    }

    final class JudgeException extends RuntimeException {

        private final boolean fatal;

        public JudgeException(boolean fatal, String message, Throwable cause) {
            super(message, cause);
            this.fatal = fatal;
        }

        public JudgeException(boolean fatal, String message) {
            this(fatal, message, null);
        }

        /** 400·401·403·404 — 그 인스턴스에서 실시간 채점을 멈춘다(설계서 §5.5). */
        public boolean fatal() {
            return fatal;
        }
    }
}
