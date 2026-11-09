package com.mirboard.domain.game.cloudroute.event;

import com.mirboard.domain.game.cloudroute.state.MatchResult;
import java.util.List;

/**
 * D-212 — 구름항로 매치가 끝났다(라운드 상한·1명 남음·사람 없음). 매치 기록·게임별 전적({@code CloudRouteMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link CloudRouteEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 함께 싣는다. 로컬 발행만 한다(D-116 —
 * 인스턴스 간 전파를 하면 같은 매치가 중복으로 남는다).
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 */
public record CloudRouteMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public CloudRouteMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
