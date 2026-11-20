package com.mirboard.domain.game.dambaduk.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.util.List;

/**
 * 담바둑 이벤트 sealed 계층 (룰 §10). 숨길 정보가 없어 <b>전부 공개</b>이고 방 순번을 쓴다(기본). 비공개는 인프라가 만드는
 * {@code ERROR} 뿐이다. payload 는 결과값 — 터·점수·고를 수 있는 수는 서버가 계산해 싣는다(클라는 판정하지 않는다). 필드 모양은 S2 에서
 * 확정한다.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DambadukEvent.StonePlaced.class, name = "STONE_PLACED"),
        @JsonSubTypes.Type(value = DambadukEvent.StoneMoved.class, name = "STONE_MOVED"),
        @JsonSubTypes.Type(value = DambadukEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = DambadukEvent.TurnSkipped.class, name = "TURN_SKIPPED"),
        @JsonSubTypes.Type(value = DambadukEvent.SeatOut.class, name = "SEAT_OUT"),
        @JsonSubTypes.Type(value = DambadukEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface DambadukEvent extends GameEvent
        permits DambadukEvent.StonePlaced, DambadukEvent.StoneMoved, DambadukEvent.TurnChanged,
        DambadukEvent.TurnSkipped, DambadukEvent.SeatOut, DambadukEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case StonePlaced __ -> "STONE_PLACED";
            case StoneMoved __ -> "STONE_MOVED";
            case TurnChanged __ -> "TURN_CHANGED";
            case TurnSkipped __ -> "TURN_SKIPPED";
            case SeatOut __ -> "SEAT_OUT";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 돌을 놓았다 (룰 §3). */
    record StonePlaced(int seat, int stoneId, StoneKind kind, int x, int y, boolean timedOut) implements DambadukEvent {
    }

    /**
     * 돌을 옮기고 담을 세웠다 (룰 §4).
     *
     * @param wallFace   세운 담의 면 번호
     * @param jumpedFace 담 넘기로 없앤 면, 아니면 −1
     */
    record StoneMoved(int seat, int stoneId, List<Dir> path, int fromX, int fromY, int toX, int toY, Dir wall,
                      int wallFace, int jumpedFace, boolean timedOut) implements DambadukEvent {
        public StoneMoved {
            path = List.copyOf(path);
        }
    }

    /**
     * 다음 차례 — 턴 카운트다운의 기준(D-131)이라 액션마다 낸다. 지금 판의 터·점수와, 옮김 단계면 그 사람이 고를 수 있는 수(배치 단계면
     * 놓을 수 있는 종류)를 싣는다.
     */
    record TurnChanged(int seat, Phase phase, Territory territory, List<StoneKind> placeKinds,
                       List<MoveOption> options) implements DambadukEvent {
        public TurnChanged {
            placeKinds = List.copyOf(placeKinds);
            options = List.copyOf(options);
        }
    }

    /** 옮길 수 있는 수가 없어 그 차례를 건너뛰었다 (룰 §4.6). */
    record TurnSkipped(int seat) implements DambadukEvent {
    }

    /**
     * 좌석이 판에서 빠졌다 (룰 §8·§9) — 3인 판이면 그 돌을 치웠다({@code removedStoneIds}, 담은 그대로). 2인 판은 곧바로
     * {@link MatchEnded} 가 따른다(돌을 치우지 않는다).
     */
    record SeatOut(int seat, ExitReason reason, List<Integer> removedStoneIds) implements DambadukEvent {
        public SeatOut {
            removedStoneIds = List.copyOf(removedStoneIds);
        }
    }

    /** 판이 끝났다 (룰 §6·§7). */
    record MatchEnded(EndReason reason, List<Standing> standings, List<Integer> winners, boolean draw,
                      int causeSeat, Territory territory, int moveCount) implements DambadukEvent {
        public MatchEnded {
            standings = List.copyOf(standings);
            winners = List.copyOf(winners);
        }
    }
}
