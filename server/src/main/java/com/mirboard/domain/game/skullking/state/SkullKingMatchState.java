package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 10라운드에 걸친 매치 누적 상태 (`docs/rules-skullking.md` §3, §12).
 *
 * <p>티츄는 같은 역할을 {@code persistence/TichuMatchState} 가 맡지만, S4 는 영속 계층을
 * 만들지 않으므로 순수 record 로 {@code state/} 에 둔다 (D-101). S5 가 옆에 스토어를 붙인다.
 *
 * <p>점수를 팀이 아니라 <b>좌석별</b>로 들고 있는 것은 D-97 판단 1 을 따른 것이다 —
 * 스컬킹은 개인전이고, 포트는 {@code Map<Integer,Integer>} 만 안다.
 *
 * @param roundNumber       다음에 진행할 라운드 (1 부터. 10 을 마치면 11 이 되어 종료)
 * @param startSeat         이번 라운드의 첫 리드 좌석 (§13-⑮ — 라운드마다 +1)
 * @param cumulativeScores  좌석 → 누적 점수 (음수 가능). 탈주 좌석도 계속 기록된다 (§13-⑳)
 * @param desertedSeats     탈주 확정 좌석 — 유령으로 남아 자동조종이 대신 둔다 (D-104, §13-⑱)
 */
public record SkullKingMatchState(int roundNumber,
                                  int startSeat,
                                  Map<Integer, Integer> cumulativeScores,
                                  Set<Integer> desertedSeats) {

    /** 매치는 10라운드 고정 (§3). 목표 점수 방식이 아니다 (§12). */
    public static final int TOTAL_ROUNDS = 10;

    /** 매치를 계속하기 위한 최소 잔존 좌석 (§2 최소 인원 → §13-⑲). */
    public static final int MIN_SEATS_TO_CONTINUE = 2;

    public SkullKingMatchState {
        cumulativeScores = cumulativeScores == null ? Map.of() : Map.copyOf(cumulativeScores);
        // null 정규화는 구 JSON(필드 부재) 역직렬화 호환 (CLAUDE.md record 규약).
        // TreeSet 은 JSON 배열 순서를 좌석 오름차순으로 고정한다.
        desertedSeats = desertedSeats == null || desertedSeats.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(desertedSeats));
    }

    /** 탈주가 없던 시절(D-101) 시그니처 호환 — 기존 호출부·테스트 무변경. */
    public SkullKingMatchState(int roundNumber, int startSeat,
                               Map<Integer, Integer> cumulativeScores) {
        this(roundNumber, startSeat, cumulativeScores, Set.of());
    }

    /**
     * 매치 시작 상태.
     *
     * @param firstStartSeat 1라운드 첫 리드 좌석. 원문이 "적당한 방법으로 정한다"라고만
     *                       해서 서버가 균일 무작위로 뽑고 시드를 남긴다 (§13-⑯)
     */
    public static SkullKingMatchState initial(int seatCount, int firstStartSeat) {
        Map<Integer, Integer> zeros = new HashMap<>();
        for (int seat = 0; seat < seatCount; seat++) {
            zeros.put(seat, 0);
        }
        return new SkullKingMatchState(1, Math.floorMod(firstStartSeat, seatCount), zeros);
    }

    /** 10라운드를 모두 마쳤거나 탈주로 조기 종료됐는가 (§12, §13-⑲). */
    @JsonIgnore
    public boolean isMatchOver() {
        return roundNumber > TOTAL_ROUNDS;
    }

    /**
     * 라운드 점수를 누적하고 다음 라운드로 넘긴다. 시작 좌석은 턴 순서 +1 로 옮긴다
     * (§13-⑮ — 원문의 "왼쪽"과 "시계 방향"이 다른 좌표계라 같은 방향으로 읽었다).
     * 유령 좌석에 떨어져도 건너뛰지 않는다 — 드레인이 그 좌석의 리드를 즉시 처리한다.
     */
    public SkullKingMatchState withRoundScored(Map<Integer, Integer> roundScores, int seatCount) {
        Map<Integer, Integer> next = new HashMap<>(cumulativeScores);
        roundScores.forEach((seat, delta) -> next.merge(seat, delta, Integer::sum));
        return new SkullKingMatchState(roundNumber + 1,
                Math.floorMod(startSeat + 1, seatCount),
                next,
                desertedSeats);
    }

    /** 좌석을 탈주로 표시한다 (D-104). 라운드 상태는 건드리지 않는다. */
    public SkullKingMatchState withSeatDeserted(int seat) {
        Set<Integer> next = new TreeSet<>(desertedSeats);
        next.add(seat);
        return new SkullKingMatchState(roundNumber, startSeat, cumulativeScores, next);
    }

    /**
     * 탈주 조기 종료 (§13-⑲) — 남은 라운드를 소진시켜 포트의 무인자 {@code isMatchOver()}
     * 계약을 새 필드 없이 재사용한다. 완주 라운드 수는 호출자가 점프 전에 읽는다.
     */
    public SkullKingMatchState abandoned() {
        return new SkullKingMatchState(TOTAL_ROUNDS + 1, startSeat, cumulativeScores, desertedSeats);
    }

    /** 아직 매치에 남아 있는 좌석 — 오름차순. */
    @JsonIgnore
    public List<Integer> activeSeats() {
        return cumulativeScores.keySet().stream()
                .filter(seat -> !desertedSeats.contains(seat))
                .sorted()
                .toList();
    }

    /**
     * 최종 승자 좌석들. 동점이면 <b>공동 승리</b>다 (§13-⑰) — 원문에 타이브레이크 지표가
     * 없어 임의 지표를 만드는 대신 무승부로 둔다. 그래서 반환이 단수가 아니라 리스트다.
     * 탈주 좌석은 후보에서 제외한다 (§13-⑳); 전원 탈주면 방어적으로 빈 리스트.
     */
    @JsonIgnore
    public List<Integer> winners() {
        List<Integer> candidates = activeSeats();
        if (candidates.isEmpty()) {
            return List.of();
        }
        int best = candidates.stream().mapToInt(cumulativeScores::get).max().orElseThrow();
        List<Integer> tied = new ArrayList<>();
        for (int seat : candidates) {
            if (cumulativeScores.get(seat) == best) {
                tied.add(seat);
            }
        }
        return List.copyOf(tied);
    }
}
