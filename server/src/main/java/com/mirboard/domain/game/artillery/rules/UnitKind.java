package com.mirboard.domain.game.artillery.rules;

/**
 * 포대 종류 (`docs/rules-artillery.md` §3, D-224). 판 시작의 고르기 단계에서 고른다 — 안 고르면 {@link #SPROUT}.
 *
 * @param maxHp       최대 체력(시작 체력)
 * @param fuel        차례마다 다시 차는 이동 연료
 * @param windPercent 쏜 씨앗이 받는 바람 영향(%) — 가로 가속 = 바람 × 46 × 이 값 / 100 (0 쪽으로 버림)
 * @param special     고유 특수탄(판당 {@link Shell#SPECIAL_PER_MATCH}발)
 */
public enum UnitKind {
    /** 새싹포 — 균형형, 덩굴탄. */
    SPROUT(100, 100, 100, Shell.VINE),
    /** 도토리포 — 무겁고 단단함, 알밤탄. */
    ACORN(120, 70, 60, Shell.CHESTNUT),
    /** 민들레포 — 가볍고 빠름, 홀씨탄. */
    DANDELION(85, 130, 140, Shell.SPORE);

    private final int maxHp;
    private final int fuel;
    private final int windPercent;
    private final Shell special;

    UnitKind(int maxHp, int fuel, int windPercent, Shell special) {
        this.maxHp = maxHp;
        this.fuel = fuel;
        this.windPercent = windPercent;
        this.special = special;
    }

    public int maxHp() {
        return maxHp;
    }

    public int fuel() {
        return fuel;
    }

    public int windPercent() {
        return windPercent;
    }

    public Shell special() {
        return special;
    }

    /** 가장 큰 연료 — 이동 범위 검사(§4)의 상한. */
    public static int maxFuel() {
        int m = 0;
        for (UnitKind k : values()) {
            m = Math.max(m, k.fuel);
        }
        return m;
    }

    /** 가장 큰 체력 — 불변식용. */
    public static int maxMaxHp() {
        int m = 0;
        for (UnitKind k : values()) {
            m = Math.max(m, k.maxHp);
        }
        return m;
    }
}
