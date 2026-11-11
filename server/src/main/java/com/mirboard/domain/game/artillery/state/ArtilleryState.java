package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import java.util.List;

/**
 * 씨앗포 상태 (`docs/rules-artillery.md`). 불변 레코드 — 전이는 엔진이 새 레코드를 만든다.
 *
 * <p><b>지형은 저장하지 않는다</b>(D-220): {@code seed} 와 {@code craters} 로 다시 만든다. {@code seed} 는 바람·봇 오차의 원천이라
 * 공개 뷰에 싣지 않는다.
 *
 * @param seed             매치 시드(비공개)
 * @param seatCount        인원
 * @param botSeats         봇 좌석(사람이 모두 탈락하면 종료 — §9.3 NO_HUMANS)
 * @param units            좌석 순 포대
 * @param craters          지금까지의 구멍(순서대로)
 * @param phase            단계
 * @param turnSeat         AIMING 이면 차례 좌석, 아니면 −1
 * @param lastSeat         마지막 차례 주인(다음 차례 계산 기준)
 * @param turnNo           지금 차례 번호(0부터)
 * @param wind             지금 차례 바람 −10~10
 * @param cloudLevel       구름 수면 줄(처음 360)
 * @param resolveUntil     RESOLVING 이 끝나는 시각(epoch ms), 아니면 0
 * @param eliminations     지금까지 탈락 순간 수(다음 탈락 번호)
 * @param lastShot         마지막 발사 대본(resync 가 재생 중 화면을 맞출 때), 없으면 null
 * @param result           끝났으면 결과
 * @param pickUntil        PICKING 이 끝나는 시각(epoch ms), 아니면 0 (D-224)
 * @param teams            2:2 팀전인가(D-225) — 팀 = 좌석 홀짝. D-225 이전 JSON 에는 없다(개인전).
 * @param turnOrder        차례 규칙(D-226). D-226 이전 JSON 에는 없다 → {@link TurnOrder#SEAT}(그 판은 좌석 순서로 끝까지).
 * @param turnStartedAt    AIMING 차례가 시작된 시각(epoch ms) — 차례 전체 마감의 기준(D-228, D-183 요트와 같은 방식). AIMING 밖은 0.
 *                         D-228 이전 JSON·시계 없이 연 판(테스트)에는 0 → 엔진 타이머 없음(인프라 턴 데드라인만), 첫 {@code MOVE} 가 채운다.
 * @param moveFuelUsed     이번 차례에 {@code MOVE} 로 이미 쓴 연료(D-228) — {@code FIRE.moveDx} 걷기·딜레이가 이어서 센다. AIMING 밖은 0,
 *                         D-228 이전 JSON 에는 없다(0 — 그 판은 MOVE 가 없었으니 맞다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtilleryState(long seed,
                             int seatCount,
                             List<Integer> botSeats,
                             List<Unit> units,
                             List<Crater> craters,
                             Phase phase,
                             int turnSeat,
                             int lastSeat,
                             int turnNo,
                             int wind,
                             int cloudLevel,
                             long resolveUntil,
                             int eliminations,
                             ShotReport lastShot,
                             MatchResult result,
                             long pickUntil,
                             boolean teams,
                             TurnOrder turnOrder,
                             long turnStartedAt,
                             int moveFuelUsed) implements GameState {

    public ArtilleryState {
        botSeats = botSeats == null ? List.of() : List.copyOf(botSeats);
        units = List.copyOf(units);
        craters = craters == null ? List.of() : List.copyOf(craters);
        turnOrder = turnOrder == null ? TurnOrder.SEAT : turnOrder;
    }

    /** D-228 이전 모양(차례 시계·MOVE 연료 없음). */
    public ArtilleryState(long seed, int seatCount, List<Integer> botSeats, List<Unit> units, List<Crater> craters,
                          Phase phase, int turnSeat, int lastSeat, int turnNo, int wind, int cloudLevel,
                          long resolveUntil, int eliminations, ShotReport lastShot, MatchResult result, long pickUntil,
                          boolean teams, TurnOrder turnOrder) {
        this(seed, seatCount, botSeats, units, craters, phase, turnSeat, lastSeat, turnNo, wind, cloudLevel,
                resolveUntil, eliminations, lastShot, result, pickUntil, teams, turnOrder, 0L, 0);
    }

    /** D-225 이전 모양(개인전·좌석 순서). */
    public ArtilleryState(long seed, int seatCount, List<Integer> botSeats, List<Unit> units, List<Crater> craters,
                          Phase phase, int turnSeat, int lastSeat, int turnNo, int wind, int cloudLevel,
                          long resolveUntil, int eliminations, ShotReport lastShot, MatchResult result, long pickUntil) {
        this(seed, seatCount, botSeats, units, craters, phase, turnSeat, lastSeat, turnNo, wind, cloudLevel,
                resolveUntil, eliminations, lastShot, result, pickUntil, false, TurnOrder.SEAT);
    }

    /** S4 이전 모양(고르기 없음). */
    public ArtilleryState(long seed, int seatCount, List<Integer> botSeats, List<Unit> units, List<Crater> craters,
                          Phase phase, int turnSeat, int lastSeat, int turnNo, int wind, int cloudLevel,
                          long resolveUntil, int eliminations, ShotReport lastShot, MatchResult result) {
        this(seed, seatCount, botSeats, units, craters, phase, turnSeat, lastSeat, turnNo, wind, cloudLevel,
                resolveUntil, eliminations, lastShot, result, 0L);
    }

    /** 차례 시계·MOVE 연료만 바꾼 사본 (D-228). */
    public ArtilleryState withTurnClock(long startedAt, int fuelUsed) {
        return new ArtilleryState(seed, seatCount, botSeats, units, craters, phase, turnSeat, lastSeat, turnNo, wind,
                cloudLevel, resolveUntil, eliminations, lastShot, result, pickUntil, teams, turnOrder, startedAt, fuelUsed);
    }

    /** 차례 주인 포대의 남은 연료 (D-228) — AIMING 이 아니면 0. */
    @JsonIgnore
    public int turnFuelLeft() {
        if (phase != Phase.AIMING || turnSeat < 0) {
            return 0;
        }
        return Math.max(0, units.get(turnSeat).kindOrDefault().fuel() - moveFuelUsed);
    }

    @JsonIgnore
    public boolean ended() {
        return phase == Phase.ENDED;
    }

    public Unit unit(int seat) {
        return units.get(seat);
    }

    @JsonIgnore
    public List<Unit> aliveUnits() {
        return units.stream().filter(Unit::alive).toList();
    }

    /** 팀 번호 — 팀전이면 좌석 홀짝, 개인전이면 좌석 자신(D-225). */
    public int teamOf(int seat) {
        return teams ? seat % 2 : seat;
    }

    @JsonIgnore
    public String phaseName() {
        return phase.name();
    }
}
