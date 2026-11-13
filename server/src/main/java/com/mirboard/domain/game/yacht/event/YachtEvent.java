package com.mirboard.domain.game.yacht.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.MatchResult;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 요트 엔진이 발행하는 이벤트 sealed 계층 (`docs/rules-yacht.md` §9.1).
 *
 * <p><b>전부 공개다.</b> 숨길 정보가 없고, 거절({@code ERROR})은 인프라가 예외 코드로 본인 큐에 보낸다. 그래서
 * {@code privateSeat} 를 재정의하지 않는다. 순번도 기본(있음)이고, 예외는 상태를 바꾸지 않는 미리보기 {@link DiceShaking}(D-268,
 * 포트 ⑧) 하나뿐이다 — 순번을 쓰지 않는다.
 *
 * <p><b>payload 는 증감이 아니라 결과값</b>이다 — 주사위 전체·홀드·굴린 횟수, 기록 뒤 소계·보너스·총점. 두 번 적용해도 같고
 * 하나가 빠져도 다음 이벤트가 맞춘다(원카드 원칙).
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 JSON 에 {@code "private"} 로 새지 않게.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = YachtEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = YachtEvent.RoundStarted.class, name = "ROUND_STARTED"),
        @JsonSubTypes.Type(value = YachtEvent.DiceHeld.class, name = "DICE_HELD"),
        @JsonSubTypes.Type(value = YachtEvent.DiceRolled.class, name = "DICE_ROLLED"),
        @JsonSubTypes.Type(value = YachtEvent.Scored.class, name = "SCORED"),
        @JsonSubTypes.Type(value = YachtEvent.PlayerEliminated.class, name = "PLAYER_ELIMINATED"),
        @JsonSubTypes.Type(value = YachtEvent.MatchEnded.class, name = "MATCH_ENDED"),
        @JsonSubTypes.Type(value = YachtEvent.DiceShaking.class, name = "DICE_SHAKING")
})
public sealed interface YachtEvent extends GameEvent
        permits YachtEvent.TurnChanged,
                YachtEvent.RoundStarted,
                YachtEvent.DiceHeld,
                YachtEvent.DiceRolled,
                YachtEvent.Scored,
                YachtEvent.PlayerEliminated,
                YachtEvent.MatchEnded,
                YachtEvent.DiceShaking {

    @Override
    default String envelopeType() {
        return switch (this) {
            case TurnChanged __ -> "TURN_CHANGED";
            case RoundStarted __ -> "ROUND_STARTED";
            case DiceHeld __ -> "DICE_HELD";
            case DiceRolled __ -> "DICE_ROLLED";
            case Scored __ -> "SCORED";
            case PlayerEliminated __ -> "PLAYER_ELIMINATED";
            case MatchEnded __ -> "MATCH_ENDED";
            case DiceShaking __ -> "DICE_SHAKING";
        };
    }

    /**
     * 차례 시작. 주사위는 비고 홀드는 풀린다.
     *
     * @param turnSeconds 차례 전체 제한(초), 0 = 끔 (§6)
     */
    record TurnChanged(int seat, int round, int turnSeconds) implements YachtEvent {
    }

    /**
     * D-202 — 동시 진행의 라운드 시작. 살아 있는 모두의 주사위가 비고 홀드가 풀린다(차례대로 진행의 {@link TurnChanged} 자리).
     *
     * @param turnSeconds 라운드 전체 제한(초), 0 = 끔
     */
    record RoundStarted(int round, int turnSeconds) implements YachtEvent {
    }

    record DiceHeld(int seat, HoldMask held) implements YachtEvent {
    }

    /**
     * @param rolls      이번 굴림까지의 횟수 1~3
     * @param candidates 그 좌석의 빈 칸마다 지금 주사위의 점수(0 포함) — 서버 계산 (§4)
     */
    record DiceRolled(int seat, Dice dice, HoldMask held, int rolls, Map<Category, Integer> candidates)
            implements YachtEvent {

        public DiceRolled {
            EnumMap<Category, Integer> ordered = new EnumMap<>(Category.class);
            ordered.putAll(candidates);
            candidates = Collections.unmodifiableMap(ordered);
        }
    }

    /**
     * @param auto 시간 초과 자동 기록이면 true (§6)
     */
    record Scored(int seat, Category category, int points, int upperSubtotal, int bonus, int total, boolean auto)
            implements YachtEvent {
    }

    /** 탈주 (§7). 사유는 지금 탈주 하나뿐이라 싣지 않는다. */
    record PlayerEliminated(int seat, int total) implements YachtEvent {
    }

    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Placement> placements) implements YachtEvent {

        public MatchEnded {
            placements = List.copyOf(placements);
        }
    }

    /**
     * D-268 — 그 좌석이 컵을 흔드는 중(포트 ⑧ 미리보기). 상태가 아니라 표시일 뿐이라 <b>순번을 쓰지 않는다</b>(D-126) — 놓쳐도 되고,
     * 받는 쪽은 멈춤 프레임을 놓쳐도 몇 초 뒤 스스로 걷는다. resync 스냅샷에는 없다.
     *
     * @param n 보낸 쪽 번호 그대로 — 받는 쪽이 순서가 뒤바뀐 낡은 프레임을 버리는 기준
     */
    record DiceShaking(int seat, boolean shaking, long n) implements YachtEvent {
        @Override
        public boolean sequenced() {
            return false;
        }
    }
}
