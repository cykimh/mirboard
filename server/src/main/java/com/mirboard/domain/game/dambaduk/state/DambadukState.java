package com.mirboard.domain.game.dambaduk.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Walls;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 담바둑 판 상태 (룰 정본 {@code docs/rules-dambaduk.md}). 불변 레코드 — 전이는 엔진이 새 레코드로 만든다. 지금 판 그대로(돌·담)를
 * 저장하고 수순은 남기지 않는다(불변식은 개수 짝으로 본다 — 담 수 = 넘기 없는 옮김 수).
 *
 * @param firstSeat      선(흑) 좌석 — 라운드 스타터가 뽑는다(룰 §2)
 * @param stones         판 위의 돌, {@code id} 오름차순. 3인 판에서 빠진 좌석의 돌은 치워져 없다
 * @param walls          세워진 담의 면 번호 오름차순({@code Grid})
 * @param placementQueue 남은 배치 차례(좌석) — 앞이 지금 놓을 사람, 배치가 끝나면 빈 목록
 * @param turnSeat       지금 액션할 좌석, 끝났으면 −1
 * @param jumpsLeft      좌석별 남은 담 넘기(0·1) — 2인으로 시작한 판은 전부 0
 * @param timeouts       좌석별 연속 시간 초과 수 (룰 §8)
 * @param exits          빠진 좌석, 빠진 순서대로
 * @param nextStoneId    다음에 놓일 돌의 번호
 * @param moveCount      지금까지의 옮김 액션 수(시간 초과 자동 수 포함)
 * @param jumpCount      그중 담 넘기 수
 * @param result         끝나기 전에는 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DambadukState(int seatCount,
                            int firstSeat,
                            Phase phase,
                            List<Stone> stones,
                            List<Integer> walls,
                            List<Integer> placementQueue,
                            int turnSeat,
                            List<Integer> jumpsLeft,
                            List<Integer> timeouts,
                            List<Exit> exits,
                            int nextStoneId,
                            int moveCount,
                            int jumpCount,
                            MatchResult result) implements GameState {

    public DambadukState {
        stones = stones == null ? List.of()
                : stones.stream().sorted(Comparator.comparingInt(Stone::id)).toList();
        walls = walls == null ? List.of() : walls.stream().sorted().toList();
        placementQueue = placementQueue == null ? List.of() : List.copyOf(placementQueue);
        jumpsLeft = jumpsLeft == null ? zeros(seatCount) : List.copyOf(jumpsLeft);
        timeouts = timeouts == null ? zeros(seatCount) : List.copyOf(timeouts);
        exits = exits == null ? List.of() : List.copyOf(exits);
    }

    private static List<Integer> zeros(int n) {
        return IntStream.range(0, n).map(i -> 0).boxed().toList();
    }

    public boolean isOver() {
        return result != null;
    }

    public Walls wallSet() {
        return Walls.of(walls);
    }

    /** 3인으로 시작한 판인가 — 담 넘기가 있는 판(룰 §4.4). */
    public boolean jumpsEnabled() {
        return seatCount == 3;
    }

    public boolean isActive(int seat) {
        return seat >= 0 && seat < seatCount && exits.stream().noneMatch(e -> e.seat() == seat);
    }

    /** 빠지지 않은 좌석들 오름차순. */
    public List<Integer> activeSeats() {
        return IntStream.range(0, seatCount).filter(this::isActive).boxed().toList();
    }

    /**
     * 좌석의 색 번호 — 선부터 좌석 +1 순으로 0 흑 · 1 백 · 2 셋째(룰 §2).
     */
    public int colorIndex(int seat) {
        return Math.floorMod(seat - firstSeat, seatCount);
    }

    public Optional<Stone> stoneAt(Cell c) {
        return stones.stream().filter(s -> s.x() == c.x() && s.y() == c.y()).findFirst();
    }

    public Optional<Stone> stone(int id) {
        return stones.stream().filter(s -> s.id() == id).findFirst();
    }

    public List<Stone> stonesOf(int seat) {
        return stones.stream().filter(s -> s.seat() == seat).toList();
    }
}
