package com.mirboard.domain.game.artillery.state;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.physics.Terrain;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import java.util.List;

/**
 * 공개 뷰 (`docs/plans/artillery.md` §5). 숨길 정보가 거의 없어 상태의 거의 전부를 보이지만 <b>시드는 뺀다</b>(다음 바람을 계산할 수 있다).
 * 고르기 단계(D-224)에서는 고른 종류도 뺀다 — "골랐다"만 보이고 다 고르면 {@code UNITS_REVEALED} 로 연다.
 * 지형은 구멍 목록이 아니라 열 단위 런 길이 부호로 싣는다 — 클라가 맵 생성을 흉내 내지 않게.
 *
 * @param terrain            열마다 흙 구간 [시작, 끝, 시작, 끝 …] (위에서부터)
 * @param fuel               지금 차례 포대의 연료(차례가 없으면 100)
 * @param resolveRemainingMs RESOLVING 의 남은 연출 시간, 아니면 null
 * @param pickRemainingMs    PICKING 의 남은 고르기 시간, 아니면 null
 * @param lastShot           RESOLVING 이면 재생 중인 대본(다시 들어온 클라가 끝 장면을 맞출 때), 아니면 null
 * @param suddenDeathTurn    이 차례 번호부터 구름이 오른다
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
                                  Long pickRemainingMs,
                                  ShotReport lastShot,
                                  MatchResult result) {

    /**
     * @param kind    고른 종류 — 고르기 단계에서는 늘 null
     * @param picked  골랐는가(고르기 단계 밖에서는 늘 true)
     * @param special 고유 특수탄(종류를 모르면 null)
     */
    public record UnitView(int seat, int x, int footY, int hp, int pumpkins, boolean alive, String outCause,
                           UnitKind kind, boolean picked, int maxHp, int fuel, Shell special, int specials,
                           List<Item> items) {
        public UnitView {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static UnitView of(Unit u, boolean hideKind) {
            UnitKind k = hideKind ? null : u.kindOrDefault();
            return new UnitView(u.seat(), u.x(), u.footY(), u.hp(), u.pumpkins(), u.alive(),
                    u.alive() ? null : u.out().cause().name(), k, hideKind ? u.kind() != null : true,
                    k == null ? u.hp() : k.maxHp(), k == null ? UnitKind.SPROUT.fuel() : k.fuel(),
                    k == null ? null : k.special(), u.specials(), u.items());
        }
    }

    public static ArtilleryPublicView of(ArtilleryState s, Long remainingMs) {
        boolean picking = s.phase() == Phase.PICKING;
        List<UnitView> units = s.units().stream().map(u -> UnitView.of(u, picking)).toList();
        boolean resolving = s.phase() == Phase.RESOLVING;
        int fuel = s.phase() == Phase.AIMING ? s.unit(s.turnSeat()).kindOrDefault().fuel() : ArtilleryEngine.FUEL;
        return new ArtilleryPublicView(s.phaseName(), s.seatCount(), Terrain.W, Terrain.H, units,
                ArtilleryEngine.terrainOf(s).columnRuns(), s.turnSeat(), s.turnNo(), s.wind(), s.cloudLevel(),
                ArtilleryEngine.SUDDEN_DEATH_TURNS_PER_SEAT * s.seatCount(), fuel,
                resolving && remainingMs != null ? Math.max(0L, remainingMs) : null,
                picking && remainingMs != null ? Math.max(0L, remainingMs) : null,
                resolving ? s.lastShot() : null, s.result());
    }
}
