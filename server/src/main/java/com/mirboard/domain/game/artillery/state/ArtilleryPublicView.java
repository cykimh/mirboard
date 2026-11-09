package com.mirboard.domain.game.artillery.state;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.physics.Terrain;
import java.util.List;

/**
 * 공개 뷰 (`docs/plans/artillery.md` §5). 숨길 정보가 없어 상태의 거의 전부를 보이지만 <b>시드는 뺀다</b>(다음 바람을 계산할 수 있다).
 * 지형은 구멍 목록이 아니라 열 단위 런 길이 부호로 싣는다 — 클라가 맵 생성을 흉내 내지 않게.
 *
 * @param terrain          열마다 흙 구간 [시작, 끝, 시작, 끝 …] (위에서부터)
 * @param resolveRemainingMs RESOLVING 의 남은 연출 시간, 아니면 null
 * @param lastShot         RESOLVING 이면 재생 중인 대본(다시 들어온 클라가 끝 장면을 맞출 때), 아니면 null
 * @param suddenDeathTurn  이 차례 번호부터 구름이 오른다
 */
public record ArtilleryPublicView(String phase,
                                  int seatCount,
                                  int width,
                                  int height,
                                  List<UnitView> units,
                                  List<int[]> terrain,
                                  int turnSeat,
                                  int turnNo,
                                  int wind,
                                  int cloudLevel,
                                  int suddenDeathTurn,
                                  int fuel,
                                  Long resolveRemainingMs,
                                  ShotReport lastShot,
                                  MatchResult result) {

    public record UnitView(int seat, int x, int footY, int hp, int pumpkins, boolean alive, String outCause) {
    }

    public static ArtilleryPublicView of(ArtilleryState s, Long resolveRemainingMs) {
        List<UnitView> units = s.units().stream()
                .map(u -> new UnitView(u.seat(), u.x(), u.footY(), u.hp(), u.pumpkins(), u.alive(),
                        u.alive() ? null : u.out().cause().name()))
                .toList();
        boolean resolving = s.phase() == Phase.RESOLVING;
        return new ArtilleryPublicView(s.phaseName(), s.seatCount(), Terrain.W, Terrain.H, units,
                ArtilleryEngine.terrainOf(s).columnRuns(), s.turnSeat(), s.turnNo(), s.wind(), s.cloudLevel(),
                ArtilleryEngine.SUDDEN_DEATH_TURNS_PER_SEAT * s.seatCount(), ArtilleryEngine.FUEL,
                resolving && resolveRemainingMs != null ? Math.max(0L, resolveRemainingMs) : null,
                resolving ? s.lastShot() : null, s.result());
    }
}
