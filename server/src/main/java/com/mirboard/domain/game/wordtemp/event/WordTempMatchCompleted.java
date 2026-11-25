package com.mirboard.domain.game.wordtemp.event;

import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import java.util.List;

/**
 * 낱말 온도 매치가 끝났다(라운드 다 함·좌석 하나·사람 없음). 매치 기록·게임별 전적({@code WordTempMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link WordTempEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 싣는다. 로컬 발행만 한다(D-116).
 * 싣는 것은 <b>공개된 것뿐</b>이다 — 출제 차례 요약(정답·공개 힌트·온도·맞힌 좌석). 오답 원문·안 쓴 후보는 없다(룰 §13).
 *
 * @param playerIds    좌석 순서대로의 유저 id(봇 포함)
 * @param turns        끝난 출제 차례 요약
 * @param tableVersion 이 판이 쓴 유사도 표 버전
 */
public record WordTempMatchCompleted(String roomId, List<Long> playerIds, MatchResult result, List<TurnSummary> turns,
                                     String tableVersion) {

    public WordTempMatchCompleted {
        playerIds = List.copyOf(playerIds);
        turns = List.copyOf(turns);
    }
}
