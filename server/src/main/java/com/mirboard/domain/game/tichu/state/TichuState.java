package com.mirboard.domain.game.tichu.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * 라운드 진행 단계를 표현하는 sealed 계층. Phase 3d 에서는 Passing / Playing / RoundEnd
 * 만 채워둔다 (Dealing 단계는 Phase 3f 엔진 통합 시 추가).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@phase")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TichuState.Passing.class, name = "PASSING"),
        @JsonSubTypes.Type(value = TichuState.Playing.class, name = "PLAYING"),
        @JsonSubTypes.Type(value = TichuState.RoundEnd.class, name = "ROUND_END")
})
public sealed interface TichuState
        permits TichuState.Passing, TichuState.Playing, TichuState.RoundEnd {

    List<PlayerState> players();

    /** 좌/우/파트너 카드 패스 단계. */
    record Passing(
            List<PlayerState> players,
            Map<Integer, Boolean> submitted) implements TichuState {
        public Passing {
            players = List.copyOf(players);
            submitted = Map.copyOf(submitted);
        }
    }

    /** 실제 트릭 플레이가 이루어지는 단계. */
    record Playing(
            List<PlayerState> players,
            TrickState trick,
            int firstFinisher) implements TichuState {
        public Playing {
            players = List.copyOf(players);
        }
    }

    /** 라운드 종료, 점수 계산 완료. */
    record RoundEnd(
            List<PlayerState> players,
            int teamAScore,
            int teamBScore) implements TichuState {
        public RoundEnd {
            players = List.copyOf(players);
        }
    }
}
