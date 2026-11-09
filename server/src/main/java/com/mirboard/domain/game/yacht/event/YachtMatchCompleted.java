package com.mirboard.domain.game.yacht.event;

import com.mirboard.domain.game.yacht.state.MatchResult;
import java.util.List;

/**
 * 요트 매치가 끝났다(12칸 다 참·사람 없음·액션 상한). 매치 기록·게임별 전적({@code YachtMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link YachtEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 싣는다. 로컬 발행만 한다(D-116).
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 */
public record YachtMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public YachtMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
