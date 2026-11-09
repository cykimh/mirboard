package com.mirboard.domain.game.omok.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.ForbiddenPoint;
import com.mirboard.domain.game.omok.state.EndReason;
import java.util.List;

/**
 * 오목 이벤트 sealed 계층 (설계서 §5). 숨길 정보가 없어 <b>전부 공개</b>이고 방 순번을 쓴다(기본). 비공개는 인프라가 만드는
 * {@code ERROR} 뿐이다.
 *
 * <p>payload 는 결과값 — {@link StonePlaced} 는 수 번호를 실어 같은 수를 두 번 받아도 같은 판이 된다. {@link TurnChanged} 는
 * 턴 카운트다운의 기준(D-131)이라 착수마다 낸다.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OmokEvent.MatchStarted.class, name = "MATCH_STARTED"),
        @JsonSubTypes.Type(value = OmokEvent.StonePlaced.class, name = "STONE_PLACED"),
        @JsonSubTypes.Type(value = OmokEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = OmokEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface OmokEvent extends GameEvent
        permits OmokEvent.MatchStarted, OmokEvent.StonePlaced, OmokEvent.TurnChanged, OmokEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case MatchStarted __ -> "MATCH_STARTED";
            case StonePlaced __ -> "STONE_PLACED";
            case TurnChanged __ -> "TURN_CHANGED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 타입만 정의한다 — 시작 때 발행하지 않고 시작 상태는 resync 로 받는다(원카드 선례). */
    record MatchStarted(int firstSeat, List<Stone> seatColors) implements OmokEvent {
        public MatchStarted {
            seatColors = List.copyOf(seatColors);
        }
    }

    /** 돌이 놓였다. {@code moveNumber} 는 1부터. */
    record StonePlaced(int seat, Stone color, int x, int y, int moveNumber, boolean timedOut) implements OmokEvent {
    }

    /** 다음 차례. 흑 차례면 그 판의 금수 자리. */
    record TurnChanged(int seat, Stone color, List<ForbiddenPoint> forbidden) implements OmokEvent {
        public TurnChanged {
            forbidden = List.copyOf(forbidden);
        }
    }

    /** 판이 끝났다 (룰 §10.1). 무승부면 {@code winnerColor} null. */
    record MatchEnded(Stone winnerColor,
                      List<Integer> winnerSeats,
                      EndReason reason,
                      int causeSeat,
                      List<Point> winLine,
                      int totalMoves) implements OmokEvent {
        public MatchEnded {
            winnerSeats = List.copyOf(winnerSeats);
            winLine = List.copyOf(winLine);
        }
    }
}
