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
                             MatchResult result) implements GameState {

    public ArtilleryState {
        botSeats = botSeats == null ? List.of() : List.copyOf(botSeats);
        units = List.copyOf(units);
        craters = craters == null ? List.of() : List.copyOf(craters);
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

    @JsonIgnore
    public String phaseName() {
        return phase.name();
    }
}
