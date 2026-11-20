package com.mirboard.domain.game.starsquare.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import java.util.List;

/**
 * 별자리 수판 엔진이 발행하는 이벤트 sealed 계층(`docs/rules-starsquare.md` §11.1).
 *
 * <p><b>전부 공개·순번 있음.</b> 숨길 것(정답·푸는 중인 남의 판)은 이벤트 타입에 <b>필드 자체가 없다</b> — {@link PuzzleStarted} 는 단서만,
 * {@link Progress} 는 수 두 개만 싣는다. 모두의 판과 정답은 퍼즐이 끝난 뒤의 {@link PuzzleEnded} 에만 있다. 거절({@code ERROR})은 인프라가
 * 예외 코드로 본인 큐에 보낸다.
 *
 * <p><b>payload 는 결과값</b>이다(진행도 전체·순위·점수 합). 시간은 남은 ms(절대 시각 아님 — 시계 차이).
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 JSON 에 {@code "private"} 로 새지 않게.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StarSquareEvent.PuzzleStarted.class, name = "PUZZLE_STARTED"),
        @JsonSubTypes.Type(value = StarSquareEvent.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = StarSquareEvent.Solved.class, name = "SOLVED"),
        @JsonSubTypes.Type(value = StarSquareEvent.Closing.class, name = "CLOSING"),
        @JsonSubTypes.Type(value = StarSquareEvent.PuzzleEnded.class, name = "PUZZLE_ENDED"),
        @JsonSubTypes.Type(value = StarSquareEvent.SeatDeserted.class, name = "SEAT_DESERTED"),
        @JsonSubTypes.Type(value = StarSquareEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface StarSquareEvent extends GameEvent
        permits StarSquareEvent.PuzzleStarted,
                StarSquareEvent.Progress,
                StarSquareEvent.Solved,
                StarSquareEvent.Closing,
                StarSquareEvent.PuzzleEnded,
                StarSquareEvent.SeatDeserted,
                StarSquareEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case PuzzleStarted __ -> "PUZZLE_STARTED";
            case Progress __ -> "PROGRESS";
            case Solved __ -> "SOLVED";
            case Closing __ -> "CLOSING";
            case PuzzleEnded __ -> "PUZZLE_ENDED";
            case SeatDeserted __ -> "SEAT_DESERTED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /**
     * 퍼즐 공개(§6.2-1). 모든 좌석의 판이 이 단서 판으로 새로 시작한다.
     *
     * @param clues   단서 판(0 = 빈 칸)
     * @param limitMs 퍼즐 제한(ms)
     * @param filled  새 판의 진행도 — 채운 칸(모든 좌석 같음)
     * @param lines   새 판의 진행도 — 맞은 줄
     */
    record PuzzleStarted(int index, int count, int size, int magicSum, Grid clues, long limitMs, int filled, int lines)
            implements StarSquareEvent {
    }

    /** 진행도가 바뀜(§6.2-3) — 수 두 개만, 판 숫자는 없다. */
    record Progress(int seat, int filled, int lines) implements StarSquareEvent {
    }

    /**
     * 완성(§6.2-4). 그 좌석의 진행도는 채운 칸 N²·맞은 줄 2N+2 가 된다.
     *
     * @param place 완성 순서(1 부터)
     */
    record Solved(int seat, int place, long elapsedMs) implements StarSquareEvent {
    }

    /** 첫 완성으로 마감이 당겨짐(§6.2-5). */
    record Closing(long remainingMs) implements StarSquareEvent {
    }

    /**
     * 퍼즐 끝(§6.3) — 순위·점수와 모두의 판·정답 하나를 공개한다.
     *
     * @param grids    좌석 순서의 판
     * @param totals   좌석 순서의 점수 합(이 퍼즐 포함)
     * @param nextInMs 다음 퍼즐까지(ms), 마지막 퍼즐이면 0
     */
    record PuzzleEnded(int index, PuzzleOutcome.EndReason reason, List<SeatResult> results, List<Grid> grids,
                       Grid solution, List<Integer> totals, long nextInMs) implements StarSquareEvent {

        public PuzzleEnded {
            results = List.copyOf(results);
            grids = List.copyOf(grids);
            totals = List.copyOf(totals);
        }
    }

    /** 탈주(§12.1). */
    record SeatDeserted(int seat) implements StarSquareEvent {
    }

    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Placement> placements) implements StarSquareEvent {

        public MatchEnded {
            placements = List.copyOf(placements);
        }
    }
}
