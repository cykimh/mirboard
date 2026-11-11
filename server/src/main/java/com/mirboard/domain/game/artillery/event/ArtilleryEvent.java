package com.mirboard.domain.game.artillery.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import com.mirboard.domain.game.core.GameEvent;
import java.util.List;

/**
 * 씨앗포 이벤트 (`docs/plans/artillery.md` §6). <b>전부 공개</b>다 — 숨길 정보가 없고 거절({@code ERROR})은 인프라가 예외 코드로
 * 본인 큐에 보낸다. payload 는 결과값이다(체력은 뒤 값, 대본 끝에 포대 전체).
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 JSON 에 새지 않게.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ArtilleryEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.ShotResolved.class, name = "SHOT_RESOLVED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.TurnSkipped.class, name = "TURN_SKIPPED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.UnitEliminated.class, name = "UNIT_ELIMINATED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.MatchEnded.class, name = "MATCH_ENDED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.UnitPicked.class, name = "UNIT_PICKED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.UnitsRevealed.class, name = "UNITS_REVEALED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.UnitMoved.class, name = "UNIT_MOVED"),
        @JsonSubTypes.Type(value = ArtilleryEvent.AimPreview.class, name = "AIM_PREVIEW")
})
public sealed interface ArtilleryEvent extends GameEvent
        permits ArtilleryEvent.TurnChanged,
                ArtilleryEvent.ShotResolved,
                ArtilleryEvent.TurnSkipped,
                ArtilleryEvent.UnitEliminated,
                ArtilleryEvent.MatchEnded,
                ArtilleryEvent.UnitPicked,
                ArtilleryEvent.UnitsRevealed,
                ArtilleryEvent.UnitMoved,
                ArtilleryEvent.AimPreview {

    @Override
    default String envelopeType() {
        return switch (this) {
            case TurnChanged __ -> "TURN_CHANGED";
            case ShotResolved __ -> "SHOT_RESOLVED";
            case TurnSkipped __ -> "TURN_SKIPPED";
            case UnitEliminated __ -> "UNIT_ELIMINATED";
            case MatchEnded __ -> "MATCH_ENDED";
            case UnitPicked __ -> "UNIT_PICKED";
            case UnitsRevealed __ -> "UNITS_REVEALED";
            case UnitMoved __ -> "UNIT_MOVED";
            case AimPreview __ -> "AIM_PREVIEW";
        };
    }

    /**
     * 차례 시작 (§4-1). 연료는 늘 가득이라 싣지 않는다. D-226 — 좌석 순 누적 딜레이와 앞으로의 차례 순서(지금 차례 주인부터)를 싣는다.
     */
    record TurnChanged(int seat, int turnNo, int wind, int cloudLevel, List<Integer> delays, List<Integer> queue)
            implements ArtilleryEvent {
        public TurnChanged {
            delays = delays == null ? List.of() : List.copyOf(delays);
            queue = queue == null ? List.of() : List.copyOf(queue);
        }

        public TurnChanged(int seat, int turnNo, int wind, int cloudLevel) {
            this(seat, turnNo, wind, cloudLevel, List.of(), List.of());
        }
    }

    /** 발사 해소 — 대본 하나 (§6). */
    record ShotResolved(ShotReport report) implements ArtilleryEvent {
    }

    record TurnSkipped(int seat, Reason reason) implements ArtilleryEvent {
        public enum Reason {
            SKIP, TIMEOUT
        }
    }

    /** 발사 밖의 탈락 — 밀물(§8)·탈주(§11). 발사로 인한 탈락은 대본 안에 있다. */
    record UnitEliminated(int seat, Unit.Cause cause, int cloudLevel) implements ArtilleryEvent {
    }

    /** 고르기 단계에서 한 사람이 골랐다 — 무엇을 골랐는지는 싣지 않는다(다 고를 때까지 숨김, D-224). */
    record UnitPicked(int seat) implements ArtilleryEvent {
    }

    /** 고르기 끝 — 전원의 종류·체력·특수탄·아이템. 바로 뒤에 첫 {@code TURN_CHANGED}. */
    record UnitsRevealed(List<ArtilleryPublicView.UnitView> units) implements ArtilleryEvent {
        public UnitsRevealed {
            units = List.copyOf(units);
        }
    }

    /**
     * 걷기 확정 (D-228) — 공개·순번 있음(상태가 바뀐다). 결과값이라 하나 빠져도 다음 것이 맞춘다.
     *
     * @param turn       차례 번호
     * @param path       걸음마다 도착 칸 [x, footY, …] (출발점 제외)
     * @param fuelLeft   남은 연료
     * @param fall       걷다 떨어졌으면 그 낙하, 아니면 null
     * @param hpAfter    걷기 뒤 체력
     * @param eliminated 이 걷기로 탈락했으면 그 까닭(차례가 끝난다), 아니면 null
     */
    record UnitMoved(int seat, int turn, List<Integer> path, int toX, int toY, int fuelLeft, ShotReport.Fall fall,
                     int hpAfter, Unit.Cause eliminated) implements ArtilleryEvent {
        public UnitMoved {
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    /**
     * 조준 미리보기 (D-229) — 공개·<b>순번 없음</b>(상태를 바꾸지 않는다, 빠져도 다음 프레임이 덮는다). 힘은 싣지 않는다.
     *
     * @param turn  차례 번호 — 받는 쪽이 지금 차례가 아닌 프레임을 버린다
     * @param shell 지금 쏠 수 있는 탄일 때만, 아니면 null
     * @param n     보낸 쪽 번호 — 받는 쪽이 지금까지 본 최대보다 클 때만 쓴다
     */
    record AimPreview(int seat, int turn, int angle, Shell shell, int n) implements ArtilleryEvent {
        @Override
        public boolean sequenced() {
            return false;
        }
    }

    /** D-225 — 팀전이면 {@code teams} 와 이긴 팀({@code winnerTeam}, 무승부면 null). */
    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Placement> placements, boolean teams,
                      Integer winnerTeam) implements ArtilleryEvent {
        public MatchEnded {
            placements = List.copyOf(placements);
        }

        public MatchEnded(MatchResult.EndReason reason, List<MatchResult.Placement> placements) {
            this(reason, placements, false, null);
        }
    }
}
