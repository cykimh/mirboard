package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import java.util.List;

/**
 * 발사 한 번의 재생 대본 (`docs/plans/artillery.md` §6.2). 클라는 이 순서대로 그린다: (물뿌리개) → 이동 → (걷다 떨어짐) → 단계마다
 * [비행 → 폭발 → 낙하]. 단계는 보통 하나, 겹씨앗이면 둘이다(D-224). 모든 값은 결과값이다 — 체력은 {@code hpAfter}, 끝에 {@link #units} 전체.
 *
 * @param item   쓴 아이템, 없으면 null
 * @param heal   물뿌리개로 오른 체력, 안 썼으면 null
 * @param stages 발사하지 못했으면(걷다가 탈락) 빈 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShotReport(int seat, int angle, int power, Shell shell, Item item, int wind,
                         Heal heal, Move move, List<Stage> stages, List<Eliminated> eliminated,
                         List<UnitView> units, long durationMs) {

    public ShotReport {
        stages = stages == null ? List.of() : List.copyOf(stages);
        eliminated = List.copyOf(eliminated);
        units = List.copyOf(units);
    }

    public record Heal(int amount, int hpAfter) {
    }

    /** @param path 걸음마다 (x, footY) 를 이어 붙인 목록. {@code fall} 은 걷다 떨어진 경우. */
    public record Move(List<Integer> path, int toX, int toY, int fuelLeft, Fall fall) {
        public Move {
            path = List.copyOf(path);
        }
    }

    /**
     * 한 발의 해소 — 씨앗들이 날고(같은 지형 위에서), 다 내려앉으면 구멍·둔덕 → 피해 → 낙하.
     *
     * @param shots 홀씨탄이면 [갈라지기 전, 씨앗 셋], 아니면 하나
     */
    public record Stage(List<Shot> shots, List<Damage> damages, List<Fall> falls) {
        public Stage {
            shots = List.copyOf(shots);
            damages = List.copyOf(damages);
            falls = List.copyOf(falls);
        }
    }

    /**
     * @param end       TERRAIN·UNIT 이면 폭발({@code crater} 있음), OUT·CLOUD·EXPIRED 면 없음, SPLIT 이면 여기서 갈라짐
     * @param startTick 단계 시작부터 센 출발 틱(갈라진 씨앗은 갈라진 틱)
     * @param ticks     이 씨앗이 난 틱 수
     */
    public record Shot(List<Integer> path, String end, int impactX, int impactY, Crater crater, int hitSeat,
                       int startTick, int ticks) {
        public Shot {
            path = List.copyOf(path);
        }
    }

    public record Damage(int seat, int amount, int hpAfter, boolean direct) {
    }

    public record Fall(int seat, int x, int fromY, int toY, int damage, int hpAfter, boolean intoCloud) {
    }

    public record Eliminated(int seat, Unit.Cause cause) {
    }

    public record UnitView(int seat, int x, int footY, int hp, boolean alive) {
        public static UnitView of(Unit u) {
            return new UnitView(u.seat(), u.x(), u.footY(), u.hp(), u.alive());
        }
    }
}
