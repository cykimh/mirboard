package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 포대 하나 (`docs/rules-artillery.md` §3). 좌석마다 하나, 탈락해도 목록에 남는다({@link #out} 이 채워짐).
 *
 * @param pumpkins 남은 호박탄
 * @param out      탈락 정보, 살아 있으면 null
 * @param kind     고른 포대 종류 — 고르기 단계에서 아직 안 골랐으면 null(D-224). S4 이전 상태의 JSON 에도 없으므로 물리는
 *                 {@link #kindOrDefault()} 를 쓴다.
 * @param specials 남은 고유 특수탄
 * @param items    남은 아이템
 * @param delay    누적 딜레이(D-226) — 가장 작은 살아 있는 포대가 다음 차례. D-226 이전 JSON 에는 없다(0).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Unit(int seat, int x, int footY, int hp, int pumpkins, Elimination out,
                   UnitKind kind, int specials, List<Item> items, int delay) {

    public Unit {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** D-226 이전 모양(딜레이 0). */
    public Unit(int seat, int x, int footY, int hp, int pumpkins, Elimination out,
                UnitKind kind, int specials, List<Item> items) {
        this(seat, x, footY, hp, pumpkins, out, kind, specials, items, 0);
    }

    /** S4 이전 모양(새싹포, 특수탄·아이템 없음) — 테스트·구 상태. */
    public Unit(int seat, int x, int footY, int hp, int pumpkins, Elimination out) {
        this(seat, x, footY, hp, pumpkins, out, UnitKind.SPROUT, 0, List.of());
    }

    @JsonIgnore
    public boolean alive() {
        return out == null;
    }

    @JsonIgnore
    public UnitKind kindOrDefault() {
        return kind == null ? UnitKind.SPROUT : kind;
    }

    @JsonIgnore
    public int maxHp() {
        return kindOrDefault().maxHp();
    }

    /** 이 탄의 남은 수 — −1 = 무제한, 다른 유닛의 특수탄은 0. */
    public int ammo(Shell shell) {
        if (shell.perMatch() < 0) {
            return -1;
        }
        if (shell == Shell.PUMPKIN) {
            return pumpkins;
        }
        return kindOrDefault().special() == shell ? specials : 0;
    }

    public boolean has(Item item) {
        return items.contains(item);
    }

    public Unit withPosition(int nx, int nfootY) {
        return new Unit(seat, nx, nfootY, hp, pumpkins, out, kind, specials, items, delay);
    }

    public Unit withHp(int nhp) {
        return new Unit(seat, x, footY, nhp, pumpkins, out, kind, specials, items, delay);
    }

    public Unit withPumpkins(int n) {
        return new Unit(seat, x, footY, hp, n, out, kind, specials, items, delay);
    }

    /** 쏜 탄 하나를 쓴다(콩알은 그대로). */
    public Unit spend(Shell shell) {
        if (shell == Shell.PUMPKIN) {
            return withPumpkins(pumpkins - 1);
        }
        if (shell.special()) {
            return new Unit(seat, x, footY, hp, pumpkins, out, kind, specials - 1, items, delay);
        }
        return this;
    }

    public Unit without(Item item) {
        List<Item> left = new ArrayList<>(items);
        left.remove(item);
        return new Unit(seat, x, footY, hp, pumpkins, out, kind, specials, left, delay);
    }

    /** 고르기(아직 숨김 — 체력은 공개 때 채운다). */
    public Unit picked(UnitKind k) {
        return new Unit(seat, x, footY, hp, pumpkins, out, k, specials, items, delay);
    }

    /** 고르기 끝 — 고른 종류의 체력·특수탄·아이템 묶음으로 채운다(D-224). */
    public Unit equipped() {
        UnitKind k = kindOrDefault();
        return new Unit(seat, x, footY, k.maxHp(), pumpkins, out, k, Shell.SPECIAL_PER_MATCH, Item.starterKit(), delay);
    }

    /** 딜레이를 바꾼다 (D-226). */
    public Unit withDelay(int d) {
        return new Unit(seat, x, footY, hp, pumpkins, out, kind, specials, items, d);
    }

    public Unit eliminated(Elimination e) {
        return new Unit(seat, x, footY, hp, pumpkins, e, kind, specials, items, delay);
    }

    /**
     * 탈락 (§6.3·§8·§11).
     *
     * @param order    탈락 순간 번호 — 같은 해소에서 함께 탈락하면 같은 번호(클수록 늦음)
     * @param hpBefore 그 해소 직전 체력(같은 순간 탈락의 순위 가르기)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Elimination(int order, Cause cause, int hpBefore) {
    }

    public enum Cause {
        KNOCKED_OUT, CLOUD_SEA, DESERTED
    }
}
