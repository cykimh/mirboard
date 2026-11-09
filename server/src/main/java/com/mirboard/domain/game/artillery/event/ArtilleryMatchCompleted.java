package com.mirboard.domain.game.artillery.event;

import com.mirboard.domain.game.artillery.state.MatchResult;
import java.util.List;

/**
 * 씨앗포 매치가 끝났다. 매치 기록·게임별 전적({@code ArtilleryMatchRecorder})이 듣는다. 서버 내부 이벤트라 좌석→유저 매핑을 싣는다.
 * 로컬 발행만 한다(D-116).
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 */
public record ArtilleryMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public ArtilleryMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
