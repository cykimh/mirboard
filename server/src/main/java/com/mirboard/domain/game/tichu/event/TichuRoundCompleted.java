package com.mirboard.domain.game.tichu.event;

import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import java.util.List;

/**
 * 한 라운드가 종료되어 점수 계산이 끝났음을 알리는 ApplicationEvent. STOMP 의 inflight
 * 이벤트 (TichuEvent.RoundEnded) 와 달리, 본 이벤트는 영속화 / 외부 시스템 통합 등의
 * "이후 처리" 를 위해 발행된다.
 */
public record TichuRoundCompleted(
        String roomId,
        List<Long> playerIds,
        List<PlayerState> finalPlayers,
        RoundScore score) {

    public TichuRoundCompleted {
        playerIds = List.copyOf(playerIds);
        finalPlayers = List.copyOf(finalPlayers);
    }
}
