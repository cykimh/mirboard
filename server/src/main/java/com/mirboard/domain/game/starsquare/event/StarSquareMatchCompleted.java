package com.mirboard.domain.game.starsquare.event;

import com.mirboard.domain.game.starsquare.state.MatchResult;
import java.util.List;

/**
 * 별자리 수판 매치가 끝났다(퍼즐 다 함·사람 없음·좌석 하나). 매치 기록·게임별 전적({@code StarSquareMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link StarSquareEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 싣는다. 로컬 발행만 한다(D-116).
 *
 * @param playerIds 좌석 순서대로의 유저 id(봇 포함)
 */
public record StarSquareMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public StarSquareMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
