package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import java.util.List;
import java.util.Map;

/**
 * 라운드 진행 단계를 표현하는 sealed 계층 (`docs/rules-skullking.md` §3).
 *
 * <pre>
 * Bidding → Playing(트릭 반복) → RoundEnd
 * </pre>
 *
 * <p><b>Dealing 이 상태가 아닌 것은 의도적이다 (D-101).</b> 명세 §3 의 Dealing 은 액션이
 * 0개인 통과 지점이다 — 티츄의 Dealing 은 그랜드티츄 선언 윈도우라 상태였지만, 스컬킹은
 * 서버가 분배하고 곧장 Bidding 으로 들어간다. 상태로 두면 아무도 살지 않는 분기가 생긴다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@phase")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SkullKingState.Bidding.class, name = "BIDDING"),
        @JsonSubTypes.Type(value = SkullKingState.Playing.class, name = "PLAYING"),
        @JsonSubTypes.Type(value = SkullKingState.RoundEnd.class, name = "ROUND_END")
})
public sealed interface SkullKingState extends GameState
        permits SkullKingState.Bidding, SkullKingState.Playing, SkullKingState.RoundEnd {

    /** 1 ~ 10 (§3). 0승 예측 점수가 이 값을 쓴다 — 트릭 수가 아니다 (§13-⑫). */
    int roundNumber();

    List<PlayerState> players();

    /** 이 라운드의 리드 시작 좌석 — 첫 트릭을 리드한 좌석 (§13-⑮). */
    int startSeat();

    @JsonIgnore
    default int seatCount() {
        return players().size();
    }

    /** 클라 분기용 단계 이름 — 포트의 {@code phaseName} 이 그대로 쓴다. */
    @JsonIgnore
    default String phaseName() {
        return switch (this) {
            case Bidding __ -> "BIDDING";
            case Playing __ -> "PLAYING";
            case RoundEnd __ -> "ROUND_END";
        };
    }

    /**
     * 승수 예측 단계 (§5). 순차로 받되 전원 제출 전까지 값을 공개하지 않는다 — 공개
     * 경계는 이벤트 라우팅이 담당하고, 상태 자체는 제출된 값을 그대로 들고 있는다.
     */
    record Bidding(int roundNumber, List<PlayerState> players, int startSeat)
            implements SkullKingState {

        public Bidding {
            players = List.copyOf(players);
        }

        /** 아직 예측을 내지 않은 좌석들 — 전원이 동시에 대기한다. */
        @JsonIgnore
        public List<Integer> awaitingSeats() {
            return players.stream().filter(p -> !p.hasBid()).map(PlayerState::seat).toList();
        }

        @JsonIgnore
        public boolean allBidsIn() {
            return players.stream().allMatch(PlayerState::hasBid);
        }
    }

    /** 트릭 플레이 단계 (§6~§9). 손패가 모두 소진되면 RoundEnd 로 간다. */
    record Playing(int roundNumber, List<PlayerState> players, int startSeat, TrickState trick)
            implements SkullKingState {

        public Playing {
            players = List.copyOf(players);
        }

        /** 지금 낼 차례인 좌석. 트릭이 다 찼으면 -1 (엔진이 즉시 정산하므로 과도기값). */
        @JsonIgnore
        public int currentTurnSeat() {
            return trick.currentTurnSeat(seatCount());
        }

        /** 손패가 전부 비었는가 — 라운드 종료 조건 (§9). */
        @JsonIgnore
        public boolean handsExhausted() {
            return players.stream().allMatch(p -> p.hand().isEmpty());
        }
    }

    /** 라운드 종료 + 좌석별 점수 산출 완료 (§10, §11). */
    record RoundEnd(int roundNumber,
                    List<PlayerState> players,
                    int startSeat,
                    Map<Integer, RoundScore> scores) implements SkullKingState {

        public RoundEnd {
            players = List.copyOf(players);
            scores = Map.copyOf(scores);
        }

        /** 좌석 → 이번 라운드 총점 (보너스 포함). 매치 누적에 더해진다. */
        @JsonIgnore
        public Map<Integer, Integer> totalsBySeat() {
            return scores.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().total()));
        }
    }
}
