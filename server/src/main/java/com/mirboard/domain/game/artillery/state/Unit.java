package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 포대 하나 (`docs/rules-artillery.md` §3). 좌석마다 하나, 탈락해도 목록에 남는다({@link #out} 이 채워짐).
 *
 * @param pumpkins 남은 호박탄
 * @param out      탈락 정보, 살아 있으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Unit(int seat, int x, int footY, int hp, int pumpkins, Elimination out) {

    @JsonIgnore
    public boolean alive() {
        return out == null;
    }

    public Unit withPosition(int nx, int nfootY) {
        return new Unit(seat, nx, nfootY, hp, pumpkins, out);
    }

    public Unit withHp(int nhp) {
        return new Unit(seat, x, footY, nhp, pumpkins, out);
    }

    public Unit withPumpkins(int n) {
        return new Unit(seat, x, footY, hp, n, out);
    }

    public Unit eliminated(Elimination e) {
        return new Unit(seat, x, footY, hp, pumpkins, e);
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
