package com.mirboard.domain.game.omok.event;

import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;

/**
 * 오목 매치가 끝났다 — 매치 기록·게임별 전적({@code OmokMatchRecorder})이 듣는다. 클라에 나가는 {@link OmokEvent.MatchEnded}
 * 와 달리 서버 내부 이벤트라 좌석→유저 매핑과 끝난 상태(수순 = 기보)를 함께 싣는다. 로컬 발행만 한다(D-116).
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 * @param state     끝난 상태 — {@code result} 가 null 이 아니다
 */
public record OmokMatchCompleted(String roomId, List<Long> playerIds, OmokState state) {

    public OmokMatchCompleted {
        playerIds = List.copyOf(playerIds);
        if (state.result() == null) {
            throw new IllegalArgumentException("OmokMatchCompleted needs an ended state");
        }
    }
}
