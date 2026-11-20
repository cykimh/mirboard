package com.mirboard.domain.game.dambaduk.event;

import com.mirboard.domain.game.dambaduk.state.DambadukState;
import java.util.List;

/**
 * 담바둑 매치가 끝났다 — 매치 기록·게임별 전적({@code DambadukMatchRecorder})이 듣는다. 클라에 나가는 {@link DambadukEvent.MatchEnded}
 * 와 달리 서버 내부 이벤트라 좌석→유저 매핑과 끝난 상태를 함께 싣는다. 로컬 발행만 한다(D-116).
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 * @param state     끝난 상태 — {@code result} 가 null 이 아니다
 */
public record DambadukMatchCompleted(String roomId, List<Long> playerIds, DambadukState state) {

    public DambadukMatchCompleted {
        playerIds = List.copyOf(playerIds);
        if (state.result() == null) {
            throw new IllegalArgumentException("DambadukMatchCompleted needs an ended state");
        }
    }
}
