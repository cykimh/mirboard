package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
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
 * @param completedRounds   정산이 끝난 라운드 기록, 1..N 순서 (D-120). 점수표·재접속 복원의
 *                          권위 원천이다. 진행 중 라운드는 정산 전이라 절대 들어오지 않는다 —
 *                          그래서 공개 전 예측값(§5)이 여기로 새지 않는다
 * @param roundsPlayed      완주 라운드 수 — <b>매치가 끝날 때 확정해 저장</b>한다 (D-122). 진행
 *                          중이면 null. 10라운드 완주면 10, 탈주 조기 종료면 진행 중이던 라운드의
 *                          앞까지. 결과 뷰가 라운드 상태에서 역산하면 종료 뒤 상태가 바뀌었을 때
 *                          (종료 뒤 남은 턴 타이머가 버려진 라운드를 끝까지 민 경로) DB 기록과
 *                          어긋났다. 필드가 없는 구 JSON 도 null — 뷰가 예전 역산으로 떨어진다
 */
// 모르는 필드 무시 — 다음에 필드가 늘어난 JSON 을 이 버전으로 롤백해도 읽을 수 있게 (D-120).
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkullKingMatchState(int roundNumber,
                                  int startSeat,
                                  Map<Integer, Integer> cumulativeScores,
                                  Set<Integer> desertedSeats,
                                  List<CompletedRound> completedRounds,
                                  Integer roundsPlayed) {

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
        // 기록이 없던 시절(D-120 이전)에 시작된 매치의 JSON 도 읽혀야 한다 — 빈 목록.
        completedRounds = completedRounds == null ? List.of() : List.copyOf(completedRounds);
        // roundsPlayed 는 null 이 뜻을 가진다(미확정) — 구 JSON(필드 부재)도 그대로 null 로 둔다.
        // 끝난 매치의 구 JSON 을 여기서 역산하지 않는 것은, 기록이 없던 시절(D-120 이전) 매치는
        // 라운드 상태 없이는 답할 수 없기 때문이다(그 역산은 뷰가 상태를 들고 한다).
    }

    /** 탈주가 없던 시절(D-101) 시그니처 호환 — 기존 호출부·테스트 무변경. */
    public SkullKingMatchState(int roundNumber, int startSeat,
                               Map<Integer, Integer> cumulativeScores) {
        this(roundNumber, startSeat, cumulativeScores, Set.of(), List.of());
    }

    /** 라운드 기록이 없던 시절(D-104) 시그니처 호환 — 빈 기록으로 시작한다. */
    public SkullKingMatchState(int roundNumber, int startSeat,
                               Map<Integer, Integer> cumulativeScores,
                               Set<Integer> desertedSeats) {
        this(roundNumber, startSeat, cumulativeScores, desertedSeats, List.of());
    }

    /** 완주 라운드 수가 없던 시절(D-120) 시그니처 호환 — 미확정(null). */
    public SkullKingMatchState(int roundNumber, int startSeat,
                               Map<Integer, Integer> cumulativeScores,
                               Set<Integer> desertedSeats,
                               List<CompletedRound> completedRounds) {
        this(roundNumber, startSeat, cumulativeScores, desertedSeats, completedRounds, null);
    }

    /**
     * 끝난 라운드 하나의 기록 (D-120) — 좌석 → 그 라운드의 점수 내역.
     *
     * <p>{@link RoundScore} 를 그대로 담는 것은 {@code RoundEnded} 이벤트와 같은 값을 남기기
     * 위해서다. 라이브 패치(이벤트)와 권위값(이 기록)이 다른 모양이면 둘을 맞추는 코드가
     * 클라에 하나 더 생긴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompletedRound(int roundNumber, Map<Integer, RoundScore> scores) {

        public CompletedRound {
            scores = Map.copyOf(scores);
        }
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
     * 라운드를 정산한다 — 점수를 누적하고, 기록을 남기고, 다음 라운드로 넘긴다 (D-120).
     * 시작 좌석은 턴 순서 +1 로 옮긴다 (§13-⑮ — 원문의 "왼쪽"과 "시계 방향"이 다른 좌표계라
     * 같은 방향으로 읽었다). 유령 좌석에 떨어져도 건너뛰지 않는다 — 드레인이 그 좌석의
     * 리드를 즉시 처리한다.
     *
     * <p>누적과 기록을 <b>한 메서드</b>가 하는 것이 요점이다. 따로 두면 기록을 빠뜨린 채
     * 점수만 누적하는 경로가 생기고, 점수표 합계가 조용히 어긋난다.
     *
     * @param round 정산하는 라운드. 지금 라운드({@link #roundNumber})와 다르면 이중 정산이거나
     *              라운드를 건너뛴 것이라 {@link IllegalStateException} — 정상 경로의 fail-fast
     *              가드다. 복구가 필요한 호출자는 {@link #hasSettled} 로 먼저 거른다
     * @throws IllegalStateException 라운드 번호 불일치
     */
    public SkullKingMatchState withRoundCompleted(int round,
                                                  Map<Integer, RoundScore> scores,
                                                  int seatCount) {
        if (round != roundNumber) {
            throw new IllegalStateException("Cannot settle round " + round
                    + " while the match is at round " + roundNumber);
        }
        Map<Integer, Integer> next = new HashMap<>(cumulativeScores);
        scores.forEach((seat, score) -> next.merge(seat, score.total(), Integer::sum));
        List<CompletedRound> history = new ArrayList<>(completedRounds);
        history.add(new CompletedRound(round, scores));
        int nextRound = roundNumber + 1;
        return new SkullKingMatchState(nextRound,
                Math.floorMod(startSeat + 1, seatCount),
                next,
                desertedSeats,
                history,
                // 마지막 라운드를 정산하면 매치가 끝난다 — 그 순간 완주 수를 확정한다 (D-122).
                nextRound > TOTAL_ROUNDS ? round : null);
    }

    /**
     * 이 라운드가 이미 정산됐는가 — 매치가 그 라운드를 지나쳤으면 참 (D-120). 정산 후 다음
     * 라운드 저장 전에 멈춘 방을 어댑터가 알아보는 데 쓴다.
     */
    public boolean hasSettled(int round) {
        return round < roundNumber;
    }

    /** 좌석을 탈주로 표시한다 (D-104). 라운드 상태·기록은 건드리지 않는다. */
    public SkullKingMatchState withSeatDeserted(int seat) {
        Set<Integer> next = new TreeSet<>(desertedSeats);
        next.add(seat);
        return new SkullKingMatchState(roundNumber, startSeat, cumulativeScores, next,
                completedRounds, roundsPlayed);
    }

    /**
     * 탈주 조기 종료 (§13-⑲) — 남은 라운드를 소진시켜 포트의 무인자 {@code isMatchOver()}
     * 계약을 재사용한다. 진행 중 라운드는 폐기되므로 기록에 더하지 않는다.
     *
     * <p>완주 라운드 수는 <b>점프 전에 여기서</b> 확정해 저장한다 (D-122) — 진행 중이던
     * 라운드({@link #roundNumber})의 앞까지다. 정산은 끝났는데 다음 라운드 저장 전에 멈춘 방
     * (D-120 복구 분기)도 매치가 이미 N+1 이라 N 이 나온다. 이미 끝난 매치면 확정값을 지킨다.
     */
    public SkullKingMatchState abandoned() {
        int played = roundsPlayed != null ? roundsPlayed : roundNumber - 1;
        return new SkullKingMatchState(TOTAL_ROUNDS + 1, startSeat, cumulativeScores,
                desertedSeats, completedRounds, played);
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
