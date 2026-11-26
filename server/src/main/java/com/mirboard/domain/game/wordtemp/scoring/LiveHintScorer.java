package com.mirboard.domain.game.wordtemp.scoring;

import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.Optional;

/**
 * 표 밖 힌트의 실시간 채점기(D-312·D-317, 룰 §6.2) — 방 잠금 <b>밖</b>에서 일하고, 포트 어댑터는 잠금 안에서 이 세 함수만 부른다.
 * 그래서 셋 다 <b>메모리만 보고 바로 돌아와야 한다</b>(네트워크·DB 를 기다리지 않는다).
 *
 * <p>채점기는 방·사용자·좌석을 모른다 — (정답, 힌트) 쌍만 안다. 같은 쌍은 한 번만 묻고 처음 받은 값이 정본이다(룰 §6.3).
 */
public interface LiveHintScorer {

    /** 지금 새 채점을 맡길 수 있는가(켜짐·API 키 있음·하루 상한 안·일시 중지 아님). 아니면 표 밖 힌트는 {@code UNKNOWN_WORD} 로 거절된다. */
    boolean available();

    /** 채점을 맡긴다 — 바로 돌아온다. 이미 진행 중이거나 결과가 있으면 다시 묻지 않는다. */
    void request(AnswerEntry answer, String hint);

    /**
     * 결과가 와 있으면 그 값. 아직이면 빈 값. 결과도 진행 중인 작업도 없으면(재시작으로 잃었다) 작업을 다시 걸고, 맡길 수 없으면 실패를 돌려준다
     * — 그래서 채점 중인 힌트는 기다림 상한을 다 채우지 않고도 풀린다. <b>한 번 결과를 돌려준 쌍은 얼마 동안 같은 결과를 계속 돌려준다</b>
     * (엔진 타이머가 {@code timer} 와 {@code onTimer} 에서 두 번 묻는다).
     */
    Optional<LiveScore> poll(AnswerEntry answer, String hint);

    /** 꺼진 채점기 — 표만으로 진행한다. 맡길 수 없고, 남아 있는 채점 중 힌트는 실패로 푼다. */
    LiveHintScorer OFF = new LiveHintScorer() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void request(AnswerEntry answer, String hint) {
        }

        @Override
        public Optional<LiveScore> poll(AnswerEntry answer, String hint) {
            return Optional.of(new LiveScore.Failed());
        }
    };
}
