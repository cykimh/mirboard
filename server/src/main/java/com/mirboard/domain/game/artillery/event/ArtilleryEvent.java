package com.mirboard.domain.game.artillery.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
        @JsonSubTypes.Type(value = ArtilleryEvent.UnitsRevealed.class, name = "UNITS_REVEALED")
})
public sealed interface ArtilleryEvent extends GameEvent
        permits ArtilleryEvent.TurnChanged,
                ArtilleryEvent.ShotResolved,
                ArtilleryEvent.TurnSkipped,
                ArtilleryEvent.UnitEliminated,
                ArtilleryEvent.MatchEnded,
                ArtilleryEvent.UnitPicked,
                ArtilleryEvent.UnitsRevealed {

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
        };
    }

    /** 차례 시작 (§4-1). 연료는 늘 가득(100)이라 싣지 않는다. */
    record TurnChanged(int seat, int turnNo, int wind, int cloudLevel) implements ArtilleryEvent {
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

    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Placement> placements) implements ArtilleryEvent {
        public MatchEnded {
            placements = List.copyOf(placements);
        }
    }
}
