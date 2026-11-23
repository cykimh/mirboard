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
 * <p><b>공개 이벤트는 전부 순번 있음.</b> 숨길 것(정답·푸는 중인 남의 판)은 공개 이벤트 타입에 <b>필드 자체가 없다</b> —
 * {@link PuzzleStarted} 는 단서만, {@link Progress} 는 수 두 개만 싣는다. 모두의 판과 정답은 퍼즐이 끝난 뒤의 {@link PuzzleEnded} 에만 있다.
 * 거절({@code ERROR})은 인프라가 예외 코드로 본인 큐에 보낸다.
 *
 * <p><b>비공개 이벤트는 {@link PeerBoard} 하나</b>(D-294) — 이 퍼즐을 <b>완성한 좌석</b>의 본인 큐로만 가는 남의 판이고 순번을 쓰지 않는다
 * (D-126). 공개 토픽으로 가는 길이 없다: {@link #privateSeat()} 가 받는 좌석을 돌려준다.
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
        @JsonSubTypes.Type(value = StarSquareEvent.MatchEnded.class, name = "MATCH_ENDED"),
        @JsonSubTypes.Type(value = StarSquareEvent.PeerBoard.class, name = "PEER_BOARD")
})
public sealed interface StarSquareEvent extends GameEvent
        permits StarSquareEvent.PuzzleStarted,
                StarSquareEvent.Progress,
                StarSquareEvent.Solved,
                StarSquareEvent.Closing,
                StarSquareEvent.PuzzleEnded,
                StarSquareEvent.SeatDeserted,
                StarSquareEvent.MatchEnded,
                StarSquareEvent.PeerBoard {

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
            case PeerBoard __ -> "PEER_BOARD";
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

    /**
     * 아직 푸는 좌석의 판 — <b>이 퍼즐을 완성한 좌석({@code viewer})의 본인 큐로만</b> 간다(§11.2, D-294). 순번 없음.
     *
     * @param viewer 받는 좌석(완성한 살아 있는 사람)
     * @param puzzle 퍼즐 번호 — 지난 퍼즐의 늦은 프레임을 클라가 거른다
     * @param seat   판의 주인(살아 있고 아직 완성하지 않은 좌석 — 사람·봇)
     */
    record PeerBoard(int viewer, int puzzle, int seat, Grid grid) implements StarSquareEvent {

        @Override
        public int privateSeat() {
            return viewer;
        }

        @Override
        public boolean sequenced() {
            return false;
        }
    }
}
