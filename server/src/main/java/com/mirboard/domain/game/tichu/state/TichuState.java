package com.mirboard.domain.game.tichu.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 라운드 진행 단계를 표현하는 sealed 계층. Phase 5b 에서 Dealing(8/14) 단계가
 * 추가되어 정식 라이프사이클은 Dealing(8) → Dealing(14) → Passing → Playing →
 * RoundEnd 가 된다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@phase")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TichuState.Dealing.class, name = "DEALING"),
        @JsonSubTypes.Type(value = TichuState.Passing.class, name = "PASSING"),
        @JsonSubTypes.Type(value = TichuState.Playing.class, name = "PLAYING"),
        @JsonSubTypes.Type(value = TichuState.RoundEnd.class, name = "ROUND_END")
})
public sealed interface TichuState
        permits TichuState.Dealing, TichuState.Passing, TichuState.Playing, TichuState.RoundEnd {

    List<PlayerState> players();

    /**
     * 카드 분배 단계.
     *
     * @param phaseCardCount     8 (Grand Tichu 선언 윈도우) 또는 14 (Tichu 선언 윈도우)
     * @param ready              본 윈도우에서 선언/스킵을 마친 좌석 집합
     * @param reservedSecondHalf phase==8 일 때만 사용 — 좌석 -> 추후 분배할 6장 보관소.
     *                           phase==14 로 전이 시 이 카드들이 hand 에 합쳐지며 비워진다.
     */
    record Dealing(
            List<PlayerState> players,
            int phaseCardCount,
            Set<Integer> ready,
            Map<Integer, List<Card>> reservedSecondHalf) implements TichuState {
        public Dealing {
            players = List.copyOf(players);
            ready = Set.copyOf(ready);
            // map values 자체도 immutable 카피.
            reservedSecondHalf = Map.copyOf(reservedSecondHalf);
        }
    }

    /**
     * 좌/우/파트너 카드 패스 단계. 4명 모두 제출하면 동시 스왑 후 Playing 으로 전이.
     *
     * @param submitted 좌석 -> 제출된 PassCardsSelection (없으면 미제출)
     */
    record Passing(
            List<PlayerState> players,
            Map<Integer, PassCardsSelection> submitted) implements TichuState {
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
