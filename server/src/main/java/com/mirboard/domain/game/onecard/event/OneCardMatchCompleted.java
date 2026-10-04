package com.mirboard.domain.game.onecard.event;

import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;

/**
 * D-128 — 원카드 매치가 끝났다(누군가 다 냄·탈락으로 한 명·사람 없음·교착). 매치 기록·게임별 전적
 * ({@code OneCardMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link OneCardEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 함께 싣는다.
 * 로컬 발행만 한다 — 인스턴스 간 전파를 하면 각 인스턴스가 다시 기록해 같은 매치가 중복으로 남는다.
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 * @param result    종료 사유와 순위표 (`docs/rules-onecard.md` §11)
 */
public record OneCardMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public OneCardMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
